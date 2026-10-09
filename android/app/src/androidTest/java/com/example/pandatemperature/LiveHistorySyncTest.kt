package com.example.pandatemperature

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.pandatemperature.data.bluetooth.BleManager
import com.example.pandatemperature.data.database.AppDatabase
import com.example.pandatemperature.ui.viewmodel.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in hardware check. Downloads history without clearing either side's data. */
@RunWith(AndroidJUnit4::class)
class LiveHistorySyncTest {
    @Test
    fun repeatFullSyncThenDisconnectAndRetry() = runBlocking<Unit> {
        assumeTrue("Requires explicit liveBle=true and a connected thermometer",
            InstrumentationRegistry.getArguments().getString("liveBle") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var vm: MainViewModel
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        var activity: MainActivity? = null
        val activityDeadline = android.os.SystemClock.elapsedRealtime() + 30_000
        while (activity == null) {
            main { activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull() }
            assertTrue("Activity did not resume", android.os.SystemClock.elapsedRealtime() < activityDeadline)
            Thread.sleep(50)
        }
        main { vm = ViewModelProvider(requireNotNull(activity))[MainViewModel::class.java] }
        fun waitFor(label: String, timeoutMs: Long = 120_000, predicate: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
            var nextReport = 0L
            while (!predicate()) {
                assertTrue("Timed out: $label", android.os.SystemClock.elapsedRealtime() < deadline)
                val now = android.os.SystemClock.elapsedRealtime()
                if (now >= nextReport) {
                    instrumentation.sendStatus(0, android.os.Bundle().apply {
                        putString("checkpoint", "$label state=${vm.connectionState.value} fetching=${vm.isFetchingHistory.value} received=${vm.historyProgress.value?.receivedCount} voltage=${vm.batteryVoltage.value}")
                    })
                    nextReport = now + 10_000
                }
                Thread.sleep(50)
            }
        }
        val connection = SavedMaintenanceConnection.create(context, vm,
            InstrumentationRegistry.getArguments().getString("targetDeviceSha256"))
        waitFor("initial connection and sync") {
            connection.connectIfDisconnected()
            vm.connectionState.value == BleManager.ConnectionState.ServicesDiscovered &&
                vm.temperature.value != null && !vm.isFetchingHistory.value &&
                vm.logs.value.any { it.message.contains("历史数据传输完成") }
        }
        val address = requireNotNull(vm.deviceAddress.value)
        val dao = AppDatabase.getDatabase(context).temperatureRecordDao()
        val baseline = dao.getAllRecordsSync(address)
        val gpsBefore = baseline.filter { it.isPhoneSample || it.latitude != null || it.longitude != null }.associateBy { it.id }
        val moduleBefore = baseline.filter { !it.isPhoneSample && it.latitude == null && it.longitude == null }.associateBy { it.timestamp }
        val expectedMinimum = InstrumentationRegistry.getArguments().getString("expectedMinimumRecords")?.toInt() ?: 100
        assertTrue("Not enough existing history for requested validation", moduleBefore.size >= expectedMinimum)

        suspend fun checkDatabase(label: String) {
            val rows = dao.getAllRecordsSync(address)
            val module = rows.filter { !it.isPhoneSample && it.latitude == null && it.longitude == null }
            assertEquals("$label duplicate history", module.size, module.map { it.timestamp }.distinct().size)
            val gpsAfter = rows.filter { it.isPhoneSample || it.latitude != null || it.longitude != null }.associateBy { it.id }
            assertTrue("$label must preserve existing phone rows, including no-GPS samples", gpsBefore.all { (id, row) -> gpsAfter[id] == row })
            val afterByTime = module.associateBy { it.timestamp }
            val retainedPolicy = vm.isPersistentHistoryRetentionEnabled(address)
            val protectedModule = if (retainedPolicy) moduleBefore.filterKeys { it in afterByTime } else moduleBefore
            if (retainedPolicy) {
                assertTrue("$label requires actual END", vm.logs.value.any { it.message.contains("历史数据传输完成") })
                assertTrue("$label requires stable complete wire reconciliation", vm.logs.value.any { it.message.contains("本地持续保留已核对3000条") })
                assertEquals("$label retained module count", 3000, module.size)
                assertEquals("$label device retained count", 3000L, vm.historyTotalRecords.value)
                assertTrue("$label retained overlap must be substantial", protectedModule.size >= minOf(100,moduleBefore.size))
            }
            protectedModule.forEach { (time, row) ->
                val current = requireNotNull(afterByTime[time]) { "$label lost protected module timestamp" }
                assertEquals("$label preserved module ID", row.id, current.id)
                assertEquals(row.temperature,current.temperature,0f)
                assertEquals(row.humidity,current.humidity,0f)
                assertEquals(row.pressure,current.pressure)
                assertEquals(row.batteryVoltage,current.batteryVoltage)
            }
            assertTrue("$label future records", module.none { it.timestamp > System.currentTimeMillis() / 1000 + 86400 })
            assertEquals("$label module-only progress count", module.size, dao.getHistoryRecordCount(address))
            Log.i("LiveHistorySyncTest", "$label PASS module=${module.size} gps=${gpsAfter.size}")
        }
        fun startFull() {
            main { vm.clearLogs(); vm.fetchHistory(forceFullSync = true) }
            waitFor("full sync starts", 10_000) { vm.isFetchingHistory.value }
        }
        fun completeFull(label: String) {
            waitFor("$label receives records", 20_000) { (vm.historyProgress.value?.receivedCount ?: 0) >= 100 }
            waitFor("$label finishes", 180_000) { !vm.isFetchingHistory.value }
            assertTrue("$label needs actual end marker", vm.logs.value.any { it.message.contains("历史数据传输完成") })
        }
        startFull()
        completeFull("full-1")
        // Request again immediately after fetching=false; old cleanup must be finished.
        startFull()
        completeFull("full-2-immediate-retry")
        checkDatabase("two-full-downloads")
        val interruptedBefore = dao.getAllRecordsSync(address).filter {
            !it.isPhoneSample && it.latitude == null && it.longitude == null
        }.map { it.id }.toSet()
        startFull()
        waitFor("partial download", 20_000) { (vm.historyProgress.value?.receivedCount ?: 0) >= 100 }
        assertTrue("Interruption must precede END", vm.isFetchingHistory.value &&
            vm.logs.value.none { it.message.contains("历史数据传输完成") })
        main { vm.disconnectDevice() }
        waitFor("disconnect invalidates sync", 15_000) {
            vm.connectionState.value == BleManager.ConnectionState.Disconnected && !vm.isFetchingHistory.value
        }
        val interruptedAfter = dao.getAllRecordsSync(address).filter {
            !it.isPhoneSample && it.latitude == null && it.longitude == null
        }.map { it.id }.toSet()
        assertTrue("A session interrupted before END must not delete any existing module ID",
            interruptedAfter.containsAll(interruptedBefore))
        val reconnectConnection = SavedMaintenanceConnection.create(context, vm,
            InstrumentationRegistry.getArguments().getString("targetDeviceSha256"))
        main { vm.clearLogs() }
        waitFor("reconnect sync completes", 120_000) {
            reconnectConnection.connectIfDisconnected()
            vm.connectionState.value == BleManager.ConnectionState.ServicesDiscovered &&
                vm.temperature.value != null && !vm.isFetchingHistory.value &&
                vm.logs.value.any { it.message.contains("历史数据传输完成") }
        }
        main { vm.clearLogs(); vm.fetchHistory() }
        waitFor("immediate incremental retry starts", 10_000) { vm.isFetchingHistory.value }
        waitFor("incremental retry finishes", 120_000) { !vm.isFetchingHistory.value }
        checkDatabase("disconnect-reconnect-and-retry")
        waitFor("realtime subscription restored", 10_000) { vm.temperature.value != null }
        Log.i("LiveHistorySyncTest", "ALL LIVE CHECKS PASSED")
    }
}