package com.example.pandatemperature.utils

import com.example.pandatemperature.data.model.TemperatureRecord
import com.example.pandatemperature.data.weather.AlertLevel
import com.example.pandatemperature.data.weather.HistoryEvaluationResult
import com.example.pandatemperature.data.weather.GpsSample
import com.example.pandatemperature.data.weather.PressureSample
import com.example.pandatemperature.data.weather.WapsState
import kotlin.math.abs
import kotlin.math.sqrt

/** 中值滤波窗口大小（与 WapsBuffer 一致） */
const val MEDIAN_FILTER_WINDOW = 5

/**
 * 对 Float 序列取中值（用于气压滤波）
 * 若不足 [MEDIAN_FILTER_WINDOW] 个则对现有元素排序取中值
 */
fun medianFilter(values: List<Float>): Float? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid]
    else (sorted[mid - 1] + sorted[mid]) / 2f
}

/**
 * 对气压采样序列取中值，返回用于趋势的平滑气压（保留时间戳取最新）
 */
fun medianFilterPressure(samples: List<PressureSample>): PressureSample? {
    if (samples.isEmpty()) return null
    val pressures = samples.map { it.pressureHpa }
    val med = medianFilter(pressures) ?: return null
    val last = samples.last()
    return PressureSample(
        timestampMs = last.timestampMs,
        pressureHpa = med,
        temperatureCelsius = last.temperatureCelsius
    )
}

/** 3 小时（秒） */
const val TENDENCY_WINDOW_SEC = 3 * 3600

/**
 * 计算 3 小时气压倾向 ΔP = 当前值 - 3h 前值
 * @param currentSmoothed 当前平滑气压（hPa）
 * @param pressureAt3hAgo 3 小时前的平滑气压（hPa），若不足 3h 数据则为 null
 * @return ΔP (hPa)，不足 3h 时返回 null
 */
fun computePressureTendency(currentSmoothed: Float, pressureAt3hAgo: Float?): Float? {
    if (pressureAt3hAgo == null) return null
    return currentSmoothed - pressureAt3hAgo
}

/**
 * 从数据库记录计算 3h 倾向：取最近一条与约 3h 前的记录
 * @param records 按时间升序的最近 3h+ 记录（含 pressure）
 * @param nowSec 当前时间戳（秒）
 * @return Pair(当前平滑气压, 3h前平滑气压) 或 null
 */
fun currentAnd3hAgoPressureFromRecords(
    records: List<TemperatureRecord>,
    nowSec: Long
): Pair<Float, Float>? {
    if (records.size < 2) return null
    val threeHoursAgo = nowSec - TENDENCY_WINDOW_SEC
    val current = records.lastOrNull()?.pressure
    if (current == null || current <= 0f) return null  // 无气压或气压为 0（故障）不参与计算
    val past = records.find { it.timestamp >= threeHoursAgo - 1800 && it.timestamp <= threeHoursAgo + 1800 }
        ?: records.minByOrNull { abs(it.timestamp - threeHoursAgo) }
    val pastP = past?.pressure
    if (pastP == null || pastP <= 0f) return null
    return Pair(current, pastP)
}

/** 位移阈值（米）：5 分钟内位移小于此视为静止（旧逻辑兼容） */
const val DISPLACEMENT_THRESHOLD_M = 25.0

/** 速度阈值（米/秒）：平均速度小于此视为静止 */
const val SPEED_THRESHOLD_MPS = 0.1

/** 进入静止：窗口内位移 < 10 m */
const val STATIONARY_ENTER_DISPLACEMENT_M = 10.0

/** 退出静止：相对锚点位移 ≥ 20 m */
const val STATIONARY_EXIT_DISPLACEMENT_M = 20.0

/** 历史评估：3 小时（秒） */
const val HISTORY_EVAL_WINDOW_SEC = 3 * 3600

/** 静止锚点预热时间（分钟）：取 1–2 分钟平均气压作为 P_start */
const val STATIONARY_ANCHOR_WARMUP_MIN = 1.5

/** EHR 最少静止时长（分钟）：低于此不触发动态阈值 */
const val EHR_MIN_DURATION_MIN = 10f

/**
 * 窗口内位移（米）：首点到末点
 */
fun displacementInWindow(gpsSamples: List<GpsSample>): Double? {
    if (gpsSamples.size < 2) return null
    val first = gpsSamples.first()
    val last = gpsSamples.last()
    return haversineMeters(first.latitude, first.longitude, last.latitude, last.longitude)
}

/**
 * 当前点相对参考点的位移（米）
 */
fun displacementFromReference(refLat: Double, refLon: Double, current: GpsSample): Double {
    return haversineMeters(refLat, refLon, current.latitude, current.longitude)
}

/**
 * 是否因“窗口位移小”处于静止区（可进入锚点）
 * 进入静止：窗口位移 < 10 m
 */
fun isInStationaryZoneByDisplacement(gpsSamples: List<GpsSample>): Boolean {
    val d = displacementInWindow(gpsSamples) ?: return false
    return d < STATIONARY_ENTER_DISPLACEMENT_M
}

/**
 * 是否应退出静止：相对参考点位移 ≥ 20 m
 */
fun shouldExitStationary(refLat: Double, refLon: Double, lastGps: GpsSample): Boolean {
    return displacementFromReference(refLat, refLon, lastGps) >= STATIONARY_EXIT_DISPLACEMENT_M
}

/**
 * 根据 5 分钟 GPS 窗口判定是否在移动
 * @param gpsSamples 按时间升序的 GPS 点（至少 2 个点才有意义）
 */
fun isMoving(gpsSamples: List<GpsSample>): Boolean {
    if (gpsSamples.size < 2) return false
    val first = gpsSamples.first()
    val last = gpsSamples.last()
    val displacementM = haversineMeters(
        first.latitude, first.longitude,
        last.latitude, last.longitude
    )
    val durationSec = (last.timestampMs - first.timestampMs) / 1000.0
    if (durationSec <= 0) return false
    val speedMps = displacementM / durationSec
    return displacementM >= DISPLACEMENT_THRESHOLD_M || speedMps >= SPEED_THRESHOLD_MPS
}

/** 简化的 Haversine，返回两点的地面距离（米） */
fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371000.0 // 地球半径米
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
    val c = 2 * kotlin.math.atan2(sqrt(a), sqrt(1 - a))
    return R * c
}

/** 暴风雨阈值（hPa） */
const val STORM_DELTA_THRESHOLD = -4f

/** 恶化阈值（hPa）：3h 下降 ≥1.5 即提示阴雨/大风可能 */
const val WORSENING_DELTA_THRESHOLD = -1.5f

/** 转晴区间（hPa） */
const val CLEARING_DELTA_LOW = 2f
const val CLEARING_DELTA_HIGH = 4f

/** EHR 动态矩阵：10–30 min 突发雷雨阈值 hPa/h */
const val EHR_FLASH_STORM_THRESHOLD = -2.5f
/** 30–90 min 天气恶化阈值 hPa/h */
const val EHR_WORSENING_THRESHOLD = -1.5f
/** >90 min 系统性降水阈值 hPa/h */
const val EHR_SYSTEMIC_RAIN_THRESHOLD = -1.0f

/**
 * 等效小时变化率 EHR = (P_now - P_start) / (duration_minutes / 60)，单位 hPa/h
 */
fun computeEhr(startP: Float, currentP: Float, durationMinutes: Float): Float {
    if (durationMinutes <= 0f) return 0f
    return (currentP - startP) / (durationMinutes / 60f)
}

/**
 * 动态触发矩阵：根据静止时长与 EHR 返回警报级别与文案
 * 仅当 durationMin >= 10 时参与判定；否则返回 None + 监测中
 */
fun decideAlertByEhr(durationMin: Float, ehr: Float): Pair<AlertLevel, String> {
    if (durationMin < EHR_MIN_DURATION_MIN) {
        return AlertLevel.None to "监测中（静止满 10 分钟后显示趋势）"
    }
    return when {
        durationMin < 30f && ehr <= EHR_FLASH_STORM_THRESHOLD ->
            AlertLevel.FlashStorm to "突发雷雨！变化极快，请立即找避雨点（EHR ${String.format("%.1f", ehr)} hPa/h）"
        durationMin < 30f ->
            AlertLevel.None to "静止 ${durationMin.toInt()} 分钟，气压趋势正常"
        durationMin < 90f && ehr <= EHR_WORSENING_THRESHOLD ->
            AlertLevel.Worsening to "天气恶化，锋面或低压槽逼近（EHR ${String.format("%.1f", ehr)} hPa/h）"
        durationMin < 90f ->
            AlertLevel.None to "静止 ${durationMin.toInt()} 分钟，气压趋势正常"
        ehr <= EHR_SYSTEMIC_RAIN_THRESHOLD ->
            AlertLevel.SystemicRain to "系统性降水，建议调整徒步计划（EHR ${String.format("%.1f", ehr)} hPa/h）"
        else ->
            AlertLevel.None to "静止 ${durationMin.toInt()} 分钟，气压趋势正常"
    }
}

/** 气压有效阈值：≤0 视为气压计故障，不参与天气计算 */
private fun isValidPressure(p: Float?) = p != null && p > 0f

/**
 * 同步完成后一次历史评估：过去 3 小时内若 GPS 一致（<10m），则用该区间内所有气压（含 GPS 为 null 的记录）算 ΔP/EHR 并给出结论
 * 无气压或气压为 0（故障）的记录不参与计算。
 */
fun evaluateHistorySegment(records: List<TemperatureRecord>): HistoryEvaluationResult {
    val withPressure = records.filter { isValidPressure(it.pressure) }.sortedBy { it.timestamp }
    if (withPressure.size < 2) {
        return HistoryEvaluationResult(
            message = "过去3小时有效气压记录不足，未做评估",
            isValid = false
        )
    }
    val withGps = withPressure.filter { it.latitude != null && it.longitude != null }
    if (withGps.isEmpty()) {
        return HistoryEvaluationResult(
            message = "过去3小时无GPS记录，无法判定是否静止，未做评估",
            isValid = false
        )
    }
    val refLat = withGps.first().latitude!!
    val refLon = withGps.first().longitude!!
    val allWithin10m = withGps.all { rec ->
        haversineMeters(refLat, refLon, rec.latitude!!, rec.longitude!!) < STATIONARY_ENTER_DISPLACEMENT_M
    }
    if (!allWithin10m) {
        return HistoryEvaluationResult(
            message = "过去3小时内有位移（GPS 变化≥10m），未做历史评估",
            isValid = false
        )
    }
    val first = withPressure.first()
    val last = withPressure.last()
    val firstP = first.pressure!!
    val lastP = last.pressure!!
    val durationMin = (last.timestamp - first.timestamp) / 60f
    val deltaP = lastP - firstP
    val ehr = computeEhr(firstP, lastP, durationMin)
    val (_, message) = decideAlertByEhr(durationMin, ehr)
    return HistoryEvaluationResult(
        message = "过去3小时处于静止，$message",
        isValid = true,
        deltaP = deltaP,
        ehrHpaPerHour = ehr,
        durationMin = durationMin
    )
}

/**
 * 决策表：根据状态、ΔP、是否“气压降且 GPS 升”返回警报级别与文案（移动或旧 3h 兼容）
 */
fun decideAlert(
    state: WapsState,
    deltaP3h: Float?,
    pressureDroppingButAltitudeRising: Boolean
): Pair<AlertLevel, String> {
    if (pressureDroppingButAltitudeRising && state == WapsState.Active) {
        return AlertLevel.Climbing to "正常爬升，仅更新海拔"
    }
    if (deltaP3h == null) {
        return AlertLevel.None to "数据收集中"
    }
    return when {
        deltaP3h < STORM_DELTA_THRESHOLD -> AlertLevel.StormWarning to "暴风雨预警！气压 3h 下降 ${kotlin.math.abs(deltaP3h)} hPa"
        deltaP3h < WORSENING_DELTA_THRESHOLD -> AlertLevel.Worsening to "天气恶化，阴雨/大风可能"
        deltaP3h in CLEARING_DELTA_LOW..CLEARING_DELTA_HIGH -> AlertLevel.Clearing to "天气转晴，冷锋过境注意防风"
        else -> AlertLevel.None to "气压趋势正常"
    }
}
