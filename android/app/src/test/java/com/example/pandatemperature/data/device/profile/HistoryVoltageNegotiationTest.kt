package com.example.pandatemperature.data.device.profile

import com.example.pandatemperature.data.device.model.DeviceTypes
import com.example.pandatemperature.data.device.model.ThermometerData
import com.example.pandatemperature.data.device.parser.DeviceStatusParser
import com.example.pandatemperature.data.device.parser.HistoryFormatAware
import com.example.pandatemperature.data.device.profile.thermometer.ThermometerProfile
import com.example.pandatemperature.data.model.TemperatureRecord
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HistoryVoltageNegotiationTest {
    private fun statusFrame(patch: Int, capabilities: Int): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(60).putShort(3000).put(0x0B).put(capabilities.toByte())
            .putShort(patch.toShort()).putShort(1).putShort(2).array()

    private fun profileFromStatus(patch: Int, capabilities: Int): ThermometerProfile {
        val status = DeviceStatusParser().parse(statusFrame(patch, capabilities))!!
        return DeviceProfileFactory.createThermometerProfile(
            status.firmwareVersion, capabilityFlags = status.capabilityFlags
        )
    }

    @Test
    fun patchEightWithoutVoltageCapabilityRetainsV2RequestAndParser() {
        for (capabilities in listOf(0, 0x3F, 0x80, 0xBF)) {
            val profile = profileFromStatus(8, capabilities)
            assertEquals(12, (profile.getHistoryParser("test-device") as HistoryFormatAware).historyRecordSize)
            assertArrayEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12), profile.buildHistoryRequest(0x12345678L))
        }
    }

    @Test
    fun explicitCapabilityPairsFourteenByteParserWithFiveByteRequest() {
        val status = DeviceStatusParser().parse(statusFrame(8, 0x7F))!!
        assertTrue(status.supportsHistoryVoltage)
        val profile = profileFromStatus(8, 0x7F)
        assertEquals(14, (profile.getHistoryParser("test-device") as HistoryFormatAware).historyRecordSize)
        assertArrayEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12, 0x03), profile.buildHistoryRequest(0x12345678L))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 3), profile.buildHistoryRequest(0))
    }

    @Test
    fun requestTimestampRetainsUnsignedUint32Range() {
        val legacy = profileFromStatus(8, 0x3F)
        val voltage = profileFromStatus(8, 0x7F)
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), legacy.buildHistoryRequest(0xFFFFFFFFL))
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1, 3), voltage.buildHistoryRequest(0xFFFFFFFFL))
    }

    @Test
    fun genericFactoryPropagatesCapabilitiesForThermometerAndUnknownType() {
        for (type in listOf(DeviceTypes.THERMOMETER, DeviceTypes.UNKNOWN)) {
            val selected = DeviceProfileFactory.createProfile<ThermometerData, TemperatureRecord>(
                type, 8, capabilityFlags = 0x40
            )!! as ThermometerProfile
            assertEquals(14, (selected.getHistoryParser("test-device") as HistoryFormatAware).historyRecordSize)
            assertEquals(5, selected.buildHistoryRequest(0).size)
        }
    }

    @Test
    fun capabilityRatherThanVersionEnablesVoltageEvenWithoutVersion() {
        val selected = DeviceProfileFactory.createThermometerProfile(null, capabilityFlags = 0x40)
        assertEquals(14, (selected.getHistoryParser("test-device") as HistoryFormatAware).historyRecordSize)
        assertEquals(5, selected.buildHistoryRequest(0).size)
        val legacy = DeviceProfileFactory.createThermometerProfile(null)
        assertEquals(8, (legacy.getHistoryParser("test-device") as HistoryFormatAware).historyRecordSize)
        assertEquals(4, legacy.buildHistoryRequest(0).size)
    }

    @Test
    fun negotiatedV3ParsesMixedUnknownAndMeasuredVoltageWithoutShiftingRecords() {
        val packet = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        for ((timestamp, millivolts) in listOf(1700000000 to 0xFFFF, 1700000060 to 2990)) {
            packet.putInt(timestamp).putShort(2500).putShort(5000).putInt(100000)
                .putShort(millivolts.toShort())
        }
        val records = profileFromStatus(8, 0x7F).getHistoryParser("test-device").parse(packet.array())!!
        assertEquals(2, records.size)
        assertEquals(listOf(1700000000L, 1700000060L), records.map { it.timestamp })
        assertNull(records[0].batteryVoltage)
        assertEquals(2.99f, records[1].batteryVoltage!!, 0.001f)
        records.forEach {
            assertEquals(25f, it.temperature, 0f)
            assertEquals(50f, it.humidity, 0f)
            assertEquals(1000f, it.pressure!!, 0f)
        }
    }
}
