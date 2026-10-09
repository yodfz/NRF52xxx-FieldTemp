package com.example.pandatemperature.data.weather

/**
 * 24h 面积图上按时间标注的气象事件类型。
 * 用于在时间轴下方显示对应图标（气压陡降→雨，陡升→转晴，冷空气/锋面，起雾/结露）。
 */
enum class ChartWeatherEventType {
    /** 气压陡然下降，降水/雷雨可能 */
    PRESSURE_DROP_RAIN,
    /** 气压陡然升高，转晴/天气好转 */
    PRESSURE_RISE_CLEARING,
    /** 冷空气/锋面迹象（温湿突变） */
    COLD_FRONT,
    /** 起雾/结露风险（高湿且温露差小） */
    FOG_DEW,
    /** 进入室内/干暖环境：温度升、湿度降且气压基本不变 */
    INDOOR_WARM_DRY
}

/**
 * 图表气象事件：表示某一时刻或时间区间内的气象现象，用于在 24h 趋势图时间轴下方显示图标。
 * @param timestampSec 区间起始时间戳（秒）；单点时即事件时刻
 * @param type 事件类型
 * @param endSec 区间结束时间戳（秒）；为 null 表示单点，展示时按单点处理
 */
data class ChartWeatherEvent(
    val timestampSec: Long,
    val type: ChartWeatherEventType,
    val endSec: Long? = null
)
