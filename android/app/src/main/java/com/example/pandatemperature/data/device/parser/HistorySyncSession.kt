package com.example.pandatemperature.data.device.parser

/** Owns a transfer until cleanup finishes; stale callbacks cannot affect its successor. */
class HistorySyncSession {
    private var generation = 0L
    private var active = false
    private var finishing = false

    @Synchronized fun begin(): Long? {
        if (active) return null
        active = true
        finishing = false
        return ++generation
    }

    @Synchronized fun owns(session: Long): Boolean = active && generation == session

    @Synchronized fun acceptsPackets(session: Long): Boolean = owns(session) && !finishing

    @Synchronized fun beginFinish(session: Long): Boolean {
        if (!acceptsPackets(session)) return false
        finishing = true
        return true
    }

    @Synchronized fun complete(session: Long): Boolean {
        if (!owns(session)) return false
        active = false
        finishing = false
        return true
    }

    @Synchronized fun invalidate() {
        generation++
        active = false
        finishing = false
    }
}
