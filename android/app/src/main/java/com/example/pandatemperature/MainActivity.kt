package com.example.pandatemperature

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Collections
import com.example.pandatemperature.ui.screen.MainScreen
import com.example.pandatemperature.ui.theme.PandaTemperatureTheme
import com.example.pandatemperature.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private enum class BluetoothReadyAction {
        STARTUP_AUTO_CONNECT,
        MANUAL_SELECT
    }
    
    private val viewModel: MainViewModel by viewModels()
    
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothManager?.adapter
    }
    
    // 扫描到的设备列表（使用线程安全的集合）
    private val scannedDevices = Collections.synchronizedList(mutableListOf<BluetoothDevice>())
    
    // 是否显示定位权限说明对话框
    private var showLocationPermissionDialog = mutableStateOf(false)

    // 蓝牙准备完成后的动作；权限/开关回调必须保留调用来源，避免启动自动连接误弹选择框。
    private var pendingBluetoothReadyAction: BluetoothReadyAction? = null
    private var startupAutoConnectStarted = false

    // 权限请求
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            continueAfterBluetoothPermissions()
        } else {
            pendingBluetoothReadyAction = null
            // 权限被拒绝
            Toast.makeText(
                this,
                "需要蓝牙权限才能使用此功能",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    // 蓝牙启用请求
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (bluetoothAdapter?.isEnabled == true) {
            continueAfterBluetoothReady()
        } else {
            pendingBluetoothReadyAction = null
            // 蓝牙未启用
            Toast.makeText(
                this,
                "需要启用蓝牙才能使用此功能",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    // 通知权限请求（Android 13+，用于天气预警）
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> /* 结果仅影响是否可发通知 */ }
    
    // 定位权限请求
    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        
        if (fineLocationGranted || coarseLocationGranted) {
            // 权限已授予，通知 ViewModel
            viewModel.onLocationPermissionGranted()
            Toast.makeText(
                this,
                "定位权限已授予，将记录GPS坐标",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            // 权限被拒绝
            Toast.makeText(
                this,
                "定位权限被拒绝，历史数据将不包含GPS坐标",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    // 用于在 Compose 中更新状态的函数（不再需要）
    // private var updateScanState: ((Boolean, Boolean) -> Unit)? = null
    // private var isDialogShowing = false
    
    override fun onResume() {
        super.onResume()
        // 从设置返回时同步定位权限状态，确保用户后来授权后手机记录会带 GPS
        syncLocationPermissionToViewModel()

        // 首次进入前台时执行一次启动自动连接准备流程。
        if (!startupAutoConnectStarted) {
            startupAutoConnectStarted = true
            ensureBluetoothReady(BluetoothReadyAction.STARTUP_AUTO_CONNECT)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // 检查并请求定位权限
        checkAndRequestLocationPermission()
        // Android 13+ 请求通知权限（天气预警需用）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setContent {
            // 定位权限说明对话框状态
            val showLocationDialog by showLocationPermissionDialog
            
            PandaTemperatureTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        viewModel = viewModel,
                        onDeviceSelect = {
                            // 先检查权限和蓝牙，通过后再显示对话框
                            checkPermissionsAndScan()
                        },
                        onDeviceSelected = { device ->
                            viewModel.connectDevice(device)
                        },
                        onDismissDeviceDialog = {
                            viewModel.closeDeviceDialog()
                        }
                    )
                    
                    // 定位权限说明对话框
                    if (showLocationDialog) {
                        LocationPermissionDialog(
                            onConfirm = {
                                showLocationPermissionDialog.value = false
                                requestLocationPermission()
                            },
                            onDismiss = {
                                showLocationPermissionDialog.value = false
                            }
                        )
                    }
                }
            }
        }
    }
    
    /**
     * 检查并请求定位权限
     */
    private fun checkAndRequestLocationPermission() {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        val coarseLocationGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        if (!fineLocationGranted && !coarseLocationGranted) {
            // 显示权限说明对话框
            showLocationPermissionDialog.value = true
        } else {
            // 已有权限，通知 ViewModel
            viewModel.onLocationPermissionGranted()
        }
    }

    /**
     * 仅同步定位权限状态到 ViewModel（不弹窗、不请求）。
     * 用于 onResume：用户从系统设置里打开定位后返回应用时，手机记录会开始带 GPS。
     */
    private fun syncLocationPermissionToViewModel() {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseLocationGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fineLocationGranted || coarseLocationGranted) {
            viewModel.onLocationPermissionGranted()
        }
    }
    
    /**
     * 请求定位权限
     */
    private fun requestLocationPermission() {
        requestLocationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    /**
     * 检查权限并开始扫描
     */
    private fun checkPermissionsAndScan() {
        ensureBluetoothReady(BluetoothReadyAction.MANUAL_SELECT)
    }

    /**
     * 检查蓝牙权限和开关，完成后执行指定动作。
     * 启动自动连接和用户手动选择设备共用此流程，但回调动作严格区分。
     */
    private fun ensureBluetoothReady(action: BluetoothReadyAction) {
        pendingBluetoothReadyAction = action
        // 先检查权限（Android 12+ 需要 BLUETOOTH_CONNECT 才能启动启用蓝牙的 Intent）
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        
        val needPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (needPermissions.isNotEmpty()) {
            // 先请求权限
            requestPermissionLauncher.launch(needPermissions.toTypedArray())
            return
        }
        
        // 权限已授予，检查蓝牙是否启用
        if (bluetoothAdapter?.isEnabled != true) {
            // 请求启用蓝牙（此时已有 BLUETOOTH_CONNECT 权限）
            val enableBtIntent = android.content.Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
            return
        }
        
        continueAfterBluetoothReady()
    }

    /** 权限已授予后的继续步骤。 */
    private fun continueAfterBluetoothPermissions() {
        if (bluetoothAdapter?.isEnabled != true) {
            val enableBtIntent = android.content.Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            continueAfterBluetoothReady()
        }
    }

    /** 蓝牙权限和开关都就绪后执行待处理动作。 */
    private fun continueAfterBluetoothReady() {
        val action = pendingBluetoothReadyAction
        pendingBluetoothReadyAction = null
        when (action) {
            BluetoothReadyAction.STARTUP_AUTO_CONNECT -> viewModel.autoConnectLatestSavedDevice()
            BluetoothReadyAction.MANUAL_SELECT -> viewModel.openDeviceDialog()
            null -> Unit
        }
    }
    
    // startDeviceScan 和 resetScanState 不再需要，删除或注释
    
    override fun onDestroy() {
        super.onDestroy()
        // 配置变更（例如屏幕旋转）会重建 Activity，但会保留同一个 ViewModel。
        // 此时不要主动断开，否则“仅启动一次”的自动连接不会再次触发。
        if (!isChangingConfigurations) {
            viewModel.disconnectDevice()
        }
        viewModel.stopScan()
    }
}

/**
 * 定位权限说明对话框
 */
@Composable
private fun LocationPermissionDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "需要定位权限",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "为了记录温湿度数据的采集位置，应用需要获取您的位置信息。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "位置信息将与温湿度数据一起保存到历史记录中，方便您追踪数据采集地点。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "如果您拒绝授权，数据仍会正常记录，但不会包含GPS坐标。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("授权")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后")
            }
        }
    )
}
