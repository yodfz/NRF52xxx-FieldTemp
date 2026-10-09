package com.example.pandatemperature.data.device.parser

import org.junit.Assert.*
import org.junit.Test

class HistorySyncProgressTest {
    @Test fun largeTransferCanContinueBeyondOneMinute() {
        val progress = HistorySyncProgress(60_000, 0, 0)
        assertFalse(progress.hasTimedOut(50_000, 10_000))
        assertFalse(progress.hasTimedOut(100_000, 20_000))
        assertFalse(progress.hasTimedOut(150_000, 30_000))
    }

    @Test fun timeoutStartsFromLastProgress() {
        val progress = HistorySyncProgress(60_000, 0, 0)
        assertFalse(progress.hasTimedOut(30_000, 100))
        assertFalse(progress.hasTimedOut(89_999, 100))
        assertTrue(progress.hasTimedOut(90_000, 100))
    }

    @Test fun idleTransferStillTimesOut() {
        val progress = HistorySyncProgress(60_000, 1_000, 0)
        assertFalse(progress.hasTimedOut(60_999, 0))
        assertTrue(progress.hasTimedOut(61_000, 0))
    }

    @Test fun futureTimestampForcesFullSyncInsteadOfSkippingCurrentRecords() {
        assertEquals(0L, historyResumeTimestamp(2_341_541_226, 1_791_471_053))
        assertEquals(1_791_470_000L, historyResumeTimestamp(1_791_470_000, 1_791_471_053))
        assertEquals(0L, historyResumeTimestamp(null, 1_791_471_053))
    }
}
