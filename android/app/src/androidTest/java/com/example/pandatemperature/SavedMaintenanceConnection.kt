package com.example.pandatemperature

import android.content.Context
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pandatemperature.data.bluetooth.BleManager
import com.example.pandatemperature.data.database.AppDatabase
import com.example.pandatemperature.ui.viewmodel.MainViewModel
import java.security.MessageDigest
import kotlinx.coroutines.flow.first

/** Test-only connection recovery. Reads saved devices, never creates or edits them. */
internal class SavedMaintenanceConnection private constructor(
    private val vm: MainViewModel,
    private val targetAddress: String
) {
    private var attempts = 0
    private var lastAttemptAt = 0L

    /** One explicit initial attempt and at most one subsequent retry, only while disconnected. */
    fun connectIfDisconnected() {
        val state = vm.connectionState.value
        if (state == BleManager.ConnectionState.ServicesDiscovered) {
            check(vm.deviceAddress.value == targetAddress) { "Connected device differs from saved maintenance target" }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (state != BleManager.ConnectionState.Disconnected || attempts >= 2 ||
            (attempts > 0 && now - lastAttemptAt < 10_000)) return
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (vm.connectionState.value == BleManager.ConnectionState.Disconnected) {
                attempts++
                lastAttemptAt = now
                // Initial-idle checks must not accept a previous connection's completion log.
                vm.clearLogs()
                vm.connectDevice(targetAddress)
            }
        }
    }

    companion object {
        suspend fun create(context: Context, vm: MainViewModel, expectedDeviceHash: String? = null): SavedMaintenanceConnection {
            val saved = AppDatabase.getDatabase(context).deviceDao().getAllDevices().first()
            val selected = if (expectedDeviceHash != null) {
                saved.filter { device ->
                    val hash = MessageDigest.getInstance("SHA-256").digest(device.macAddress.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    hash == expectedDeviceHash.lowercase()
                }.singleOrNull() ?: error("Verified maintenance target must match exactly one saved device")
            } else {
                saved.singleOrNull() ?: error("Maintenance without a device receipt requires exactly one saved device")
            }
            return SavedMaintenanceConnection(vm, selected.macAddress)
        }
    }
}
