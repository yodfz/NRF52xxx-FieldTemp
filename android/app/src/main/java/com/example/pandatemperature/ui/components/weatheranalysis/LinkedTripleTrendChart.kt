package com.example.pandatemperature.ui.components.weatheranalysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pandatemperature.data.model.TemperatureRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

// 参考图样式：深色背景、浅灰网格、红联指针
private val ChartBackgroundDark = Color(0xFF1a1a1e)
private val ChartGridColor = Color(0xFF3d3d42)
private val PointerRed = Color(0xFFE53935)
private val PressureBlue = Color(0xFF64B5F6)
private val TempOrange = Color(0xFFFF9800)
private val HumidityGreen = Color(0xFF66BB6A)
private val TextLight = Color(0xFFE0E0E0)
private val InfoBarBackground = Color(0xFF2d2d30)

/**
 * WMDP 可视化 MVP：
 * - 温/湿/压三条曲线垂直排布
 * - 单一联动指针：拖动任意区域，三图同步高亮同一时间点
 *
 * 说明：
 * - X 轴以 records 的 timestamp（秒）为准（无 timestamp 则退化为 index）。
 * - 压力仅绘制有效值（p>0）；若无有效气压则显示占位提示。
 */
@Composable
fun LinkedTripleTrendChart(
    records: List<TemperatureRecord>,
    modifier: Modifier = Modifier
) {
    if (records.isEmpty()) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            color = ChartBackgroundDark
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "暂无24小时数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextLight.copy(alpha = 0.7f)
                )
            }
        }
        return
    }

    val sorted = remember(records) { records.sortedBy { it.timestamp } }
    val temps = remember(sorted) { sorted.map { it.temperature } }
    val hums = remember(sorted) { sorted.map { it.humidity.coerceIn(0f, 100f) } }
    val pressures = remember(sorted) { sorted.map { it.pressure } }
    val validPressures = remember(sorted) { sorted.mapNotNull { it.pressure?.takeIf { p -> p > 0f } } }

    val minT = temps.minOrNull() ?: 0f
    val maxT = temps.maxOrNull() ?: 0f
    val minH = hums.minOrNull() ?: 0f
    val maxH = hums.maxOrNull() ?: 100f
    val hasPressure = validPressures.isNotEmpty()
    // 气压 Y 轴固定刻度：800 hPa = 0%，1100 hPa = 100%
    val pressureYMin = 800f
    val pressureYMax = 1100f

    // 联动选中点（以 index 为准）
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    fun indexFromX(x: Float, widthPx: Float): Int {
        if (widthPx <= 1f) return 0
        val ratio = (x / widthPx).coerceIn(0f, 1f)
        val idx = (ratio * (sorted.size - 1)).toInt()
        return idx.coerceIn(0, sorted.size - 1)
    }

    val firstSec = sorted.firstOrNull()?.timestamp?.takeIf { it > 0 } ?: 0L
    val lastSec = sorted.lastOrNull()?.timestamp?.takeIf { it > 0 } ?: 0L
    val timeLabels = remember(firstSec, lastSec) {
        if (lastSec <= firstSec) listOf("00:00", "06:00", "12:00", "18:00", "24:00")
        else (0..4).map { i ->
            val t = firstSec + (lastSec - firstSec) * i / 4
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t * 1000))
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = ChartBackgroundDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 信息栏：深灰底、白字（参考图 tooltip 样式）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color = InfoBarBackground, shape = RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val timeText = if (selectedIndex != null) {
                    val ts = sorted[selectedIndex!!].timestamp
                    if (ts > 0) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts * 1000))
                    else "--:--"
                } else "--:--"
                Text(
                    text = "时间: $timeText",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = TextLight,
                    fontSize = 12.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val t = selectedIndex?.let { temps[it] }
                    val h = selectedIndex?.let { hums[it] }
                    val p = selectedIndex?.let { pressures[it] }
                    if (hasPressure) {
                        Text(
                            text = "气压: ${if (p != null && p > 0f) String.format(Locale.getDefault(), "%.1f hPa", p) else "--"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = PressureBlue,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = "温度: ${t?.let { String.format(Locale.getDefault(), "%.1f °C", it) } ?: "--"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TempOrange,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "湿度: ${h?.let { String.format(Locale.getDefault(), "%.0f%%", it) } ?: "--"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = HumidityGreen,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp
                    )
                }
            }

            // 三联图顺序：气压 → 温度 → 湿度（与参考图一致）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(sorted) {
                        detectDragGestures(
                            onDragStart = { if (selectedIndex == null) selectedIndex = sorted.size - 1 },
                            onDrag = { change, _ -> change.consume() }
                        )
                    },
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (hasPressure) {
                    MiniLineNullable(
                        title = "气压 (hPa)",
                        color = PressureBlue,
                        values = pressures,
                        yMin = pressureYMin,
                        yMax = pressureYMax,
                        selectedIndex = selectedIndex,
                        onSelectIndex = { selectedIndex = it },
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(ChartBackgroundDark, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "无有效气压数据",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextLight.copy(alpha = 0.5f),
                            fontSize = 11.sp
                        )
                    }
                }
                MiniLine(
                    title = "温度 (°C)",
                    color = TempOrange,
                    values = temps,
                    yMin = minT,
                    yMax = maxT,
                    selectedIndex = selectedIndex,
                    onSelectIndex = { selectedIndex = it },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                MiniLine(
                    title = "相对湿度 (%)",
                    color = HumidityGreen,
                    values = hums,
                    yMin = minH,
                    yMax = maxH,
                    selectedIndex = selectedIndex,
                    onSelectIndex = { selectedIndex = it },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }

            // X 轴时间标签：00:00 06:00 12:00 18:00 24:00
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                timeLabels.forEach { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 10.sp,
                        color = ChartGridColor
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniLine(
    title: String,
    color: Color,
    values: List<Float>,
    yMin: Float,
    yMax: Float,
    selectedIndex: Int?,
    onSelectIndex: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(ChartBackgroundDark, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = TextLight
            )
            Spacer(modifier = Modifier.height(4.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(values) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val idx = indexFromX(offset.x, size.width.toFloat(), values.size)
                                onSelectIndex(idx)
                            },
                            onDrag = { change, _ ->
                                val idx = indexFromX(change.position.x, size.width.toFloat(), values.size)
                                onSelectIndex(idx)
                                change.consume()
                            }
                        )
                    }
            ) {
                drawSeries(
                    values = values,
                    yMin = yMin,
                    yMax = yMax,
                    color = color,
                    selectedIndex = selectedIndex
                )
            }
        }
    }
}

@Composable
private fun MiniLineNullable(
    title: String,
    color: Color,
    values: List<Float?>,
    yMin: Float,
    yMax: Float,
    selectedIndex: Int?,
    onSelectIndex: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(ChartBackgroundDark, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = TextLight
            )
            Spacer(modifier = Modifier.height(4.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(values) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val idx = indexFromX(offset.x, size.width.toFloat(), values.size)
                                onSelectIndex(idx)
                            },
                            onDrag = { change, _ ->
                                val idx = indexFromX(change.position.x, size.width.toFloat(), values.size)
                                onSelectIndex(idx)
                                change.consume()
                            }
                        )
                    }
            ) {
                drawSeriesNullable(
                    values = values,
                    yMin = yMin,
                    yMax = yMax,
                    color = color,
                    selectedIndex = selectedIndex
                )
            }
        }
    }
}

private fun indexFromX(x: Float, widthPx: Float, count: Int): Int {
    if (count <= 1) return 0
    val ratio = (x / widthPx).coerceIn(0f, 1f)
    return (ratio * (count - 1)).toInt().coerceIn(0, count - 1)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid() {
    val w = size.width
    val h = size.height
    for (i in 1..4) {
        val x = w * i / 5
        drawLine(ChartGridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
    }
    for (i in 1..4) {
        val y = h * i / 5
        drawLine(ChartGridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeries(
    values: List<Float>,
    yMin: Float,
    yMax: Float,
    color: Color,
    selectedIndex: Int?
) {
    val w = size.width
    val h = size.height
    val range = (yMax - yMin).takeIf { abs(it) > 1e-6f } ?: 1f

    drawGrid()

    val path = Path()
    for (i in values.indices) {
        val v = values[i]
        val x = if (values.size <= 1) 0f else (i.toFloat() / (values.size - 1)) * w
        val y = h - ((v - yMin) / range).coerceIn(0f, 1f) * h
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // 曲线上小圆点（采样绘制，避免过密）
    val sampleStep = (values.size / 40).coerceAtLeast(1)
    for (i in values.indices step sampleStep) {
        val v = values[i]
        val x = if (values.size <= 1) 0f else (i.toFloat() / (values.size - 1)) * w
        val y = h - ((v - yMin) / range).coerceIn(0f, 1f) * h
        drawCircle(color = color, radius = 2.5f, center = Offset(x, y))
    }

    // 红色联动指针 + 空心红圈
    if (selectedIndex != null && selectedIndex in values.indices) {
        val x = if (values.size <= 1) 0f else (selectedIndex.toFloat() / (values.size - 1)) * w
        drawLine(
            color = PointerRed,
            start = Offset(x, 0f),
            end = Offset(x, h),
            strokeWidth = 2f
        )
        val v = values[selectedIndex]
        val y = h - ((v - yMin) / range).coerceIn(0f, 1f) * h
        drawCircle(color = PointerRed, radius = 6f, center = Offset(x, y), style = Stroke(width = 2f))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeriesNullable(
    values: List<Float?>,
    yMin: Float,
    yMax: Float,
    color: Color,
    selectedIndex: Int?
) {
    val w = size.width
    val h = size.height
    val range = (yMax - yMin).takeIf { abs(it) > 1e-6f } ?: 1f

    drawGrid()

    val path = Path()
    var started = false
    for (i in values.indices) {
        val v = values[i]
        if (v == null) {
            started = false
            continue
        }
        val x = if (values.size <= 1) 0f else (i.toFloat() / (values.size - 1)) * w
        val y = h - ((v - yMin) / range).coerceIn(0f, 1f) * h
        if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    val sampleStep = (values.size / 40).coerceAtLeast(1)
    for (i in values.indices step sampleStep) {
        val v = values[i] ?: continue
        val x = if (values.size <= 1) 0f else (i.toFloat() / (values.size - 1)) * w
        val y = h - ((v - yMin) / range).coerceIn(0f, 1f) * h
        drawCircle(color = color, radius = 2.5f, center = Offset(x, y))
    }

    if (selectedIndex != null && selectedIndex in values.indices) {
        val x = if (values.size <= 1) 0f else (selectedIndex.toFloat() / (values.size - 1)) * w
        drawLine(color = PointerRed, start = Offset(x, 0f), end = Offset(x, h), strokeWidth = 2f)
        val v = values[selectedIndex]
        if (v != null) {
            val y = h - ((v - yMin) / range).coerceIn(0f, 1f) * h
            drawCircle(color = PointerRed, radius = 6f, center = Offset(x, y), style = Stroke(width = 2f))
        }
    }
}

