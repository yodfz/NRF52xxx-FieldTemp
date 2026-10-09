package com.example.pandatemperature.ui.components

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pandatemperature.data.bluetooth.BleManager
import com.example.pandatemperature.data.model.TemperatureRecord
import com.example.pandatemperature.ui.theme.HumCyan
import com.example.pandatemperature.ui.theme.PrimaryBlue
import com.example.pandatemperature.ui.theme.HumidityColor
import com.example.pandatemperature.ui.theme.RowStripe
import com.example.pandatemperature.ui.theme.TempRed
import com.example.pandatemperature.ui.theme.TemperatureColor
import java.text.SimpleDateFormat
import java.util.*
import com.example.pandatemperature.utils.calculateAltitude
import com.example.pandatemperature.utils.getWeatherDescription
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 历史预览卡片组件 - 重新设计版
 * 参考设计：UI/panda-pro-x1-dashboard/components/HistoryList.tsx
 */
@Composable
fun HistoryCard(
    connectionState: BleManager.ConnectionState,
    historyRecords: List<TemperatureRecord>,
    onClearHistory: () -> Unit,
    onShowMore: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // 只显示最近5条记录
    val latestRecords = historyRecords.take(5)
    val isConnected = connectionState != BleManager.ConnectionState.Disconnected
    
    Box(modifier = modifier.fillMaxWidth()) {
        // 内容区域
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    // 未连接时，对内容应用模糊效果（Android 12+）
                    if (!isConnected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Modifier.graphicsLayer {
                            renderEffect = RenderEffect
                                .createBlurEffect(15f, 15f, Shader.TileMode.CLAMP)
                                .asComposeRenderEffect()
                        }
                    } else {
                        Modifier
                    }
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        // 标题和"全部 >"按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "历史预览",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                letterSpacing = 1.sp
            )
            
            if (onShowMore != null) {
                Row(
                    modifier = Modifier.clickable(onClick = onShowMore),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "全部",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "查看更多",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        
        // 历史记录列表
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (latestRecords.isEmpty()) {
                // 无数据时显示占位符
                HistoryPreviewItemPlaceholder(isLast = true)
            } else {
                latestRecords.forEachIndexed { index, record ->
                    val itemModifier = if (onShowMore != null) {
                        Modifier.clickable(onClick = onShowMore)
                    } else Modifier
                    
                    // 交错进入动画
                    val visible = remember { mutableStateOf(false) }
                    LaunchedEffect(record) {
                        kotlinx.coroutines.delay((index * 50).toLong())
                        visible.value = true
                    }
                    
                    val offsetY by animateDpAsState(
                        targetValue = if (visible.value) 0.dp else 20.dp,
                        animationSpec = tween(300, easing = FastOutSlowInEasing),
                        label = "historyItemOffset"
                    )
                    val alpha by animateFloatAsState(
                        targetValue = if (visible.value) 1f else 0f,
                        animationSpec = tween(300, easing = FastOutSlowInEasing),
                        label = "historyItemAlpha"
                    )
                    
                    HistoryPreviewItem(
                        record = record,
                        isLast = index == latestRecords.size - 1,
                        modifier = itemModifier.graphicsLayer {
                            translationY = offsetY.toPx()
                            this.alpha = alpha
                        }
                    )
                }
            }
        }
        }
    }
}

/**
 * 历史预览项占位符（无数据时显示）
 */
@Composable
private fun HistoryPreviewItemPlaceholder(
    isLast: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = if (isLast) 0.dp else 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.02f),
                shape = MaterialTheme.shapes.medium
            ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.04f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：日期时间占位符
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // 日期和时间在一行
                Text(
                    text = "--年--月--日 --:--:--",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
            }
            
            // 右侧：温湿度占位符
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // 温度
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "温度",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontSize = 9.sp,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "--°C",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontSize = 12.sp
                    )
                }
                // 湿度
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "湿度",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontSize = 9.sp,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "--%",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/**
 * 历史预览项
 */
@Composable
private fun HistoryPreviewItem(
    record: TemperatureRecord,
    isLast: Boolean,
    modifier: Modifier = Modifier
) {
    // 日期时间合并格式（modifier 可由外部传入 clickable 以支持点击跳转详情页）
    val dateTimeFormat = SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.getDefault())
    
    val dateTimeStr = if (record.timestamp > 0) {
        dateTimeFormat.format(Date(record.timestamp * 1000))
    } else {
        "未同步时间"
    }
    
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = if (isLast) 0.dp else 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.02f),
                shape = MaterialTheme.shapes.medium
            ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.04f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：日期时间、气压和GPS
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // 日期和时间在一行
                Text(
                    text = dateTimeStr,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 11.sp
                )
                // 气压显示（12px字体）⭐ v1.1 新增
                Text(
                    text = record.pressure?.let { "${String.format("%.1f", it)} hPa" } ?: "-- hPa",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
                // GPS坐标 + 海拔显示（如果有）⭐ 海拔在坐标后
                if (record.latitude != null && record.longitude != null) {
                    val altFromPressure = record.pressure?.let { calculateAltitude(it, record.temperature) }
                    val gpsText = "📍 ${String.format("%.4f", record.latitude)}, ${String.format("%.4f", record.longitude)}"
                    val text = if (altFromPressure != null) "${gpsText} · ${altFromPressure}m" else gpsText
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        fontSize = 10.sp
                    )
                }
            }
            
            // 右侧：温湿度
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // 温度
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "温度",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "${String.format("%.1f", record.temperature)}°C",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = TemperatureColor,
                        fontSize = 12.sp
                    )
                }
                // 湿度
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "湿度",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "${record.humidity.toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = HumidityColor,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/**
 * 表头行
 */
@Composable
fun TableHeaderRow(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "时间",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "温湿度",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 表格数据行（带斑马纹效果）
 */
@Composable
fun HistoryTableRow(
    record: TemperatureRecord,
    index: Int,
    modifier: Modifier = Modifier
) {
    // 根据索引判断单双行，应用不同背景色
    val backgroundColor = if (index % 2 == 0) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    }
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 时间列（左对齐）
        Text(
            text = if (record.timestamp > 0) {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date(record.timestamp * 1000))
            } else {
                "未同步时间"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        
        // 温湿度列（右对齐，上下换行）
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "${String.format("%.1f", record.temperature)}°C",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = TemperatureColor
            )
            Text(
                text = "${String.format("%.1f", record.humidity)}%",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = HumidityColor
            )
            Text(
                text = record.batteryVoltage?.let { "${String.format("%.1f", it)} V" } ?: "-- V",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


/**
 * 历史数据项（用于 HistoryScreen 等页面）
 * 按照 HTML 设计实现
 */
@Composable
fun HistoryItem(
    record: TemperatureRecord,
    index: Int = 0,
    deviceName: String? = null,
    isConnected: Boolean = false,
    modifier: Modifier = Modifier
) {
    // 时间格式化
    val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val timeStr = if (record.timestamp > 0) {
        timeFormat.format(Date(record.timestamp * 1000))
    } else {
        "未同步时间"
    }
    
    // 气压值（如果有）
    val pressureVal = record.pressure ?: 0f
    val hasPressure = pressureVal > 0
    
    // 计算海拔和天气
    val altitude = if (hasPressure) calculateAltitude(pressureVal, record.temperature) else 0
    val weatherDesc = if (hasPressure) getWeatherDescription(pressureVal) else ""
    
    // 斑马纹背景：HTML中偶数行使用正常背景，奇数行使用斑马纹背景
    // HTML: 偶数行 bg-background-light dark:bg-background-dark
    // HTML: 奇数行 bg-slate-100/50 dark:bg-row-stripe
    val backgroundColor = if (index % 2 == 0) {
        MaterialTheme.colorScheme.surface // HTML: bg-background-light dark:bg-background-dark
    } else {
        RowStripe.copy(alpha = 1f) // HTML: dark:bg-row-stripe (不需要alpha，直接使用)
    }
    
    // 获取边框颜色（需要在 Composable 上下文中获取）
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
    
    // HTML设计：只有底部边框（border-b），没有其他边框
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .heightIn(min = 72.dp) // HTML: min-h-[72px]
            .drawBehind {
                // 底部边框：HTML border-b border-slate-100 dark:border-slate-800/50
                // 注意：drawBehind 必须在 padding 之前，这样分割线才会贴在整个行的底部
                drawLine(
                    color = borderColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 0.5.dp.toPx()
                )
            }
            .padding(horizontal = 16.dp, vertical = 12.dp), // HTML: px-4 py-3
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：时间戳 + 气压/海拔/天气信息
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 时间戳（mono字体，13px）- HTML: text-slate-600 dark:text-white
            Text(
                text = timeStr,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                ),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface // HTML: text-slate-600 dark:text-white
            )
            
            // 气压、海拔、天气信息和GPS
            if (hasPressure) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // 气压值
                    Text(
                        text = "气压: ${String.format("%.1f", pressureVal)} hPa",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = Color(0xFF9cabba) // HTML: text-slate-500 dark:text-[#9cabba]
                    )
                    
                    // 海拔和天气描述
                    Text(
                        text = "海拔: ${altitude}m | $weatherDesc",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = Color(0xFF9cabba).copy(alpha = 0.8f)
                    )
                    
                    // GPS坐标 + 海拔显示（如果有）⭐ 海拔在坐标后
                    if (record.latitude != null && record.longitude != null) {
                        val gpsText = "📍 ${String.format("%.4f", record.latitude)}, ${String.format("%.4f", record.longitude)}"
                        val text = "${gpsText} · 海拔 ${altitude}m"
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = PrimaryBlue.copy(alpha = 0.8f)
                        )
                    }
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "无气压数据",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = Color(0xFF9cabba).copy(alpha = 0.6f)
                    )
                    
                    // GPS坐标显示（如果有，无气压时不显示海拔）
                    if (record.latitude != null && record.longitude != null) {
                        Text(
                            text = "📍 ${String.format("%.4f", record.latitude)}, ${String.format("%.4f", record.longitude)}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = PrimaryBlue.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
        
        // 右侧：温度和湿度 + 箭头图标
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 温度和湿度
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // 温度（红色，18px，粗体）
                Text(
                    text = "${String.format("%.1f", record.temperature)}°C",
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                    fontWeight = FontWeight.Bold,
                    color = TempRed
                )
                
                // 湿度（青色，11px，粗体）
                Text(
                    text = "${record.humidity.toInt()}% RH",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    fontWeight = FontWeight.Bold,
                    color = HumCyan
                )
                Text(
                    text = record.batteryVoltage?.let { "${String.format("%.1f", it)} V" } ?: "-- V",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // 箭头图标 - HTML: text-slate-300 dark:text-slate-700
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Color(0xFF9E9E9E).copy(alpha = 0.6f) // HTML: text-slate-300 dark:text-slate-700
            )
        }
    }
}
