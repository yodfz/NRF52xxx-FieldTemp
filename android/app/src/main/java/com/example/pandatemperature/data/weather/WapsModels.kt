package com.example.pandatemperature.data.weather

/**
 * 气压采样点（用于滤波与倾向计算）
 */
data class PressureSample(
    val timestampMs: Long,
    val pressureHpa: Float,
    val temperatureCelsius: Float?
)

/**
 * GPS 采样点（用于 5 分钟运动判定）
 */
data class GpsSample(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val timestampMs: Long
)

/**
 * WAPS 运动状态
 */
enum class WapsState {
    /** 静止/扎营：锁定海拔，气压变化归天气 */
    Stationary,
    /** 移动中：GPS 修正海拔，MSLP 换算后算倾向 */
    Active
}

/**
 * 气压倾向等级（用于 30 分钟“注意”）
 */
enum class TendencyLevel {
    Normal,
    /** 斜率变陡，需注意 */
    Attention
}

/**
 * 天气警报级别（决策表输出）
 */
enum class AlertLevel {
    /** 无异常 */
    None,
    /** 暴风雨预警：ΔP < -4 hPa（旧 3h 逻辑保留兼容） */
    StormWarning,
    /** 天气恶化：-4 ≤ ΔP < -2 或 EHR 30-90min 段 */
    Worsening,
    /** 天气转晴：+2 ≤ ΔP ≤ +4，冷锋过境 */
    Clearing,
    /** 移动中气压降但 GPS 升：正常爬升，不报警 */
    Climbing,
    /** 突发雷雨：EHR 动态矩阵 10-30min 段，需强通知 */
    FlashStorm,
    /** 系统性降水：EHR 动态矩阵 >90min 段 */
    SystemicRain
}

/**
 * 同步完成后的「最近一次历史评估」结果（常驻展示）
 * 基于过去 3 小时内 GPS 一致区间内的气压数据做一次评估
 */
data class HistoryEvaluationResult(
    /** 评估结论文案 */
    val message: String,
    /** 是否在 GPS 一致区间内有效评估（否则仅说明未评估原因） */
    val isValid: Boolean,
    /** 气压变化量 ΔP (hPa)，有效时非 null */
    val deltaP: Float? = null,
    /** 等效小时变化率 (hPa/h)，有效时非 null */
    val ehrHpaPerHour: Float? = null,
    /** 评估时间跨度（分钟），有效时非 null */
    val durationMin: Float? = null,
    /** 评估时间戳（毫秒），用于 UI 显示“评估时间” */
    val evaluatedAtMs: Long = System.currentTimeMillis()
)

/**
 * WAPS 单次评估结果（供 UI 与通知使用）
 */
data class WapsResult(
    val state: WapsState,
    val mslpHpa: Float?,
    val deltaP3hHpa: Float?,
    val alert: AlertLevel,
    val message: String,
    val isDataCollecting: Boolean = false,
    /** 等效小时变化率 hPa/h，静止且锚点锁定后有值 */
    val ehrHpaPerHour: Float? = null,
    /** 静止持续分钟数，仅静止时有值 */
    val stationaryDurationMin: Float? = null
)
