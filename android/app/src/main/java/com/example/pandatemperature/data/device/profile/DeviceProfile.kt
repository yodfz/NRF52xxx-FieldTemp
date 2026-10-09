package com.example.pandatemperature.data.device.profile

import com.example.pandatemperature.data.device.model.Feature
import com.example.pandatemperature.data.device.model.HistoryRecord
import com.example.pandatemperature.data.device.model.SensorData
import com.example.pandatemperature.data.device.parser.DataParser
import com.example.pandatemperature.data.model.DeviceStatus

/**
 * 设备配置接口（泛型版本）
 * 定义设备支持的特征、服务和数据解析方式
 * 
 * @param T 实时传感器数据类型，必须实现 SensorData 接口
 * @param H 历史记录数据类型，必须实现 HistoryRecord 接口
 */
interface DeviceProfile<T : SensorData, H : HistoryRecord> {
    /** 设备类型（字符串常量，参见 DeviceTypes） */
    val deviceType: String
    
    /** 固件版本号，null 表示未知或老固件 */
    val firmwareVersion: Int?
    
    /** 设备支持的功能特性集合 */
    val supportedFeatures: Set<Feature>
    
    /**
     * 获取实时数据解析器
     */
    fun getRealtimeDataParser(): DataParser<T>
    
    /**
     * 获取设备状态解析器
     */
    fun getStatusParser(): DataParser<DeviceStatus>
    
    /**
     * 获取历史数据解析器
     * @param deviceId 设备 MAC 地址，用于创建记录
     */
    fun getHistoryParser(deviceId: String): DataParser<List<H>>
    
    /**
     * 检查是否支持某个功能
     */
    fun supports(feature: Feature): Boolean = feature in supportedFeatures
}
