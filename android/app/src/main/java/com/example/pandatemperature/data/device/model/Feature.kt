package com.example.pandatemperature.data.device.model

/**
 * 设备功能特性枚举
 * 定义设备可能支持的各种功能
 */
enum class Feature {
    /** 温度测量 */
    TEMPERATURE,
    
    /** 湿度测量 */
    HUMIDITY,
    
    /** 气压测量 */
    PRESSURE,
    
    /** 最高最低温度记录 */
    MAX_MIN_TEMPERATURE,
    
    /** 历史数据存储 */
    HISTORY,
    
    /** 时间同步 */
    TIME_SYNC,
    
    /** 采集间隔配置 */
    INTERVAL_CONFIG,
    
    /** 电池电量 */
    BATTERY
}
