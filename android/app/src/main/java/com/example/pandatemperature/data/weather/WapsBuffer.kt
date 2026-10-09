package com.example.pandatemperature.data.weather

import java.util.ArrayDeque

/** 气压中值滤波窗口大小 */
const val PRESSURE_MEDIAN_WINDOW = 5

/** 5 分钟（毫秒） */
const val GPS_WINDOW_MS = 5 * 60 * 1000L

/** GPS 采样间隔（毫秒） */
const val GPS_SAMPLE_INTERVAL_MS = 10_000L

/** 静止锚点预热时长（毫秒）：1.5 分钟 */
const val STATIONARY_ANCHOR_WARMUP_MS = (1.5 * 60 * 1000).toLong()

/**
 * WAPS 采样缓存：气压滑动窗口 + GPS 5 分钟窗口 + 静止锚点与预热
 * 线程不安全，由 ViewModel 在单协程内调用
 */
class WapsBuffer {

    private val pressureSamples = ArrayDeque<PressureSample>(PRESSURE_MEDIAN_WINDOW + 8)
    private val gpsSamples = ArrayDeque<GpsSample>(32)

    /** 最近一次 1 分钟平滑气压（每 1 分钟更新） */
    var lastSmoothedPressure: PressureSample? = null
        private set

    /** 进入静止时锁定的海拔（米），静止期间不变 */
    var lockedAltitudeM: Double? = null
        set(value) {
            field = value
        }

    // ---------- 静止锚点（EHR 用）----------
    /** 进入静止区时的参考点经纬度，用于 20m 退出判定 */
    var stationaryRefLat: Double? = null
        private set
    var stationaryRefLon: Double? = null
        private set
    /** 进入静止区的时间（毫秒），用于预热计时 */
    var stationaryRefTimeMs: Long? = null
        private set
    /** 预热期内收集的平滑气压，用于取平均作为 P_start */
    private val warmupPressures = mutableListOf<Float>()
    /** 锚点气压 P_start（预热结束后取 warmup 平均） */
    var stationaryAnchorP: Float? = null
        private set
    /** 锚点锁定时间（毫秒），用于计算静止时长 */
    var stationaryAnchorTimeMs: Long? = null
        private set

    /**
     * 添加气压采样；保留最近 [PRESSURE_MEDIAN_WINDOW] 个用于中值滤波
     */
    fun addPressure(sample: PressureSample) {
        pressureSamples.addLast(sample)
        while (pressureSamples.size > PRESSURE_MEDIAN_WINDOW) {
            pressureSamples.removeFirst()
        }
    }

    /** 当前用于滤波的原始气压序列（最多 5 个） */
    fun getPressureSamplesForMedian(): List<PressureSample> = pressureSamples.toList()

    /** 更新“最近 1 分钟平滑值”（由外部算好中值后写入） */
    fun setSmoothedPressure(sample: PressureSample) {
        lastSmoothedPressure = sample
    }

    /**
     * 添加 GPS 采样；保留 5 分钟内的点
     */
    fun addGps(sample: GpsSample) {
        gpsSamples.addLast(sample)
        val cutoff = sample.timestampMs - GPS_WINDOW_MS
        while (gpsSamples.isNotEmpty() && gpsSamples.first().timestampMs < cutoff) {
            gpsSamples.removeFirst()
        }
    }

    /** 最近 5 分钟内的 GPS 点（用于运动判定） */
    fun getGpsSamplesLast5Min(): List<GpsSample> = gpsSamples.toList()

    /** 进入静止区时调用：记录参考点与时间，开始预热 */
    fun setStationaryReference(lat: Double, lon: Double, timeMs: Long) {
        if (stationaryRefLat == null) {
            stationaryRefLat = lat
            stationaryRefLon = lon
            stationaryRefTimeMs = timeMs
            warmupPressures.clear()
        }
    }

    /** 预热期内每 1 分钟传入一次平滑气压 */
    fun addWarmupPressure(p: Float) {
        if (stationaryAnchorP == null) warmupPressures.add(p)
    }

    /** 若预热时长已够，锁定锚点 P_start = 预热期平均气压，返回 true */
    fun tryLockAnchor(nowMs: Long): Boolean {
        val refTime = stationaryRefTimeMs ?: return false
        if (stationaryAnchorP != null) return true
        if (nowMs - refTime < STATIONARY_ANCHOR_WARMUP_MS) return false
        if (warmupPressures.isEmpty()) return false
        stationaryAnchorP = warmupPressures.average().toFloat()
        stationaryAnchorTimeMs = nowMs
        return true
    }

    /** 是否已锁定锚点（可计算 EHR） */
    fun isAnchorLocked(): Boolean = stationaryAnchorP != null && stationaryAnchorTimeMs != null

    /** 清除静止锚点与参考点（退出静止时调用） */
    fun clearStationaryAnchor() {
        stationaryRefLat = null
        stationaryRefLon = null
        stationaryRefTimeMs = null
        warmupPressures.clear()
        stationaryAnchorP = null
        stationaryAnchorTimeMs = null
    }

    /** 清空所有缓存（断开连接时调用） */
    fun clear() {
        pressureSamples.clear()
        gpsSamples.clear()
        lastSmoothedPressure = null
        lockedAltitudeM = null
        clearStationaryAnchor()
    }
}
