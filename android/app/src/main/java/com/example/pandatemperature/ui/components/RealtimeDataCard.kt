package com.example.pandatemperature.ui.components

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pandatemperature.data.bluetooth.BleManager
import com.example.pandatemperature.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*
import com.example.pandatemperature.data.weather.AlertLevel
import com.example.pandatemperature.data.weather.WapsResult
import com.example.pandatemperature.data.weather.WapsState
import com.example.pandatemperature.utils.calculateAltitudeHybrid
import com.example.pandatemperature.utils.getWeatherDescription
import androidx.compose.animation.core.*
import androidx.compose.runtime.*

/**
 * 实时数据卡片组件 - 重新设计版
 * 参考设计：UI/panda-pro-x1-dashboard/components/DashboardCard.tsx
 */
@Composable
fun RealtimeDataCard(
    connectionState: BleManager.ConnectionState,
    temperature: Float?,
    humidity: Float?,
    pressure: Float? = null,  // ⭐ v1.1 新增：气压（hPa）
    maxTemperature: Float? = null,
    minTemperature: Float? = null,
    maxTemperatureTime: String? = null,  // 最高温度时间（如"14:30"）
    minTemperatureTime: String? = null,  // 最低温度时间（如"05:15"）
    isMaxMinTempSupported: Boolean = false,  // 设备是否支持最高最低温度
    maxMinTempNeedManualSync: Boolean = false,  // 读取失败且重试仍失败，提示用户点击手动同步
    lastUpdateTime: Long?,
    batteryVoltage: Float? = null,  // ⭐ v1.1 新增：电池电压
    batteryPercent: Float? = null,  // ⭐ v1.1 新增：电池电量百分比
    isLoadingRealtimeData: Boolean = false,  // ⭐ v1.1 更新：统一加载状态
    onReadRealtimeData: () -> Unit,  // ⭐ v1.1 更新：统一读取方法
    onRefreshMaxMinTemp: () -> Unit = {},  // 点击最高最低温度区域刷新
    onDeviceSelect: () -> Unit = {},  // 新增：连接设备回调
    wapsResult: WapsResult? = null,  // WAPS 天气预警结果（静止/移动、ΔP、判定）
    gpsAltitudeMeters: Double? = null,  // GPS 海拔（有则与气压混合计算海拔，无则仅气压）
    modifier: Modifier = Modifier
) {
    // 海拔/天气：仅在有有效气压时计算（无气压或气压为 0 视为气压计故障，不参与计算）
    val hasValidPressure = pressure != null && pressure > 0f
    val altitude = if (hasValidPressure) calculateAltitudeHybrid(pressure!!, temperature, gpsAltitudeMeters).toInt() else 0
    val weatherDesc = if (hasValidPressure) getWeatherDescription(pressure!!) else ""
    
    // 数值动画
    val animatedTemperature by animateFloatAsState(
        targetValue = temperature ?: 0f,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "temperature"
    )
    val animatedHumidity by animateFloatAsState(
        targetValue = humidity ?: 0f,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "humidity"
    )
    val animatedPressure by animateFloatAsState(
        targetValue = pressure ?: 0f,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "pressure"
    )
    
    // 深色背景卡片
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = connectionState == BleManager.ConnectionState.Disconnected) { onDeviceSelect() } // 点击卡片也可以触发连接
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.04f),
                shape = MaterialTheme.shapes.medium
            ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.04f)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // 内容区域（温度、湿度数据）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
            // 左侧：温度
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 图标 + 标题
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    // 温度图标
                    Icon(
                        imageVector = Icons.Filled.Thermostat,
                        contentDescription = "温度",
                        modifier = Modifier.size(12.dp),
                        tint = TemperatureColor
                    )
                    Text(
                        text = "当前温度",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        letterSpacing = 0.5.sp
                    )
                }
                
                // 大号数值
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    if (isLoadingRealtimeData) {
                        Box(
                            modifier = Modifier.height(48.dp).width(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = TemperatureColor
                            )
                        }
                    } else {
                        Text(
                            text = if (temperature != null) String.format("%.1f", animatedTemperature) else "--",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = TemperatureColor,
                            fontSize = 36.sp,
                            letterSpacing = (-0.5).sp
                        )
                        Text(
                            text = "°C",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TemperatureColor.copy(alpha = 0.8f),
                            fontSize = 18.sp,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }
                
                // 最高/最低值及时间（仅当设备支持时显示；无数据且不需手动同步时不显示文字）
                if (isMaxMinTempSupported) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable(
                                enabled = connectionState == BleManager.ConnectionState.ServicesDiscovered
                            ) { onRefreshMaxMinTemp() }
                            .padding(4.dp)
                    ) {
                        val hasData = maxTemperature != null || minTemperature != null
                        if (hasData) {
                            // 最高
                            Column(
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "最高:",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = TemperatureColor,
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        text = "${maxTemperature?.let { String.format("%.1f", it) } ?: "--"}°C",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.sp
                                    )
                                }
                                Text(
                                    text = maxTemperatureTime ?: "--年--月--日 --:--:--",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    fontSize = 9.sp
                                )
                            }
                            // 最低
                            Column(
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "最低:",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        text = "${minTemperature?.let { String.format("%.1f", it) } ?: "--"}°C",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.sp
                                    )
                                }
                                Text(
                                    text = minTemperatureTime ?: "--年--月--日 --:--:--",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    fontSize = 9.sp
                                )
                            }
                        } else if (maxMinTempNeedManualSync) {
                            Text(
                                text = "点击尝试手动同步",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 10.sp
                            )
                        } else {
                            // 无数据但设备支持：显示占位，仍可点击刷新
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "最高: --°C",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    fontSize = 10.sp
                                )
                                Text(
                                    text = "最低: --°C",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    fontSize = 10.sp
                                )
                                Text(
                                    text = "点击刷新",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }
                }
            }
            
            // 右侧：湿度
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 图标 + 标题
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    // 湿度图标
                    Icon(
                        imageVector = Icons.Filled.WaterDrop,
                        contentDescription = "湿度",
                        modifier = Modifier.size(14.dp),
                        tint = HumidityColor
                    )
                    Text(
                        text = "当前湿度",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        letterSpacing = 0.5.sp
                    )
                }
                
                // 大号数值
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    if (isLoadingRealtimeData) {
                        Box(
                            modifier = Modifier.height(48.dp).width(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = HumidityColor
                            )
                        }
                    } else {
                        Text(
                            text = if (humidity != null) animatedHumidity.toInt().toString() else "--",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = HumidityColor,
                            fontSize = 36.sp,
                            letterSpacing = (-0.5).sp
                        )
                        Text(
                            text = "%",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = HumidityColor.copy(alpha = 0.8f),
                            fontSize = 18.sp,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }
                
                // 气压显示（替换最高最低湿度）⭐ v1.1 更新
                Spacer(modifier = Modifier.height(12.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(4.dp)  // 与左侧最高最低区域对齐
                ) {
                    // 气压值
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "气压:",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                        Text(
                            text = if (pressure != null && pressure > 0f) String.format("%.1f", animatedPressure) else "--",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                        Text(
                            text = "hPa",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 10.sp
                        )
                    }
                    
                    // 海拔和天气描述（仅有效气压时显示，气压为 0 视为故障不参与计算）
                    if (hasValidPressure) {
                        Text(
                            text = "海拔: ${altitude}m | $weatherDesc",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            fontSize = 10.sp
                        )
                    }
                    // WAPS 天气预警状态（EHR 动态斜率 + 静止 10m 进入 / 20m 退出）
                    if (wapsResult != null) {
                        val stateStr = if (wapsResult.state == WapsState.Stationary) "静止" else "移动"
                        val ehrStr = wapsResult.ehrHpaPerHour?.let { "EHR: ${String.format("%.1f", it)} hPa/h" }
                            ?: "收集中"
                        val durationStr = wapsResult.stationaryDurationMin?.let { "静止 ${it.toInt()} 分钟" } ?: ""
                        val line2 = listOfNotNull("状态: $stateStr", ehrStr, durationStr).filter { it.isNotEmpty() }.joinToString(" | ") + " | ${wapsResult.message}"
                        val alertColor = when (wapsResult.alert) {
                            AlertLevel.StormWarning, AlertLevel.FlashStorm -> Color(0xFFD32F2F)
                            AlertLevel.Worsening -> Color(0xFFFF9800)
                            AlertLevel.SystemicRain -> Color(0xFF1976D2)
                            AlertLevel.Clearing -> Color(0xFF388E3C)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        }
                        Text(
                            text = line2,
                            style = MaterialTheme.typography.bodySmall,
                            color = alertColor,
                            fontSize = 10.sp
                        )
                    }
                }
            }
            }
        }
    }
}

