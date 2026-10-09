package com.example.pandatemperature

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.pandatemperature.data.bluetooth.BleConstants
import com.example.pandatemperature.data.bluetooth.BleManager
import com.example.pandatemperature.data.ota.AndroidOtaTransport
import com.example.pandatemperature.data.ota.OtaAuthKey
import com.example.pandatemperature.data.ota.OtaImageParser
import com.example.pandatemperature.data.ota.OtaRunner
import com.example.pandatemperature.ui.viewmodel.FirmwareOtaViewModel
import com.example.pandatemperature.ui.viewmodel.MainViewModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Default skipped. Upload only verifies; reboot requires a separate explicit trigger action. */
@RunWith(AndroidJUnit4::class)
class LiveOtaMaintenanceTest {
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    /** Structural signed-bin checks only; MCUboot remains the cryptographic trust boundary. */
    private fun checkSignedStructure(bytes: ByteArray) {
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertTrue("MCUboot header incomplete", bytes.size >= 32)
        val headerSize = header.getShort(8).toInt() and 0xFFFF
        val protectedSize = header.getShort(10).toInt() and 0xFFFF
        val imageSize = header.getInt(12).toLong() and 0xFFFFFFFFL
        assertTrue("MCUboot header/image lengths invalid", headerSize >= 32 && imageSize > 0)
        val regularStart = headerSize.toLong() + imageSize + protectedSize
        assertTrue("Signed TLV region missing", regularStart + 4 <= bytes.size)
        val start = regularStart.toInt()
        assertEquals("MCUboot regular TLV magic", 0x6907, header.getShort(start).toInt() and 0xFFFF)
        val tlvLength = header.getShort(start + 2).toInt() and 0xFFFF
        val end = start + tlvLength
        assertTrue("Signed TLV region truncated", tlvLength >= 4 && end <= bytes.size)
        var position = start + 4
        var hashPresent = false
        var p256SignaturePresent = false
        while (position < end) {
            assertTrue("TLV entry header truncated", position + 4 <= end)
            val type = bytes[position].toInt() and 0xFF
            val length = header.getShort(position + 2).toInt() and 0xFFFF
            assertTrue("TLV entry payload truncated", position + 4 + length <= end)
            if (type == 0x10 && length == 32) hashPresent = true
            if (type == 0x22 && length > 0) p256SignaturePresent = true
            position += 4 + length
        }
        assertTrue("MCUboot SHA256 and ECDSA-P256 TLVs required", hashPresent && p256SignaturePresent)
    }

    @Test
    fun explicitlyRequestedOtaPhase() = runBlocking<Unit> {
        val action = InstrumentationRegistry.getArguments().getString("otaAction")
        assumeTrue("Requires explicit otaAction=upload, trigger, write_pause or erase_cut", action == "upload" || action == "trigger" || action == "write_pause" || action == "erase_cut")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val maintenance = File(context.filesDir, "maintenance")
        val imageFile = File(maintenance, "zephyr.signed.bin")
        val authFile = File(maintenance, "auth.txt")
        val receipt = File(maintenance, "verified-upload.json")
        var vm: MainViewModel? = null
        var ota: FirmwareOtaViewModel? = null
        var exclusive = false
        var previousAuth: String? = null
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        try {
            assertTrue("Stage zephyr.signed.bin in internal maintenance files first", imageFile.isFile)
            assertTrue("Stage auth.txt in internal maintenance files first", authFile.isFile)
            val bytes = imageFile.readBytes()
            val image = OtaImageParser.parse(bytes)
            checkSignedStructure(bytes)
            val authText = authFile.readText().trim()
            val auth = requireNotNull(OtaAuthKey.parse(authText)) { "Invalid authorization file format" }
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            var activity: MainActivity? = null
            val activityDeadline = SystemClock.elapsedRealtime() + 30_000
            while (activity == null) {
                main {
                    activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>().firstOrNull()
                }
                assertTrue("Activity did not resume", SystemClock.elapsedRealtime() < activityDeadline)
                Thread.sleep(50)
            }
            main {
                val provider = ViewModelProvider(requireNotNull(activity))
                vm = provider[MainViewModel::class.java]
                ota = provider[FirmwareOtaViewModel::class.java]
            }
            val liveVm = requireNotNull(vm)
            val liveOta = requireNotNull(ota)
            val expectedDeviceHash = if (action == "trigger" || action == "erase_cut") {
                assertTrue("A completed upload receipt is required", receipt.isFile)
                JSONObject(receipt.readText()).getString("device_sha256")
            } else InstrumentationRegistry.getArguments().getString("targetDeviceSha256")
            val savedConnection = SavedMaintenanceConnection.create(context, liveVm, expectedDeviceHash)
            fun report(checkpoint: String) {
                val progress = liveOta.state.value.progress
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("ota_checkpoint", checkpoint)
                    putString("ota_phase", liveOta.state.value.phase.name)
                    putString("ota_message", liveOta.state.value.message)
                    putLong("total_bytes", image.total)
                    putLong("queued_bytes", progress?.queuedBytes ?: 0L)
                    putLong("confirmed_bytes", progress?.confirmedBytes ?: 0L)
                    putInt("percent", progress?.percent ?: 0)
                })
            }
            fun waitFor(label: String, timeout: Long = 300_000, ready: () -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + timeout
                var nextReport = 0L
                while (!ready()) {
                    assertTrue("Timed out: $label", SystemClock.elapsedRealtime() < deadline)
                    if (SystemClock.elapsedRealtime() >= nextReport) {
                        report(label)
                        nextReport = SystemClock.elapsedRealtime() + 2_000
                    }
                    Thread.sleep(50)
                }
            }
            waitFor("connection_and_history_idle", 180_000) {
                savedConnection.connectIfDisconnected()
                liveVm.connectionState.value == BleManager.ConnectionState.ServicesDiscovered &&
                    liveVm.temperature.value != null && !liveVm.isFetchingHistory.value &&
                    liveVm.logs.value.any { it.message.contains("历史数据传输完成") || it.message.contains("存储中无历史记录") }
            }
            val manager = BleManager.getInstance(context)
            for (uuid in listOf(BleConstants.OTA_CONTROL_CHAR, BleConstants.OTA_DATA_CHAR, BleConstants.OTA_STATUS_CHAR)) {
                assertTrue("Required OTA characteristic missing", manager.isCharacteristicAvailable(uuid))
            }
            exclusive = liveVm.beginExclusiveOta()
            assertTrue("History/maintenance is busy; no OTA command sent", exclusive)
            val imageHash = digest(bytes)
            val deviceHash = digest(requireNotNull(liveVm.deviceAddress.value).toByteArray(Charsets.UTF_8))
            if (action == "upload") {
                receipt.delete()
                previousAuth = liveOta.state.value.authKeyText
                main {
                    liveOta.onAuthKeyChanged(authText, persist = false)
                    liveOta.loadImage(Uri.fromFile(imageFile))
                }
                waitFor("image_preflight", 15_000) { liveOta.state.value.phase != FirmwareOtaViewModel.Phase.LOADING }
                assertEquals("Image preflight failed", FirmwareOtaViewModel.Phase.READY, liveOta.state.value.phase)
                main { liveOta.startUpload() }
                waitFor("upload_to_verify") {
                    liveOta.state.value.phase == FirmwareOtaViewModel.Phase.VERIFIED ||
                        liveOta.state.value.phase == FirmwareOtaViewModel.Phase.ERROR
                }
                assertEquals("OTA did not reach VERIFY: ${liveOta.state.value.message}", FirmwareOtaViewModel.Phase.VERIFIED, liveOta.state.value.phase)
                receipt.writeText(JSONObject().apply {
                    put("image_sha256", imageHash)
                    put("device_sha256", deviceHash)
                    put("total_bytes", image.total)
                    put("verified_at_ms", System.currentTimeMillis())
                }.toString())
                report("VERIFY_UPLOAD_COMPLETE_NO_TRIGGER")
            } else if (action == "write_pause") {
                // Explicitly reset OTA staging so a previous VERIFY cannot auto-resume past the pause point.
                receipt.delete()
                File(maintenance, "partial-upload.json").delete()
                val partial = writeConfirmedPartialImage(AndroidOtaTransport(manager), image, bytes, auth) { name, status, queued ->
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("ota_checkpoint", name)
                        putString("ota_phase", status.state.name)
                        putLong("confirmed_bytes", status.confirmedOffset)
                        putLong("queued_bytes", queued)
                        putLong("total_bytes", status.total)
                    })
                }
                File(maintenance, "partial-upload.json").writeText(JSONObject().apply {
                    put("image_sha256", imageHash)
                    put("device_sha256", deviceHash)
                    put("total_bytes", image.total)
                    put("confirmed_offset", partial.status.confirmedOffset)
                    put("queued_bytes", partial.queuedBytes)
                    put("device_state", partial.status.state.name)
                    put("paused_at_ms", System.currentTimeMillis())
                    put("stage", "CONFIRMED_PARTIAL_BEFORE_END")
                    put("program_wip_cut_tested", false)
                }.toString())
            } else if (action == "erase_cut") {
                assertTrue("A verified complete secondary image receipt is required", receipt.isFile)
                val verified = JSONObject(receipt.readText())
                assertEquals("Receipt belongs to a different image", imageHash, verified.getString("image_sha256"))
                assertEquals("Receipt belongs to a different device", deviceHash, verified.getString("device_sha256"))
                assertEquals(image.total, verified.getLong("total_bytes"))
                // Invalidate local VERIFY proof before the explicit staging CANCEL.
                receipt.delete()
                File(maintenance, "erase-cut-attempt.json").delete()
                var currentToast: Toast? = null
                val promptTiming = InstrumentationRegistry.getArguments().getString("erasePromptTiming") ?: "before_start_write"
                val attempt = attemptManualEraseCut(AndroidOtaTransport(manager), image, auth,
                    promptTiming = promptTiming,
                    toast = { text -> main {
                        currentToast?.cancel()
                        currentToast = Toast.makeText(context, text, Toast.LENGTH_SHORT).also { it.show() }
                    } },
                    checkpoint = { checkpoint -> report(checkpoint) }
                )
                File(maintenance, "erase-cut-attempt.json").writeText(JSONObject().apply {
                    put("image_sha256", imageHash)
                    put("device_sha256", deviceHash)
                    put("total_bytes", image.total)
                    put("prompt_timing", attempt.promptTiming)
                    put("prompt_issued_elapsed_ms", attempt.promptIssuedAt)
                    put("start_issued_elapsed_ms", attempt.startIssuedAt)
                    put("start_write_returned_elapsed_ms", attempt.writeReturnedAt)
                    put("start_write_acknowledged", attempt.writeAcknowledged)
                    put("ready_observed_elapsed_ms", attempt.readyObservedAt)
                    put("disconnected_observed_elapsed_ms", attempt.disconnectedObservedAt)
                    put("verdict", attempt.verdict)
                    put("raw_secondary_evidence_required", true)
                    put("nor_wip_pulse_observed", false)
                    put("attempt_at_ms", System.currentTimeMillis())
                }.toString())
                // CANCEL invalidates the previous VERIFY receipt even if the operator misses the cut.
                receipt.delete()
                assertEquals("Manual erase cut not demonstrated; READY or timeout means a missed window",
                    "CUT_CANDIDATE_RAW_SECONDARY_EVIDENCE_REQUIRED", attempt.verdict)
            } else {
                assertTrue("A completed upload receipt is required", receipt.isFile)
                val verified = JSONObject(receipt.readText())
                assertEquals("Receipt belongs to a different image", imageHash, verified.getString("image_sha256"))
                assertEquals("Receipt belongs to a different device", deviceHash, verified.getString("device_sha256"))
                assertEquals(image.total, verified.getLong("total_bytes"))
                val runner = OtaRunner(AndroidOtaTransport(manager), authKey = auth)
                assertTrue("OTA status reattachment failed", runner.prepareTrigger())
                assertTrue("Device rejected TRIGGER", runner.triggerUpgrade())
                receipt.delete()
                imageFile.delete()
                report("TRIGGER_REQUESTED_REBOOT_REQUIRES_SEPARATE_VERIFICATION")
            }
        } finally {
            // No cleanup CANCEL: upload leaves VERIFY; write_pause leaves confirmed partial staging.
            val authorizationRemoved = !authFile.exists() || authFile.delete()
            try {
                if (ota != null) main {
                    ota!!.reset()
                    previousAuth?.let { ota!!.onAuthKeyChanged(it, persist = false) }
                }
            } finally {
                if (exclusive) withContext(NonCancellable) { vm?.endExclusiveOta() }
            }
            assertTrue("Temporary authorization file cleanup failed", authorizationRemoved)
        }
    }
}
