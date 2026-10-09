package com.example.pandatemperature.data.device.profile.thermometer

import com.example.pandatemperature.data.device.model.MaxMinTemperature
import com.example.pandatemperature.data.device.model.ThermometerData
import com.example.pandatemperature.data.device.parser.DataParser
import com.example.pandatemperature.data.device.profile.DeviceProfile
import com.example.pandatemperature.data.model.TemperatureRecord

/**
 * 温湿度计设备配置接口
 * 继承泛型 DeviceProfile，添加温度计特有的方法
 */
interface ThermometerProfile : DeviceProfile<ThermometerData, TemperatureRecord> {
    
    /** 实时数据特征 UUID（用于读取/订阅实时数据） */
    val realtimeDataCharUuid: String
    
    /** 温度特征 UUID（老固件使用，新固件可能为 null） */
    val temperatureCharUuid: String?
    
    /** 湿度特征 UUID（老固件使用，新固件可能为 null） */
    val humidityCharUuid: String?
    
    /**
     * 是否使用合并的实时数据特征（新固件）
     * true: 使用 REALTIME_DATA_CHAR 一次性读取温湿度+气压
     * false: 使用分离的 TEMP_CHAR + HUMIDITY_CHAR
     */
    val usesCombinedRealtimeData: Boolean
    
    // ========== 历史数据获取配置 ==========
    
    /**
     * 是否支持历史记录信息特征（HISTORY_INFO_CHAR）
     * true: 支持读取总记录数、起始扇区等信息
     * false: 不支持，进度以接收数量为准
     */
    val supportsHistoryInfo: Boolean
    
    /**
     * 是否使用异步通知方法获取历史数据
     * true: 使用 enableNotificationAsync（支持回调和重试）
     * false: 使用 enableNotification 同步方法（老固件兼容性更好）
     */
    val useAsyncNotificationForHistory: Boolean
    
    /** Legacy history request remains uint32 little-endian seconds. */
    fun buildHistoryRequest(timestamp: Long): ByteArray = java.nio.ByteBuffer.allocate(4)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(timestamp.toInt()).array()

    /**
     * 获取最高最低温度解析器
     */
    fun getMaxMinTempParser(): DataParser<MaxMinTemperature>
}
