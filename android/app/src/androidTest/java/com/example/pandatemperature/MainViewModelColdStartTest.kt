package com.example.pandatemperature

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pandatemperature.data.bluetooth.BleManager
import com.example.pandatemperature.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises constructor-time Main.immediate collection, without opening a BLE connection. */
@RunWith(AndroidJUnit4::class)
class MainViewModelColdStartTest {
    @Test
    fun initialDisconnectDoesNotTerminateTheConnectionCollector() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val manager = BleManager.getInstance(application)
        // Do not disturb an existing hardware session when this class is run with live tests.
        assumeTrue("Requires a disconnected cold start; no hardware connection is changed",
            manager.connectionState.value == BleManager.ConnectionState.Disconnected)
        val stateField = BleManager::class.java.getDeclaredField("_connectionState").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(manager) as MutableStateFlow<BleManager.ConnectionState>
        val store = ViewModelStore()
        lateinit var vm: MainViewModel
        lateinit var scopeJob: Job
        try {
            // Main.immediate receives Disconnected before the constructor returns. A session
            // field declared after init used to throw here and silently kill the collector.
            instrumentation.runOnMainSync {
                vm = MainViewModel(application)
                store.put("cold-start-regression", vm)
                scopeJob = requireNotNull(vm.viewModelScope.coroutineContext[Job])
            }
            instrumentation.waitForIdleSync()
            assertNotNull(MainViewModel::class.java.getDeclaredField("historySession").apply {
                isAccessible = true
            }.get(vm))
            assertEquals(1, vm.logs.value.count { it.message == "设备已断开连接" })
            assertFalse(vm.isFetchingHistory.value)
            assertTrue("Constructor reset must leave the connection collector active",
                scopeJob.children.any { it.isActive })

            // Synthetic states only: neither connectDevice nor disconnectDevice is called.
            // The second reset proves the collector survived, rather than merely logging
            // the first disconnect before throwing during partially initialized access.
            instrumentation.runOnMainSync { state.value = BleManager.ConnectionState.Connecting }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { state.value = BleManager.ConnectionState.Disconnected }
            instrumentation.waitForIdleSync()
            assertEquals("The listener must continue observing after constructor reset",
                2, vm.logs.value.count { it.message == "设备已断开连接" })
            assertTrue(scopeJob.children.any { it.isActive })
            assertFalse(vm.isFetchingHistory.value)
        } finally {
            instrumentation.runOnMainSync {
                state.value = BleManager.ConnectionState.Disconnected
                store.clear()
            }
        }
        assertTrue("ViewModelStore cleanup must cancel the test listener", scopeJob.isCancelled)
    }
}
