package com.example.pandatemperature.data.device.profile.thermometer

import com.example.pandatemperature.data.bluetooth.BleConstants
import com.example.pandatemperature.data.device.model.DeviceTypes
import com.example.pandatemperature.data.device.model.Feature
import com.example.pandatemperature.data.device.model.MaxMinTemperature
import com.example.pandatemperature.data.device.model.ThermometerData
import com.example.pandatemperature.data.device.parser.*
import com.example.pandatemperature.data.model.DeviceStatus
import com.example.pandatemperature.data.model.TemperatureRecord

/**
 * 温度计 V1 配置（老固件）
 * 
 * 特点：
 * - 使用 ESS 服务（0x181A）的标准温度和湿度特征
 * - 温度和湿度需要分别读取
 * - 不支持气压
 * - 固件版本号为 0 或不存在
 * - 历史数据使用 8 字节格式
 */
class ThermometerV1Profile : ThermometerProfile {
    
    override val deviceType: String = DeviceTypes.THERMOMETER
    
    override val firmwareVersion: Int? = null
    
    override val supportedFeatures: Set<Feature> = setOf(
        Feature.TEMPERATURE,
        Feature.HUMIDITY,
        Feature.HISTORY,
        Feature.TIME_SYNC,
        Feature.INTERVAL_CONFIG
        // 注意：老固件可能不支持 MAX_MIN_TEMPERATURE
    )
    
    override val usesCombinedRealtimeData: Boolean = false
    
    // 老固件使用分离的温度和湿度特征
    override val realtimeDataCharUuid: String = BleConstants.TEMP_CHAR  // 主特征为温度
    override val temperatureCharUuid: String = BleConstants.TEMP_CHAR
    override val humidityCharUuid: String = BleConstants.HUMIDITY_CHAR
    
    // ========== 历史数据获取配置 ==========
    // 老固件不支持 HISTORY_INFO_CHAR（该 UUID 在老固件上可能是电池特征）
    override val supportsHistoryInfo: Boolean = false
    // 老固件使用同步通知方法（与 release/1.0.0 保持一致，兼容性更好）
    override val useAsyncNotificationForHistory: Boolean = false
    
    private val realtimeParser = RealtimeDataParserV1()
    private val statusParser = DeviceStatusParser()
    private val maxMinTempParser = MaxMinTempParser()
    
    override fun getRealtimeDataParser(): DataParser<ThermometerData> {
        // 返回 V1 解析器，但实际使用时需要分别调用 parseTemperature 和 parseHumidity
        return realtimeParser
    }
    
    /**
     * 获取 V1 专用的实时数据解析器
     * 用于分别解析温度和湿度
     */
    fun getRealtimeParserV1(): RealtimeDataParserV1 = realtimeParser
    
    override fun getStatusParser(): DataParser<DeviceStatus> = statusParser
    
    override fun getHistoryParser(deviceId: String): DataParser<List<TemperatureRecord>> {
        return HistoryDataParser(deviceId, HistoryRecordFormat.V1)
    }
    
    override fun getMaxMinTempParser(): DataParser<MaxMinTemperature> = maxMinTempParser
}
