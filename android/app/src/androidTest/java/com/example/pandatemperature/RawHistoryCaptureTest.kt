package com.example.pandatemperature

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.pandatemperature.data.bluetooth.BleConstants
import com.example.pandatemperature.data.bluetooth.BleManager
import com.example.pandatemperature.data.device.parser.DeviceStatusParser
import com.example.pandatemperature.data.device.parser.HistoryFormatAware
import com.example.pandatemperature.data.device.profile.DeviceProfileFactory
import com.example.pandatemperature.ui.viewmodel.MainViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Independent opt-in wire read session. Never replaces a running MainViewModel history callback. */
@RunWith(AndroidJUnit4::class)
class RawHistoryCaptureTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun sha(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    @Test
    fun captureConfirmedRetainedV3Set() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit rawHistoryAction=capture", args.getString("rawHistoryAction") == "capture")
        assertEquals("Capture is allowed only after hardware retention", "3000", args.getString("expectedCount"))
        val targetHash = requireNotNull(args.getString("targetDeviceSha256")) { "Explicit target digest required" }.lowercase()
        assertTrue("Invalid target digest", targetHash.length == 64 && targetHash.all { it in '0'..'9' || it in 'a'..'f' })
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var activity: MainActivity? = null
        val activityDeadline = SystemClock.elapsedRealtime() + 30_000
        while (activity == null) {
            main { activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull() }
            assertTrue("Activity did not resume", SystemClock.elapsedRealtime() < activityDeadline)
            Thread.sleep(50)
        }
        lateinit var vm: MainViewModel
        main { vm = ViewModelProvider(requireNotNull(activity))[MainViewModel::class.java] }
        val connection = SavedMaintenanceConnection.create(context, vm, targetHash)
        val deadline = SystemClock.elapsedRealtime() + 180_000
        while (true) {
            connection.connectIfDisconnected()
            if (vm.connectionState.value == BleManager.ConnectionState.ServicesDiscovered &&
                vm.temperature.value != null && !vm.isFetchingHistory.value &&
                vm.logs.value.any { it.message.contains("历史数据传输完成") || it.message.contains("存储中无历史记录") }) break
            assertTrue("Timed out waiting for actual connection/history teardown", SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(50)
        }
        assertEquals("Connected target differs", targetHash, sha(requireNotNull(vm.deviceAddress.value).toByteArray(Charsets.UTF_8)))
        val manager = BleManager.getInstance(context)
        var exclusive = false
        var notificationAttempted = false
        suspend fun read(uuid: String): ByteArray {
            val pending = CompletableDeferred<ByteArray?>()
            manager.readCharacteristic(uuid) { pending.complete(it) }
            return requireNotNull(withTimeoutOrNull(5_000L) { pending.await() }) { "Required hardware read failed" }
        }
        suspend fun setHistoryNotification(enable: Boolean, callback: (ByteArray) -> Unit = {}): Boolean {
            val pending = CompletableDeferred<Boolean>()
            manager.enableNotificationAsync(BleConstants.HISTORY_CHAR, enable, callback) { pending.complete(it) }
            return withTimeoutOrNull(5_000L) { pending.await() } == true
        }
        try {
            // beginExclusiveOta also requires the product history finish job to be inactive.
            // Product teardown disables HISTORY notifications and removes its callback before this lock succeeds.
            exclusive = vm.beginExclusiveOta()
            assertTrue("Product history/maintenance still owns BLE; capture not started", exclusive)
            val beforeStatus = DeviceStatusParser().parse(read(BleConstants.STATUS_CHAR))!!
            assertFalse("Hardware maintenance busy", beforeStatus.isDataClearInProgress)
            assertTrue("Hardware must support V3 and retention", beforeStatus.supportsHistoryVoltage && beforeStatus.supportsHistoryRetention)
            val beforeInfo = read(BleConstants.HISTORY_INFO_CHAR)
            assertEquals("Expected exact 8-byte HISTORY_INFO", 8, beforeInfo.size)
            assertEquals("Do not capture the pre-retention 33k ring", 3000, ByteBuffer.wrap(beforeInfo).order(ByteOrder.LITTLE_ENDIAN).int)
            assertEquals(3000, beforeStatus.recordCount)
            val profile = DeviceProfileFactory.createThermometerProfile(beforeStatus.firmwareVersion, capabilityFlags = beforeStatus.capabilityFlags)
            assertEquals(14, (profile.getHistoryParser("capture-only") as HistoryFormatAware).historyRecordSize)
            val request = profile.buildHistoryRequest(0)
            assertArrayEquals(byteArrayOf(0, 0, 0, 0, 3), request)
            val written = CompletableDeferred<Boolean>()
            manager.writeCharacteristic(BleConstants.HISTORY_CHAR, request, onWrite = { written.complete(it) })
            assertTrue("V3 request write failed", withTimeoutOrNull(5_000L) { written.await() } == true)
            val packets = Channel<ByteArray>(Channel.UNLIMITED)
            notificationAttempted = true
            assertTrue("Independent capture notification failed", setHistoryNotification(true) { packets.trySend(it.copyOf()) })
            val raw = ByteArrayOutputStream()
            val journal = ByteArrayOutputStream()
            val csv = StringBuilder("timestamp,temperature_centi_c,humidity_centi_percent,pressure_pa,battery_mv\n")
            val seen = mutableSetOf<Long>()
            var frameCount = 0
            var endReceived = false
            var countNoticeReceived = false
            val startedAt = SystemClock.elapsedRealtime()
            val capturedAt = System.currentTimeMillis() / 1000
            val complete = withTimeoutOrNull(120_000L) {
                while (!endReceived) {
                    val packet = requireNotNull(withTimeoutOrNull(15_000L) { packets.receive() }) { "Raw capture made no progress" }
                    journal.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(packet.size).array())
                    journal.write(packet)
                    frameCount++
                    if (packet.size == 1 && packet[0] == BleConstants.HISTORY_END_FLAG) {
                        endReceived = true
                        break
                    }
                    if (packet.size == 4) {
                        // Firmware CCC emits a uint32 count notice before scheduling any data frames.
                        assertTrue("Unexpected duplicate/late count notice", !countNoticeReceived && seen.isEmpty())
                        assertEquals(3000, ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).int)
                        countNoticeReceived = true
                        continue
                    }
                    assertTrue("Raw V3 notification has partial/invalid records", packet.isNotEmpty() && packet.size % 14 == 0)
                    raw.write(packet)
                    val wire = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
                    for (offset in packet.indices step 14) {
                        val timestamp = wire.getInt(offset).toLong() and 0xFFFFFFFFL
                        assertTrue("Raw hardware timestamp invalid/future/duplicate", timestamp > 0 && timestamp <= capturedAt + 86400 && seen.add(timestamp))
                        csv.append(timestamp).append(',').append(wire.getShort(offset + 4).toInt()).append(',')
                            .append(wire.getShort(offset + 6).toInt() and 0xFFFF).append(',')
                            .append(wire.getInt(offset + 8).toLong() and 0xFFFFFFFFL).append(',')
                            .append(wire.getShort(offset + 12).toInt() and 0xFFFF).append('\n')
                    }
                    assertTrue("Capture exceeds exact 3000-record retained set", seen.size <= 3000)
                }
                true
            } == true
            assertTrue("Raw hardware END was not received", complete && endReceived)
            assertEquals("Raw hardware count mismatch", 3000, seen.size)
            assertEquals(42_000, raw.size())
            assertTrue("Capture callback teardown failed", setHistoryNotification(false))
            notificationAttempted = false
            val afterInfo = read(BleConstants.HISTORY_INFO_CHAR)
            val afterStatus = DeviceStatusParser().parse(read(BleConstants.STATUS_CHAR))!!
            assertArrayEquals("Hardware ring moved during capture; recapture rather than claim equality", beforeInfo, afterInfo)
            assertFalse("Hardware maintenance still busy", afterStatus.isDataClearInProgress)
            assertEquals(3000, afterStatus.recordCount)
            assertTrue(afterStatus.supportsHistoryVoltage && afterStatus.supportsHistoryRetention)
            val directory = File(context.filesDir, "maintenance/raw-history-${System.currentTimeMillis()}")
            assertTrue("Cannot create capture directory", directory.mkdirs())
            val rawFile = File(directory, "records-v3.bin").apply { writeBytes(raw.toByteArray()) }
            val csvFile = File(directory, "hardware-records.csv").apply { writeText(csv.toString()) }
            val framesFile = File(directory, "notification-frames.bin").apply { writeBytes(journal.toByteArray()) }
            val manifest = JSONObject().apply {
                put("capture_source", "ble_raw_history")
                put("record_size", 14)
                put("device_sha256", targetHash)
                put("record_count", seen.size)
                put("capability_flags", afterStatus.capabilityFlags)
                put("end_marker_received", endReceived)
                put("count_notice_received", countNoticeReceived)
                put("maintenance_busy", afterStatus.isDataClearInProgress)
                put("history_info_before_hex", hex(beforeInfo))
                put("history_info_after_hex", hex(afterInfo))
                put("captured_at_unix", capturedAt)
                put("duration_ms", SystemClock.elapsedRealtime() - startedAt)
                put("notification_frame_count", frameCount)
                put("raw_records_file", rawFile.name)
                put("raw_frames_file", framesFile.name)
                put("history_end_flag_hex", hex(byteArrayOf(BleConstants.HISTORY_END_FLAG)))
                put("raw_journal_format", "repeated_uint32LE_length_then_notification_bytes_including_END")
                put("sha256", JSONObject().apply {
                    put(rawFile.name, sha(rawFile.readBytes()))
                    put(csvFile.name, sha(csvFile.readBytes()))
                    put(framesFile.name, sha(framesFile.readBytes()))
                })
            }
            File(directory, "manifest.json").writeText(manifest.toString(2))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("capture_dir", "maintenance/${directory.name}")
                putInt("hardware_record_count", seen.size)
                putInt("raw_bytes", raw.size())
                putBoolean("end_received", endReceived)
                putBoolean("history_info_stable", true)
                putString("raw_sha256", sha(raw.toByteArray()))
            })
        } finally {
            withContext(NonCancellable) {
                try {
                    if (notificationAttempted) {
                        val stopped = setHistoryNotification(false)
                        instrumentation.sendStatus(0, Bundle().apply { putBoolean("history_capture_teardown_confirmed", stopped) })
                        assertTrue("Capture CCC teardown was not confirmed; no clean completion claim", stopped)
                    }
                } finally {
                    if (exclusive) vm.endExclusiveOta()
                }
            }
        }
    }
}
