package com.example.pandatemperature.data.device.parser

import org.junit.Assert.*
import org.junit.Test

class HistorySyncSessionTest {
    @Test fun cleanupKeepsTransferBusyUntilConsumerAndNotificationsAreStopped() {
        val sessions = HistorySyncSession()
        val first = sessions.begin()!!
        assertTrue(sessions.beginFinish(first))
        assertNull(sessions.begin()) // rapid retry during suspended cleanup
        assertFalse(sessions.acceptsPackets(first))
        assertTrue(sessions.complete(first))
        val second = sessions.begin()!!
        assertTrue(sessions.acceptsPackets(second))
    }

    @Test fun staleFinishCannotReleaseTheReconnectedTransfer() {
        val sessions = HistorySyncSession()
        val disconnected = sessions.begin()!!
        assertTrue(sessions.beginFinish(disconnected))
        sessions.invalidate()
        val reconnected = sessions.begin()!!
        assertFalse(sessions.complete(disconnected))
        assertFalse(sessions.beginFinish(disconnected))
        assertTrue(sessions.owns(reconnected))
        assertTrue(sessions.acceptsPackets(reconnected))
        assertNull(sessions.begin())
    }

    @Test fun latePacketsAndDuplicateEndMarkersAreRejected() {
        val sessions = HistorySyncSession()
        val first = sessions.begin()!!
        assertTrue(sessions.beginFinish(first))
        assertFalse(sessions.beginFinish(first))
        assertTrue(sessions.complete(first))
        val second = sessions.begin()!!
        assertFalse(sessions.acceptsPackets(first))
        assertFalse(sessions.beginFinish(first))
        assertTrue(sessions.acceptsPackets(second))
    }

    @Test fun repeatedSyncAndReconnectDoNotLeaveTheGateBusy() {
        val sessions = HistorySyncSession()
        repeat(20) {
            val transfer = sessions.begin()!!
            if (it % 2 == 0) {
                assertTrue(sessions.beginFinish(transfer))
                assertTrue(sessions.complete(transfer))
            } else {
                sessions.invalidate()
                assertFalse(sessions.complete(transfer))
            }
        }
        assertNotNull(sessions.begin())
    }
}
