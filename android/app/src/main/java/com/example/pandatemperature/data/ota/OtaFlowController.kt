package com.example.pandatemperature.data.ota

/**
 * OTA 数据传输流控。
 *
 * 核心规则（来自 `OTA协议说明.md` §6）：**必须以设备 Status 里「已写入 Flash 的连续偏移」为准**，
 * 不能用"我已发出多少字节"。未确认字节数不超过 [windowBytes] 时继续发送，超过就等通知。
 *
 * 该类只做纯计算，不碰 BLE，便于单元测试。
 *
 * @param total 镜像总字节数
 * @param windowBytes 流控窗口（默认 4 KiB）
 */
class OtaFlowController(
    val total: Long,
    private val windowBytes: Int = OtaConstants.FLOW_WINDOW_BYTES
) {

    /** 已交给传输层的字节数（顺序追加，等价于镜像里的下一个偏移）。 */
    var queuedOffset: Long = 0L
        private set

    /** 设备已写入 Flash 的连续偏移（来自 Status 通知）。 */
    var confirmedOffset: Long = 0L
        private set

    /** 更新设备已确认偏移（截断到 [0, total]）。 */
    fun onConfirmed(offset: Long) {
        confirmedOffset = offset.coerceIn(0L, total)
    }

    /** 续传：以设备回报的偏移作为发送与确认的共同起点。 */
    fun resumeFromDeviceOffset(offset: Long) {
        val clamped = offset.coerceIn(0L, total)
        queuedOffset = clamped
        confirmedOffset = clamped
    }

    /** 是否还能继续排队发送（未确认字节数未超过窗口）。 */
    fun canQueueMore(): Boolean = (queuedOffset - confirmedOffset) < windowBytes

    /** 未确认字节数。 */
    fun unconfirmedBytes(): Long = (queuedOffset - confirmedOffset).coerceAtLeast(0L)

    /** 剩余待发送字节数。 */
    fun remaining(): Long = (total - queuedOffset).coerceAtLeast(0L)

    /** 已全部排队发送完毕。 */
    fun isFullyQueued(): Boolean = queuedOffset >= total

    /**
     * 下一片大小：不超过 [chunkSize]（= 协商 MTU − 3），也不超过剩余字节数。
     * 返回 0 表示没有可发送的数据。
     */
    fun nextChunkSize(chunkSize: Int): Int {
        val size = minOf(chunkSize.toLong(), remaining())
        return if (size <= 0L) 0 else size.toInt()
    }

    /** 记录一片已排队发送。 */
    fun onQueued(length: Int) {
        queuedOffset = (queuedOffset + length).coerceAtMost(total)
    }

    /**
     * 设备会把一整页（[OtaConstants.RESUME_ALIGNMENT]）写满才推进确认偏移，
     * 因此尾部不足一页的数据在 END 前不会落盘。这个边界是"整页都已写盘"的下界。
     */
    fun fullyDrainedBoundary(): Long = total - (total % OtaConstants.RESUME_ALIGNMENT)

    /** 设备是否已把所有整页写入 Flash（尾部不足一页的部分留给 END flush）。 */
    fun isAllPagesWritten(): Boolean = confirmedOffset >= fullyDrainedBoundary()

    /** 进度百分比（以设备已确认偏移为准，0~100）。 */
    fun progressPercent(): Int =
        if (total <= 0L) 0 else ((confirmedOffset * 100L) / total).toInt().coerceIn(0, 100)

    companion object {
        /** 把偏移向下对齐到 [OtaConstants.RESUME_ALIGNMENT] 边界。 */
        fun alignDownToBlock(offset: Long): Long {
            val block = OtaConstants.RESUME_ALIGNMENT.toLong()
            return offset - (offset % block)
        }
    }
}
