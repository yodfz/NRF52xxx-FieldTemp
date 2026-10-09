package com.example.pandatemperature.ui.components

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.pandatemperature.data.model.Device
import com.example.pandatemperature.data.model.DeviceType

/**
 * 设备选择对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSelectionDialog(
    savedDevices: List<Device> = emptyList(),
    onlineDevices: Set<String> = emptySet(),
    scannedDevices: List<BluetoothDevice> = emptyList(),
    isScanning: Boolean,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onConnectByAddress: (String) -> Unit = {},
    onDismiss: () -> Unit,
    onStartScan: () -> Unit = {} // 新增：开始扫描回调
) {
    // 是否处于添加新设备模式
    var isAddingDevice by remember { mutableStateOf(false) }

    // 每次进入"添加新设备"模式时，确保开始扫描
    LaunchedEffect(isAddingDevice) {
        if (isAddingDevice) {
            onStartScan()
        }
    }
    
    // BottomSheet 状态
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true // 直接展开到全屏高度
    )
    
    // 由于 ModalBottomSheet 的 onDismissRequest 只有在用户手动关闭（下滑、点击遮罩）时触发
    // 我们需要确保在 onDismiss 被调用时，也能正确关闭
    // 这里我们直接使用 ModalBottomSheet，它的可见性通常由上层控制（if (showDialog) ...）
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.fillMaxWidth() // 默认就是全宽
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp) // 底部留白
                .heightIn(min = 300.dp, max = 500.dp) // 限制最大高度
        ) {
            // 标题栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isAddingDevice) {
                    IconButton(onClick = { isAddingDevice = false }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                } else {
                    Spacer(modifier = Modifier.width(48.dp)) // 占位，保持标题居中
                }
                
                Text(
                    text = if (isAddingDevice) "添加新设备" else "选择设备",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 内容区域
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
            ) {
                if (isAddingDevice) {
                    // 扫描新设备视图
                    ScanningDeviceList(
                        scannedDevices = scannedDevices,
                        savedDevices = savedDevices,
                        isScanning = isScanning,
                        onDeviceSelected = onDeviceSelected
                    )
                } else {
                    // 已保存设备视图
                    SavedDeviceList(
                        savedDevices = savedDevices,
                        onlineDevices = onlineDevices,
                        onDeviceSelected = { /* unused */ },
                        onAddClick = { isAddingDevice = true },
                        onConnectByAddress = onConnectByAddress
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedDeviceList(
    savedDevices: List<Device>,
    onlineDevices: Set<String>,
    onDeviceSelected: (Device) -> Unit, // 未使用
    onAddClick: () -> Unit,
    onConnectByAddress: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        if (savedDevices.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无已保存设备",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(savedDevices) { device ->
                    val isOnline = onlineDevices.contains(device.macAddress)
                    SavedDeviceItem(
                        device = device,
                        isOnline = isOnline,
                        onClick = { onConnectByAddress(device.macAddress) }
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        // 添加新设备按钮
        Button(
            onClick = onAddClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp), // 减小高度
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent
            ),
            contentPadding = PaddingValues(0.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF4facfe),
                                Color(0xFF00f2fe)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp) // 缩小图标
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "添加新设备",
                        style = MaterialTheme.typography.titleSmall, // 减小字体
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedDeviceItem(
    device: Device,
    isOnline: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (device.type == DeviceType.THERMOMETER) Icons.Default.DeviceThermostat else Icons.Default.Sensors,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // 信息（优先显示匹配蓝牙地址的设备昵称）
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.nickname?.takeIf { it.isNotBlank() } ?: device.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                    // 显示固件版本号
                    if (device.firmwareVersion != null && device.firmwareVersion > 0) {
                        Text(
                            text = "(v${device.firmwareVersion})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
                Text(
                    text = device.macAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // 状态
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isOnline) "在线" else "离线",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isOnline) Color(0xFF00E5FF) else Color.Gray
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isOnline) Color(0xFF00E5FF) else Color.Gray)
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanningDeviceList(
    scannedDevices: List<BluetoothDevice>,
    savedDevices: List<Device>,
    isScanning: Boolean,
    onDeviceSelected: (BluetoothDevice) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (isScanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "正在搜索附近设备...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        if (scannedDevices.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("未发现新设备")
            }
        } else {
            // 过滤掉已保存的设备
            val newDevices = scannedDevices.filter { scanned ->
                savedDevices.none { saved -> saved.macAddress == scanned.address }
            }
            
            if (newDevices.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("未发现新设备")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(newDevices) { device ->
                        ScanningDeviceItem(
                            device = device,
                            onClick = { onDeviceSelected(device) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanningDeviceItem(
    device: BluetoothDevice,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp) // 降低阴影
    ) {
        Row(
            modifier = Modifier.padding(12.dp), // 减少内边距
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Sensors,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(20.dp) // 缩小图标
            )
            Spacer(modifier = Modifier.width(12.dp)) // 减少间距
            Column {
                Text(
                    text = device.name ?: "未知设备",
                    style = MaterialTheme.typography.bodyMedium, // 减小字体
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
