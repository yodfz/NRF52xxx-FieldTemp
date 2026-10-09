package com.example.pandatemperature.data.device.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HistoryDataParserTest {

    @Test
    fun parsesEightByteV1RecordWithoutPressureOrVoltage() {
        val packet = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1_700_000_000)
            .putShort(2534.toShort())
            .putShort(4567.toShort())
            .array()

        val record = HistoryDataParser("AA:BB", HistoryRecordFormat.V1)
            .parse(packet)
            ?.single() ?: error("record missing")

        assertEquals(1_700_000_000L, record.timestamp)
        assertEquals(25.34f, record.temperature, 0.001f)
        assertEquals(45.67f, record.humidity, 0.001f)
        assertNull(record.pressure)
        assertNull(record.batteryVoltage)
    }

    @Test
    fun parsesTwelveByteV2RecordWithoutVoltage() {
        val packet = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1_700_000_000)
            .putShort(2534.toShort())
            .putShort(4567.toShort())
            .putInt(101325)
            .array()

        val record = HistoryDataParser("AA:BB", HistoryRecordFormat.V2)
            .parse(packet)
            ?.single() ?: error("record missing")

        assertEquals(1013.25f, record.pressure ?: error("pressure missing"), 0.001f)
        assertNull(record.batteryVoltage)
    }

    @Test
    fun parsesFourteenByteV3RecordWithVoltage() {
        val packet = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1_700_000_000)
            .putShort(2534.toShort())
            .putShort(4567.toShort())
            .putInt(101325)
            .putShort(2900.toShort())
            .array()

        val record = HistoryDataParser("AA:BB", HistoryRecordFormat.V3)
            .parse(packet)
            ?.single() ?: error("record missing")

        assertEquals(2.9f, record.batteryVoltage ?: error("voltage missing"), 0.001f)
    }

    @Test
    fun exposesSelectedFormatForDiagnosticsAndContractChecks() {
        val parser = HistoryDataParser("AA:BB", HistoryRecordFormat.V3)

        assertEquals("V3", parser.historyFormatName)
        assertEquals(14, parser.historyRecordSize)
        // expectedMinLength 必须与自述的记录大小一致，避免诊断输出与实际解析错位。
        assertEquals(parser.historyRecordSize, parser.expectedMinLength)
    }

    @Test
    fun keepsRecordSizeMappingStableAcrossFormats() {
        assertEquals(8, HistoryDataParser("AA:BB", HistoryRecordFormat.V1).historyRecordSize)
        assertEquals(12, HistoryDataParser("AA:BB", HistoryRecordFormat.V2).historyRecordSize)
        assertEquals(14, HistoryDataParser("AA:BB", HistoryRecordFormat.V3).historyRecordSize)
    }

    @Test
    fun v3VoltageFieldFillsExactlyTheTailOfTheRecord() {
        // 电压字段必须刚好占满 V3 记录尾部，否则偏移或长度之一被改动而未同步。
        assertEquals(
            HistoryDataParser.RECORD_SIZE_V3,
            HistoryDataParser.VOLTAGE_OFFSET_IN_V3 + HistoryDataParser.VOLTAGE_SIZE
        )
    }
    @Test
    fun v3UnknownVoltageSentinelStaysNullForLegacyStoredHistory() {
        val packet = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1_700_000_000).putShort(2534.toShort()).putShort(4567.toShort())
            .putInt(101325).putShort(0xFFFF.toShort()).array()
        val record = HistoryDataParser("test-device", HistoryRecordFormat.V3).parse(packet)!!.single()
        assertNull(record.batteryVoltage)
        assertEquals(1_700_000_000L, record.timestamp)
        assertEquals(1013.25f, record.pressure!!, 0.001f)
    }
}
