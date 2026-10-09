package com.example.pandatemperature.data.device.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RealtimeDataParserTest {

    private val parser = RealtimeDataParserV2()

    /** 构造温度 25.34°C / 湿度 45.67%RH / 气压 1013.2hPa 的 6 字节基础帧。 */
    private fun sixBytePacket(): ByteArray =
        ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(2534.toShort())
            .putShort(4567.toShort())
            .putShort(10132.toShort())
            .array()

    private fun eightBytePacket(voltageMillivolts: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(2534.toShort())
            .putShort(4567.toShort())
            .putShort(10132.toShort())
            .putShort(voltageMillivolts.toShort())
            .array()

    @Test
    fun parsesLegacySixByteRealtimePacketWithoutVoltage() {
        val result = parser.parse(sixBytePacket())

        assertEquals(25.34f, result?.temperature ?: error("temperature missing"), 0.001f)
        assertEquals(45.67f, result?.humidity ?: error("humidity missing"), 0.001f)
        assertEquals(1013.2f, result?.pressure ?: error("pressure missing"), 0.001f)
        assertNull(result?.batteryVoltage)
    }

    /** 旧协议 6 字节帧必须继续兼容：不携带电压字段，且 reported 标记为 false。 */
    @Test
    fun legacySixBytePacketDoesNotReportVoltageField() {
        val result = parser.parse(sixBytePacket())

        assertNull(result?.batteryVoltage)
        assertFalse(result?.batteryVoltageReported ?: true)
    }

    /** 8 字节帧携带了电压字段，即使值为无效，也应当标记 reported=true。 */
    @Test
    fun eightBytePacketReportsVoltageField() {
        val result = parser.parse(eightBytePacket(2900))

        assertTrue(result?.batteryVoltageReported ?: false)
    }

    @Test
    fun parsesOptionalEightByteRealtimePacketVoltageInVolts() {
        val result = parser.parse(eightBytePacket(2900))

        assertEquals(2.9f, result?.batteryVoltage ?: error("voltage missing"), 0.001f)
    }

    /** 本次核心回归：固件哨兵 0xFFFF 表示「暂无有效电压」，必须映射为 null（界面显示 --）。 */
    @Test
    fun mapsInvalidSentinelVoltageToNullInsteadOfAbsurdReading() {
        val result = parser.parse(eightBytePacket(0xFFFF))

        assertNull(result?.batteryVoltage)
        // 哨兵值只影响电压字段，温湿度气压仍应正常解析。
        assertEquals(25.34f, result?.temperature ?: error("temperature missing"), 0.001f)
        assertEquals(45.67f, result?.humidity ?: error("humidity missing"), 0.001f)
        assertEquals(1013.2f, result?.pressure ?: error("pressure missing"), 0.001f)
    }

    /** 正常电压读数仍要能解析，避免量程/哨兵判断「误伤」合法值。 */
    @Test
    fun parsesTypicalEightBytePacketVoltage() {
        assertEquals(3.3f, parser.parse(eightBytePacket(0x0CE4))?.batteryVoltage ?: error("voltage missing"), 0.001f)
    }

    /**
     * 量程检查是第二道防线：0 mV 不是哨兵，但低于 nRF52810 的 1.7 V 工作下限，
     * 物理上不可能，按设计统一判为无效。
     */
    @Test
    fun treatsZeroMillivoltsAsInvalidOutOfRange() {
        assertNull(parser.parse(eightBytePacket(0x0000))?.batteryVoltage)
    }

    @Test
    fun acceptsVoltageExactlyAtLowerBound() {
        assertEquals(1.7f, parser.parse(eightBytePacket(0x06A4))?.batteryVoltage ?: error("voltage missing"), 0.001f)
    }

    @Test
    fun acceptsVoltageExactlyAtUpperBound() {
        assertEquals(3.6f, parser.parse(eightBytePacket(0x0E10))?.batteryVoltage ?: error("voltage missing"), 0.001f)
    }

    @Test
    fun rejectsVoltageJustBelowLowerBound() {
        assertNull(parser.parse(eightBytePacket(0x06A3))?.batteryVoltage)
    }

    @Test
    fun rejectsVoltageJustAboveUpperBound() {
        assertNull(parser.parse(eightBytePacket(0x0E11))?.batteryVoltage)
    }

    /** 协议常量与解析行为对齐，防止量程边界随时间漂移。 */
    @Test
    fun exposesDeclaredVoltageRangeAndSentinel() {
        assertEquals(1700, RealtimeDataParserV2.BATTERY_VOLTAGE_MIN_MV)
        assertEquals(3600, RealtimeDataParserV2.BATTERY_VOLTAGE_MAX_MV)
        assertEquals(0xFFFF, RealtimeDataParserV2.BATTERY_VOLTAGE_INVALID_MV)
        assertEquals(6, RealtimeDataParserV2.BATTERY_VOLTAGE_OFFSET)
        assertEquals(8, RealtimeDataParserV2.FRAME_SIZE_WITH_VOLTAGE)
    }
}
