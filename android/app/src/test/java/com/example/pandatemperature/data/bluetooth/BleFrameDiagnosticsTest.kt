package com.example.pandatemperature.data.bluetooth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BleFrameDiagnosticsTest {

    @After
    fun tearDown() {
        BleFrameDiagnostics.reset()
    }

    private fun realtimeFrame(size: Int): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putShort(2534)
                putShort(4567)
                putShort(10132)
                if (size >= 8) putShort(2900)
            }
            .array()

    @Test
    fun reportsRealtimeFrameWithoutVoltageField() {
        val message = BleFrameDiagnostics.observeRealtime(
            data = realtimeFrame(6),
            minLength = 6,
            voltageOffset = 6
        )

        assertNotNull(message)
        assertTrue(message!!.contains("帧长=6B"))
        assertTrue(message.contains("不含电压字段"))
    }

    @Test
    fun reportsRealtimeFrameContainingVoltageField() {
        val message = BleFrameDiagnostics.observeRealtime(
            data = realtimeFrame(8),
            minLength = 6,
            voltageOffset = 6
        )

        assertNotNull(message)
        assertTrue(message!!.contains("帧长=8B"))
        assertTrue(message.contains("含电压字段"))
    }

    @Test
    fun suppressesDuplicateRealtimeFrameLength() {
        val frame = realtimeFrame(8)

        assertNotNull(BleFrameDiagnostics.observeRealtime(frame, 6, 6))
        assertNull(BleFrameDiagnostics.observeRealtime(frame, 6, 6))
    }

    @Test
    fun reportsAgainWhenRealtimeFrameLengthChanges() {
        assertNotNull(BleFrameDiagnostics.observeRealtime(realtimeFrame(6), 6, 6))

        val message = BleFrameDiagnostics.observeRealtime(realtimeFrame(8), 6, 6)

        assertNotNull(message)
        assertTrue(message!!.contains("帧长=8B"))
    }

    @Test
    fun flagsInvalidSentinelVoltageInRealtimeDiagnostics() {
        val frame = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(2534)
            .putShort(4567)
            .putShort(10132)
            .putShort(0xFFFF.toShort())
            .array()

        val message = BleFrameDiagnostics.observeRealtime(frame, 6, 6)

        assertNotNull(message)
        assertTrue(message!!.contains("无效哨兵"))
        assertTrue(message.contains("0xFFFF"))
    }

    @Test
    fun flagsOutOfRangeVoltageInRealtimeDiagnostics() {
        val frame = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(2534)
            .putShort(4567)
            .putShort(10132)
            .putShort(0) // 0 mV，低于 nRF52810 的 1.7 V 工作下限
            .array()

        val message = BleFrameDiagnostics.observeRealtime(frame, 6, 6)

        assertNotNull(message)
        assertTrue(message!!.contains("超出量程"))
    }

    @Test
    fun resetAllowsBaselineReportAgain() {
        val frame = realtimeFrame(6)

        assertNotNull(BleFrameDiagnostics.observeRealtime(frame, 6, 6))
        assertNull(BleFrameDiagnostics.observeRealtime(frame, 6, 6))

        BleFrameDiagnostics.reset()

        assertNotNull(BleFrameDiagnostics.observeRealtime(frame, 6, 6))
    }

    @Test
    fun reportsHistoryFrameWithSelectedFormatAndRemainder() {
        val message = BleFrameDiagnostics.observeHistory(
            data = ByteArray(28),
            recordSize = 14,
            formatName = "V3"
        )

        assertNotNull(message)
        assertTrue(message!!.contains("帧长=28B"))
        assertTrue(message.contains("格式=V3"))
        assertTrue(message.contains("完整记录=2 条"))
        assertTrue(message.contains("尾部余 0 B"))
    }

    @Test
    fun reportsHistoryTrailingBytesWhenFrameIsNotAligned() {
        val message = BleFrameDiagnostics.observeHistory(
            data = ByteArray(13),
            recordSize = 14,
            formatName = "V3"
        )

        assertNotNull(message)
        assertTrue(message!!.contains("完整记录=0 条"))
        assertTrue(message.contains("尾部余 13 B"))
    }

    @Test
    fun realtimeAndHistoryChannelsAreDeduplicatedIndependently() {
        assertNotNull(BleFrameDiagnostics.observeRealtime(realtimeFrame(8), 6, 6))
        assertNotNull(
            BleFrameDiagnostics.observeHistory(ByteArray(14), 14, "V3")
        )
    }

    @Test
    fun truncatesLongFramePreviewButKeepsFullLength() {
        val message = BleFrameDiagnostics.observeHistory(
            data = ByteArray(64),
            recordSize = 14,
            formatName = "V3"
        )

        assertNotNull(message)
        assertTrue(message!!.contains("帧长=64B"))
        assertTrue(message.contains("共 64B"))
    }

    @Test
    fun voltageFieldOffsetsMatchRealtimeParserContract() {
        // 诊断与解析共用同一组协议常量，防止两处魔数漂移。
        assertEquals(6, com.example.pandatemperature.data.device.parser.RealtimeDataParserV2.BATTERY_VOLTAGE_OFFSET)
        assertEquals(6, com.example.pandatemperature.data.device.parser.RealtimeDataParserV2.FRAME_SIZE_BASE)
        assertEquals(8, com.example.pandatemperature.data.device.parser.RealtimeDataParserV2.FRAME_SIZE_WITH_VOLTAGE)
    }
}
