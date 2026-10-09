package com.example.pandatemperature.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pandatemperature.data.bluetooth.BleConstants
import com.example.pandatemperature.data.bluetooth.BleManager
import com.example.pandatemperature.data.model.DeviceStatus
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.scale

/**
 * 设备信息卡片组件（展示设备配置和状态）
 * v1.2 更新：增加更多设备信息展示，界面紧凑化
 */
@Composable
fun ConfigCard(
    connectionState: BleManager.ConnectionState,
    currentInterval: Int?,
    deviceStatus: DeviceStatus? = null,
    historyTotalRecords: Long? = null,
    onEditClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnected = connectionState != BleManager.ConnectionState.Disconnected
    
    // 2025年设计：无阴影，使用边框
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                shape = MaterialTheme.shapes.large
            ),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⚙️ 设备信息",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                // 编辑按钮 - 紧凑化 + 动画
                var buttonPressed by remember { mutableStateOf(false) }
                val buttonScale by animateFloatAsState(
                    targetValue = if (buttonPressed) 0.95f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessHigh
                    ),
                    label = "configButtonScale"
                )
                
                Button(
                    onClick = onEditClick,
                    enabled = isConnected,
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    modifier = Modifier
                        .height(32.dp)
                        .scale(buttonScale)
                ) {
                    Text(
                        "配置",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }
            
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            
            // 信息列表
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 历史记录间隔（固件 v3 起采样固定 1 秒，此处为落盘周期）
                InfoRow(
                    label = if (deviceStatus?.supportsHistoryInterval == true)
                        "历史记录间隔" else "采集间隔",
                    value = currentInterval?.let { "${it}秒" } ?: "--",
                    highlight = true
                )
                
                // 预计保留时长（固件 v3 起上报；旧固件按上限估算）
                val retentionDays = deviceStatus?.retentionDays
                    ?: currentInterval?.let { BleConstants.estimateRetentionDays(it) }
                retentionDays?.let {
                    InfoRow(
                        label = "预计保留",
                        value = "约 ${it} 天"
                    )
                }
                
                // 固件版本（新固件版本号语义为 patch 号，用能力字节区分，见 DeviceStatus）
                deviceStatus?.let { status ->
                    if (status.firmwareVersion > 0) {
                        InfoRow(
                            label = "固件版本",
                            value = status.firmwareVersionLabel
                        )
                    }
                }
                
                // 存储记录数
                val recordCount = historyTotalRecords ?: deviceStatus?.recordCount?.toLong()
                InfoRow(
                    label = "存储记录",
                    value = recordCount?.let { "${it}条" } ?: "--"
                )
                
                // 时间同步状态
                deviceStatus?.let { status ->
                    InfoRow(
                        label = "时间同步",
                        value = if (status.isTimeSynced) "✓ 已同步" else "✗ 未同步",
                        valueColor = if (status.isTimeSynced) {
                            Color(0xFF4CAF50)  // 绿色
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
                
                // 清空数据进行中提示
                if (deviceStatus?.isDataClearInProgress == true) {
                    InfoRow(
                        label = "状态",
                        value = "正在清空数据...",
                        valueColor = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

/**
 * 信息行组件
 */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    highlight: Boolean = false,
    valueColor: Color? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (highlight) {
                    Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
                                )
                            )
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                } else {
                    Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                }
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = if (highlight) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Medium,
            color = valueColor ?: if (highlight) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
}
