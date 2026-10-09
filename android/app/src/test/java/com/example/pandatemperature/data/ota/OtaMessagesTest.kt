package com.example.pandatemperature.data.ota

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OtaMessagesTest {

    private val ihVer = byteArrayOf(1, 0, 6, 0, 0, 0, 0, 0)
    private val sha = ByteArray(32) { it.toByte() }

    @Test
    fun startMessageMatchesFirmwareLayoutExactly() {
        val message = OtaMessages.buildStart(
            total = 144_936L,
            ihVer = ihVer,
            sha256 = sha,
            authKey = SAMPLE_AUTH_KEY
        )

        assertEquals(OtaConstants.START_MESSAGE_SIZE, message.size)
        assertEquals(OtaConstants.OP_START, message[0].toInt())
        assertArrayEquals(
            SAMPLE_AUTH_KEY,
            message.copyOfRange(1, 1 + OtaConstants.AUTH_KEY_SIZE)
        )
        val total = ByteBuffer.wrap(message, 17, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        assertEquals(144_936L, total)
        assertArrayEquals(ihVer, message.copyOfRange(21, 29))
        assertArrayEquals(sha, message.copyOfRange(29, 61))
    }

    @Test
    fun controlOpcodesAreSingleBytes() {
        assertEquals(OtaConstants.OP_END, OtaMessages.buildEnd()[0].toInt())
        assertEquals(OtaConstants.OP_CANCEL, OtaMessages.buildCancel()[0].toInt())
        assertEquals(OtaConstants.OP_TRIGGER, OtaMessages.buildTrigger()[0].toInt())
        assertEquals(1, OtaMessages.buildEnd().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun startRejectsWrongAuthKeyLength() {
        OtaMessages.buildStart(1L, ihVer, sha, ByteArray(15))
    }

    @Test(expected = IllegalArgumentException::class)
    fun startRejectsWrongIhVerLength() {
        OtaMessages.buildStart(1L, ByteArray(7), sha, SAMPLE_AUTH_KEY)
    }

    @Test(expected = IllegalArgumentException::class)
    fun startRejectsWrongShaLength() {
        OtaMessages.buildStart(1L, ihVer, ByteArray(31), SAMPLE_AUTH_KEY)
    }
}
