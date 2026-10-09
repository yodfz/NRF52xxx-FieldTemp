package com.example.pandatemperature.data.device.profile.thermometer

import com.example.pandatemperature.data.device.parser.DataParser
import com.example.pandatemperature.data.device.parser.HistoryDataParser
import com.example.pandatemperature.data.device.parser.HistoryRecordFormat
import com.example.pandatemperature.data.model.TemperatureRecord

/**
 * Explicit capability 0x40 selects 14B history, including millivolts at offset 12.
 * A five-byte request opts into V3; four-byte requests keep old App compatibility.
 */
class ThermometerV3Profile(
    firmwareVersion: Int
) : ThermometerV2Profile(firmwareVersion) {

    override fun buildHistoryRequest(timestamp: Long): ByteArray =
        super.buildHistoryRequest(timestamp) + byteArrayOf(0x03)

    override fun getHistoryParser(deviceId: String): DataParser<List<TemperatureRecord>> {
        return HistoryDataParser(deviceId, HistoryRecordFormat.V3)
    }
}
