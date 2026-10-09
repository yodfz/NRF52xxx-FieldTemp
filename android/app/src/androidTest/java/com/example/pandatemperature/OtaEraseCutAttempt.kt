package com.example.pandatemperature

import android.os.SystemClock
import com.example.pandatemperature.data.ota.AndroidOtaTransport
import com.example.pandatemperature.data.ota.OtaConstants
import com.example.pandatemperature.data.ota.OtaImageInfo
import com.example.pandatemperature.data.ota.OtaMessages
import com.example.pandatemperature.data.ota.OtaState
import com.example.pandatemperature.data.ota.OtaStatus
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*

internal data class EraseCutAttempt(
    val startIssuedAt: Long,
    val writeReturnedAt: Long,
    val writeAcknowledged: Boolean,
    val readyObservedAt: Long,
    val disconnectedObservedAt: Long,
    val verdict: String,
    val promptIssuedAt: Long,
    val promptTiming: String
)

/** Manual logical erase-window attempt only. NOR WIP and raw slot evidence are external checks. */
internal suspend fun attemptManualEraseCut(
    transport: AndroidOtaTransport,
    image: OtaImageInfo,
    auth: ByteArray,
    promptTiming: String = "before_start_write",
    toast: (String) -> Unit,
    checkpoint: (String) -> Unit
): EraseCutAttempt {
    require(promptTiming == "before_start_write") { "Only before_start_write prompt timing is supported" }
    val statuses = Channel<OtaStatus>(Channel.UNLIMITED)
    val readyAt = AtomicLong(0L)
    val startAt = AtomicLong(0L)
    val mtu = transport.requestMtu(OtaConstants.PREFERRED_MTU, OtaConstants.FALLBACK_MTU)
    assertTrue("MTU cannot fit START", mtu >= OtaConstants.MIN_START_MTU)
    assertTrue("OTA status subscription failed", transport.subscribeStatus { status ->
        if (startAt.get() > 0 && status.state == OtaState.READY) {
            readyAt.compareAndSet(0L, SystemClock.elapsedRealtime())
        }
        statuses.trySend(status)
    })
    assertTrue("CANCEL staging reset rejected", transport.writeControl(OtaMessages.buildCancel()))
    val idle = withTimeoutOrNull(OtaConstants.START_READY_TIMEOUT_MS) {
        var status = statuses.receive()
        while (status.state != OtaState.IDLE && !status.isError) status = statuses.receive()
        status
    }
    assertNotNull("CANCEL did not reach IDLE", idle)
    assertFalse("CANCEL returned an OTA error", idle!!.isError)
    while (statuses.tryReceive().isSuccess) { }
    for (seconds in 10 downTo 1) {
        assertTrue("Disconnected before START; no erase attempt", transport.isConnected())
        toast("擦除断电测试：$seconds 秒后准备拔电池")
        checkpoint("ERASE_COUNTDOWN_$seconds")
        delay(1000)
    }
    assertTrue("Disconnected before START; no erase attempt", transport.isConnected())
    val startMessage = OtaMessages.buildStart(image.total, image.ihVer, image.sha256, auth)
    val promptAt = SystemClock.elapsedRealtime()
    toast("现在拔电池")
    // No checkpoint IPC or ACK wait between the prompt and the START write.
    startAt.set(SystemClock.elapsedRealtime())
    val acknowledged = transport.writeControl(startMessage)
    val returnedAt = SystemClock.elapsedRealtime()
    // A failed ACK, including power cut before START arrived, does not prove an erase happened.
    checkpoint("START_ISSUED_AFTER_BEFORE_START_WRITE_PROMPT")
    checkpoint(if (acknowledged) "START_WRITE_ACKNOWLEDGED" else "START_WRITE_ACK_FAILED_DELIVERY_UNKNOWN")
    val deadline = SystemClock.elapsedRealtime() + 15_000
    while (transport.isConnected() && readyAt.get() == 0L && SystemClock.elapsedRealtime() < deadline) {
        delay(10)
    }
    val disconnectedAt = if (!transport.isConnected()) SystemClock.elapsedRealtime() else 0L
    val readyObserved = readyAt.get()
    val verdict = when {
        readyObserved > 0L -> "MISSED_ERASE_WINDOW_READY_OBSERVED"
        disconnectedAt > 0L -> "CUT_CANDIDATE_RAW_SECONDARY_EVIDENCE_REQUIRED"
        else -> "MISSED_ERASE_WINDOW_NO_DISCONNECT"
    }
    checkpoint(verdict)
    return EraseCutAttempt(startAt.get(), returnedAt, acknowledged, readyObserved, disconnectedAt, verdict, promptAt, promptTiming)
}
