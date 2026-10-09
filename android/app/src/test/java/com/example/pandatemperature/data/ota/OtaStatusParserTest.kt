package com.example.pandatemperature.data.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OtaStatusParserTest {

    private fun status(
        offset: Long,
        total: Long,
        state: Int,
        err: Int,
        progress: Int
    ): ByteArray = ByteBuffer.allocate(OtaConstants.STATUS_MESSAGE_SIZE)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(offset.toInt())
        .putInt(total.toInt())
        .put(state.toByte())
        .put(err.toByte())
        .put(progress.toByte())
        .put(0)
        .array()

    @Test
    fun parsesReceivingStatus() {
        val parsed = OtaStatusParser.parse(status(4000, 144_936, OtaState.RECEIVING.value, 0, 2))
            ?: error("status missing")

        assertEquals(4000L, parsed.confirmedOffset)
        assertEquals(144_936L, parsed.total)
        assertEquals(OtaState.RECEIVING, parsed.state)
        assertEquals(OtaError.NONE, parsed.error)
        assertEquals(2, parsed.progressPercent)
        assertEquals(0, parsed.flags)
    }

    @Test
    fun flagsErrorStateFromDevice() {
        val parsed = OtaStatusParser.parse(status(0, 144_936, OtaState.ERROR.value, OtaError.AUTH.value, 0))
            ?: error("status missing")

        assertTrue(parsed.isError)
        assertEquals(OtaError.AUTH, parsed.error)
    }

    @Test
    fun parsesLargeOffsetsAsUnsigned() {
        val parsed = OtaStatusParser.parse(status(0xFFFFFFFFL, 144_936, OtaState.RECEIVING.value, 0, 100))
            ?: error("status missing")

        assertEquals(0xFFFFFFFFL, parsed.confirmedOffset)
    }

    @Test
    fun returnsNullForShortMessage() {
        assertNull(OtaStatusParser.parse(ByteArray(OtaConstants.STATUS_MESSAGE_SIZE - 1)))
    }

    @Test
    fun parsesOverrunErrorIntroducedByAsyncStartFirmware() {
        val parsed = OtaStatusParser.parse(status(108_800, 144_936, OtaState.ERROR.value, 11, 75))
            ?: error("status missing")

        assertTrue(parsed.isError)
        assertEquals(OtaError.OVERRUN, parsed.error)
        assertEquals(11, parsed.error.value)
        // 溢出是保护性中止：已确认偏移仍然有效，可用于重发 START 后续传
        assertEquals(108_800L, parsed.confirmedOffset)
        assertTrue(parsed.error.label.contains("溢出"))
    }

    @Test
    fun unknownStateValueIsRejected() {
        assertNull(OtaStatusParser.parse(status(0, 100, 99, 0, 0)))
    }
}
