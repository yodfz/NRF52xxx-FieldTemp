package com.example.pandatemperature.data.device.profile

import com.example.pandatemperature.data.bluetooth.BleConstants
import com.example.pandatemperature.data.device.parser.HistoryFormatAware
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceProfileFactoryTest {

    private fun historyRecordSize(
        firmwareVersion: Int?,
        services: Set<String> = emptySet()
    ): Int {
        val profile = DeviceProfileFactory.createThermometerProfile(firmwareVersion, services)
        val parser = profile.getHistoryParser("AA:BB") as HistoryFormatAware
        return parser.historyRecordSize
    }

    /**
     * 隐患回归：新固件（1.0.6+0）在状态帧里上报的是 **patch 号 6**，
     * 绝不能因此被误判成 14 字节 V3 历史。未声明 0x40 能力的固件仍是 12 字节 V2。
     */
    @Test
    fun firmwarePatchNumberSixMustNotSelectFourteenByteHistory() {
        assertEquals(12, historyRecordSize(firmwareVersion = 8))
        assertEquals(12, historyRecordSize(firmwareVersion = 6))
        assertEquals(12, historyRecordSize(firmwareVersion = 5))
        assertEquals(12, historyRecordSize(firmwareVersion = 1))
    }

    @Test
    fun oldTwoDigitVersionFormatStillSelectsTwelveByteHistory() {
        // 旧固件 v1.2 曾编码为 12；同样是 12 字节 V2 历史
        assertEquals(12, historyRecordSize(firmwareVersion = 12))
        assertEquals(12, historyRecordSize(firmwareVersion = 2))
    }

    @Test
    fun realtimeServiceSelectsTwelveByteHistoryEvenWithoutVersion() {
        assertEquals(
            12,
            historyRecordSize(firmwareVersion = null, services = setOf(BleConstants.REALTIME_DATA_SERVICE))
        )
    }

    @Test
    fun legacyEssFirmwareWithoutRealtimeServiceUsesEightByteHistory() {
        assertEquals(8, historyRecordSize(firmwareVersion = null, services = emptySet()))
        assertEquals(8, historyRecordSize(firmwareVersion = 0, services = emptySet()))
    }

    @Test
    fun historyFormatNameIsV2ForFieldFirmware() {
        val profile = DeviceProfileFactory.createThermometerProfile(6)
        val parser = profile.getHistoryParser("AA:BB") as HistoryFormatAware
        assertEquals("V2", parser.historyFormatName)
    }
}
