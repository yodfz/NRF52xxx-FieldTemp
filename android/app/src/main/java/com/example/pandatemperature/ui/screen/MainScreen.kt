package com.example.pandatemperature.ui.screen

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.bluetooth.BluetoothDevice
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pandatemperature.data.bluetooth.BleManager
import com.example.pandatemperature.data.model.TaskStatus
import com.example.pandatemperature.ui.components.*
import com.example.pandatemperature.ui.components.weatherinsights.WeatherInsightsSection
import com.example.pandatemperature.ui.viewmodel.MainViewModel
import kotlin.math.abs
import androidx.compose.animation.*
import androidx.compose.animation.core.*

/**
 * 主界面 - 带底部导航栏
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onDeviceSelect: () -> Unit,
    // scannedDevices: List<BluetoothDevice> = emptyList(), // 移除
    // isScanning: Boolean = false, // 移除
    // showDialog: Boolean = false, // 移除
    onDeviceSelected: (BluetoothDevice) -> Unit = {}, // 保持，用于扫描列表的回调
    onDismissDeviceDialog: () -> Unit = {}
) {
    // 收集状态
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()
    // ... 其他状态 ...
    
    // 收集 Dialog 相关的状态
    val showDialog by viewModel.showDeviceDialog.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val scannedDevices by viewModel.scannedDeviceList.collectAsStateWithLifecycle()
    val temperature by viewModel.temperature.collectAsStateWithLifecycle()
    val humidity by viewModel.humidity.collectAsStateWithLifecycle()
    val pressure by viewModel.pressure.collectAsStateWithLifecycle()  // ⭐ v1.1 新增：气压
    val maxTemperature by viewModel.maxTemperature.collectAsStateWithLifecycle()
    val minTemperature by viewModel.minTemperature.collectAsStateWithLifecycle()
    val maxTemperatureTimestamp by viewModel.maxTemperatureTimestamp.collectAsStateWithLifecycle()  // 协议 v1.2
    val minTemperatureTimestamp by viewModel.minTemperatureTimestamp.collectAsStateWithLifecycle()  // 协议 v1.2
    val isMaxMinTempSupported by viewModel.isMaxMinTempSupported.collectAsStateWithLifecycle()
    val maxMinTempNeedManualSync by viewModel.maxMinTempNeedManualSync.collectAsStateWithLifecycle()
    val lastUpdateTime by viewModel.lastUpdateTime.collectAsStateWithLifecycle()
    val isLoadingRealtimeData by viewModel.isLoadingRealtimeData.collectAsStateWithLifecycle()  // ⭐ v1.1 更新
    val batteryVoltage by viewModel.batteryVoltage.collectAsStateWithLifecycle()  // ⭐ v1.1 新增
    val batteryPercent by viewModel.batteryPercent.collectAsStateWithLifecycle()  // ⭐ v1.1 新增
    val taskStatus by viewModel.taskStatus.collectAsStateWithLifecycle()
    val deviceStatus by viewModel.deviceStatus.collectAsStateWithLifecycle()
    val homeHistoryRecords by viewModel.homeHistoryRecords.collectAsStateWithLifecycle(initialValue = emptyList())
    val last24HoursRecords by viewModel.last24HoursRecords.collectAsStateWithLifecycle(initialValue = emptyList())
    val interval by viewModel.interval.collectAsStateWithLifecycle() // 获取采集间隔配置
    val wapsResult by viewModel.wapsResult.collectAsStateWithLifecycle()
    val lastGpsAltitudeMeters by viewModel.lastGpsAltitudeMeters.collectAsStateWithLifecycle()
    val lastHistoryEvaluationResult by viewModel.lastHistoryEvaluationResult.collectAsStateWithLifecycle()
    val weatherInsights by viewModel.weatherInsights.collectAsStateWithLifecycle()
    // ⭐ v1.2 新增：配置相关状态
    val historyTotalRecords by viewModel.historyTotalRecords.collectAsStateWithLifecycle()
    val isClearingData by viewModel.isClearingData.collectAsStateWithLifecycle()
    
    // ⭐ 历史数据传输状态（用于阻止息屏）
    val isFetchingHistory by viewModel.isFetchingHistory.collectAsStateWithLifecycle()

    // 已保存设备 + 当前连接地址（用于首页设备状态优先显示昵称）
    val savedDevicesForDisplay by viewModel.savedDevices.collectAsStateWithLifecycle(initialValue = emptyList())
    val deviceAddress by viewModel.deviceAddress.collectAsStateWithLifecycle()
    val displayDeviceName = remember(savedDevicesForDisplay, deviceAddress, deviceName) {
        val addr = deviceAddress
        if (addr == null) deviceName
        else savedDevicesForDisplay.find { it.macAddress == addr }?.nickname?.takeIf { it.isNotBlank() } ?: deviceName
    }

    // 配置 Modal 显示状态
    var showConfigModal by remember { mutableStateOf(false) }
    // 设备配置弹窗打开瞬间的电压快照，弹窗停留期间不随实时通知变化。
    var configBatteryVoltage by remember { mutableStateOf<Float?>(null) }
    
    // 计算最高/最低温度的时间 ⭐ v1.2 更新：优先使用设备返回的时间戳
    val stats = remember(homeHistoryRecords, maxTemperature, minTemperature, maxTemperatureTimestamp, minTemperatureTimestamp) {
        calculateStats(homeHistoryRecords, maxTemperature, minTemperature, maxTemperatureTimestamp, minTemperatureTimestamp)
    }
    
    // 加载最近24小时数据（首次进入首页时）
    LaunchedEffect(Unit) {
        viewModel.loadLast24HoursRecords()
    }
    
    // 当历史数据更新时，重新加载24小时数据
    LaunchedEffect(homeHistoryRecords) {
        viewModel.loadLast24HoursRecords()
    }
    
    // 导航状态
    var selectedTabIndex by remember { mutableStateOf(0) }
    // 从首页“整体评估小结”进入的全屏分析页（需覆盖底部 NavBar）
    var showInsightsPage by remember { mutableStateOf(false) }
    val navigationItems = listOf(
        "首页" to Icons.Default.Home,
        "历史数据" to Icons.Filled.History,
        // "工具" to Icons.Default.Build,  // 暂时屏蔽
        "设置" to Icons.Default.Settings
    )
    
    // 历史数据TAB是否已加载过
    var historyTabLoaded by remember { mutableStateOf(false) }
    
    // ⭐ 历史数据传输过程中保持屏幕常亮
    KeepScreenOn(enabled = isFetchingHistory)
    
    // Snackbar 状态
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    
    // 监听 Snackbar 消息并显示
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { data ->
            snackbarHostState.showSnackbar(
                message = data.message,
                duration = SnackbarDuration.Short
            )
            viewModel.clearSnackbar()
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = {
                // Snackbar 不在这里显示，移到 Box 最外层
            },
            topBar = {
                // 不再使用 topBar，设备信息移到首页内容中
            },
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface, // 明确背景色
                    modifier = Modifier.height(83.dp) // ⭐ 增加约 3dp 高度（原 80dp）
                ) {
                navigationItems.forEachIndexed { index, (title, icon) ->
                    val isSelected = selectedTabIndex == index
                    
                    // 选中动画
                    val iconScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.1f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "iconScale_$index"
                    )
                    val iconColor by animateColorAsState(
                        targetValue = if (isSelected) Color(0xFF0d7ff2) else Color(0xFF757575),
                        animationSpec = tween(200),
                        label = "iconColor_$index"
                    )
                    val textColor by animateColorAsState(
                        targetValue = if (isSelected) Color(0xFF0d7ff2) else Color(0xFF757575),
                        animationSpec = tween(200),
                        label = "textColor_$index"
                    )
                    
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 4.dp, bottom = 8.dp)
                            .clickable { selectedTabIndex = index },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                icon,
                                contentDescription = title,
                                tint = iconColor,
                                modifier = Modifier
                                    .size(24.dp)
                                    .scale(iconScale)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelSmall,
                                color = textColor
                            )
                        }
                    }
                }
            }
            

            },
            contentWindowInsets = WindowInsets(0.dp), // 禁用默认padding，我们手动处理
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
            ) {
                // 页面切换动画
                AnimatedContent(
                    targetState = selectedTabIndex,
                    transitionSpec = {
                        if (targetState > initialState) {
                            // 向右滑动
                            slideInHorizontally { it } + fadeIn() togetherWith
                                    slideOutHorizontally { -it } + fadeOut()
                        } else {
                            // 向左滑动
                            slideInHorizontally { -it } + fadeIn() togetherWith
                                    slideOutHorizontally { it } + fadeOut()
                        }.using(SizeTransform(clip = false))
                    },
                    label = "pageTransition"
                ) { targetIndex ->
                    when (targetIndex) {
                        0 -> {
                            // 首页：实时温湿度 + 历史温湿度预览
                            HomeScreen(
                                connectionState = connectionState,
                                deviceName = displayDeviceName,
                                taskStatus = taskStatus,
                                firmwareVersionLabel = deviceStatus?.firmwareVersionLabel,
                                temperature = temperature,
                                humidity = humidity,
                                pressure = pressure,  // ⭐ v1.1 新增
                                gpsAltitudeMeters = lastGpsAltitudeMeters,  // 有则与气压混合计算海拔
                                maxTemperature = maxTemperature,
                                minTemperature = minTemperature,
                                isMaxMinTempSupported = isMaxMinTempSupported,
                                maxMinTempNeedManualSync = maxMinTempNeedManualSync,
                                lastUpdateTime = lastUpdateTime,
                                batteryVoltage = batteryVoltage,  // ⭐ v1.1 新增
                                batteryPercent = batteryPercent,  // ⭐ v1.1 新增
                                isLoadingRealtimeData = isLoadingRealtimeData,  // ⭐ v1.1 更新
                                historyRecords = homeHistoryRecords,
                                last24HoursRecords = last24HoursRecords,
                                stats = stats,
                                wapsResult = wapsResult,  // WAPS 天气预警
                                weatherInsights = weatherInsights,  // 首页气象洞察
                                onDeviceSelect = onDeviceSelect,
                                onReadRealtimeData = { viewModel.readRealtimeData() },  // ⭐ v1.1 更新
                                onRefreshMaxMinTemp = { viewModel.readMaxMinTemperature() },  // 点击最高最低温度刷新
                                onClearHistory = { viewModel.clearHistory() },
                                onDisconnectDevice = { viewModel.disconnectDevice() },
                                onShowMoreHistory = { selectedTabIndex = 1 }, // 跳转到历史数据页
                                onConfigClick = {
                                    if (connectionState == BleManager.ConnectionState.ServicesDiscovered) {
                                        viewModel.readInterval() // 打开前先读取一次
                                        configBatteryVoltage = batteryVoltage
                                            ?: deviceAddress?.let { address ->
                                                savedDevicesForDisplay.find { it.macAddress == address }?.latestBatteryVoltage
                                            }
                                        showConfigModal = true
                                    }
                                },
                                onShowInsightsPage = { showInsightsPage = true },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(paddingValues)
                            )
                        

                        }
                    1 -> {
                        // 历史数据页
                        LaunchedEffect(Unit) {
                            if (!historyTabLoaded) {
                                viewModel.loadHistoryDetail()
                                historyTabLoaded = true
                            }
                        }
                        HistoryTabContent(
                            viewModel = viewModel,
                            modifier = Modifier
                                .fillMaxSize()
                                // 历史数据页不使用 paddingValues，由 HistoryScreen 自己处理布局
                        )
                    }
                    2 -> {
                        // 设置页（工具页已屏蔽，索引从3变为2）
                        SettingsScreen(
                            viewModel = viewModel,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(paddingValues)
                        )
                    }
                }
            }
        }
        
        // 设备选择对话框
        // 只要 showDialog 为 true 就显示，scannedDevices 和 isScanning 由 ViewModel 控制
        if (showDialog) {
            val savedDevices by viewModel.savedDevices.collectAsStateWithLifecycle(initialValue = emptyList())
            val onlineDevices by viewModel.onlineDevices.collectAsStateWithLifecycle(initialValue = emptySet())
            
            DeviceSelectionDialog(
                savedDevices = savedDevices,
                onlineDevices = onlineDevices,
                scannedDevices = scannedDevices,
                isScanning = isScanning,
                onDeviceSelected = { device ->
                    onDeviceSelected(device)
                },
                onConnectByAddress = { address ->
                    // 停止扫描并连接
                    // onDismissDeviceDialog() // 移除，viewModel 会处理
                    viewModel.connectDevice(address)
                },
                onDismiss = {
                    viewModel.closeDeviceDialog()
                    onDismissDeviceDialog()
                },
                onStartScan = { viewModel.startScanPublic() } // 新增：手动开始扫描回调
            )
        }

            // 配置编辑 Modal
            if (showConfigModal) {
            val currentDevice = deviceAddress?.let { addr ->
                savedDevicesForDisplay.find { it.macAddress == addr }
            }
            ConfigModal(
                currentInterval = interval,
                deviceStatus = deviceStatus,
                historyTotalRecords = historyTotalRecords,
                batteryVoltage = configBatteryVoltage,
                isClearDataAvailable = viewModel.isClearDataAvailable(),
                isClearingData = isClearingData,
                currentNickname = currentDevice?.nickname,
                onSave = { newInterval ->
                    viewModel.setInterval(newInterval)
                },
                onSaveNickname = { nickname ->
                    deviceAddress?.let { viewModel.updateDeviceNickname(it, nickname) }
                },
                onClearData = { viewModel.clearAllDeviceData() },
                onResetMaxMinTemp = { viewModel.resetMaxMinTemperature() },
                onDismiss = { showConfigModal = false }
            )
        }
        }

        // 全屏分析页：覆盖整个 MainScreen（含底部 NavBar）
        val insightsForPage = weatherInsights
        if (showInsightsPage && insightsForPage != null) {
            WeatherInsightsFullPage(
                insights = insightsForPage,
                records24h = last24HoursRecords,
                connectionState = connectionState,
                onBack = { showInsightsPage = false },
                modifier = Modifier.fillMaxSize()
            )
        }
        
        // Snackbar 显示在最高层级，垂直水平居中
        Box(
            modifier = Modifier
                .fillMaxSize()
                .wrapContentSize(Alignment.Center),
            contentAlignment = Alignment.Center
        ) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) { snackbarData ->
                val currentSnackbarMessage = snackbarMessage
                val snackbarType = currentSnackbarMessage?.type ?: com.example.pandatemperature.ui.viewmodel.MainViewModel.SnackbarType.INFO
                
                // 根据类型选择图标和颜色
                val (icon, iconColor, backgroundColor) = when (snackbarType) {
                    com.example.pandatemperature.ui.viewmodel.MainViewModel.SnackbarType.SUCCESS -> Triple(
                        Icons.Default.CheckCircle,
                        Color(0xFF4CAF50),  // 绿色图标
                        MaterialTheme.colorScheme.inverseSurface
                    )
                    com.example.pandatemperature.ui.viewmodel.MainViewModel.SnackbarType.ERROR -> Triple(
                        Icons.Default.Cancel,
                        Color(0xFFF44336),  // 红色图标
                        MaterialTheme.colorScheme.inverseSurface
                    )
                    com.example.pandatemperature.ui.viewmodel.MainViewModel.SnackbarType.INFO -> Triple(
                        Icons.Default.Info,
                        Color(0xFF2196F3),  // 蓝色图标
                        MaterialTheme.colorScheme.inverseSurface
                    )
                }
                
                Snackbar(
                    modifier = Modifier.padding(12.dp),
                    containerColor = backgroundColor,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = iconColor
                        )
                        Text(
                            text = snackbarData.visuals.message,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

/**
 * 统计数据（最高/最低值及时间）⭐ v1.1 更新：移除湿度统计
 */
data class Stats(
    val maxTemperatureTime: String?,
    val minTemperatureTime: String?
)

/**
 * 计算统计数据 ⭐ v1.2 更新：优先使用设备返回的时间戳，若无则从历史记录匹配
 */
private fun calculateStats(
    records: List<com.example.pandatemperature.data.model.TemperatureRecord>,
    maxTemperature: Float?,
    minTemperature: Float?,
    maxTemperatureTimestamp: Long? = null,  // 协议 v1.2：设备返回的时间戳（秒）
    minTemperatureTimestamp: Long? = null   // 协议 v1.2：设备返回的时间戳（秒）
): Stats {
    val timeFormat = java.text.SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", java.util.Locale.getDefault())
    
    // 优先使用设备返回的时间戳，若无则从历史记录匹配
    val maxTempTime = maxTemperatureTimestamp?.let { timestamp ->
        timeFormat.format(java.util.Date(timestamp * 1000))
    } ?: maxTemperature?.let { maxTemp ->
        records.find { abs(it.temperature - maxTemp) < 0.1f }?.timestamp
            ?.let { timeFormat.format(java.util.Date(it * 1000)) }
    }
    
    val minTempTime = minTemperatureTimestamp?.let { timestamp ->
        timeFormat.format(java.util.Date(timestamp * 1000))
    } ?: minTemperature?.let { minTemp ->
        records.find { abs(it.temperature - minTemp) < 0.1f }?.timestamp
            ?.let { timeFormat.format(java.util.Date(it * 1000)) }
    }
    
    return Stats(
        maxTemperatureTime = maxTempTime,
        minTemperatureTime = minTempTime
    )
}

/**
 * 首页内容
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    connectionState: BleManager.ConnectionState,
    deviceName: String?,
    taskStatus: TaskStatus?,
    /** 固件版本显示文案（与配置弹窗/配置卡片同一口径，见 DeviceStatus.firmwareVersionLabel） */
    firmwareVersionLabel: String? = null,
    temperature: Float?,
    humidity: Float?,
    pressure: Float? = null,  // ⭐ v1.1 新增：气压
    gpsAltitudeMeters: Double? = null,  // GPS 海拔（有则与气压混合计算海拔）
    maxTemperature: Float?,
    minTemperature: Float?,
    isMaxMinTempSupported: Boolean,
    maxMinTempNeedManualSync: Boolean,
    lastUpdateTime: Long?,
    batteryVoltage: Float?,  // ⭐ v1.1 新增
    batteryPercent: Float?,  // ⭐ v1.1 新增
    isLoadingRealtimeData: Boolean,  // ⭐ v1.1 更新
    historyRecords: List<com.example.pandatemperature.data.model.TemperatureRecord>,
    last24HoursRecords: List<com.example.pandatemperature.data.model.TemperatureRecord>,
    stats: Stats,
    wapsResult: com.example.pandatemperature.data.weather.WapsResult? = null,  // WAPS 天气预警
    weatherInsights: com.example.pandatemperature.data.weather.insights.WeatherInsightsResult? = null,  // 首页气象洞察
    onDeviceSelect: () -> Unit,
    onReadRealtimeData: () -> Unit,  // ⭐ v1.1 更新
    onRefreshMaxMinTemp: () -> Unit,  // 点击最高最低温度刷新
    onClearHistory: () -> Unit,
    onDisconnectDevice: () -> Unit,
    onShowMoreHistory: () -> Unit,
    onConfigClick: () -> Unit,
    onShowInsightsPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 遮罩显示状态
    var isMaskDismissed by remember { mutableStateOf(false) }
    
    // 断开连接确认对话框状态
    var showDisconnectConfirmDialog by remember { mutableStateOf(false) }

    // 监听连接状态，如果连接成功，重置 isMaskDismissed
    // 这样下次断开时，遮罩会默认显示
    LaunchedEffect(connectionState) {
        if (connectionState == BleManager.ConnectionState.Connected) {
            isMaskDismissed = false
        }
    }

    // 获取系统栏高度（全面屏安全区域）
    val systemBars = WindowInsets.systemBars
    val topPadding = systemBars.asPaddingValues().calculateTopPadding()

    Box(modifier = modifier.fillMaxSize()) {
        // 卡片进入动画状态
        var cardsVisible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            cardsVisible = true
        }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                // 如果显示遮罩，对底层内容应用模糊效果
                .then(
                    if (connectionState == BleManager.ConnectionState.Disconnected && !isMaskDismissed) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Modifier.graphicsLayer {
                                renderEffect = RenderEffect
                                    .createBlurEffect(10f, 10f, Shader.TileMode.CLAMP)
                                    .asComposeRenderEffect()
                            }
                        } else {
                            Modifier
                        }
                    } else {
                        Modifier
                    }
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 顶部间距（包含系统栏高度）
            Spacer(modifier = Modifier.height(topPadding + 8.dp))
            
            // 设备状态栏 - 动画延迟0ms
            val statusBarOffsetY by animateDpAsState(
                targetValue = if (cardsVisible) 0.dp else 30.dp,
                animationSpec = tween(300, delayMillis = 0, easing = FastOutSlowInEasing),
                label = "statusBarOffset"
            )
            val statusBarAlpha by animateFloatAsState(
                targetValue = if (cardsVisible) 1f else 0f,
                animationSpec = tween(300, delayMillis = 0, easing = FastOutSlowInEasing),
                label = "statusBarAlpha"
            )
            
            StatusBar(
                connectionState = connectionState,
                deviceName = deviceName,
                taskStatus = taskStatus,
                batteryVoltage = batteryVoltage,
                batteryPercent = batteryPercent,
                firmwareVersionLabel = firmwareVersionLabel,
                onClick = {
                    if (connectionState == BleManager.ConnectionState.Disconnected) {
                        onDeviceSelect()
                    } else {
                        // 已连接时，显示断开确认对话框
                        showDisconnectConfirmDialog = true
                    }
                },
                onConfigClick = onConfigClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = statusBarOffsetY.toPx()
                        alpha = statusBarAlpha
                    }
            )
            
            // 实时数据卡片 - 动画延迟100ms
            val realtimeCardOffsetY by animateDpAsState(
                targetValue = if (cardsVisible) 0.dp else 30.dp,
                animationSpec = tween(300, delayMillis = 100, easing = FastOutSlowInEasing),
                label = "realtimeCardOffset"
            )
            val realtimeCardAlpha by animateFloatAsState(
                targetValue = if (cardsVisible) 1f else 0f,
                animationSpec = tween(300, delayMillis = 100, easing = FastOutSlowInEasing),
                label = "realtimeCardAlpha"
            )
            
            RealtimeDataCard(
                connectionState = connectionState,
                temperature = temperature,
                humidity = humidity,
                pressure = pressure,  // ⭐ v1.1 新增
                gpsAltitudeMeters = gpsAltitudeMeters,
                maxTemperature = maxTemperature,
                minTemperature = minTemperature,
                maxTemperatureTime = stats.maxTemperatureTime,
                minTemperatureTime = stats.minTemperatureTime,
                isMaxMinTempSupported = isMaxMinTempSupported,
                maxMinTempNeedManualSync = maxMinTempNeedManualSync,
                lastUpdateTime = lastUpdateTime,
                batteryVoltage = batteryVoltage,  // ⭐ v1.1 新增
                batteryPercent = batteryPercent,  // ⭐ v1.1 新增
                isLoadingRealtimeData = isLoadingRealtimeData,  // ⭐ v1.1 更新
                onReadRealtimeData = onReadRealtimeData,  // ⭐ v1.1 更新
                onRefreshMaxMinTemp = onRefreshMaxMinTemp,  // 点击最高最低温度刷新
                onDeviceSelect = onDeviceSelect,  // 新增：连接设备回调
                wapsResult = wapsResult,  // WAPS 天气预警
                modifier = Modifier.fillMaxWidth()
            )
            
            // 整体评估小结区域动画（延迟 200ms）
            val summaryOffsetY by animateDpAsState(
                targetValue = if (cardsVisible) 0.dp else 30.dp,
                animationSpec = tween(300, delayMillis = 200, easing = FastOutSlowInEasing),
                label = "summaryOffsetY"
            )
            val summaryAlpha by animateFloatAsState(
                targetValue = if (cardsVisible) 1f else 0f,
                animationSpec = tween(300, delayMillis = 200, easing = FastOutSlowInEasing),
                label = "summaryAlpha"
            )
            
            // 整体评估小结：点击弹出卡片分析（替代原“最近一次历史评估”常驻文案）
            if (weatherInsights != null) {
                val summaryText = remember(weatherInsights) {
                    val core = weatherInsights.overviewSummary3h
                        ?.takeIf { it.isNotBlank() }
                        ?: weatherInsights.overviewSummary?.takeIf { it.isNotBlank() }
                        ?: weatherInsights.items.firstOrNull()?.summary?.takeIf { it.isNotBlank() }
                        ?: "数据收集中"
                    "整体评估小结：$core（点击查看分析）"
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onShowInsightsPage() }
                        .graphicsLayer {
                            translationY = summaryOffsetY.toPx()
                            alpha = summaryAlpha
                        },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = summaryText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            } else {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = false) {}
                        .graphicsLayer {
                            translationY = summaryOffsetY.toPx()
                            alpha = summaryAlpha
                        },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ) {
                    Text(
                        text = "整体评估小结：暂无近期评估（点击查看分析）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }

            // 趋势分析卡片（24小时）- 动画延迟300ms
            val trendCardOffsetY by animateDpAsState(
                targetValue = if (cardsVisible) 0.dp else 30.dp,
                animationSpec = tween(300, delayMillis = 300, easing = FastOutSlowInEasing),
                label = "trendCardOffset"
            )
            val trendCardAlpha by animateFloatAsState(
                targetValue = if (cardsVisible) 1f else 0f,
                animationSpec = tween(300, delayMillis = 300, easing = FastOutSlowInEasing),
                label = "trendCardAlpha"
            )
            
            TrendChartCard(
                records = last24HoursRecords,
                connectionState = connectionState,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = trendCardOffsetY.toPx()
                        alpha = trendCardAlpha
                    }
            )
            
            // 历史数据卡片（首页预览）- 动画延迟400ms
            val historyCardOffsetY by animateDpAsState(
                targetValue = if (cardsVisible) 0.dp else 30.dp,
                animationSpec = tween(300, delayMillis = 400, easing = FastOutSlowInEasing),
                label = "historyCardOffset"
            )
            val historyCardAlpha by animateFloatAsState(
                targetValue = if (cardsVisible) 1f else 0f,
                animationSpec = tween(300, delayMillis = 400, easing = FastOutSlowInEasing),
                label = "historyCardAlpha"
            )
            
            HistoryCard(
                connectionState = connectionState,
                historyRecords = historyRecords,
                onClearHistory = {}, // 首页不再需要清空功能
                onShowMore = onShowMoreHistory,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = historyCardOffsetY.toPx()
                        alpha = historyCardAlpha
                    }
            )
            
            // 底部间距
            Spacer(modifier = Modifier.height(12.dp))
        }

        // 全屏玻璃遮罩层和按钮
        if (connectionState == BleManager.ConnectionState.Disconnected && !isMaskDismissed) {
            // 半透明背景
            // 如果支持渲染模糊（Android 12+），使用极淡的白色（几乎透明）以凸显模糊效果
            // 如果不支持（Android 11及以下），使用深色半透明遮罩作为降级方案
            val overlayColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Color.White.copy(alpha = 0.05f) 
            } else {
                Color.Black.copy(alpha = 0.6f)
            }
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(overlayColor)
                    .clickable(enabled = false) {} // 拦截点击事件
            )
            
            // 按钮组
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 连接按钮
                Box(
                    modifier = Modifier
                        .width(200.dp)
                        .height(50.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(onClick = onDeviceSelect)
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFF4A90E2),
                                    Color(0xFF357ABD)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Bluetooth,
                            contentDescription = "连接设备",
                            modifier = Modifier.size(22.dp),
                            tint = Color.White
                        )
                        Text(
                            text = "连接设备",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 16.sp
                        )
                    }
                }

                // 取消按钮
                Box(
                    modifier = Modifier
                        .width(200.dp)
                        .height(50.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(onClick = { isMaskDismissed = true })
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "取消",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp
                    )
                }
            }
        }
        
        // 断开连接确认对话框
        if (showDisconnectConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDisconnectConfirmDialog = false },
                title = { Text("断开连接") },
                text = { Text("确定要断开与设备的连接吗？") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDisconnectConfirmDialog = false
                            onDisconnectDevice()
                        }
                    ) {
                        Text("确认断开")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDisconnectConfirmDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }

    }
}

@Composable
private fun WeatherInsightsFullPage(
    insights: com.example.pandatemperature.data.weather.insights.WeatherInsightsResult,
    records24h: List<com.example.pandatemperature.data.model.TemperatureRecord>,
    connectionState: BleManager.ConnectionState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 手势/系统返回时退回首页，不退出应用
    BackHandler { onBack() }

    // 覆盖在首页之上，视觉上相当于一个“新页面”
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部栏
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = statusBarHeight)
                        .height(56.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                    Text(
                        text = "气象数据分析（24h推算）",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .padding(end = 6.dp), // 避免系统滚动条压在卡片边缘，消除“内部卡片有滚动条”的观感
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 与首页一致的面积图（温/湿/压 双 Y 轴面积图）
                TrendChartCard(
                    records = records24h,
                    connectionState = connectionState,
                    modifier = Modifier.fillMaxWidth()
                )

                WeatherInsightsSection(
                    insights = insights,
                    showScopeLabel = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

/**
 * 历史数据TAB内容
 */
@Composable
private fun HistoryTabContent(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    HistoryScreen(
        viewModel = viewModel,
        onBack = {}, // 作为TAB内容，不需要返回按钮
        modifier = modifier
    )
}
