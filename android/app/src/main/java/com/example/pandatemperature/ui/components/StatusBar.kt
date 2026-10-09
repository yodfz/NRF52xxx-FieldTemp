package com.example.pandatemperature.ui.components

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
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
import com.example.pandatemperature.data.model.TaskStatus
import com.example.pandatemperature.ui.theme.SuccessColor
import com.example.pandatemperature.ui.theme.ErrorColor

import androidx.compose.material.icons.filled.Settings
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.scale

/**
 * 顶部状态栏组件 - 可点击连接/断开设备
 * 参考设计：UI/panda-pro-x1-dashboard/components/Header.tsx
 */
@Composable
fun StatusBar(
    connectionState: BleManager.ConnectionState,
    deviceName: String?,
    taskStatus: TaskStatus? = null,
    batteryVoltage: Float? = null,
    batteryPercent: Float? = null,
    /**
     * 固件版本显示文案（如 `v6` / `v1.2`）或 null。
     * 口径与配置弹窗、配置卡片一致（见 DeviceStatus.firmwareVersionLabel）：
     * 新固件版本号是 patch 号，旧固件是主/次版本编码，由能力字节区分。
     */
    firmwareVersionLabel: String? = null,
    onClick: () -> Unit,
    onConfigClick: () -> Unit = {}, // 新增配置点击回调
    modifier: Modifier = Modifier
) {
    val isConnected = connectionState != BleManager.ConnectionState.Disconnected
    
    // 连接状态变化动画
    val scale by animateFloatAsState(
        targetValue = if (isConnected) 1f else 0.98f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "statusBarScale"
    )
    
    // 蓝牙图标脉冲动画
    val infiniteTransition = rememberInfiniteTransition(label = "bluetooth")
    val bluetoothPulse by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bluetoothPulse"
    )
    val statusText = if (isConnected) {
        taskStatus?.getDisplayText() ?: "工作中・数据实时接收"
    } else {
        "待连接..."
    }
    val displayDeviceName = if (isConnected) {
        deviceName ?: "Panda-Pro-X1"
    } else {
        "未连接设备"
    }
    
    // 深色背景，半透明效果
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.05f),
                shape = MaterialTheme.shapes.medium
            )
            .clip(MaterialTheme.shapes.medium),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // 内容区域
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
            // 左侧：蓝牙图标 + 设备名称 + 状态文本
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 蓝牙图标 - 连接时添加脉冲动画
                    Icon(
                        imageVector = Icons.Filled.Bluetooth,
                        contentDescription = "蓝牙",
                        modifier = Modifier
                            .size(18.dp)
                            .scale(if (isConnected) bluetoothPulse else 1f),
                        tint = if (isConnected) Color(0xFF00FBFF) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = displayDeviceName,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp
                        )
                        
                        // 显示固件版本号（口径同配置弹窗/配置卡片；无版本号时不占位）
                        val versionTag = firmwareVersionLabel?.takeIf { it.isNotBlank() && it != "--" }
                        if (isConnected && versionTag != null) {
                            Text(
                                text = "($versionTag)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }

                        // 电压预留位：连接后常驻显示，避免有无电压数据时布局跳动。
                        // 固件不上报电压字段时显示占位符（实测本机型实时帧仅 6 字节，无电压）。
                        if (isConnected) {
                            Text(
                                text = batteryVoltage?.let { String.format("%.1fV", it) } ?: "-- V",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isConnected) SuccessColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 24.dp) // 对齐到设备名称下方
                )
            }
            
            // 右侧：设置按钮
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 设置按钮（仅完全连接且服务发现完成时显示）
                // 使用 IconButton 代替 Icon + clickable，以正确处理点击事件拦截，避免被外层 Surface 的 clickable 拦截
                // 只有 ServicesDiscovered 状态才显示设置按钮（Connected 状态只是短暂的中间状态，随后会变为 ServicesDiscovered）
                if (connectionState == BleManager.ConnectionState.ServicesDiscovered) {
                    IconButton(
                        onClick = onConfigClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "设置",
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            }
        }
    }
}
