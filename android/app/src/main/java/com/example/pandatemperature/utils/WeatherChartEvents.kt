package com.example.pandatemperature.utils

import com.example.pandatemperature.data.model.TemperatureRecord
import com.example.pandatemperature.data.weather.ChartWeatherEvent
import com.example.pandatemperature.data.weather.ChartWeatherEventType
import kotlin.math.abs
import kotlin.math.ln

/**
 * 从 24h 记录中计算“图表气象事件”，用于在趋势图时间轴下方按时间标注图标。
 * 规则（与洞察引擎一致）：
 * - 气压陡降（约 1h 内 ΔP &lt; -2 hPa）→ 降水/雷雨可能
 * - 气压陡升（约 1h 内 ΔP &gt; 2 hPa）→ 转晴
 * - 室内/干暖环境（约 1h 内 ΔT ≥ +1.5°C 且 ΔRH ≤ -8% 且 |ΔP| ≤ 0.6 hPa）→ 室内
 * - 6h 内明显降温且湿度突变 → 冷空气/锋面
 * - 高湿且温露差小 → 起雾/结露
 */
object WeatherChartEvents {

    private const val WINDOW_1H_SEC = 3600L
    private const val WINDOW_6H_SEC = 6 * 3600L
    private const val PRESSURE_DROP_THRESHOLD = -2f
    private const val PRESSURE_RISE_THRESHOLD = 2f
    private const val INDOOR_WARMING_1H_THRESHOLD = 1.5f
    private const val INDOOR_HUMIDITY_DROP_1H_THRESHOLD = -8f
    private const val INDOOR_PRESSURE_STABLE_MAX_ABS = 1.5f
    private const val INDOOR_STRONG_WARMING_THRESHOLD = 5.0f
    private const val COOLING_6H_THRESHOLD = -6f
    private const val HUMIDITY_CHANGE_6H = 15f
    // 结露预警阈值：采用中风险标准（图表标注用较宽松的阈值，避免漏掉风险时段）
    private const val FOG_RH_MIN = 75f
    private const val FOG_SPREAD_MAX = 3f
    private const val FOG_MERGE_WINDOW_SEC = 2 * 3600L
    private const val MAX_PRESSURE_SEARCH_SEC = 1800L
    /** 寻找历史对比点时的最大时间偏差容忍度（超过此偏差则认为数据太久远，不可比） */
    private const val MAX_TIME_GAP_TOLERANCE_SEC = 3600L

    fun compute(records: List<TemperatureRecord>): List<ChartWeatherEvent> {
        if (records.size < 2) return emptyList()
        val sorted = records.sortedBy { it.timestamp }
        val result = mutableListOf<ChartWeatherEvent>()

        // 1h 滑动：气压陡降 / 陡升
        val withPressure = sorted.filter { it.pressure != null && it.pressure!! > 0f }
        for (i in withPressure.indices) {
            val end = withPressure[i]
            val startSec = end.timestamp - WINDOW_1H_SEC
            val start = withPressure.find { it.timestamp >= startSec - 1800 && it.timestamp <= startSec + 1800 }
                ?: withPressure.minByOrNull { abs(it.timestamp - startSec) }
            if (start == null || start.timestamp >= end.timestamp) continue
            // 安全检查：如果找到的历史点距离目标时间太远（超过容忍度），则不进行比较（避免把 5 小时前的变化当成 1 小时的变化）
            if (abs(start.timestamp - startSec) > MAX_TIME_GAP_TOLERANCE_SEC) continue

            val deltaP = (end.pressure ?: 0f) - (start.pressure ?: 0f)
            if (deltaP <= PRESSURE_DROP_THRESHOLD) {
                result.add(ChartWeatherEvent(end.timestamp, ChartWeatherEventType.PRESSURE_DROP_RAIN))
            } else if (deltaP >= PRESSURE_RISE_THRESHOLD) {
                result.add(ChartWeatherEvent(end.timestamp, ChartWeatherEventType.PRESSURE_RISE_CLEARING))
            }
        }

        // 1h 滑动：室内/干暖环境（温度升、湿度降、气压基本不变）
        // 说明：
        // - 有有效气压时：用 |ΔP| 抑制误判（避免把真实天气突变当成“进屋”）
        // - 气压缺失时：退化为仅按温升+湿降触发（否则会出现“明明进屋了但不显示小房子”的体验问题）
        // 温湿用原始 sorted（每条记录都有），气压用 withPressure 寻找最接近 1h 起点/终点的记录。
        for (i in sorted.indices) {
            val end = sorted[i]
            val startSec = end.timestamp - WINDOW_1H_SEC
            val start = sorted.find { it.timestamp >= startSec - 1800 && it.timestamp <= startSec + 1800 }
                ?: sorted.minByOrNull { abs(it.timestamp - startSec) }
            if (start == null || start.timestamp >= end.timestamp) continue
            // 安全检查：如果找到的历史点距离目标时间太远，则不进行比较
            if (abs(start.timestamp - startSec) > MAX_TIME_GAP_TOLERANCE_SEC) continue

            val deltaT = end.temperature - start.temperature
            val deltaRH = end.humidity.coerceIn(0f, 100f) - start.humidity.coerceIn(0f, 100f)

            if (deltaT < INDOOR_WARMING_1H_THRESHOLD || deltaRH > INDOOR_HUMIDITY_DROP_1H_THRESHOLD) continue

            // 取起止气压（若缺失则允许触发，但若两端都存在则必须满足“气压基本不变”）
            val endP = end.pressure?.takeIf { it > 0f }
                ?: withPressure.minByOrNull { abs(it.timestamp - end.timestamp) }
                    ?.takeIf { abs(it.timestamp - end.timestamp) <= MAX_PRESSURE_SEARCH_SEC }
                    ?.pressure?.takeIf { it > 0f }
            val startP = start.pressure?.takeIf { it > 0f }
                ?: withPressure.minByOrNull { abs(it.timestamp - start.timestamp) }
                    ?.takeIf { abs(it.timestamp - start.timestamp) <= MAX_PRESSURE_SEARCH_SEC }
                    ?.pressure?.takeIf { it > 0f }
            val deltaPAbs = if (endP != null && startP != null) abs(endP - startP) else null

            // 判定：气压平稳 OR (强升温且气压变化不过大)
            val isStable = deltaPAbs == null || deltaPAbs <= INDOOR_PRESSURE_STABLE_MAX_ABS
            val isStrongWarming = deltaT >= INDOOR_STRONG_WARMING_THRESHOLD
            // 如果是强升温（如从户外进屋），允许更大的气压波动（可能是天气本身在变，或者是传感器温漂），只要不是极端变化（>3hPa）
            val isAcceptable = isStable || (isStrongWarming && deltaPAbs != null && deltaPAbs <= 3.0f)

            if (isAcceptable) {
                result.add(ChartWeatherEvent(end.timestamp, ChartWeatherEventType.INDOOR_WARM_DRY))
            }
        }

        // 6h 滑动：冷空气/锋面（ΔT ≤ -6 且 |ΔH| ≥ 15）
        for (i in sorted.indices) {
            val end = sorted[i]
            val startSec = end.timestamp - WINDOW_6H_SEC
            val start = sorted.find { it.timestamp >= startSec - 1800 && it.timestamp <= startSec + 1800 }
                ?: sorted.minByOrNull { abs(it.timestamp - startSec) }
            if (start == null || start.timestamp >= end.timestamp) continue
            // 安全检查：如果找到的历史点距离目标时间太远，则不进行比较
            if (abs(start.timestamp - startSec) > MAX_TIME_GAP_TOLERANCE_SEC) continue
            
            val deltaT = end.temperature - start.temperature
            val deltaH = end.humidity.coerceIn(0f, 100f) - start.humidity.coerceIn(0f, 100f)
            if (deltaT <= COOLING_6H_THRESHOLD && abs(deltaH) >= HUMIDITY_CHANGE_6H) {
                result.add(ChartWeatherEvent(end.timestamp, ChartWeatherEventType.COLD_FRONT))
            }
        }

        // 起雾/结露：逐点检查，再合并邻近
        val fogCandidates = sorted.mapNotNull { r ->
            val t = r.temperature
            val rh = r.humidity.coerceIn(0f, 100f)
            val td = dewPointCelsius(t, rh)
            val spread = abs(t - td)
            if (rh >= FOG_RH_MIN && spread <= FOG_SPREAD_MAX) r.timestamp else null
        }
        val mergedFog = mergeNearbyTimestamps(fogCandidates, FOG_MERGE_WINDOW_SEC)
        mergedFog.forEach { ts -> result.add(ChartWeatherEvent(ts, ChartWeatherEventType.FOG_DEW)) }

        val distinct = result.distinctBy { it.timestampSec to it.type }.sortedBy { it.timestampSec }
        return mergeIntoRanges(distinct)
    }

    /** 同类型、时间差在 RANGE_MERGE_WINDOW_SEC 内的事件合并为一个区间（一个图标）。 */
    private const val RANGE_MERGE_WINDOW_SEC = 2 * 3600L

    private fun mergeIntoRanges(events: List<ChartWeatherEvent>): List<ChartWeatherEvent> {
        if (events.isEmpty()) return emptyList()
        val out = mutableListOf<ChartWeatherEvent>()
        for ((_, group) in events.groupBy { it.type }) {
            val sorted = group.sortedBy { it.timestampSec }
            var rangeStart = sorted[0].timestampSec
            var rangeEnd = sorted[0].timestampSec
            for (i in 1 until sorted.size) {
                if (sorted[i].timestampSec - rangeEnd <= RANGE_MERGE_WINDOW_SEC) {
                    rangeEnd = sorted[i].timestampSec
                } else {
                    out.add(ChartWeatherEvent(rangeStart, sorted[0].type, rangeEnd))
                    rangeStart = sorted[i].timestampSec
                    rangeEnd = sorted[i].timestampSec
                }
            }
            out.add(ChartWeatherEvent(rangeStart, sorted[0].type, rangeEnd))
        }
        return out.sortedBy { it.timestampSec }
    }

    private fun dewPointCelsius(tC: Float, rhPercent: Float): Float {
        val a = 17.62
        val b = 243.12
        val rh = (rhPercent.coerceIn(1f, 100f) / 100.0).toDouble()
        val t = tC.toDouble()
        val gamma = (a * t) / (b + t) + ln(rh)
        val td = (b * gamma) / (a - gamma)
        return td.toFloat()
    }

    /** 合并时间戳：同一窗口内的只保留一个代表时间（取簇内第一个）。 */
    private fun mergeNearbyTimestamps(timestamps: List<Long>, windowSec: Long): List<Long> {
        if (timestamps.isEmpty()) return emptyList()
        val sorted = timestamps.sorted()
        val merged = mutableListOf<Long>()
        var clusterStart = sorted[0]
        for (i in 1 until sorted.size) {
            if (sorted[i] - sorted[i - 1] > windowSec) {
                merged.add(clusterStart)
                clusterStart = sorted[i]
            }
        }
        merged.add(clusterStart)
        return merged
    }
}
