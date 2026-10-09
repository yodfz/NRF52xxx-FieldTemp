package com.example.pandatemperature.data.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaFlowControllerTest {

    @Test
    fun chunkSizeFollowsNegotiatedMtuNotHardcoded125() {
        assertEquals(125, OtaConstants.dataChunkSize(128))
        assertEquals(62, OtaConstants.dataChunkSize(65))
    }

    @Test
    fun windowBlocksWhenUnconfirmedReachesLimit() {
        val flow = OtaFlowController(total = 100_000L, windowBytes = 4 * 1024)

        flow.onQueued(4096)
        assertFalse(flow.canQueueMore())
        assertEquals(4096L, flow.unconfirmedBytes())

        flow.onConfirmed(1024)
        assertTrue(flow.canQueueMore())
        assertEquals(3072L, flow.unconfirmedBytes())
    }

    @Test
    fun nextChunkNeverExceedsChunkSizeOrRemaining() {
        val flow = OtaFlowController(total = 200L)
        assertEquals(125, flow.nextChunkSize(125))
        flow.onQueued(150)
        assertEquals(50, flow.nextChunkSize(125))
        flow.onQueued(50)
        assertEquals(0, flow.nextChunkSize(125))
        assertTrue(flow.isFullyQueued())
    }

    @Test
    fun resumeUsesDeviceConfirmedOffsetAsStart() {
        val flow = OtaFlowController(total = 10_000L)
        flow.resumeFromDeviceOffset(2048)

        assertEquals(2048L, flow.queuedOffset)
        assertEquals(2048L, flow.confirmedOffset)
        assertEquals(7952L, flow.remaining())
        assertEquals(20, flow.progressPercent())
    }

    @Test
    fun resumeOffsetIsClampedToTotal() {
        val flow = OtaFlowController(total = 1000L)
        flow.resumeFromDeviceOffset(5000)
        assertEquals(1000L, flow.queuedOffset)
    }

    @Test
    fun allPagesWrittenBoundaryLeavesRoomForBufferedTail() {
        // total = 566*256 + 40：设备只会写到 144896，尾部 40 字节留给 END flush。
        val flow = OtaFlowController(total = 144_936L)
        assertEquals(144_896L, flow.fullyDrainedBoundary())
        flow.onConfirmed(144_896)
        assertTrue(flow.isAllPagesWritten())
    }

    @Test
    fun allPagesWrittenIsImmediateForSubPageImage() {
        val flow = OtaFlowController(total = 100L)
        assertEquals(0L, flow.fullyDrainedBoundary())
        assertTrue(flow.isAllPagesWritten())
    }

    /**
     * 流控窗口必须严格以「设备 Status 回报的已写入 Flash 连续偏移」为准，
     * 而不是"我已发出多少字节"：设备把数据放进 256B 缓冲、凑满一页才落盘，
     * 所以 confirmedOffset 会滞后，只认它才能避免把设备 512B 环形缓冲挤爆（err=11）。
     */
    @Test
    fun windowKeysOffDeviceConfirmedFlashOffsetNotBytesSent() {
        val flow = OtaFlowController(total = 100_000L, windowBytes = 4 * 1024)

        // 已排队 8 KiB，但设备一页都没确认 -> 窗口必须关闭
        flow.onQueued(4096)
        flow.onQueued(4096)
        assertFalse(flow.canQueueMore())
        assertEquals(8192L, flow.unconfirmedBytes())

        // 设备只确认了 2 KiB（严重滞后于已发送的 8 KiB）-> 窗口仍关闭
        flow.onConfirmed(2048)
        assertFalse(flow.canQueueMore())
        assertEquals(6144L, flow.unconfirmedBytes())

        // 确认推进到 8 KiB 才重新开窗
        flow.onConfirmed(8192)
        assertTrue(flow.canQueueMore())
        assertEquals(0L, flow.unconfirmedBytes())
    }

    @Test
    fun alignDownUsesResumeAlignment() {
        assertEquals(1024L, OtaFlowController.alignDownToBlock(1024))
        assertEquals(1024L, OtaFlowController.alignDownToBlock(1100))
        assertEquals(0L, OtaFlowController.alignDownToBlock(255))
    }
}
