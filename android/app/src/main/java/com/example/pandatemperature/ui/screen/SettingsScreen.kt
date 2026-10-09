package com.example.pandatemperature.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pandatemperature.ui.viewmodel.MainViewModel

/**
 * 设置页面子页面枚举
 */
private enum class SettingsSubScreen {
    Main,            // 主设置列表
    DeviceManagement, // 设备管理
    FirmwareUpdate    // 固件升级（BLE OTA）
}

/**
 * 设置页面
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    // 子页面导航状态
    var currentSubScreen by remember { mutableStateOf(SettingsSubScreen.Main) }
    
    // 收集已保存设备列表
    val savedDevices by viewModel.savedDevices.collectAsStateWithLifecycle(initialValue = emptyList())
    
    val weatherAlertEnabled by viewModel.weatherAlertEnabled.collectAsStateWithLifecycle()
    when (currentSubScreen) {
        SettingsSubScreen.Main -> {
            SettingsMainScreen(
                onNavigateToDeviceManagement = { currentSubScreen = SettingsSubScreen.DeviceManagement },
                onNavigateToFirmwareUpdate = { currentSubScreen = SettingsSubScreen.FirmwareUpdate },
                weatherAlertEnabled = weatherAlertEnabled,
                onWeatherAlertEnabledChange = { viewModel.setWeatherAlertEnabled(it) },
                modifier = modifier
            )
        }
        SettingsSubScreen.DeviceManagement -> {
            DeviceManagementScreen(
                devices = savedDevices,
                onBack = { currentSubScreen = SettingsSubScreen.Main },
                onDeleteDevice = { device -> viewModel.removeDevice(device) },
                modifier = modifier
            )
        }
        SettingsSubScreen.FirmwareUpdate -> {
            FirmwareUpdateScreen(
                onBack = { currentSubScreen = SettingsSubScreen.Main },
                modifier = modifier
            )
        }
    }
}

/**
 * 设置主页面 - 显示设置列表菜单
 */
@Composable
private fun SettingsMainScreen(
    onNavigateToDeviceManagement: () -> Unit,
    onNavigateToFirmwareUpdate: () -> Unit,
    weatherAlertEnabled: Boolean = true,
    onWeatherAlertEnabledChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 获取系统栏高度（全面屏安全区域）
    val systemBars = WindowInsets.systemBars
    val topPadding = systemBars.asPaddingValues().calculateTopPadding()
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 顶部间距（包含系统栏高度）
        Spacer(modifier = Modifier.height(topPadding + 8.dp))
        
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineMedium
        )
        
        // 设置列表
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            // 天气预警
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "天气预警", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "气压骤降时暴风雨提醒（需连接带气压的设备）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(
                    checked = weatherAlertEnabled,
                    onCheckedChange = onWeatherAlertEnabledChange
                )
            }
            HorizontalDivider()
            // 设备管理
            SettingsMenuItem(
                icon = Icons.Default.DeviceHub,
                title = "设备管理",
                subtitle = "管理已保存的设备",
                onClick = onNavigateToDeviceManagement
            )
            HorizontalDivider()
            // 固件升级（BLE OTA）
            SettingsMenuItem(
                icon = Icons.Default.SystemUpdate,
                title = "固件升级",
                subtitle = "通过蓝牙升级设备固件（zephyr.signed.bin）",
                onClick = onNavigateToFirmwareUpdate
            )
        }
    }
}

/**
 * 设置菜单项
 */
@Composable
private fun SettingsMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline
        )
    }
}
