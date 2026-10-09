package com.example.pandatemperature.utils

import com.example.pandatemperature.data.model.TemperatureRecord
import com.example.pandatemperature.data.weather.insights.InsightDataQuality
import com.example.pandatemperature.data.weather.insights.InsightMetric
import com.example.pandatemperature.data.weather.insights.InsightScope
import com.example.pandatemperature.data.weather.insights.InsightSeverity
import com.example.pandatemperature.data.weather.insights.WeatherInsightItem
import com.example.pandatemperature.data.weather.insights.WeatherInsightsResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * 纯本地（无在线API）“气象洞察”生成引擎。
 *
 * 设计目标：
 * - 纯函数：输入历史记录 -> 输出洞察卡片列表
 * - 规则可解释：输出结论 + 指标数值 + 解释文案
 * - 数据不足/传感器故障：有明确兜底
 */
object WeatherInsightsEngine {

    private const val WINDOW_3H_SEC = 3 * 3600L
    private const val WINDOW_6H_SEC = 6 * 3600L
    private const val WINDOW_24H_SEC = 24 * 3600L

    /** 气压有效范围 (hPa)，与 SPL06-001 量程一致，超出不参与计算 */
    private const val PRESSURE_VALID_MIN = 10f
    private const val PRESSURE_VALID_MAX = 2000f
    /** 地面气象合理区间 (hPa)，仅用于 Pmin/Pmax 展示与 24h 变幅描述，过滤异常值 */
    private const val PRESSURE_DISPLAY_MIN = 850f
    private const val PRESSURE_DISPLAY_MAX = 1100f

    fun generateInsights(records: List<TemperatureRecord>, nowSec: Long): WeatherInsightsResult {
        if (records.isEmpty()) {
            return WeatherInsightsResult(
                items = listOf(
                    WeatherInsightItem(
                        id = "data_empty",
                        title = "暂无可分析数据",
                        summary = "最近24小时没有历史记录，无法生成气象分析。",
                        severity = InsightSeverity.Info
                    )
                ),
                dataQuality = InsightDataQuality.Poor
            )
        }

        val sorted = records.sortedBy { it.timestamp }
        // 口径：以“24小时数据的最后时间戳”为锚点做推算（避免系统时间与记录断档导致切窗偏移）
        val anchorEndSec = sorted.last().timestamp.takeIf { it > 0 } ?: nowSec
        val startSec = sorted.first().timestamp.takeIf { it > 0 }

        val last24h = sliceByWindow(sorted, anchorEndSec, WINDOW_24H_SEC)
        val last6h = sliceByWindow(sorted, anchorEndSec, WINDOW_6H_SEC)
        val last3h = sliceByWindow(sorted, anchorEndSec, WINDOW_3H_SEC)

        // 顺序：预测(3h) 在 冷空气/锋面 上面；同严重度时按此固定顺序排
        val displayOrder = listOf("pressure_trend", "forecast_3h", "front_signal", "comfort", "fog_risk")
        val orderRank = { id: String -> displayOrder.indexOf(id).let { if (it < 0) 999 else it } }

        val items = buildList {
            add(buildPressureTrendInsight(last24h, last6h, last3h, anchorEndSec))
            add(buildForecast3hInsight(last3h, anchorEndSec))
            add(buildFrontSignalInsight(last24h, last6h, last3h, anchorEndSec))
            add(buildComfortInsight(sorted, anchorEndSec))
            add(buildFogRiskInsight(sorted, anchorEndSec))
        }.filterNotNull()
            .sortedWith(compareByDescending<WeatherInsightItem> { severityRank(it.severity) }.thenBy { orderRank(it.id) })

        val quality = estimateQuality(sorted)
        val overviewSummary = buildOverviewSummary(last24h, last3h, anchorEndSec)
        val overviewSummary3h = buildOverviewSummary3h(last3h, anchorEndSec)

        return WeatherInsightsResult(
            items = items,
            dataQuality = quality,
            overviewSummary = overviewSummary,
            overviewSummary3h = overviewSummary3h,
            timeRangeStartSec = startSec,
            timeRangeEndSec = anchorEndSec
        )
    }

    /**
     * 首页一行近期评估（约 50 字内，不写“几小时”）。
     */
    private fun buildOverviewSummary3h(
        last3h: List<TemperatureRecord>,
        anchorEndSec: Long
    ): String {
        if (last3h.isEmpty()) return "数据收集中，暂无近期评估"
        val delta3h = deltaOverWindow(last3h, anchorEndSec, WINDOW_3H_SEC)
        val validPressures = last3h.mapNotNull { it.pressure?.takeIf { p -> p > 0f } }
        return if (validPressures.size >= 2 && delta3h != null) {
            when {
                delta3h <= -3f -> "气压下降明显，降水与对流风险升高，建议加固风绳。"
                delta3h <= -2f -> "气压骤降，暴雨或强风可能在1-3小时内到达，请加固风绳。"
                delta3h <= -1.5f -> "气压缓慢下降，天气可能转差，留意变化。"
                delta3h >= 1.5f -> "气压回升，天气趋稳。"
                else -> "气压基本平稳，天气暂无大变化。"
            }
        } else "气压数据不足，结论仅供参考。"
    }

    /**
     * 基于全部 24h 数据生成“整体评估小结”文案（MVP：可解释、短句、便于 UI 直接展示）。
     *
     * 注意：本函数只做展示总结，不作为告警触发依据（告警逻辑仍由各卡片负责）。
     */
    private fun buildOverviewSummary(
        last24h: List<TemperatureRecord>,
        last3h: List<TemperatureRecord>,
        anchorEndSec: Long
    ): String {
        if (last24h.isEmpty()) return "暂无可分析数据（等待24h记录加载）"

        val temps = last24h.map { it.temperature }
        val hums = last24h.map { it.humidity.coerceIn(0f, 100f) }
        val validPressures = last24h.mapNotNull { it.pressure?.takeIf { p -> p > 0f } }

        val tMin = temps.minOrNull() ?: 0f
        val tMax = temps.maxOrNull() ?: 0f
        val tAvg = temps.average().toFloat()

        val hMin = hums.minOrNull() ?: 0f
        val hMax = hums.maxOrNull() ?: 0f
        val hAvg = hums.average().toFloat()

        // 近似舒适占比：RH 30~70 且 T 18~26
        val comfortCount = last24h.count { r ->
            val rh = r.humidity.coerceIn(0f, 100f)
            r.temperature in 18f..26f && rh in 30f..70f
        }
        val comfortRatio = if (last24h.isNotEmpty()) comfortCount * 100 / last24h.size else 0

        // 3 小时气压变化（符合文档：3h 下降>3hPa）
        val delta3h = deltaOverWindow(last3h, anchorEndSec, WINDOW_3H_SEC)
        val pressureClause = if (validPressures.size >= 2 && delta3h != null) {
            val tendency = when {
                delta3h <= -3f -> "近3小时气压下降${String.format("%.1f", abs(delta3h))}hPa，降水/对流风险升高，建议加固风绳"
                delta3h <= -2f -> "近3小时气压下降约1.5–2 hPa，暴雨或强风可能在1-3小时内到达，请加固风绳"
                delta3h <= -1.5f -> "近3小时气压缓慢下降（${String.format("%.1f", abs(delta3h))}hPa），天气可能转差"
                delta3h >= 1.5f -> "近3小时气压回升（+${String.format("%.1f", delta3h)}hPa），天气趋稳"
                else -> "近3小时气压基本平稳（${String.format("%+.1f", delta3h)}hPa）"
            }
            "；$tendency"
        } else {
            "；气压数据不足（无法评估3h倾向）"
        }

        val humidityClause = when {
            hAvg >= 75f -> "湿度偏高（平均${String.format("%.0f", hAvg)}%），可能偏闷"
            hAvg <= 35f -> "空气偏干（平均${String.format("%.0f", hAvg)}%），注意补水保湿"
            else -> "湿度整体适中（平均${String.format("%.0f", hAvg)}%）"
        }

        return buildString {
            append("24h：温度")
            append(String.format("%.1f~%.1f°C（均值%.1f）", tMin, tMax, tAvg))
            append("，")
            append(humidityClause)
            append("，舒适占比约")
            append(comfortRatio)
            append("%")
            append(pressureClause)
        }
    }

    private fun estimateQuality(records: List<TemperatureRecord>): InsightDataQuality {
        val hasTemp = records.any { it.temperature != 0f || it.humidity != 0f } // 仅用于“全0”异常兜底
        val validPressureCount = records.count { it.pressure != null && it.pressure > 0f }
        return when {
            records.size < 10 || !hasTemp -> InsightDataQuality.Poor
            validPressureCount >= 10 -> InsightDataQuality.Good
            validPressureCount in 2..9 -> InsightDataQuality.Limited
            else -> InsightDataQuality.Limited
        }
    }

    private fun buildPressureTrendInsight(
        last24h: List<TemperatureRecord>,
        last6h: List<TemperatureRecord>,
        last3h: List<TemperatureRecord>,
        nowSec: Long
    ): WeatherInsightItem? {
        val p24 = last24h.mapNotNull { it.pressure?.takeIf { p -> p in PRESSURE_VALID_MIN..PRESSURE_VALID_MAX } }
        if (p24.size < 2) {
            return WeatherInsightItem(
                id = "pressure_trend",
                title = "气压趋势",
                summary = "有效气压记录不足，无法评估趋势（气压为0或超出传感器量程视为无效）。",
                severity = InsightSeverity.Info,
                metrics = emptyList(),
                scopes = listOf(InsightScope.Scope3h)
            )
        }

        val nowP = last24h.lastOrNull()?.pressure?.takeIf { it in PRESSURE_VALID_MIN..PRESSURE_VALID_MAX }
            ?: p24.last()

        // Pmin/Pmax 与 24h 变幅描述用地面气象合理区间，避免异常值污染展示
        val displayP24 = p24.filter { it in PRESSURE_DISPLAY_MIN..PRESSURE_DISPLAY_MAX }
        val p24Min = displayP24.minOrNull()
        val p24Max = displayP24.maxOrNull()
        val hasValidMinMax = displayP24.size >= 2 && p24Min != null && p24Max != null

        val delta3h = deltaOverWindow(last3h, nowSec, WINDOW_3H_SEC)
        val delta6h = deltaOverWindow(last6h, nowSec, WINDOW_6H_SEC)
        val delta24h = deltaOverWindow(last24h, nowSec, WINDOW_24H_SEC)
        val ehr3h = delta3h?.let { it / 3f }

        // 3h 内下降 1.5–2 hPa 单独提示：雷雨或大风可能即将到来
        val (severity, summary) = when {
            ehr3h == null -> InsightSeverity.Attention to "数据不足：建议继续采集，静止满3小时后趋势更可靠。"
            delta3h != null && delta3h in -2f..-1.5f -> InsightSeverity.Attention to "气压骤降（3h下降${String.format("%.1f", abs(delta3h))}hPa），暴雨或强风可能在1-3小时内到达，请加固风绳。"
            ehr3h <= -2.5f -> InsightSeverity.Warning to "气压下降很快，短时强对流/雷雨风险升高，建议加固风绳并准备避雨。"
            ehr3h <= -1.5f -> InsightSeverity.Attention to "气压下降偏快，天气可能转差（阴雨/风增强），留意变化。"
            ehr3h <= -0.8f -> InsightSeverity.Attention to "气压缓慢下降，降水可能性增加（不等于一定下雨）。"
            ehr3h >= 0.8f -> InsightSeverity.Normal to "气压回升，天气趋于稳定/转好。"
            else -> InsightSeverity.Normal to "气压趋势整体平稳。"
        }

        // 24h 描述：先跌后涨（最低点不在首尾）或简单变幅；用展示合理区间
        val recordsWithValidP = last24h.mapIndexedNotNull { i, r ->
            r.pressure?.takeIf { it in PRESSURE_DISPLAY_MIN..PRESSURE_DISPLAY_MAX }?.let { i to it }
        }
        val firstP = recordsWithValidP.minByOrNull { it.first }?.second
        val lastP = recordsWithValidP.maxByOrNull { it.first }?.second
        val idxOfMin = recordsWithValidP.minByOrNull { it.second }?.first
        val dipThenRise = hasValidMinMax && firstP != null && lastP != null && idxOfMin != null &&
            idxOfMin > 0 && idxOfMin < last24h.size - 1 &&
            p24Min!! < firstP && p24Min < lastP
        val clause24h = when {
            delta24h == null || p24.size < 2 -> null
            dipThenRise && hasValidMinMax -> "24小时内曾明显下降后回升，波动较大（Δ${String.format("%.1f", p24Max!! - p24Min!!)} hPa）。"
            delta24h <= -2f -> "24小时气压下降${String.format("%.1f", abs(delta24h))} hPa，整体偏低压。"
            delta24h >= 2f -> "24小时气压回升${String.format("%.1f", delta24h)} hPa，天气趋稳。"
            hasValidMinMax -> "24小时气压变幅${String.format("%.1f", p24Max!! - p24Min!!)} hPa，趋势平稳。"
            else -> "24小时气压趋势平稳。"
        }
        val fullSummary = if (clause24h != null) "$summary $clause24h" else summary

        val metrics = buildList {
            add(InsightMetric("当前", String.format("%.1f hPa", nowP)))
            delta24h?.let { add(InsightMetric("ΔP(24h)", String.format("%.1f hPa", it))) }
            if (hasValidMinMax) {
                add(InsightMetric("Pmin(24h)", String.format("%.1f hPa", p24Min!!)))
                add(InsightMetric("Pmax(24h)", String.format("%.1f hPa", p24Max!!)))
            }
            delta3h?.let { add(InsightMetric("Δ3h", String.format("%.1f hPa", it))) }
            delta6h?.let { add(InsightMetric("Δ6h", String.format("%.1f hPa", it))) }
            ehr3h?.let { add(InsightMetric("EHR(3h)", String.format("%.1f hPa/h", it))) }
        }

        val timeDesc = if (severity == InsightSeverity.Warning || severity == InsightSeverity.Attention) {
            "时段：${formatTimeRange(nowSec - WINDOW_3H_SEC, nowSec)}"
        } else null

        return WeatherInsightItem(
            id = "pressure_trend",
            title = "气压趋势与降水倾向",
            summary = fullSummary,
            severity = severity,
            metrics = metrics,
            relevantTimeDescription = timeDesc,
            scopes = listOf(InsightScope.Scope24h, InsightScope.Scope3h)
        )
    }

    private fun buildFrontSignalInsight(
        last24h: List<TemperatureRecord>,
        last6h: List<TemperatureRecord>,
        last3h: List<TemperatureRecord>,
        nowSec: Long
    ): WeatherInsightItem? {
        if (last24h.size < 2) return null

        val deltaT24h = deltaTemperatureOverWindow(last24h, nowSec, WINDOW_24H_SEC)
        val deltaH24h = deltaHumidityOverWindow(last24h, nowSec, WINDOW_24H_SEC)
        val deltaT6h = deltaTemperatureOverWindow(last6h, nowSec, WINDOW_6H_SEC)
        val deltaH6h = deltaHumidityOverWindow(last6h, nowSec, WINDOW_6H_SEC)
        val deltaT3h = deltaTemperatureOverWindow(last3h, nowSec, WINDOW_3H_SEC)
        val deltaH3h = deltaHumidityOverWindow(last3h, nowSec, WINDOW_3H_SEC)

        // 口径：24h + 6h 判定严重度；文案与指标同时体现 24小时、3小时
        val strongCooling6h = deltaT6h != null && deltaT6h <= -6f
        val humidChange = deltaH6h != null && abs(deltaH6h) >= 15f
        val strongCooling24h = deltaT24h != null && deltaT24h <= -8f

        val clause24h = when {
            deltaT24h == null && deltaH24h == null -> "数据不足"
            else -> "温度${deltaT24h?.let { String.format("%+.1f", it) } ?: "-"}°C，湿度${deltaH24h?.let { String.format("%+.0f", it) } ?: "-"}%"
        }
        val clause3h = when {
            deltaT3h == null && deltaH3h == null -> "数据不足"
            else -> "温度${deltaT3h?.let { String.format("%+.1f", it) } ?: "-"}°C，湿度${deltaH3h?.let { String.format("%+.0f", it) } ?: "-"}%"
        }

        val conclusion = when {
            strongCooling6h && humidChange -> "温湿突变明显，可能有冷空气/锋面活动，注意保暖与风雨。"
            strongCooling6h -> "存在降温趋势且近段降温明显，可能有冷空气影响。"
            strongCooling24h -> "24小时出现明显降温，可能有冷空气过程。"
            else -> "温湿变化平稳，未见明显锋面信号。"
        }

        val severity = when {
            strongCooling6h && humidChange -> InsightSeverity.Attention
            strongCooling6h || strongCooling24h -> InsightSeverity.Attention
            else -> InsightSeverity.Normal
        }

        val summary = "24小时：$clause24h。3小时：$clause3h。$conclusion"

        val metrics = buildList {
            deltaT24h?.let { add(InsightMetric("ΔT(24h)", String.format("%.1f °C", it))) }
            deltaH24h?.let { add(InsightMetric("ΔRH(24h)", String.format("%.0f %%", it))) }
            deltaT3h?.let { add(InsightMetric("ΔT(3h)", String.format("%.1f °C", it))) }
            deltaH3h?.let { add(InsightMetric("ΔRH(3h)", String.format("%.0f %%", it))) }
        }

        val timeDesc = if (severity == InsightSeverity.Attention) {
            if (strongCooling6h) "近6小时：${formatTimeRange(nowSec - WINDOW_6H_SEC, nowSec)}"
            else "24小时：${formatTimeRange(nowSec - WINDOW_24H_SEC, nowSec)}"
        } else null

        return WeatherInsightItem(
            id = "front_signal",
            title = "冷空气/锋面迹象",
            summary = summary,
            severity = severity,
            metrics = metrics,
            relevantTimeDescription = timeDesc,
            scopes = listOf(InsightScope.Scope24h, InsightScope.Scope3h)
        )
    }

    private fun buildComfortInsight(records: List<TemperatureRecord>, anchorEndSec: Long): WeatherInsightItem? {
        val last = records.lastOrNull() ?: return null
        val t = last.temperature
        val rh = last.humidity.coerceIn(0f, 100f)

        // 露点（简化 Magnus）：Td = (b*gamma)/(a-gamma)
        val td = dewPointCelsius(t, rh)
        val spread = t - td

        // Steadman 体感（无风速时用 1 m/s）
        val apparentTemp = steadmanApparentTemperatureCelsius(t, rh, 1f)

        val (severity, summary) = when {
            rh >= 80f && t >= 26f -> InsightSeverity.Attention to "偏闷热：湿度高会降低散热效率，体感更热。"
            rh >= 70f -> InsightSeverity.Attention to "偏潮湿：湿度较高，体感略闷，建议通风或除湿。"
            rh <= 30f -> InsightSeverity.Attention to "偏干燥：注意补水与保湿。"
            else -> InsightSeverity.Normal to "体感整体舒适。"
        }

        val metrics = listOf(
            InsightMetric("T", String.format("%.1f °C", t)),
            InsightMetric("RH", String.format("%.0f %%", rh)),
            InsightMetric("体感", String.format("%.1f °C", apparentTemp)),
            InsightMetric("露点", String.format("%.1f °C", td)),
            InsightMetric("T-Td", String.format("%.1f °C", spread))
        )

        val timeDesc = if (severity == InsightSeverity.Attention) "约 ${formatTime(anchorEndSec)}" else null

        return WeatherInsightItem(
            id = "comfort",
            title = "体感与舒适度",
            summary = summary,
            severity = severity,
            metrics = metrics,
            relevantTimeDescription = timeDesc,
            scopes = listOf(InsightScope.Scope3h)
        )
    }

    private fun buildFogRiskInsight(records: List<TemperatureRecord>, anchorEndSec: Long): WeatherInsightItem? {
        val last = records.lastOrNull() ?: return null
        val t = last.temperature
        val rh = last.humidity.coerceIn(0f, 100f)
        val td = dewPointCelsius(t, rh)
        val spread = abs(t - td)

        // 分级预警：高风险 > 中风险 > 低风险
        val highRisk = rh >= 80f && spread <= 2f
        val mediumRisk = rh >= 75f && spread <= 3f
        
        val (severity, summary) = when {
            highRisk -> InsightSeverity.Warning to "结露风险很高！温度接近露点（${String.format("%.1f", td)}°C），请立即拉开帐篷通风窗，将羽绒睡袋移离帐壁。"
            mediumRisk -> InsightSeverity.Attention to "有结露风险。温度${String.format("%.1f", t)}°C，露点${String.format("%.1f", td)}°C，建议拉开通风窗保持空气流通。"
            else -> InsightSeverity.Normal to "结露风险较低，当前环境适宜。"
        }

        val timeDesc = if (severity == InsightSeverity.Warning || severity == InsightSeverity.Attention) {
            "约 ${formatTime(anchorEndSec)}"
        } else null

        return WeatherInsightItem(
            id = "fog_risk",
            title = "结露预警",
            summary = summary,
            severity = severity,
            metrics = listOf(
                InsightMetric("T", String.format("%.1f °C", t)),
                InsightMetric("RH", String.format("%.0f %%", rh)),
                InsightMetric("露点", String.format("%.1f °C", td)),
                InsightMetric("T-Td", String.format("%.1f °C", spread))
            ),
            relevantTimeDescription = timeDesc,
            scopes = listOf(InsightScope.Scope3h)
        )
    }

    private fun buildForecast3hInsight(
        last3h: List<TemperatureRecord>,
        anchorEndSec: Long
    ): WeatherInsightItem? {
        val points = last3h
            .asSequence()
            .filter { it.timestamp > 0 }
            .mapNotNull { r ->
                val p = r.pressure?.takeIf { it > 0f } ?: return@mapNotNull null
                r.timestamp to p
            }
            .toList()

        if (points.size < 3) {
            return WeatherInsightItem(
                id = "forecast_3h",
                title = "短时预测 (3h)",
                summary = "有效气压点不足，暂无法做3小时预测（建议继续采集）。",
                severity = InsightSeverity.Info,
                scopes = listOf(InsightScope.Scope3h)
            )
        }

        val (slopeHpaPerHour, spanHours, usedCount) = linearSlopeHpaPerHour(points)
        val predictedDelta3h = slopeHpaPerHour * 3f

        val confidence = when {
            spanHours >= 2.5 && usedCount >= 12 -> "高"
            spanHours >= 1.5 && usedCount >= 6 -> "中"
            else -> "低"
        }

        val (severity, summary) = when {
            slopeHpaPerHour <= -2.0f ->
                InsightSeverity.Warning to "预计未来3小时气压继续快速下降，降水/强对流风险上升，建议提前加固风绳并准备避雨。"
            slopeHpaPerHour <= -1.0f ->
                InsightSeverity.Attention to "预计未来3小时气压缓慢下降，降水可能性上升，留意天气变化。"
            slopeHpaPerHour >= 1.0f ->
                InsightSeverity.Normal to "预计未来3小时气压回升，天气趋于稳定/转好。"
            else ->
                InsightSeverity.Normal to "预计未来3小时气压变化不大，趋势整体平稳。"
        }

        val metrics = listOf(
            InsightMetric("斜率", String.format("%.2f hPa/h", slopeHpaPerHour)),
            InsightMetric("预计Δ3h", String.format("%.1f hPa", predictedDelta3h)),
            InsightMetric("覆盖", String.format("%.1f h", spanHours)),
            InsightMetric("置信度", confidence)
        )

        val timeDesc = if (severity == InsightSeverity.Warning || severity == InsightSeverity.Attention) {
            "基于时段：${formatTimeRange(anchorEndSec - WINDOW_3H_SEC, anchorEndSec)}"
        } else null

        return WeatherInsightItem(
            id = "forecast_3h",
            title = "短时预测 (3h)",
            summary = summary,
            severity = severity,
            metrics = metrics,
            relevantTimeDescription = timeDesc,
            scopes = listOf(InsightScope.Scope3h)
        )
    }

    private fun sliceByWindow(
        sorted: List<TemperatureRecord>,
        endSec: Long,
        windowSec: Long
    ): List<TemperatureRecord> {
        val start = endSec - windowSec
        return sorted.filter { it.timestamp in start..endSec }
    }

    private fun deltaOverWindow(
        windowRecords: List<TemperatureRecord>,
        nowSec: Long,
        windowSec: Long
    ): Float? {
        if (windowRecords.size < 2) return null
        val end = windowRecords.lastOrNull { it.pressure != null && it.pressure > 0f }?.pressure ?: return null
        val startSec = nowSec - windowSec
        val startRec = windowRecords
            .filter { it.pressure != null && it.pressure > 0f }
            .minByOrNull { abs(it.timestamp - startSec) }
            ?: return null
        val startP = startRec.pressure ?: return null
        return end - startP
    }

    private fun deltaTemperatureOverWindow(
        windowRecords: List<TemperatureRecord>,
        nowSec: Long,
        windowSec: Long
    ): Float? {
        if (windowRecords.size < 2) return null
        val endT = windowRecords.last().temperature
        val startSec = nowSec - windowSec
        val startRec = windowRecords.minByOrNull { abs(it.timestamp - startSec) } ?: return null
        return endT - startRec.temperature
    }

    private fun deltaHumidityOverWindow(
        windowRecords: List<TemperatureRecord>,
        nowSec: Long,
        windowSec: Long
    ): Float? {
        if (windowRecords.size < 2) return null
        val endH = windowRecords.last().humidity
        val startSec = nowSec - windowSec
        val startRec = windowRecords.minByOrNull { abs(it.timestamp - startSec) } ?: return null
        return endH - startRec.humidity
    }

    /**
     * Magnus 露点近似（摄氏度）。
     * 对于 Android 端展示足够；RH=0 时做下限保护。
     */
    private fun dewPointCelsius(tC: Float, rhPercent: Float): Float {
        val a = 17.62
        val b = 243.12
        val rh = (rhPercent.coerceIn(1f, 100f) / 100.0).toDouble()
        val t = tC.toDouble()
        val gamma = (a * t) / (b + t) + ln(rh)
        val td = (b * gamma) / (a - gamma)
        return td.toFloat()
    }

    /**
     * Steadman 体感温度（°C），无辐射版。
     * AT = Ta + 0.33×e − 0.70×ws − 4.00；e = (rh/100)×6.105×exp(17.27×Ta/(237.7+Ta))（hPa）。
     * 参考：澳大利亚气象局、Breezy Weather Discussion #1085。
     * @param windSpeedMps 风速 m/s；无传感器时用 1（微风）或 0。
     */
    private fun steadmanApparentTemperatureCelsius(tC: Float, rhPercent: Float, windSpeedMps: Float = 1f): Float {
        val ta = tC.toDouble()
        val rh = (rhPercent.coerceIn(0f, 100f) / 100.0).toDouble()
        val e = rh * 6.105 * exp(17.27 * ta / (237.7 + ta))
        val ws = windSpeedMps.toDouble().coerceAtLeast(0.0)
        val at = ta + 0.33 * e - 0.70 * ws - 4.00
        return at.toFloat()
    }

    /**
     * 线性回归斜率：pressure = a + b*time，返回 b（hPa/h）
     * @return Triple(slopeHpaPerHour, spanHours, usedCount)
     */
    private fun linearSlopeHpaPerHour(points: List<Pair<Long, Float>>): Triple<Float, Double, Int> {
        // x: hours since first point
        val firstSec = points.first().first.toDouble()
        val xs = DoubleArray(points.size)
        val ys = DoubleArray(points.size)
        for (i in points.indices) {
            val (sec, p) = points[i]
            xs[i] = (sec.toDouble() - firstSec) / 3600.0
            ys[i] = p.toDouble()
        }
        val n = points.size
        val xMean = xs.sum() / n
        val yMean = ys.sum() / n
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - xMean
            num += dx * (ys[i] - yMean)
            den += dx * dx
        }
        val slope = if (den <= 1e-12) 0.0 else num / den // hPa per hour
        val spanHours = xs.maxOrNull()!! - xs.minOrNull()!!
        return Triple(slope.toFloat(), spanHours, n)
    }

    private fun severityRank(s: InsightSeverity): Int = when (s) {
        InsightSeverity.Warning -> 4
        InsightSeverity.Attention -> 3
        InsightSeverity.Normal -> 2
        InsightSeverity.Info -> 1
    }

    /** 格式化为「HH:mm」 */
    private fun formatTime(sec: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(sec * 1000))

    /** 格式化为「M月d日 HH:mm」，用于跨天时段 */
    private fun formatDateHour(sec: Long): String =
        SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(sec * 1000))

    /** 生成时段文案：若同一天则「今日 HH:mm–HH:mm」，否则「M月d日 HH:mm–M月d日 HH:mm」 */
    private fun formatTimeRange(startSec: Long, endSec: Long): String {
        val sameDay = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(startSec * 1000)) == SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(endSec * 1000))
        return if (sameDay) {
            "今日 ${formatTime(startSec)}–${formatTime(endSec)}"
        } else {
            "${formatDateHour(startSec)}–${formatDateHour(endSec)}"
        }
    }
}

