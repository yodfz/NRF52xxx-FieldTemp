package com.example.pandatemperature

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.pandatemperature.data.bluetooth.BleManager
import com.example.pandatemperature.data.database.AppDatabase
import com.example.pandatemperature.ui.viewmodel.MainViewModel
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit operator maintenance; defaults to skipped and never clears the App database. */
@RunWith(AndroidJUnit4::class)
class HistoryMaintenanceTest {
    @Test
    fun explicitlyRequestedMaintenance() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val action = args.getString("maintenanceAction")
        assumeTrue("Requires explicit maintenanceAction=archive_sync, retain or inspect", action == "archive_sync" || action == "retain" || action == "inspect")
        if (action == "retain") {
            assertEquals("Retention requires an explicit limit", "3000", args.getString("retainCount"))
            assertEquals("Operator must verify the complete archive before device pruning", "true", args.getString("archiveVerified"))
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (action != "inspect") {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        var activity: MainActivity? = null
        val activityDeadline = SystemClock.elapsedRealtime() + 30_000
        while (activity == null) {
            main {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull()
            }
            assertTrue("Activity failed to resume", SystemClock.elapsedRealtime() < activityDeadline)
            Thread.sleep(50)
        }
        lateinit var vm: MainViewModel
        main { vm = ViewModelProvider(requireNotNull(activity))[MainViewModel::class.java] }
        var received = 0
        fun waitFor(label: String, timeout: Long = 180_000, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            var nextReport = 0L
            while (true) {
                received = maxOf(received, vm.historyProgress.value?.receivedCount ?: 0)
                if (predicate()) return
                assertTrue("Timed out: $label", SystemClock.elapsedRealtime() < deadline)
                if (SystemClock.elapsedRealtime() >= nextReport) {
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("checkpoint", label)
                        putInt("received_records", received)
                    })
                    nextReport = SystemClock.elapsedRealtime() + 10_000
                }
                Thread.sleep(50)
            }
        }
        fun Bundle.putDeviceFacts() {
            putString("device_facts_source", "existing_viewmodel_state_not_fresh_raw_proof")
            val status = vm.deviceStatus.value
            putInt("firmware_version", status?.firmwareVersion ?: -1)
            putString("firmware_version_label", status?.firmwareVersionLabel ?: "--")
            putInt("capability_flags", status?.capabilityFlags ?: -1)
            putBoolean("supports_history_voltage", status?.supportsHistoryVoltage == true)
            putBoolean("supports_history_retention", status?.supportsHistoryRetention == true)
            val volts = vm.batteryVoltage.value
            putBoolean("battery_voltage_valid", volts != null)
            if (volts != null) {
                putFloat("battery_voltage", volts)
                putInt("battery_voltage_mv", (volts * 1000f).roundToInt())
            }
            putLong("hardware_record_count", vm.historyTotalRecords.value ?: -1L)
        }
        if (action == "inspect") {
            // Existing connection only: do not reconnect, issue history commands, or access DAO.
            waitFor("existing connection, status and realtime sample", 30_000) {
                vm.connectionState.value == BleManager.ConnectionState.ServicesDiscovered &&
                    vm.deviceStatus.value != null && vm.temperature.value != null
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("maintenance_action", "inspect")
                putDeviceFacts()
                putBoolean("history_fetch_in_progress", vm.isFetchingHistory.value)
                putString("result", "PASS")
            })
            return@runBlocking
        }
        val savedConnection = SavedMaintenanceConnection.create(context, vm, args.getString("targetDeviceSha256"))
        waitFor("initial connection and automatic synchronization") {
            savedConnection.connectIfDisconnected()
            vm.connectionState.value == BleManager.ConnectionState.ServicesDiscovered &&
                vm.temperature.value != null && !vm.isFetchingHistory.value &&
                vm.logs.value.any { it.message.contains("历史数据传输完成") }
        }
        val address = requireNotNull(vm.deviceAddress.value)
        val dao = AppDatabase.getDatabase(context).temperatureRecordDao()
        val before = dao.getAllRecordsSync(address)
        val gpsBefore = before.filter { it.isPhoneSample || it.latitude != null || it.longitude != null }.associateBy { it.id }
        val moduleBefore = before.filter { !it.isPhoneSample && it.latitude == null && it.longitude == null }.associateBy { it.timestamp }

        if (action == "retain") {
            assertTrue("Firmware must explicitly advertise retention capability", vm.deviceStatus.value?.supportsHistoryRetention == true)
            assertTrue("Retention was not confirmed by count and idle status", vm.retainLatestDeviceHistory(3000))
            assertEquals(3000L, vm.historyTotalRecords.value)
            assertFalse(vm.deviceStatus.value!!.isDataClearInProgress)
        }
        received = 0
        main { vm.clearLogs(); vm.fetchHistory(forceFullSync = true) }
        waitFor("requested full synchronization starts", 10_000) { vm.isFetchingHistory.value }
        waitFor("requested full synchronization completes", 600_000) { !vm.isFetchingHistory.value }
        assertTrue("A real end marker is required", vm.logs.value.any { it.message.contains("历史数据传输完成") })
        if (action == "retain") assertEquals("Retained device data must be fully read back", 3000, received)
        else assertTrue("Archive synchronization must transfer records", received > 0)

        val after = dao.getAllRecordsSync(address)
        val gpsAfter = after.filter { it.isPhoneSample || it.latitude != null || it.longitude != null }.associateBy { it.id }
        assertTrue("Existing phone samples, including location-free samples, must remain intact", gpsBefore.all { (id, row) -> gpsAfter[id] == row })
        val moduleAfter = after.filter { !it.isPhoneSample && it.latitude == null && it.longitude == null }
        assertEquals("No duplicate device timestamps", moduleAfter.size, moduleAfter.map { it.timestamp }.distinct().size)
        assertTrue("No future timestamps", moduleAfter.none { it.timestamp > System.currentTimeMillis() / 1000 + 86400 })
        val latest = moduleAfter.sortedByDescending { it.timestamp }.take(if (action == "retain") 3000 else moduleAfter.size)
        val overlap = latest.mapNotNull { row -> moduleBefore[row.timestamp]?.let { it to row } }
        assertTrue("Expected substantial retained history overlap", overlap.size >= minOf(100, moduleBefore.size))
        overlap.forEach { (old, current) ->
            // createdAt can change on upsert; compare only the transmitted sensor core.
            assertEquals(old.timestamp, current.timestamp)
            assertEquals(old.temperature, current.temperature, 0f)
            assertEquals(old.humidity, current.humidity, 0f)
            assertEquals(old.pressure, current.pressure)
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("maintenance_action", action)
            putDeviceFacts()
            putInt("all_device_rows", after.size)
            putInt("module_rows", moduleAfter.size)
            putInt("phone_rows", gpsAfter.size)
            putInt("received_records", received)
            putLong("latest_timestamp", after.maxOfOrNull { it.timestamp } ?: 0L)
            putLong("hardware_record_count", vm.historyTotalRecords.value ?: -1L)
            putString("result", "PASS")
        })
    }
}
