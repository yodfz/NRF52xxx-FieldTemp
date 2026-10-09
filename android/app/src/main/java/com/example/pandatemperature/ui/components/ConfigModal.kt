package com.example.pandatemperature.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pandatemperature.data.bluetooth.BleConstants
import com.example.pandatemperature.data.model.DeviceStatus

/**
 * 设备配置编辑 Modal
 * v1.2 更新：增加设备信息展示、清空数据、重置温度范围功能
 */
@Composable
fun ConfigModal(
    currentInterval: Int?,
    deviceStatus: DeviceStatus? = null,
    historyTotalRecords: Long? = null,
    /** 打开弹窗时的电压快照，不随弹窗停留期间的实时数据变化。 */
    batteryVoltage: Float? = null,
    isClearDataAvailable: Boolean = false,
    isClearingData: Boolean = false,
    currentNickname: String? = null,
    onSave: (Int) -> Unit,
    onSaveNickname: (String?) -> Unit = {},
    onClearData: () -> Unit = {},
    onResetMaxMinTemp: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var intervalInput by remember { mutableStateOf(currentInterval?.toString() ?: "60") }
    var nicknameInput by remember { mutableStateOf(currentNickname ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // 清空数据确认对话框状态
    var showClearDataConfirm by remember { mutableStateOf(false) }
    // 重置温度确认对话框状态
    var showResetTempConfirm by remember { mutableStateOf(false) }
    
    // 当 currentInterval 变化时更新输入框
    LaunchedEffect(currentInterval) {
        currentInterval?.let { intervalInput = it.toString() }
    }
    LaunchedEffect(currentNickname) {
        nicknameInput = currentNickname ?: ""
    }
    
    // 验证输入值
    fun validateAndSave() {
        val interval = intervalInput.toIntOrNull()
        when {
            interval == null -> {
                errorMessage = "请输入有效的数字"
            }
            interval < BleConstants.HISTORY_INTERVAL_MIN -> {
                // 下限 60 秒是固件的容量红线：65000×60s ≈ 45 天，低于它无法保证 30 天保留
                errorMessage = "历史记录间隔不能小于${BleConstants.HISTORY_INTERVAL_MIN}秒（否则无法保证30天保留）"
            }
            interval > BleConstants.HISTORY_INTERVAL_MAX -> {
                errorMessage = "历史记录间隔不能大于${BleConstants.HISTORY_INTERVAL_MAX}秒"
            }
            else -> {
                errorMessage = null
                onSave(interval)
                val nickname = nicknameInput.trim().takeIf { it.isNotBlank() }
                onSaveNickname(nickname)
                onDismiss()
            }
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "设备配置",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // === 设备信息区域 ===
                Text(
                    text = "设备信息",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // 固件版本
                        InfoItem(
                            label = "固件版本",
                            value = deviceStatus?.firmwareVersionLabel ?: "--"
                        )
                        // 电池电压（打开弹窗时快照）
                        InfoItem(
                            label = "电池电压",
                            value = batteryVoltage?.let { String.format("%.1fV", it) } ?: "--"
                        )
                        // 存储记录数
                        val recordCount = historyTotalRecords ?: deviceStatus?.recordCount?.toLong()
                        InfoItem(
                            label = "存储记录",
                            value = recordCount?.let { "${it}条" } ?: "--"
                        )
                        // 时间同步状态
                        deviceStatus?.let { status ->
                            InfoItem(
                                label = "时间同步",
                                value = if (status.isTimeSynced) "已同步" else "未同步",
                                valueColor = if (status.isTimeSynced) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
                
                // === 设备昵称（选填，用于首页与选择设备时优先显示）===
                Text(
                    text = "设备昵称",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = nicknameInput,
                    onValueChange = { nicknameInput = it },
                    label = { Text("输入昵称（选填）", style = MaterialTheme.typography.bodySmall) },
                    placeholder = { Text("如：客厅温湿度计", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp),
                    singleLine = true
                )
                
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
                
                // === 历史记录间隔设置 ===
                // 固件 v3 起采样与落盘解耦：实时采样固定 1 秒，此处配置的是**历史记录落盘周期**。
                Text(
                    text = "历史记录间隔",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                
                OutlinedTextField(
                    value = intervalInput,
                    onValueChange = { 
                        intervalInput = it
                        errorMessage = null
                    },
                    label = {
                        Text(
                            "秒 (${BleConstants.HISTORY_INTERVAL_MIN}-${BleConstants.HISTORY_INTERVAL_MAX})",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp),
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { 
                        { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                )
                
                // 实时采样固定 1 秒 + 按间隔估算的保留时长
                val retentionDays = intervalInput.toIntOrNull()
                    ?.takeIf { it >= BleConstants.HISTORY_INTERVAL_MIN }
                    ?.let { BleConstants.estimateRetentionDays(it) }
                Text(
                    text = buildString {
                        append("实时采样固定 1 秒（用于实时显示与最高最低温度）")
                        if (retentionDays != null) {
                            append("\n按物理容量估算约 ")
                            append(retentionDays)
                            append(" 天，实际以设备保留策略为准")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
                
                // === 操作按钮区域（并排、小字体）===
                Text(
                    text = "数据管理",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 重置温度范围按钮
                    Box(modifier = Modifier.weight(1f)) {
                        CompactActionButton(
                            icon = Icons.Default.Refresh,
                            text = "重置最高最低温度",
                            onClick = { showResetTempConfirm = true }
                        )
                    }
                    // 清空历史数据按钮
                    if (isClearDataAvailable) {
                        Box(modifier = Modifier.weight(1f)) {
                            CompactActionButton(
                                icon = Icons.Default.Delete,
                                text = "清空历史数据",
                                onClick = { showClearDataConfirm = true },
                                enabled = !isClearingData,
                                isDestructive = true
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { validateAndSave() },
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    "保存",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", fontSize = 14.sp)
            }
        }
    )
    
    // 清空数据确认对话框
    if (showClearDataConfirm) {
        AlertDialog(
            onDismissRequest = { showClearDataConfirm = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("确认清空数据？") },
            text = { 
                Text(
                    "此操作将删除设备中存储的所有历史数据，操作不可撤销！",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ) 
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDataConfirm = false
                        onClearData()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("确认清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
    
    // 重置温度确认对话框
    if (showResetTempConfirm) {
        AlertDialog(
            onDismissRequest = { showResetTempConfirm = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text("确认重置温度范围？") },
            text = { 
                Text(
                    "此操作将清除设备记录的最高和最低温度数据。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ) 
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetTempConfirm = false
                        onResetMaxMinTemp()
                        onDismiss() // 关闭设置弹窗
                    }
                ) {
                    Text("确认重置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetTempConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 信息项组件
 */
@Composable
private fun InfoItem(
    label: String,
    value: String,
    valueColor: Color? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * 紧凑操作按钮（小字体、用于并排）
 */
@Composable
private fun CompactActionButton(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isDestructive: Boolean = false
) {
    val contentColor = if (isDestructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (enabled) contentColor else MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}


