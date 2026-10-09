package com.example.pandatemperature.ui.components

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pandatemperature.data.model.TemperatureRecord
import com.example.pandatemperature.data.weather.ChartWeatherEvent
import com.example.pandatemperature.data.weather.ChartWeatherEventType
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.comparisons.minOf

// 气压 Y 轴固定刻度：800 hPa = 0%（底部），1100 hPa = 100%（顶部），1000 约在 2/3 高度
private const val PRESSURE_CHART_MIN_HPA = 800f
private const val PRESSURE_CHART_MAX_HPA = 1100f
private val PRESSURE_CHART_RANGE_HPA = PRESSURE_CHART_MAX_HPA - PRESSURE_CHART_MIN_HPA

/**
 * 双 Y 轴面积图组件（可选气压）
 * 左侧 Y 轴显示温度，右侧 Y 轴显示湿度；有有效气压时增加气压曲线（归一化为相对 hPa 绘制）
 */
@Composable
fun LineChart(
    records: List<TemperatureRecord>,
    showLegend: Boolean = true,
    chartEvents: List<ChartWeatherEvent> = emptyList(),
    modifier: Modifier = Modifier
) {
    if (records.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Text(
                text = "暂无数据",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    // 温度颜色（红色系，带透明度）
    val temperatureColor = Color(0xFFFF6B6B) // 红色 - 温度线条
    val temperatureGradientStart = Color(0x99FF6B6B) // 渐变起始色 (alpha 0.6)
    val temperatureGradientEnd = Color(0x33FF6B6B) // 渐变结束色 (alpha 0.2)
    
    // 湿度颜色（青色系，带透明度）
    val humidityColor = Color(0xFF4ECDC4)    // 青色 - 湿度线条
    val humidityGradientStart = Color(0x994ECDC4) // 渐变起始色 (alpha 0.6)
    val humidityGradientEnd = Color(0x334ECDC4) // 渐变结束色 (alpha 0.2)
    
    // 气压颜色（橙色系，整体透明度/2，作为底层）- 仅在有有效气压时使用
    val pressureColor = Color(0x80FFA726)       // 橙色 - 气压线条 (alpha 0.5)
    val pressureGradientStart = Color(0x4DFFA726) // 渐变起始色 (alpha 0.3)
    val pressureGradientEnd = Color(0x1AFFA726)  // 渐变结束色 (alpha 0.1)
    
    // 获取主题颜色（在 Composable 中获取）
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
    
    // 计算数据范围
    val temperatures = records.map { it.temperature }
    val humidities = records.map { it.humidity }
    
    val minTemp = temperatures.minOrNull() ?: 0f
    val maxTemp = temperatures.maxOrNull() ?: 0f
    val minHum = humidities.minOrNull() ?: 0f
    val maxHum = humidities.maxOrNull() ?: 0f
    
    // 添加一些边距，让图表看起来更美观
    val tempRange = maxTemp - minTemp
    val humRange = maxHum - minHum
    val tempPadding = tempRange * 0.1f
    val humPadding = humRange * 0.1f
    
    val adjustedMinTemp = minTemp - tempPadding
    val adjustedMaxTemp = maxTemp + tempPadding
    val adjustedMinHum: Float = (minHum - humPadding).coerceAtLeast(0f)
    val adjustedMaxHum: Float = (maxHum + humPadding).coerceAtMost(100f)
    
    val tempRangeAdjusted: Float = adjustedMaxTemp - adjustedMinTemp
    val humRangeAdjusted: Float = adjustedMaxHum - adjustedMinHum

    // 气压：固定刻度（PRESSURE_CHART_*），信息栏显示相对 1000 hPa
    val validPressures = records.mapNotNull { r -> r.pressure?.takeIf { it > 0f } }
    val hasPressure = validPressures.isNotEmpty()
    // 气压基准值：取当前显示范围内第一个有效气压值（用于计算相对变化量）
    val pressureBaseline = validPressures.firstOrNull() ?: 1000f

    // 选中状态：记录当前选中的数据点索引（松手后保留）
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    // 拖拽位置对应的时间（按 x 线性插值），用于与按时间放置的图标对齐，保证移上去有提示
    var selectedTimeSec by remember { mutableStateOf<Long?>(null) }
    // 点击气象图标高亮的区间（同一图标再次点击取消高亮）
    var selectedEventRange by remember { mutableStateOf<ChartWeatherEvent?>(null) }
    // 用户是否正在拖拽（未拖拽时数据更新自动将竖线定位到最新数据）
    var isUserDragging by remember { mutableStateOf(false) }
    LaunchedEffect(chartEvents) { selectedEventRange = null }
    LaunchedEffect(records.size, records.lastOrNull()?.timestamp) {
        if (!isUserDragging && records.isNotEmpty()) {
            selectedIndex = records.size - 1
            selectedTimeSec = records.lastOrNull()?.timestamp
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 图例
        if (showLegend) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp), // 减少垂直padding
                horizontalArrangement = Arrangement.Center
            ) {
                // 温度图例
                Row(
                    modifier = Modifier.padding(end = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(temperatureColor)
                    )
                    Text(
                        text = "温度 (°C)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
                // 湿度图例
                Row(
                    modifier = Modifier.padding(end = if (hasPressure) 24.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(humidityColor)
                    )
                    Text(
                        text = "湿度 (%)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
                // 气压图例（仅当有有效气压时显示）
                if (hasPressure) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .background(pressureColor)
                        )
                        Text(
                            text = "气压 (hPa)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // 信息栏（固定高度，避免触摸时布局重排）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp) // 固定高度
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 时间
            Text(
                text = if (selectedIndex != null && selectedIndex!! < records.size) {
                    val record = records[selectedIndex!!]
                    if (record.timestamp > 0) {
                        val date = Date(record.timestamp * 1000)
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
                    } else {
                        "--"
                    }
                } else {
                    "--"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 温度
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(temperatureColor)
                )
                Text(
                    text = if (selectedIndex != null && selectedIndex!! < records.size) {
                        String.format("%.1f °C", records[selectedIndex!!].temperature)
                    } else {
                        "--"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 湿度
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(humidityColor)
                )
                Text(
                    text = if (selectedIndex != null && selectedIndex!! < records.size) {
                        String.format("%.1f %%", records[selectedIndex!!].humidity)
                    } else {
                        "--"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 气压（仅当有有效气压时显示，真实值 hPa，可选 Δ）
            if (hasPressure) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(pressureColor)
                    )
                    Text(
                        text = if (selectedIndex != null && selectedIndex!! < records.size) {
                            val r = records[selectedIndex!!]
                            val p = r.pressure
                            if (p != null && p > 0f) {
                                val delta = p - pressureBaseline
                                String.format("%.1f hPa (%+.1f)", p, delta)
                            } else "--"
                        } else "--",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 图表区域 - 使用aspectRatio限制宽高比，让图表保持横向长方形（Y轴比X轴短）
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f) // 使用16:9的宽高比，确保图表是横向的长方形
        ) {
            val maxWidthPx = constraints.maxWidth
            val paddingLeft = 0.dp
            val paddingRight = 0.dp
            val density = LocalDensity.current
            val paddingLeftPx = remember(density, paddingLeft) { with(density) { paddingLeft.toPx() } }
            val paddingRightPx = remember(density, paddingRight) { with(density) { paddingRight.toPx() } }
            // 拖拽到气象事件附近时显示 tooltip：用「拖拽位置对应时间」查找，与图标行（按时间比例放置）对齐
            val tooltipEvent = remember(selectedIndex, selectedTimeSec, records, chartEvents) {
                if (selectedIndex == null || selectedIndex!! !in records.indices || chartEvents.isEmpty()) return@remember null
                val t0 = records.first().timestamp
                val t1 = records.last().timestamp
                val timeRange = (t1 - t0).toFloat().coerceAtLeast(1f)
                // 优先用按 x 插值得到的时间，这样拖到图标位置就能对上该图标的事件
                val selectedTs = selectedTimeSec ?: records[selectedIndex!!].timestamp
                val inRange = chartEvents.find { ev ->
                    val end = ev.endSec ?: ev.timestampSec
                    selectedTs in ev.timestampSec..end
                }
                inRange ?: chartEvents.minByOrNull { ev ->
                    val end = ev.endSec ?: ev.timestampSec
                    when {
                        selectedTs < ev.timestampSec -> ev.timestampSec - selectedTs
                        selectedTs > end -> selectedTs - end
                        else -> 0L
                    }
                }?.takeIf { nearest ->
                    val end = nearest.endSec ?: nearest.timestampSec
                    when {
                        selectedTs in nearest.timestampSec..end -> true
                        else -> (minOf(
                            abs(selectedTs - nearest.timestampSec),
                            abs(selectedTs - end)
                        ) <= 60 * 60)
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(records.size, maxWidthPx, paddingLeftPx, paddingRightPx) {
                        detectDragGestures(
                            onDragStart = { isUserDragging = true },
                            onDragEnd = { isUserDragging = false },
                            onDragCancel = { isUserDragging = false }
                        ) { change, _ ->
                            val touchX = change.position.x
                            val leftAxisX = paddingLeftPx
                            val rightAxisX = maxWidthPx - paddingRightPx
                            val chartWidth = rightAxisX - leftAxisX
                            if (records.isNotEmpty() && chartWidth > 0) {
                                val relativeX = (touchX - leftAxisX).coerceIn(0f, chartWidth)
                                val index = if (records.size > 1) {
                                    ((relativeX / chartWidth) * (records.size - 1)).toInt()
                                        .coerceIn(0, records.size - 1)
                                } else 0
                                selectedIndex = index
                                // 按 x 线性插值得到时间，与图标行（按时间比例）一致，便于拖到雨点等图标时出提示
                                val t0 = records.first().timestamp
                                val t1 = records.last().timestamp
                                val timeRange = (t1 - t0).toFloat().coerceAtLeast(1f)
                                selectedTimeSec = (t0 + (relativeX / chartWidth) * timeRange).toLong()
                            }
                        }
                    }
            ) {
            Column(modifier = Modifier.fillMaxSize()) {
            val paddingTop = 0.dp
            val paddingBottom = if (chartEvents.isNotEmpty()) 32.dp else 0.dp
            val rangeHighlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // 绘制坐标轴和网格线
                val leftAxisX = paddingLeft.toPx()
                val rightAxisX = size.width - paddingRight.toPx()
                val bottomAxisY = size.height - paddingBottom.toPx()
                val topAxisY = paddingTop.toPx()
                
                val chartWidth = rightAxisX - leftAxisX
                val chartHeight = bottomAxisY - topAxisY
                
                // 绘制网格线（水平 + 垂直）
                val gridLineCount = 8
                val timeLabelCount = minOf(records.size, 6) // 最多显示6个时间标签
                val verticalGridLineCount = if (timeLabelCount <= 1) 1 else timeLabelCount * 2 // 竖线数量加倍

                // 绘制水平网格线
                for (i in 0..gridLineCount) {
                    val y = topAxisY + (chartHeight / gridLineCount) * i
                    val alpha = 0.2f // 统一使用淡色
                    
                    drawLine(
                        color = onSurfaceVariantColor.copy(alpha = alpha),
                        start = Offset(leftAxisX, y),
                        end = Offset(rightAxisX, y),
                        strokeWidth = 0.5.dp.toPx()
                    )
                }

                // 绘制垂直网格线（数量为时间标签的 2 倍）
                for (i in 0 until verticalGridLineCount) {
                    val x = if (verticalGridLineCount > 1) {
                        leftAxisX + (chartWidth / (verticalGridLineCount - 1)) * i
                    } else {
                        leftAxisX + chartWidth / 2
                    }
                    val alpha = 0.2f // 统一使用淡色

                    drawLine(
                        color = onSurfaceVariantColor.copy(alpha = alpha),
                        start = Offset(x, topAxisY),
                        end = Offset(x, bottomAxisY),
                        strokeWidth = 0.5.dp.toPx()
                    )
                }
                
                // 绘制左侧 Y 轴（温度）- 不绘制轴线，只保留网格
                
                // 绘制右侧 Y 轴（湿度）- 不绘制轴线，只保留网格
                
                // 绘制 X 轴 - 不绘制轴线，只保留网格
                
                // 绘制文本标签
                drawIntoCanvas { canvas ->
                    // 绘制左侧 Y 轴标签（温度）- 内嵌
                    for (i in 0..gridLineCount) {
                        // 跳过顶部和底部的标签，避免遮挡
                        if (i == 0 || i == gridLineCount) continue

                        val y = topAxisY + (chartHeight / gridLineCount) * i
                        val tempValue = adjustedMaxTemp - (tempRangeAdjusted / gridLineCount) * i
                        
                        val textPaint = Paint().apply {
                            color = onSurfaceVariantColor.copy(alpha = 0.7f).toArgb()
                            textSize = 10.sp.toPx()
                            textAlign = Paint.Align.LEFT // 左对齐
                            isAntiAlias = true
                        }
                        canvas.nativeCanvas.drawText(
                            String.format("%.1f°", tempValue),
                            leftAxisX + 8.dp.toPx(), // 内嵌在左侧
                            y - 6.dp.toPx(), // 稍微向上偏移
                            textPaint
                        )
                    }
                    
                    // 绘制右侧 Y 轴标签（湿度）- 内嵌
                    for (i in 0..gridLineCount) {
                        // 跳过顶部和底部的标签，避免遮挡
                        if (i == 0 || i == gridLineCount) continue

                        val y = topAxisY + (chartHeight / gridLineCount) * i
                        val humValue = adjustedMaxHum - (humRangeAdjusted / gridLineCount) * i
                        
                        val textPaint = Paint().apply {
                            color = onSurfaceVariantColor.copy(alpha = 0.7f).toArgb()
                            textSize = 10.sp.toPx()
                            textAlign = Paint.Align.RIGHT // 右对齐
                            isAntiAlias = true
                        }
                        canvas.nativeCanvas.drawText(
                            String.format("%.1f%%", humValue),
                            rightAxisX - 8.dp.toPx(), // 内嵌在右侧
                            y - 6.dp.toPx(), // 稍微向上偏移
                            textPaint
                        )
                    }
                    
                    // 绘制 X 轴标签（时间）- 内嵌在底部
                    for (i in 0 until timeLabelCount) {
                        val index = if (timeLabelCount > 1) {
                            (records.size - 1) * i / (timeLabelCount - 1)
                        } else {
                            0
                        }
                        val x = if (timeLabelCount > 1) {
                            leftAxisX + (chartWidth / (timeLabelCount - 1)) * i
                        } else {
                            leftAxisX + chartWidth / 2
                        }
                        
                        val record = records[index]
                        val timeStr = if (record.timestamp > 0) {
                            val date = Date(record.timestamp * 1000)
                            SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
                        } else {
                            ""
                        }
                        
                        val textPaint = Paint().apply {
                            color = onSurfaceVariantColor.copy(alpha = 0.7f).toArgb()
                            textSize = 9.sp.toPx()
                            textAlign = if (i == 0) Paint.Align.LEFT else if (i == timeLabelCount - 1) Paint.Align.RIGHT else Paint.Align.CENTER
                            isAntiAlias = true
                        }

                        // 调整文本位置，避免贴边
                        val drawX = if (i == 0) x + 4.dp.toPx() else if (i == timeLabelCount - 1) x - 4.dp.toPx() else x

                        canvas.nativeCanvas.drawText(
                            timeStr,
                            drawX,
                            bottomAxisY - 8.dp.toPx(), // 内嵌在底部上方
                            textPaint
                        )
                    }
                }
                
                // 绘制气压面积图（最底层，归一化相对气压 ΔhPa，仅当有有效气压时）
                if (hasPressure && records.isNotEmpty()) {
                    val pressureAreaPath = Path().apply {
                        var isFirst = true
                        records.forEachIndexed { index, record ->
                            val x = if (records.size > 1) {
                                leftAxisX + (chartWidth / (records.size - 1)) * index
                            } else {
                                leftAxisX + chartWidth / 2
                            }
                            val p = record.pressure
                            val normalized = if (p != null && p > 0f) (p - PRESSURE_CHART_MIN_HPA) / PRESSURE_CHART_RANGE_HPA else 0.5f
                            val ratio = normalized.coerceIn(0f, 1f)
                            val y = bottomAxisY - (chartHeight * ratio)
                            if (isFirst) {
                                moveTo(x, bottomAxisY)
                                lineTo(x, y)
                                isFirst = false
                            } else {
                                lineTo(x, y)
                            }
                        }
                        if (records.isNotEmpty()) {
                            val lastX = if (records.size > 1) {
                                leftAxisX + (chartWidth / (records.size - 1)) * (records.size - 1)
                            } else leftAxisX + chartWidth / 2
                            lineTo(lastX, bottomAxisY)
                            close()
                        }
                    }
                    val pressureLinePath = Path().apply {
                        var isFirst = true
                        records.forEachIndexed { index, record ->
                            val x = if (records.size > 1) {
                                leftAxisX + (chartWidth / (records.size - 1)) * index
                            } else {
                                leftAxisX + chartWidth / 2
                            }
                            val p = record.pressure
                            val normalized = if (p != null && p > 0f) (p - PRESSURE_CHART_MIN_HPA) / PRESSURE_CHART_RANGE_HPA else 0.5f
                            val ratio = normalized.coerceIn(0f, 1f)
                            val y = bottomAxisY - (chartHeight * ratio)
                            if (isFirst) {
                                moveTo(x, y)
                                isFirst = false
                            } else {
                                lineTo(x, y)
                            }
                        }
                    }
                    drawPath(
                        path = pressureAreaPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(pressureGradientStart, pressureGradientEnd),
                            startY = topAxisY,
                            endY = bottomAxisY
                        )
                    )
                    drawPath(
                        path = pressureLinePath,
                        color = pressureColor.copy(alpha = 0.35f),
                        style = Stroke(
                            width = 1.5.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
                
                // 绘制温度面积图
                if (records.isNotEmpty()) {
                    // 创建温度面积路径（闭合路径）
                    val tempAreaPath = Path().apply {
                        var isFirst = true
                        val points = mutableListOf<Offset>()
                        
                        records.forEachIndexed { index, record ->
                            // 优化X轴计算，避免变形
                            val x = if (records.size > 1) {
                                leftAxisX + (chartWidth / (records.size - 1)) * index
                            } else {
                                leftAxisX + chartWidth / 2
                            }
                            val tempRatio = (record.temperature - adjustedMinTemp) / tempRangeAdjusted
                            val y = bottomAxisY - (chartHeight * tempRatio)
                            val point = Offset(x, y)
                            points.add(point)
                            
                            if (isFirst) {
                                moveTo(x, bottomAxisY) // 从底部开始
                                lineTo(x, y) // 到数据点
                                isFirst = false
                            } else {
                                lineTo(x, y)
                            }
                        }
                        
                        // 闭合路径：从最后一个点回到底部，再回到起点
                        if (points.isNotEmpty()) {
                            val lastPoint = points.last()
                            lineTo(lastPoint.x, bottomAxisY) // 回到底部
                            close() // 闭合路径
                        }
                    }
                    
                    // 创建温度线条路径（仅线条，不闭合）
                    val tempLinePath = Path().apply {
                        var isFirst = true
                        records.forEachIndexed { index, record ->
                            // 优化X轴计算，避免变形
                            val x = if (records.size > 1) {
                                leftAxisX + (chartWidth / (records.size - 1)) * index
                            } else {
                                leftAxisX + chartWidth / 2
                            }
                            val tempRatio = (record.temperature - adjustedMinTemp) / tempRangeAdjusted
                            val y = bottomAxisY - (chartHeight * tempRatio)
                            
                            if (isFirst) {
                                moveTo(x, y)
                                isFirst = false
                            } else {
                                lineTo(x, y)
                            }
                        }
                    }
                    
                    // 绘制温度面积（带渐变效果）
                    val tempGradient = Brush.horizontalGradient(
                        colors = listOf(temperatureGradientStart, temperatureGradientEnd),
                        startX = leftAxisX,
                        endX = rightAxisX
                    )
                    drawPath(
                        path = tempAreaPath,
                        brush = tempGradient
                    )
                    
                    // 绘制温度线条（半透明）
                    drawPath(
                        path = tempLinePath,
                        color = temperatureColor.copy(alpha = 0.7f),
                        style = Stroke(
                            width = 1.5.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
                
                // 绘制湿度面积图
                if (records.isNotEmpty()) {
                    // 创建湿度面积路径（闭合路径）
                    val humAreaPath = Path().apply {
                        var isFirst = true
                        val points = mutableListOf<Offset>()
                        
                        records.forEachIndexed { index, record ->
                            // 优化X轴计算，避免变形
                            val x = if (records.size > 1) {
                                leftAxisX + (chartWidth / (records.size - 1)) * index
                            } else {
                                leftAxisX + chartWidth / 2
                            }
                            val humRatio = (record.humidity - adjustedMinHum) / humRangeAdjusted
                            val y = bottomAxisY - (chartHeight * humRatio)
                            val point = Offset(x, y)
                            points.add(point)
                            
                            if (isFirst) {
                                moveTo(x, bottomAxisY) // 从底部开始
                                lineTo(x, y) // 到数据点
                                isFirst = false
                            } else {
                                lineTo(x, y)
                            }
                        }
                        
                        // 闭合路径：从最后一个点回到底部，再回到起点
                        if (points.isNotEmpty()) {
                            val lastPoint = points.last()
                            lineTo(lastPoint.x, bottomAxisY) // 回到底部
                            close() // 闭合路径
                        }
                    }
                    
                    // 创建湿度线条路径（仅线条，不闭合）
                    val humLinePath = Path().apply {
                        var isFirst = true
                        records.forEachIndexed { index, record ->
                            // 优化X轴计算，避免变形
                            val x = if (records.size > 1) {
                                leftAxisX + (chartWidth / (records.size - 1)) * index
                            } else {
                                leftAxisX + chartWidth / 2
                            }
                            val humRatio = (record.humidity - adjustedMinHum) / humRangeAdjusted
                            val y = bottomAxisY - (chartHeight * humRatio)
                            
                            if (isFirst) {
                                moveTo(x, y)
                                isFirst = false
                            } else {
                                lineTo(x, y)
                            }
                        }
                    }
                    
                    // 绘制湿度面积（带渐变效果）
                    val humGradient = Brush.verticalGradient(
                        colors = listOf(humidityGradientStart, humidityGradientEnd),
                        startY = topAxisY,
                        endY = bottomAxisY
                    )
                    drawPath(
                        path = humAreaPath,
                        brush = humGradient
                    )
                    
                    // 绘制湿度线条（半透明）
                    drawPath(
                        path = humLinePath,
                        color = humidityColor.copy(alpha = 0.7f),
                        style = Stroke(
                            width = 1.5.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
                
                // 绘制选中竖线（crosshair）
                if (selectedIndex != null && selectedIndex!! < records.size) {
                    val selectedX = if (records.size > 1) {
                        leftAxisX + (chartWidth / (records.size - 1)) * selectedIndex!!
                    } else {
                        leftAxisX + chartWidth / 2
                    }
                    
                    // 绘制竖线（变细）
                    drawLine(
                        color = onSurfaceVariantColor.copy(alpha = 0.5f),
                        start = Offset(selectedX, topAxisY),
                        end = Offset(selectedX, bottomAxisY),
                        strokeWidth = 1.dp.toPx()
                    )
                    
                    // 获取选中数据点
                    val selectedRecord = records[selectedIndex!!]
                    
                    // 计算温度亮点的Y坐标
                    val tempRatio = (selectedRecord.temperature - adjustedMinTemp) / tempRangeAdjusted
                    val tempY = bottomAxisY - (chartHeight * tempRatio)
                    
                    // 计算湿度亮点的Y坐标
                    val humRatio = (selectedRecord.humidity - adjustedMinHum) / humRangeAdjusted
                    val humY = bottomAxisY - (chartHeight * humRatio)
                    
                    // 绘制温度亮点（红色）- 粒子发光效果
                    val tempGlowRadius = 8.dp.toPx()
                    val tempBlurRadius = 16.dp.toPx()
                    val tempCoreRadius = 4.dp.toPx()
                    
                    drawIntoCanvas { canvas ->
                        val tempGlowPaint = Paint().apply {
                            color = temperatureColor.toArgb()
                            isAntiAlias = true
                            maskFilter = BlurMaskFilter(tempBlurRadius, BlurMaskFilter.Blur.NORMAL)
                        }
                        canvas.nativeCanvas.drawCircle(selectedX, tempY, tempGlowRadius, tempGlowPaint)
                    }
                    // 内核（实心亮点）
                    drawCircle(
                        color = temperatureColor,
                        radius = tempCoreRadius,
                        center = Offset(selectedX, tempY)
                    )
                    
                    // 绘制湿度亮点（青色）- 粒子发光效果
                    val humGlowRadius = 8.dp.toPx()
                    val humBlurRadius = 16.dp.toPx()
                    val humCoreRadius = 4.dp.toPx()
                    
                    drawIntoCanvas { canvas ->
                        val humGlowPaint = Paint().apply {
                            color = humidityColor.toArgb()
                            isAntiAlias = true
                            maskFilter = BlurMaskFilter(humBlurRadius, BlurMaskFilter.Blur.NORMAL)
                        }
                        canvas.nativeCanvas.drawCircle(selectedX, humY, humGlowRadius, humGlowPaint)
                    }
                    // 内核（实心亮点）
                    drawCircle(
                        color = humidityColor,
                        radius = humCoreRadius,
                        center = Offset(selectedX, humY)
                    )
                    
                    // 气压亮点（仅当有有效气压时）
                    if (hasPressure) {
                        val p = selectedRecord.pressure
                        val pressNormalized = if (p != null && p > 0f) (p - PRESSURE_CHART_MIN_HPA) / PRESSURE_CHART_RANGE_HPA else 0.5f
                        val pressRatio = pressNormalized.coerceIn(0f, 1f)
                        val pressY = bottomAxisY - (chartHeight * pressRatio)
                        drawIntoCanvas { canvas ->
                            val pressGlowPaint = Paint().apply {
                                color = pressureColor.toArgb()
                                isAntiAlias = true
                                maskFilter = BlurMaskFilter(humBlurRadius, BlurMaskFilter.Blur.NORMAL)
                            }
                            canvas.nativeCanvas.drawCircle(selectedX, pressY, humGlowRadius, pressGlowPaint)
                        }
                        drawCircle(
                            color = pressureColor,
                            radius = humCoreRadius,
                            center = Offset(selectedX, pressY)
                        )
                    }
                }
                // 点击图标高亮对应区间（半透明竖条带）
                selectedEventRange?.let { ev ->
                    val t0 = records.first().timestamp
                    val t1 = records.last().timestamp
                    val timeRange = (t1 - t0).toFloat().coerceAtLeast(1f)
                    val endSec = ev.endSec ?: ev.timestampSec
                    var startX = leftAxisX + chartWidth * (ev.timestampSec - t0) / timeRange
                    var endX = leftAxisX + chartWidth * (endSec - t0) / timeRange
                    if (startX > endX) startX = endX.also { endX = startX }
                    val left = startX.coerceIn(leftAxisX, rightAxisX)
                    var w = (endX.coerceIn(leftAxisX, rightAxisX) - left).coerceAtLeast(0f)
                    if (w < 8.dp.toPx()) w = 8.dp.toPx()
                    drawRect(
                        color = rangeHighlightColor,
                        topLeft = Offset(left, topAxisY),
                        size = androidx.compose.ui.geometry.Size(w, chartHeight)
                    )
                }
            }
            }  // 结束 Canvas，使下方图标行在 Column 内渲染
            // 时间轴下方气象事件图标（气压陡降→雨，陡升→转晴，冷空气/锋面，起雾/结露）
            if (chartEvents.isNotEmpty() && records.size >= 2) {
                val t0 = records.first().timestamp
                val t1 = records.last().timestamp
                val timeRange = (t1 - t0).toFloat().coerceAtLeast(1f)
                val iconSize = 20.dp
                val halfIcon = 10.dp
                val widthDp = with(density) { maxWidthPx.toDp() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .align(Alignment.BottomStart),
                    contentAlignment = Alignment.CenterStart
                ) {
                    chartEvents.forEach { event ->
                        val midSec = (event.timestampSec + (event.endSec ?: event.timestampSec)) / 2
                        val ratio = ((midSec - t0) / timeRange).coerceIn(0f, 1f)
                        val xOffset = widthDp * ratio - halfIcon
                        val isSelected = selectedEventRange == event
                        Icon(
                            imageVector = when (event.type) {
                                ChartWeatherEventType.PRESSURE_DROP_RAIN -> Icons.Filled.WaterDrop
                                ChartWeatherEventType.PRESSURE_RISE_CLEARING -> Icons.Filled.WbSunny
                                ChartWeatherEventType.COLD_FRONT -> Icons.Filled.AcUnit
                                ChartWeatherEventType.FOG_DEW -> Icons.Filled.Cloud
                                ChartWeatherEventType.INDOOR_WARM_DRY -> Icons.Filled.Home
                            },
                            contentDescription = when (event.type) {
                                ChartWeatherEventType.PRESSURE_DROP_RAIN -> "降水可能"
                                ChartWeatherEventType.PRESSURE_RISE_CLEARING -> "转晴"
                                ChartWeatherEventType.COLD_FRONT -> "冷空气/锋面"
                                ChartWeatherEventType.FOG_DEW -> "起雾/结露"
                                ChartWeatherEventType.INDOOR_WARM_DRY -> "进入室内/干暖"
                            },
                            modifier = Modifier
                                .size(iconSize)
                                .align(Alignment.CenterStart)
                                .offset(x = xOffset)
                                .clickable(
                                    onClick = {
                                        selectedEventRange = if (selectedEventRange == event) null else event
                                    }
                                )
                                .then(
                                    if (isSelected) Modifier.background(
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                        RoundedCornerShape(4.dp)
                                    ) else Modifier
                                ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = if (isSelected) 1f else 0.85f
                            )
                        )
                    }
                }
            }
            // 红线（竖线）上方 tooltip：拖到气象事件附近 或 点击图标选中时 显示含义
            val activeEvent = if (isUserDragging) tooltipEvent else selectedEventRange
            
            if (activeEvent != null && records.isNotEmpty()) {
                val chartWidthPx = maxWidthPx - paddingLeftPx - paddingRightPx
                
                // 计算提示框水平位置 X 坐标
                val targetXPx = if (isUserDragging && selectedIndex != null && selectedIndex!! < records.size) {
                    if (records.size > 1) {
                        paddingLeftPx + (chartWidthPx / (records.size - 1)) * selectedIndex!!
                    } else paddingLeftPx + chartWidthPx / 2
                } else {
                    // 点击模式：定位到事件中心
                    val t0 = records.first().timestamp
                    val t1 = records.last().timestamp
                    val timeRange = (t1 - t0).toFloat().coerceAtLeast(1f)
                    val midSec = (activeEvent.timestampSec + (activeEvent.endSec ?: activeEvent.timestampSec)) / 2
                    val ratio = ((midSec - t0) / timeRange).coerceIn(0f, 1f)
                    paddingLeftPx + chartWidthPx * ratio
                }
                
                val selectedXDp = with(density) { targetXPx.toDp() }
                val maxWidthDp = with(density) { maxWidthPx.toDp() }
                val tooltipHalfWidth = 44.dp // 估算宽度一半，用于居中
                val edgePadding = 8.dp
                // 限制在图表范围内，不溢出
                val tooltipLeft = (selectedXDp - tooltipHalfWidth).coerceIn(edgePadding, maxWidthDp - tooltipHalfWidth * 2 - edgePadding)
                
                val typeLabel = when (activeEvent.type) {
                    ChartWeatherEventType.PRESSURE_DROP_RAIN -> "降水/雷雨可能"
                    ChartWeatherEventType.PRESSURE_RISE_CLEARING -> "转晴"
                    ChartWeatherEventType.COLD_FRONT -> "冷空气/锋面"
                    ChartWeatherEventType.FOG_DEW -> "起雾/结露"
                    ChartWeatherEventType.INDOOR_WARM_DRY -> "进入室内/干暖环境"
                }
                val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                val tooltipText = if (activeEvent.endSec != null && activeEvent.endSec != activeEvent.timestampSec) {
                    "${timeFmt.format(Date(activeEvent.timestampSec * 1000))}–${timeFmt.format(Date(activeEvent.endSec!! * 1000))} $typeLabel"
                } else typeLabel
                
                Box(modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = tooltipLeft, y = 8.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                    shadowElevation = 4.dp
                ) {
                    Text(
                        text = tooltipText,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                }
            }
        }
    }
    }
}
