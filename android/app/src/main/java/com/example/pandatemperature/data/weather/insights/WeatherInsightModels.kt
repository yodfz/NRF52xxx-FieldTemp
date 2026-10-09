package com.example.pandatemperature.data.weather.insights

/**
 * 首页“气象分析”输出的严重级别（用于 UI 着色与排序）
 */
enum class InsightSeverity {
    /** 信息/中性 */
    Info,
    /** 正常/稳定 */
    Normal,
    /** 需要注意（趋势变陡或存在风险征兆） */
    Attention,
    /** 警告（较高风险，建议采取行动） */
    Warning
}

/**
 * 数据质量（用于展示“数据不足/传感器故障/可参考”等提示）
 */
enum class InsightDataQuality {
    /** 数据充足，结论可靠 */
    Good,
    /** 数据可用但不足，结论仅供参考 */
    Limited,
    /** 数据不足或传感器无效，无法评估 */
    Poor
}

/**
 * 指标键值对（供卡片展示用）
 */
data class InsightMetric(
    val label: String,
    val value: String
)

/**
 * 洞察口径：用于首页只展示 3h、详情页区分展示
 */
enum class InsightScope {
    /** 3 小时口径（气压趋势、短时预测、体感、雾露等） */
    Scope3h,
    /** 24 小时口径（冷空气/锋面等） */
    Scope24h
}

/**
 * 单条气象洞察（卡片）
 * @param scopes 口径列表，可多选（如冷空气/锋面同时用 24h+3h）。详情页按列表显示多个「3小时口径」「24小时口径」TAG
 * @param relevantTimeDescription 红色/黄色时展示的“具体时间点”文案（如「时段：14:00–17:00」「约 17:30」），仅当 severity 为 Warning/Attention 时展示
 */
data class WeatherInsightItem(
    val id: String,
    val title: String,
    val summary: String,
    val severity: InsightSeverity = InsightSeverity.Info,
    val metrics: List<InsightMetric> = emptyList(),
    val evaluatedAtMs: Long = System.currentTimeMillis(),
    val relevantTimeDescription: String? = null,
    val scopes: List<InsightScope> = emptyList()
)

/**
 * 一次洞察生成结果（包含时间范围与数据质量）
 */
data class WeatherInsightsResult(
    val items: List<WeatherInsightItem>,
    val dataQuality: InsightDataQuality,
    /**
     * 基于全部 24h 数据生成的“整体总结”文案（用于详情页顶部展示）。
     * 为空时 UI 需要自行兜底。
     */
    val overviewSummary: String? = null,
    /**
     * 基于 3h 口径的短小结（用于首页“整体评估小结”一行展示）。
     * 为空时 UI 可回退到 overviewSummary。
     */
    val overviewSummary3h: String? = null,
    /** 覆盖的历史窗口（秒时间戳） */
    val timeRangeStartSec: Long? = null,
    val timeRangeEndSec: Long? = null,
    val evaluatedAtMs: Long = System.currentTimeMillis()
)

