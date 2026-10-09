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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.pandatemperature.data.bluetooth.BleManager
import com.example.pandatemperature.data.model.DeviceStatus

/**
 * 设备状态卡片组件
 */
@Composable
fun StatusCard(
    connectionState: BleManager.ConnectionState,
    deviceStatus: DeviceStatus?,
    isMaxMinTempSupported: Boolean = true,
    onResetMaxMinTemperature: () -> Unit = {},
    modifier: Modifier = Modifier
) {
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
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "📈 设备状态",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            
            Divider()
            
            // 状态显示
            // 固件 v3 起 interval 表示"历史记录间隔"，采样固定 1 秒（用能力标志区分旧固件语义）
            val intervalLabel = if (deviceStatus?.supportsHistoryInterval == true) "历史记录间隔" else "采集间隔"
            StatusItem(intervalLabel, deviceStatus?.interval?.let { "${it}秒" } ?: "--秒")
            if (deviceStatus?.supportsHistoryInterval == true) {
                StatusItem("实时采样间隔", deviceStatus.sampleInterval?.let { "${it}秒" } ?: "1秒")
                StatusItem(
                    "预计保留时长",
                    deviceStatus.retentionDays?.let { "约 ${it} 天" } ?: "--"
                )
            }
            StatusItem("存储记录数", deviceStatus?.recordCount?.toString() ?: "--")
            StatusItem("设备连接", if (deviceStatus?.isConnected == true) "是" else "否")
            StatusItem("时间同步", if (deviceStatus?.isTimeSynced == true) "是" else "否")
            
            Divider()
            
            // 重置最高最低温度按钮
            val isConnected = connectionState != BleManager.ConnectionState.Disconnected
            Button(
                onClick = onResetMaxMinTemperature,
                enabled = isConnected && isMaxMinTempSupported,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("重置最高最低温湿度")
            }
        }
    }
}

@Composable
private fun StatusItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    // 2025年设计：无阴影，使用边框和渐变
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                shape = MaterialTheme.shapes.medium
            )
            .clip(MaterialTheme.shapes.medium),
        shape = MaterialTheme.shapes.medium,
        color = androidx.compose.ui.graphics.Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    )
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
