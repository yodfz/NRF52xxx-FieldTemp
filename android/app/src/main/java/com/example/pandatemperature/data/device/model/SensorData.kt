package com.example.pandatemperature.data.device.model

/**
 * 传感器数据基类接口
 * 所有设备的实时数据都应实现此接口
 */
interface SensorData {
    /** 数据获取时间戳（毫秒） */
    val timestamp: Long
    
    /** 是否有任何有效数据 */
    val hasAnyData: Boolean
}

/**
 * 历史记录基类接口
 * 所有设备的历史记录都应实现此接口
 */
interface HistoryRecord {
    /** 记录时间戳（Unix 秒） */
    val timestamp: Long
    
    /** 设备 MAC 地址 */
    val deviceId: String
}
