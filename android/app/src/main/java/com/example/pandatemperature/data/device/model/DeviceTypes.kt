package com.example.pandatemperature.data.device.model

/**
 * 设备类型常量定义
 * 使用字符串常量而非枚举，便于动态扩展
 */
object DeviceTypes {
    /** 温湿度计 */
    const val THERMOMETER = "thermometer"
    
    /** 计步器 */
    const val PEDOMETER = "pedometer"
    
    /** 心率监测仪 */
    const val HEART_RATE_MONITOR = "heart_rate_monitor"
    
    /** 空气质量检测仪 */
    const val AIR_QUALITY_MONITOR = "air_quality_monitor"
    
    /** 未知设备 */
    const val UNKNOWN = "unknown"
    
    /**
     * 根据设备名称推断设备类型
     */
    fun inferFromDeviceName(name: String?): String {
        if (name == null) return UNKNOWN
        
        val lowerName = name.lowercase()
        return when {
            lowerName.contains("temp") || lowerName.contains("panda") || lowerName.contains("yodfz") -> THERMOMETER
            lowerName.contains("step") || lowerName.contains("pedometer") -> PEDOMETER
            lowerName.contains("heart") || lowerName.contains("hr") -> HEART_RATE_MONITOR
            lowerName.contains("air") || lowerName.contains("aqi") -> AIR_QUALITY_MONITOR
            else -> UNKNOWN
        }
    }
}
