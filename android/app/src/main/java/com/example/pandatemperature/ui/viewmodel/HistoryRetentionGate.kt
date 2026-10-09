package com.example.pandatemperature.ui.viewmodel

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Destructive reconciliation is allowed only for one complete, stable device window. */
object HistoryRetentionGate {
    fun canTrim(requestTimestamp: Long, endReceived: Boolean, received: Int,
                timestamps: Set<Long>, before: ByteArray?, after: ByteArray?,
                savesSucceeded: Boolean, ownsSession: Boolean): Boolean {
        if (requestTimestamp != 0L || !endReceived || received != 3000 || timestamps.size != 3000 ||
            timestamps.any { it <= 0 } || !savesSucceeded || !ownsSession) return false
        if (before?.size != 8 || after?.size != 8 || !before.contentEquals(after)) return false
        return ByteBuffer.wrap(before).order(ByteOrder.LITTLE_ENDIAN).int == 3000
    }
}
