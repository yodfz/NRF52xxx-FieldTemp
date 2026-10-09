package com.example.pandatemperature.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pandatemperature.data.bluetooth.BleManager
import com.example.pandatemperature.data.ota.OtaConstants
import com.example.pandatemperature.ui.viewmodel.FirmwareOtaViewModel

/**
 * 固件升级（BLE OTA）界面。
 *
 * 只负责展示与交互，协议与状态机在 `data/ota` 的纯逻辑层。
 */
@Composable
fun FirmwareUpdateScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FirmwareOtaViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val isConnected = connectionState == BleManager.ConnectionState.ServicesDiscovered ||
        connectionState == BleManager.ConnectionState.Connected

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::loadImage) }

    val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(modifier = Modifier.height(topPadding + 8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !state.isBusy) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "固件升级",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        // 连接状态
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = if (isConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (isConnected) "已连接设备，可以升级" else "未连接设备：请先在首页连接温湿度计",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // 文件选择与镜像信息
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "固件文件",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "必须是 ${OtaConstants.REQUIRED_FILE_HINT}（含 MCUboot 头与签名），" +
                        "不是裸 zephyr.bin，也不是 merged.hex。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("选择固件文件")
                }
                state.fileName?.let { name ->
                    InfoRow("文件", name)
                    InfoRow("大小", "${state.totalBytes} 字节")
                    state.versionLabel?.let { InfoRow("镜像版本", it) }
                    state.sha256Prefix?.let { InfoRow("SHA-256", "$it…") }
                }
            }
        }

        // 授权密钥
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "授权密钥",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = state.authKeyText,
                    onValueChange = viewModel::onAuthKeyChanged,
                    enabled = !state.isBusy,
                    singleLine = true,
                    label = { Text("16 字符或 32 位十六进制") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "当前为联调默认值，只防误触、不防逆向。量产必须与固件同步更换。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // 进度
        state.progress?.let { progress ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "升级进度 ${progress.percent}%（分片 ${progress.chunkSize} B）",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    LinearProgressIndicator(
                        progress = { progress.confirmedBytes.toFloat() / progress.totalBytes.coerceAtLeast(1L) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "已确认 ${progress.confirmedBytes} / ${progress.totalBytes} 字节，" +
                            "已发送 ${progress.queuedBytes} 字节",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 状态消息
        state.message?.let { message ->
            val isError = state.phase == FirmwareOtaViewModel.Phase.ERROR
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (state.phase) {
                FirmwareOtaViewModel.Phase.VERIFIED -> {
                    Button(
                        onClick = viewModel::triggerUpgrade,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("立即重启并安装")
                    }
                }
                FirmwareOtaViewModel.Phase.DONE -> {
                    OutlinedButton(
                        onClick = viewModel::reset,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("完成")
                    }
                }
                else -> {
                    Button(
                        onClick = viewModel::startUpload,
                        enabled = state.phase == FirmwareOtaViewModel.Phase.READY && isConnected,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("开始升级")
                    }
                }
            }
            if (state.isBusy) {
                OutlinedButton(
                    onClick = viewModel::cancelUpload,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("取消")
                }
            }
        }

        Text(
            text = "说明：上传完成后设备不会自动重启，需再点一次「立即重启并安装」。" +
                "升级期间设备会暂停历史落盘；若中途断连，重新选择同一文件开始即可从设备已确认偏移续传。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            fontWeight = FontWeight.Medium
        )
    }
}
