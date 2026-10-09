package com.example.pandatemperature.data.device.parser

/** Uses a monotonic clock: an active transfer may outlive the idle timeout. */
class HistorySyncProgress(
    private val idleTimeoutMs: Long,
    startedAtMs: Long,
    initialReceivedCount: Int
) {
    private var lastProgressAtMs = startedAtMs
    private var lastReceivedCount = initialReceivedCount

    fun hasTimedOut(nowMs: Long, receivedCount: Int): Boolean {
        if (receivedCount > lastReceivedCount) {
            lastReceivedCount = receivedCount
            lastProgressAtMs = nowMs
        }
        return nowMs - lastProgressAtMs >= idleTimeoutMs
    }
}

/** A future record must never become the lower bound of incremental requests. */
fun historyResumeTimestamp(latestTimestamp: Long?, nowSeconds: Long): Long =
    latestTimestamp?.takeIf { it > 0 && it <= nowSeconds } ?: 0L
