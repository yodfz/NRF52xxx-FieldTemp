package com.example.pandatemperature.data.device.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DeviceStatusParserTest {

    /** 构造只关心「版本号 + 能力字节」的最小帧，用于校验版本显示口径。 */
    private fun labelFor(version: Int, caps: Int): String {
        val frame = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(60)
            .putShort(0)
            .put(0.toByte())
            .put(caps.toByte())
            .putShort(version.toShort())
            .array()
        return parser.parse(frame)!!.firmwareVersionLabel
    }

    private val parser = DeviceStatusParser()

    /**
     * 固件 1.0.6+0 的 12 字节状态帧：
     * interval=60, records=3696, status=0x0B(连接|对时|墙钟可信), caps=0x3F, version=6, sample=1, retention=45
     */
    private fun firmware106Frame(): ByteArray = ByteBuffer.allocate(12)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(60)
        .putShort(3696)
        .put(0x0B)
        .put(0x3F)
        .putShort(6)
        .putShort(1)
        .putShort(45)
        .array()

    @Test
    fun parsesFirmware106StatusFrame() {
        val status = parser.parse(firmware106Frame()) ?: error("status missing")

        assertEquals(60, status.interval)
        assertEquals(3696, status.recordCount)
        assertTrue(status.isConnected)
        assertTrue(status.isTimeSynced)
        assertFalse(status.isDataClearInProgress)
        assertTrue(status.isWallClockTrusted)
        assertEquals(0x3F, status.capabilityFlags)
        assertEquals(6, status.firmwareVersion)
        assertEquals(1, status.sampleInterval)
        assertEquals(45, status.retentionDays)
    }

    @Test
    fun mapsAllSixCapabilityBits() {
        val status = parser.parse(firmware106Frame()) ?: error("status missing")

        assertTrue(status.supportsHistoryInterval)   // bit0
        assertTrue(status.hasFixedSampleInterval)    // bit1
        assertTrue(status.supportsEventRecords)      // bit2
        assertTrue(status.historyIsPeriodMean)       // bit3
        assertTrue(status.supportsWallclockAlign)    // bit4（新增）
        assertTrue(status.supportsPartialWindow)     // bit5（新增）
    }

    @Test
    fun keepsLegacyEightByteFrameCompatible() {
        // 旧固件：8 字节，能力字节为 0，无 sample/retention
        val legacy = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1)
            .putShort(1328)
            .put(0x03)
            .put(0x00)
            .putShort(2)
            .array()

        val status = parser.parse(legacy) ?: error("status missing")

        assertEquals(1, status.interval)
        assertEquals(1328, status.recordCount)
        assertTrue(status.isConnected)
        assertTrue(status.isTimeSynced)
        assertFalse(status.isWallClockTrusted)
        assertEquals(0, status.capabilityFlags)
        assertFalse(status.supportsHistoryInterval)
        assertNull(status.sampleInterval)
        assertNull(status.retentionDays)
        assertEquals("v0.2", status.firmwareVersionLabel)
    }

    @Test
    fun wallClockTrustIsIndependentFromTimeSyncBeforeThisBoot() {
        // 状态位 bit1（对时）为 0、bit3（墙钟可信）为 0：只从 NVS 恢复的旧时间
        val frame = firmware106Frame().also { it[4] = 0x01 } // 仅连接
        val status = parser.parse(frame) ?: error("status missing")

        assertFalse(status.isTimeSynced)
        assertFalse(status.isWallClockTrusted)
    }

    @Test
    fun formatsVersionLabelPerFirmwareGeneration() {
        // 新固件（能力字节非 0）：版本号是 patch 号
        assertEquals("v6", labelFor(version = 6, caps = 0x3F))
        assertEquals("v5", labelFor(version = 5, caps = 0x01))
        // 旧固件（能力字节为 0）：版本号是主/次版本编码
        assertEquals("v1.2", labelFor(version = 12, caps = 0))
        assertEquals("v0.2", labelFor(version = 2, caps = 0))
        // 同一个数字 6 在两种世代下的解释不同，说明口径必须由能力字节决定
        assertEquals("v0.6", labelFor(version = 6, caps = 0))
        // 版本号缺失时不伪造
        assertEquals("--", labelFor(version = 0, caps = 0))
    }

    @Test
    fun formatForLogExposesWallClockAndCapabilities() {
        val log = parser.formatForLog(parser.parse(firmware106Frame())!!)

        assertTrue(log.contains("记录间隔:60秒"))
        assertTrue(log.contains("墙钟:可信"))
        assertTrue(log.contains("能力:0x3F"))
    }

    @Test
    fun returnsNullForTooShortFrame() {
        assertNull(parser.parse(ByteArray(4)))
    }
}
