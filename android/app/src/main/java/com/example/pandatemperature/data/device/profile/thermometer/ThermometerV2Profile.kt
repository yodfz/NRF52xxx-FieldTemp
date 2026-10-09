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
 * 温度计 V2 配置（新固件 v1.1+）
 * 
 * 特点：
 * - 使用自定义实时数据服务（REALTIME_DATA_SERVICE）
 * - 温度+湿度+气压一次性读取（6字节）
 * - 支持气压测量
 * - 有固件版本号（> 0）
 * - 历史数据使用 12 字节格式（含气压）
 * - 支持最高最低温度特性
 */
open class ThermometerV2Profile(
    override val firmwareVersion: Int?
) : ThermometerProfile {
    
    override val deviceType: String = DeviceTypes.THERMOMETER
    
    override val supportedFeatures: Set<Feature> = setOf(
        Feature.TEMPERATURE,
        Feature.HUMIDITY,
        Feature.PRESSURE,
        Feature.MAX_MIN_TEMPERATURE,
        Feature.HISTORY,
        Feature.TIME_SYNC,
        Feature.INTERVAL_CONFIG
    )
    
    override val usesCombinedRealtimeData: Boolean = true
    
    // 新固件使用合并的实时数据特征
    override val realtimeDataCharUuid: String = BleConstants.REALTIME_DATA_CHAR
    override val temperatureCharUuid: String? = null  // 新固件不使用分离特征
    override val humidityCharUuid: String? = null     // 新固件不使用分离特征
    
    // ========== 历史数据获取配置 ==========
    // 新固件支持 HISTORY_INFO_CHAR（可读取总记录数、起始扇区等信息）
    override val supportsHistoryInfo: Boolean = true
    // 新固件使用异步通知方法（支持回调和重试）
    override val useAsyncNotificationForHistory: Boolean = true
    
    private val realtimeParser = RealtimeDataParserV2()
    private val statusParser = DeviceStatusParser()
    private val maxMinTempParser = MaxMinTempParser()
    
    override fun getRealtimeDataParser(): DataParser<ThermometerData> = realtimeParser
    
    override fun getStatusParser(): DataParser<DeviceStatus> = statusParser
    
    open override fun getHistoryParser(deviceId: String): DataParser<List<TemperatureRecord>> {
        return HistoryDataParser(deviceId, HistoryRecordFormat.V2)
    }
    
    override fun getMaxMinTempParser(): DataParser<MaxMinTemperature> = maxMinTempParser
}
