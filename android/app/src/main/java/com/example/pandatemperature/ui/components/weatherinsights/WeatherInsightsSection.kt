package com.example.pandatemperature.ui.components.weatherinsights

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pandatemperature.data.weather.insights.InsightDataQuality
import com.example.pandatemperature.data.weather.insights.InsightScope
import com.example.pandatemperature.data.weather.insights.InsightSeverity
import com.example.pandatemperature.data.weather.insights.WeatherInsightItem
import com.example.pandatemperature.data.weather.insights.WeatherInsightsResult

@Composable
fun WeatherInsightsSection(
    insights: WeatherInsightsResult?,
    showScopeLabel: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (insights == null) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 顶部小提示：数据质量（详情页用 24h 总结）
        DataQualityBanner(
            quality = insights.dataQuality,
            summary = insights.overviewSummary,
            modifier = Modifier.fillMaxWidth()
        )

        insights.items.forEach { item ->
            WeatherInsightCard(
                item = item,
                showScopeLabel = showScopeLabel,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DataQualityBanner(
    quality: InsightDataQuality,
    summary: String?,
    modifier: Modifier = Modifier
) {
    val (text, color) = when (quality) {
        InsightDataQuality.Good -> (summary?.takeIf { it.isNotBlank() } ?: "气象分析：已生成总结（24h+3h）") to Color(0xFF388E3C)
        InsightDataQuality.Limited -> "气象分析：数据有限，结论仅供参考" to Color(0xFFFF9800)
        InsightDataQuality.Poor -> "气象分析：数据不足，无法可靠评估" to Color(0xFF757575)
    }

    Surface(
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.04f),
                shape = MaterialTheme.shapes.medium
            ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.04f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, shape = CircleShape)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WeatherInsightCard(
    item: WeatherInsightItem,
    showScopeLabel: Boolean = false,
    modifier: Modifier = Modifier
) {
    val severityColor = when (item.severity) {
        InsightSeverity.Warning -> Color(0xFFD32F2F)
        InsightSeverity.Attention -> Color(0xFFFF9800)
        InsightSeverity.Normal -> Color(0xFF388E3C)
        InsightSeverity.Info -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    }

    val scopeTags = when {
        !showScopeLabel || item.scopes.isEmpty() -> emptyList()
        else -> item.scopes.distinct().map { s ->
            when (s) {
                InsightScope.Scope24h -> "24小时口径" to (Color(0xFFE3F2FD) to Color(0xFF1565C0))
                InsightScope.Scope3h -> "3小时口径" to (Color(0xFFE8F5E9) to Color(0xFF2E7D32))
            }
        }
    }

    Surface(
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.04f),
                shape = MaterialTheme.shapes.medium
            ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.04f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(severityColor, shape = CircleShape)
                    )
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        scopeTags.forEach { (label, colors) ->
                            val (tagBg, tagText) = colors
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = tagBg
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 10.sp,
                                    color = tagText,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 红/黄时展示具体时间点
            if ((item.severity == InsightSeverity.Warning || item.severity == InsightSeverity.Attention) &&
                !item.relevantTimeDescription.isNullOrBlank()
            ) {
                Text(
                    text = "时间：${item.relevantTimeDescription}",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                )
            }

            Text(
                text = item.summary.trim(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )

            if (item.metrics.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item.metrics.take(5).forEach { m ->
                        MetricChip(label = m.label, value = m.value)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

