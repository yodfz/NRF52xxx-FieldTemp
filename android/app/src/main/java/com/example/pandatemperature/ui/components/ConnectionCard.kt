package com.example.pandatemperature.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.pandatemperature.data.bluetooth.BleManager
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.scale

/**
 * 连接控制卡片组件
 */
@Composable
fun ConnectionCard(
    connectionState: BleManager.ConnectionState,
    deviceName: String?,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "🔗 设备连接",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            
            Divider()
            
            // 提示信息
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "提示：点击\"连接设备\"后会弹出系统蓝牙设备选择对话框，系统会优先显示 PandaTemperature 设备。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
            
            // 按钮组 - 手机端优化，按钮更大
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 连接按钮动画状态
                var connectPressed by remember { mutableStateOf(false) }
                val connectScale by animateFloatAsState(
                    targetValue = if (connectPressed) 0.95f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessHigh
                    ),
                    label = "connectScale"
                )
                
                Button(
                    onClick = onConnectClick,
                    enabled = connectionState == BleManager.ConnectionState.Disconnected,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .scale(connectScale)
                ) {
                    Text("连接设备")
                }
                
                // 断开按钮动画状态
                var disconnectPressed by remember { mutableStateOf(false) }
                val disconnectScale by animateFloatAsState(
                    targetValue = if (disconnectPressed) 0.95f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessHigh
                    ),
                    label = "disconnectScale"
                )
                
                Button(
                    onClick = onDisconnectClick,
                    enabled = connectionState != BleManager.ConnectionState.Disconnected,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .scale(disconnectScale)
                ) {
                    Text("断开连接")
                }
            }
            
            // 当前连接设备信息
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "当前连接设备",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = deviceName ?: "未连接",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
