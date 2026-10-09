package com.example.pandatemperature.data.device.model

/**
 * 温湿度计实时数据模型
 * 实现 SensorData 接口，支持泛型设备架构
 */
data class ThermometerData(
    /** 温度值（摄氏度），null 表示不支持或读取失败 */
    val temperature: Float? = null,
    
    /** 湿度值（百分比），null 表示不支持或读取失败 */
    val humidity: Float? = null,
    
    /** 气压值（hPa），null 表示不支持或读取失败 */
    val pressure: Float? = null,

    /**
     * 设备电池电压（V），null 表示当前固件尚未上报或读取失败。
     * 未来实时协议在现有 6 字节后追加 uint16 小端毫伏值。
     */
    val batteryVoltage: Float? = null,

    /**
     * 本帧是否携带了电压字段。
     *
     * 用于区分两种「[batteryVoltage] 为 null」：
     * - `false`：旧 6 字节固件根本没有电压能力，上层应保持既有显示、不要清掉；
     * - `true`：新 8 字节固件上报了电压字段但值无效（哨兵 `0xFFFF` 或超出量程），
     *   表示设备当前没有有效电压，上层应清为 null 让界面显示 `--`。
     */
    val batteryVoltageReported: Boolean = false,
    
    /** 数据获取时间戳（毫秒） */
    override val timestamp: Long = System.currentTimeMillis()
) : SensorData {
    /** 是否有有效的温度数据 */
    val hasTemperature: Boolean get() = temperature != null
    
    /** 是否有有效的湿度数据 */
    val hasHumidity: Boolean get() = humidity != null
    
    /** 是否有有效的气压数据 */
    val hasPressure: Boolean get() = pressure != null
    
    /** 是否有任何有效数据 */
    override val hasAnyData: Boolean get() = hasTemperature || hasHumidity || hasPressure || batteryVoltage != null
}

/**
 * 类型别名：保持向后兼容
 */
typealias RealtimeData = ThermometerData

/**
 * 最高最低温度数据模型
 * 
 * 协议 v1.2 扩展：新增时间戳字段（12字节格式）
 * - 兼容旧格式（4字节）：时间戳为 null
 * - 特殊值：max=-32768 或 min=32767 表示未记录/已重置
 */
data class MaxMinTemperature(
    /** 最高温度（摄氏度） */
    val maxTemperature: Float,
    
    /** 最高温度发生时间戳（Unix秒），null 表示老固件不支持 */
    val maxTemperatureTimestamp: Long? = null,
    
    /** 最低温度（摄氏度） */
    val minTemperature: Float,
    
    /** 最低温度发生时间戳（Unix秒），null 表示老固件不支持 */
    val minTemperatureTimestamp: Long? = null,
    
    /** 是否有有效的最高温度记录（非特殊值且时间戳非0） */
    val hasMaxRecord: Boolean = true,
    
    /** 是否有有效的最低温度记录（非特殊值且时间戳非0） */
    val hasMinRecord: Boolean = true
)
