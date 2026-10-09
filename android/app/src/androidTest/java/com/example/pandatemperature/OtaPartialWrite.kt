package com.example.pandatemperature

import com.example.pandatemperature.data.ota.AndroidOtaTransport
import com.example.pandatemperature.data.ota.OtaConstants
import com.example.pandatemperature.data.ota.OtaFlowController
import com.example.pandatemperature.data.ota.OtaImageInfo
import com.example.pandatemperature.data.ota.OtaMessages
import com.example.pandatemperature.data.ota.OtaState
import com.example.pandatemperature.data.ota.OtaStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*

internal data class PartialOtaWrite(val status: OtaStatus, val queuedBytes: Long)

/** Test-only END-before pause, using production messages, transport and confirmed-byte flow control. */
internal suspend fun writeConfirmedPartialImage(
    transport: AndroidOtaTransport,
    image: OtaImageInfo,
    bytes: ByteArray,
    auth: ByteArray,
    checkpoint: (String, OtaStatus, Long) -> Unit
): PartialOtaWrite {
    val threshold = 32_768L
    assertTrue("Partial-write stage requires an image larger than threshold plus flow window",
        image.total > threshold + OtaConstants.FLOW_WINDOW_BYTES)
    val mtu = transport.requestMtu(OtaConstants.PREFERRED_MTU, OtaConstants.FALLBACK_MTU)
    assertTrue("MTU cannot fit START", mtu >= OtaConstants.MIN_START_MTU)
    val chunkSize = OtaConstants.dataChunkSize(mtu)
    val statuses = Channel<OtaStatus>(Channel.UNLIMITED)
    assertTrue("OTA status subscription failed", transport.subscribeStatus { statuses.trySend(it) })

    suspend fun awaitStatus(timeout: Long, accept: (OtaStatus) -> Boolean): OtaStatus {
        return requireNotNull(withTimeoutOrNull(timeout) {
            while (true) {
                val status = statuses.receive()
                assertFalse("OTA device error code=${status.error.value}", status.isError)
                if (accept(status)) return@withTimeoutOrNull status
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }) { "OTA partial-write status timeout" }
    }
    // This explicit destructive-to-staging action resets VERIFY/resume only, never module history.
    assertTrue("CANCEL staging reset rejected", transport.writeControl(OtaMessages.buildCancel()))
    awaitStatus(OtaConstants.START_READY_TIMEOUT_MS) { it.state == OtaState.IDLE }
    while (statuses.tryReceive().isSuccess) { }
    assertTrue("START rejected", transport.writeControl(OtaMessages.buildStart(image.total, image.ihVer, image.sha256, auth)))
    val ready = awaitStatus(OtaConstants.START_READY_TIMEOUT_MS) { it.state == OtaState.READY }
    assertEquals("A reset staging slot must start at zero", 0L, ready.confirmedOffset)
    assertEquals(image.total, ready.total)
    checkpoint("READY_FOR_PARTIAL_WRITE", ready, 0)
    val flow = OtaFlowController(image.total)
    flow.resumeFromDeviceOffset(ready.confirmedOffset)
    var latest = ready
    fun consume(status: OtaStatus) {
        assertFalse("OTA device error code=${status.error.value}", status.isError)
        if (status.state == OtaState.RECEIVING || status.state == OtaState.READY) {
            assertEquals("OTA status image total changed", image.total, status.total)
            latest = status
            flow.onConfirmed(status.confirmedOffset)
        }
    }
    var nextReportAt = 0L
    while (flow.confirmedOffset < threshold) {
        while (true) consume(statuses.tryReceive().getOrNull() ?: break)
        if (flow.confirmedOffset >= threshold) break
        if (!flow.canQueueMore()) {
            consume(awaitStatus(8_000L) { it.state == OtaState.RECEIVING })
            continue
        }
        // Keep at least one page unsent; END/TRIGGER are deliberately absent from this stage.
        val length = minOf(flow.nextChunkSize(chunkSize), (image.total - OtaConstants.RESUME_ALIGNMENT - flow.queuedOffset).toInt())
        assertTrue("Partial-write budget exhausted before confirmed threshold", length > 0)
        val offset = flow.queuedOffset.toInt()
        assertTrue("Partial Data write failed", transport.writeData(bytes.copyOfRange(offset, offset + length)))
        flow.onQueued(length)
        val now = android.os.SystemClock.elapsedRealtime()
        if (now >= nextReportAt) {
            checkpoint("WRITING_PARTIAL_IMAGE", latest, flow.queuedOffset)
            nextReportAt = now + 1_000
        }
    }
    assertEquals(OtaState.RECEIVING, latest.state)
    assertTrue("Need a genuine device-confirmed partial image", latest.confirmedOffset >= threshold && latest.confirmedOffset < image.total)
    assertTrue("Queued bytes must remain below total", flow.queuedOffset < image.total)
    checkpoint("PARTIAL_CONFIRMED_NO_END_NO_TRIGGER_NOT_WIP", latest, flow.queuedOffset)
    return PartialOtaWrite(latest, flow.queuedOffset)
}
