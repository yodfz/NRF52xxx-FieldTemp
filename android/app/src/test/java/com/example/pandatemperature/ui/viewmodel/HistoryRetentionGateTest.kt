package com.example.pandatemperature.ui.viewmodel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
class HistoryRetentionGateTest {
    private val timestamps = (1L..3000L).toSet()
    private fun info(count: Int = 3000, start: Int = 9) = ByteBuffer.allocate(8)
        .order(ByteOrder.LITTLE_ENDIAN).putInt(count).putShort(start.toShort()).putShort(14).array()
    private fun gate(timestamp: Long = 0, end: Boolean = true, received: Int = 3000,
        keys: Set<Long> = timestamps, before: ByteArray? = info(), after: ByteArray? = info(),
        saved: Boolean = true, owns: Boolean = true) = HistoryRetentionGate.canTrim(
            timestamp, end, received, keys, before, after, saved, owns)
    @Test fun completeStableWireSetMayTrim() { assertTrue(gate()) }
    @Test fun partialOrDuplicateTransferNeverAuthorizesDeletion() {
        assertFalse(gate(received = 2999)); assertFalse(gate(received = 3001))
        assertFalse(gate(keys = timestamps - 12L)); assertFalse(gate(keys = timestamps - 12L + 0L))
    }
    @Test fun endAndFullRequestAreMandatory() {
        assertFalse(gate(end = false)); assertFalse(gate(timestamp = 1))
    }
    @Test fun freshStableExactInfoIsMandatory() {
        assertFalse(gate(before = null)); assertFalse(gate(after = null))
        assertFalse(gate(after = info(start = 10))); assertFalse(gate(before = info(2999), after = info(2999)))
        assertFalse(gate(before = info() + byteArrayOf(0)))
    }
    @Test fun failedSaveAndStaleSessionCannotDelete() {
        assertFalse(gate(saved = false)); assertFalse(gate(owns = false))
    }
}
