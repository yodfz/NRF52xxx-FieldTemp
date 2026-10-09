package com.example.pandatemperature.ui.viewmodel

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.pandatemperature.data.bluetooth.BleConstants
import com.example.pandatemperature.data.bluetooth.BleFrameDiagnostics
import com.example.pandatemperature.data.bluetooth.BleManager
import com.example.pandatemperature.data.database.AppDatabase
import com.example.pandatemperature.data.database.dao.DeviceDao
import com.example.pandatemperature.data.database.dao.TemperatureRecordDao
import com.example.pandatemperature.data.model.*
import com.example.pandatemperature.data.model.HistoryProgress
import com.example.pandatemperature.data.device.model.DeviceTypes
import com.example.pandatemperature.data.device.model.resolveDisplayedBatteryVoltage
import com.example.pandatemperature.data.device.profile.DeviceProfileFactory
import com.example.pandatemperature.data.device.parser.RetentionRequestBuilder
import com.example.pandatemperature.data.device.profile.thermometer.ThermometerProfile
import com.example.pandatemperature.data.device.parser.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.example.pandatemperature.data.location.LocationManager
import com.example.pandatemperature.service.BleConnectionForegroundService
import com.example.pandatemperature.data.weather.HistoryEvaluationResult
import com.example.pandatemperature.data.weather.WapsResult
import com.example.pandatemperature.data.weather.WapsMonitor
import com.example.pandatemperature.data.weather.insights.WeatherInsightsResult
import com.example.pandatemperature.utils.calculateAltitude
import com.example.pandatemperature.utils.showStormWarningNotification
import com.example.pandatemperature.utils.evaluateHistorySegment
import com.example.pandatemperature.utils.WeatherInsightsEngine
import com.example.pandatemperature.utils.isMoving
import com.example.pandatemperature.utils.HISTORY_EVAL_WINDOW_SEC
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive

/**
 * 主 ViewModel
 * 管理应用状态和业务逻辑
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        // 蓝牙操作超时常量（毫秒）
        private const val BLE_READ_TIMEOUT_MS = 5000L      // 读取特征超时：5秒
        private const val BLE_WRITE_TIMEOUT_MS = 5000L     // 写入特征超时：5秒
        private const val HISTORY_IDLE_TIMEOUT_MS = 60_000L
        private const val BLE_NOTIFY_TIMEOUT_MS = 5000L    // 启用/禁用通知超时：5秒
        
        // 定时写入常量
        private const val GPS_WAIT_TIMEOUT_MS = 30000L     // GPS等待超时：30秒
        private const val DATA_RECORD_INTERVAL_MS = 30000L // 数据记录间隔：30秒
    }
    
    private val bleManager = BleManager.getInstance(application)
    private val database = AppDatabase.getDatabase(application)
    private val recordDao: TemperatureRecordDao = database.temperatureRecordDao()
    private val deviceDao: DeviceDao = database.deviceDao()
    private val locationManager = LocationManager.getInstance(application)
    
    // 连接状态
    val connectionState: StateFlow<BleManager.ConnectionState> = bleManager.connectionState
    val deviceName: StateFlow<String?> = bleManager.deviceName
    val deviceAddress: StateFlow<String?> = bleManager.deviceAddress
    
    // 已保存的设备
    val savedDevices: Flow<List<Device>> = deviceDao.getAllDevices()
    
    // 扫描到的设备地址集合（用于判断在线状态）
    private val _scannedDevices = MutableStateFlow<Set<String>>(emptySet())
    
    // 在线设备集合（扫描到的 + 当前连接的）
    val onlineDevices: Flow<Set<String>> = combine(_scannedDevices, deviceAddress) { scanned, connected ->
        if (connected != null) scanned + connected else scanned
    }
    
    // 扫描到的设备列表（用于UI显示新设备）
    private val _scannedDeviceList = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scannedDeviceList: StateFlow<List<BluetoothDevice>> = _scannedDeviceList.asStateFlow()
    
    // 是否显示设备选择对话框
    private val _showDeviceDialog = MutableStateFlow(false)
    val showDeviceDialog: StateFlow<Boolean> = _showDeviceDialog.asStateFlow()
    
    // 扫描状态
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    
    // 自动停止扫描的任务
    private var scanTimeoutJob: Job? = null

    // 每次 ViewModel 生命周期只允许一次启动自动连接；手动连接不走此标记。
    private var startupAutoConnectAttempted = false

    // 当前正在查看历史记录的设备地址（默认为当前连接设备，断开后保留）
    private val _viewingDeviceAddress = MutableStateFlow<String?>(null)
    
    // 实时数据（v1.1 更新：通过实时数据服务一次性获取）
    private val _temperature = MutableStateFlow<Float?>(null)
    val temperature: StateFlow<Float?> = _temperature.asStateFlow()
    
    private val _humidity = MutableStateFlow<Float?>(null)
    val humidity: StateFlow<Float?> = _humidity.asStateFlow()
    
    private val _pressure = MutableStateFlow<Float?>(null)  // ⭐ v1.1 新增：实时气压（hPa）
    val pressure: StateFlow<Float?> = _pressure.asStateFlow()
    
    private val _maxTemperature = MutableStateFlow<Float?>(null)
    val maxTemperature: StateFlow<Float?> = _maxTemperature.asStateFlow()
    
    private val _minTemperature = MutableStateFlow<Float?>(null)
    val minTemperature: StateFlow<Float?> = _minTemperature.asStateFlow()
    
    // 协议 v1.2：最高最低温度时间戳（Unix秒）
    private val _maxTemperatureTimestamp = MutableStateFlow<Long?>(null)
    val maxTemperatureTimestamp: StateFlow<Long?> = _maxTemperatureTimestamp.asStateFlow()
    
    private val _minTemperatureTimestamp = MutableStateFlow<Long?>(null)
    val minTemperatureTimestamp: StateFlow<Long?> = _minTemperatureTimestamp.asStateFlow()

    // 最高最低温度特性是否支持（用于兼容不同固件版本）
    private val _isMaxMinTempSupported = MutableStateFlow(false)
    val isMaxMinTempSupported: StateFlow<Boolean> = _isMaxMinTempSupported.asStateFlow()
    
    // 最高最低温度首次读取失败，等待历史同步结束后重试；重试仍失败时需用户手动同步
    private var maxMinTempReadFailed = false
    private val _maxMinTempNeedManualSync = MutableStateFlow(false)
    val maxMinTempNeedManualSync: StateFlow<Boolean> = _maxMinTempNeedManualSync.asStateFlow()
    
    // 当前设备配置（根据固件版本自动选择）
    private var currentDeviceProfile: ThermometerProfile? = null
    
    // 电池电量（v1.1 新增）
    private val _batteryVoltage = MutableStateFlow<Float?>(null)
    val batteryVoltage: StateFlow<Float?> = _batteryVoltage.asStateFlow()
    
    private val _batteryPercent = MutableStateFlow<Float?>(null)
    val batteryPercent: StateFlow<Float?> = _batteryPercent.asStateFlow()
    
    private val _lastUpdateTime = MutableStateFlow<Long?>(null)
    val lastUpdateTime: StateFlow<Long?> = _lastUpdateTime.asStateFlow()
    
    // 加载状态
    private val _isLoadingRealtimeData = MutableStateFlow(false)  // ⭐ v1.1 更新：统一加载状态
    val isLoadingRealtimeData: StateFlow<Boolean> = _isLoadingRealtimeData.asStateFlow()
    
    // 兼容旧接口（已废弃，保留用于向后兼容）
    val isLoadingTemperature: StateFlow<Boolean> = _isLoadingRealtimeData
    val isLoadingHumidity: StateFlow<Boolean> = _isLoadingRealtimeData
    
    // 设备配置
    private val _interval = MutableStateFlow<Int?>(null)
    val interval: StateFlow<Int?> = _interval.asStateFlow()
    
    // 设备状态
    private val _deviceStatus = MutableStateFlow<DeviceStatus?>(null)
    val deviceStatus: StateFlow<DeviceStatus?> = _deviceStatus.asStateFlow()
    
    // 历史数据
    @OptIn(ExperimentalCoroutinesApi::class)
    val historyRecords: Flow<List<TemperatureRecord>> = _viewingDeviceAddress.flatMapLatest { address ->
        if (address != null) {
            recordDao.getAllRecords(address)
        } else {
            flowOf(emptyList())
        }
    }
    
    // 首页历史数据（只显示最新20条）
    @OptIn(ExperimentalCoroutinesApi::class)
    val homeHistoryRecords: Flow<List<TemperatureRecord>> = _viewingDeviceAddress.flatMapLatest { address ->
        if (address != null) {
            recordDao.getLatestRecordsFlow(address, 20)
        } else {
            flowOf(emptyList())
        }
    }
    
    // 历史数据详情页面的数据
    private val _historyDetailRecords = MutableStateFlow<List<TemperatureRecord>>(emptyList())
    val historyDetailRecords: StateFlow<List<TemperatureRecord>> = _historyDetailRecords.asStateFlow()
    
    // 最近24小时的数据
    private val _last24HoursRecords = MutableStateFlow<List<TemperatureRecord>>(emptyList())
    val last24HoursRecords: StateFlow<List<TemperatureRecord>> = _last24HoursRecords.asStateFlow()
    
    // 历史数据分页相关
    private val _isLoadingMoreHistory = MutableStateFlow(false)
    val isLoadingMoreHistory: StateFlow<Boolean> = _isLoadingMoreHistory.asStateFlow()
    
    private val _hasMoreHistory = MutableStateFlow(true)
    val hasMoreHistory: StateFlow<Boolean> = _hasMoreHistory.asStateFlow()
    
    private var currentHistoryPage = 0
    private val historyPageSize = 20
    
    // 历史数据时间筛选（秒级时间戳）
    private val _filterStartTime = MutableStateFlow<Long?>(null)
    val filterStartTime: StateFlow<Long?> = _filterStartTime.asStateFlow()
    
    private val _filterEndTime = MutableStateFlow<Long?>(null)
    val filterEndTime: StateFlow<Long?> = _filterEndTime.asStateFlow()
    
    // 日志
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()
    
    // 历史数据获取状态
    private val _isFetchingHistory = MutableStateFlow(false)
    val isFetchingHistory: StateFlow<Boolean> = _isFetchingHistory.asStateFlow()
    private var isMaintainingHistory = false
    
    // 任务状态（用于显示连接后的任务进度）
    private val _taskStatus = MutableStateFlow<TaskStatus?>(null)
    val taskStatus: StateFlow<TaskStatus?> = _taskStatus.asStateFlow()
    
    private data class RetainedSync(val session: Long, val deviceId: String, val retryAttempt: Int) {
        var before: ByteArray? = null
        var after: ByteArray? = null
        var requestTimestamp = -1L
        var endReceived = false
        var savesSucceeded = true
        val timestamps = mutableSetOf<Long>()
    }
    private var retainedSync: RetainedSync? = null
    private var historyFetchDeviceId: String? = null
    private fun retainedHistoryKey(deviceId: String) = "retain_history_3000_$deviceId"
    internal fun isPersistentHistoryRetentionEnabled(deviceId: String): Boolean =
        prefs.getBoolean(retainedHistoryKey(deviceId), false)
    private var historyDataList = mutableListOf<TemperatureRecord>()
    private val historyMutex = Mutex()
    private var isHistorySubscribed = false
    
    // 历史记录信息（来自 HISTORY_INFO_CHAR）
    private var expectedHistoryTotal: Long? = null
    private var historyStartSector: Int? = null
    private var historyStartIndex: Int? = null
    
    // ⭐ v1.2 新增：暴露历史信息到UI
    private val _historyTotalRecords = MutableStateFlow<Long?>(null)
    val historyTotalRecords: StateFlow<Long?> = _historyTotalRecords.asStateFlow()
    
    // ⭐ v1.2 新增：清空数据状态
    private val _isClearingData = MutableStateFlow(false)
    val isClearingData: StateFlow<Boolean> = _isClearingData.asStateFlow()
    
    // 历史记录进度统计
    @Volatile
    private var totalHistoryReceivedCount: Int = 0
    private var recordsReceivedInSession: Int = 0
    private var historyPacketIndex: Int = 0
    private var historyPacketTotalEstimate: Int? = null
    private var historyRecordsPerPacketEstimate: Int? = null
    private var lastRequestedTimestamp: Long = 0 // 记录最后一次请求的时间戳
    
    // 历史数据接收进度状态（用于UI显示进度条）
    private val _historyProgress = MutableStateFlow<HistoryProgress?>(null)
    val historyProgress: StateFlow<HistoryProgress?> = _historyProgress.asStateFlow()
    
    // 历史数据处理通道和任务
    private val historyPacketChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var historyProcessingJob: Job? = null
    
    // 定时数据记录相关
    private var dataRecordJob: Job? = null
    private val _isRecordingData = MutableStateFlow(false)
    val isRecordingData: StateFlow<Boolean> = _isRecordingData.asStateFlow()
    
    // 定位权限状态
    private val _hasLocationPermission = MutableStateFlow(false)
    val hasLocationPermission: StateFlow<Boolean> = _hasLocationPermission.asStateFlow()
    
    // WAPS 天气预警（采样循环已提取到 WapsMonitor，这里只保留对外状态）
    private val _wapsResult = MutableStateFlow<WapsResult?>(null)
    val wapsResult: StateFlow<WapsResult?> = _wapsResult.asStateFlow()
    /** 最近一次 GPS 海拔（米），用于与气压混合计算海拔 */
    private val _lastGpsAltitudeMeters = MutableStateFlow<Double?>(null)
    val lastGpsAltitudeMeters: StateFlow<Double?> = _lastGpsAltitudeMeters.asStateFlow()
    /** 最近一次历史评估（同步完成后基于 3h 内 GPS 一致区间评估，常驻展示） */
    private val _lastHistoryEvaluationResult = MutableStateFlow<HistoryEvaluationResult?>(null)
    val lastHistoryEvaluationResult: StateFlow<HistoryEvaluationResult?> = _lastHistoryEvaluationResult.asStateFlow()

    /** 首页气象洞察（纯本地计算，无在线 API 依赖） */
    private val _weatherInsights = MutableStateFlow<WeatherInsightsResult?>(null)
    val weatherInsights: StateFlow<WeatherInsightsResult?> = _weatherInsights.asStateFlow()
    private val prefs = application.getSharedPreferences("panda_prefs", Context.MODE_PRIVATE)
    private val _weatherAlertEnabled = MutableStateFlow(prefs.getBoolean("weather_alert_enabled", true))
    val weatherAlertEnabled: StateFlow<Boolean> = _weatherAlertEnabled.asStateFlow()

    /**
     * WAPS 采样循环：实时值/开关/GPS 由 [WapsMonitor.Inputs] 注入，结果由 [WapsMonitor.Outputs] 回收，
     * 本类只负责随连接生命周期启停（见 [startWaps] / [stopWaps]）。
     */
    private val wapsMonitor = WapsMonitor(
        inputs = WapsMonitor.Inputs(
            alertEnabled = { _weatherAlertEnabled.value },
            deviceReady = { connectionState.value == BleManager.ConnectionState.ServicesDiscovered },
            pressureHpa = { _pressure.value },
            temperatureC = { _temperature.value },
            location = {
                if (_hasLocationPermission.value) {
                    locationManager.getCachedLocation() ?: locationManager.getCurrentLocation(5000)
                } else null
            }
        ),
        outputs = WapsMonitor.Outputs(
            onResult = { _wapsResult.value = it },
            onGpsAltitudeMeters = { _lastGpsAltitudeMeters.value = it },
            onStormAlert = { showStormWarningNotification(getApplication(), it) }
        )
    )
    
    // Snackbar 消息反馈
    data class SnackbarData(
        val message: String,
        val type: SnackbarType = SnackbarType.INFO
    )
    
    enum class SnackbarType {
        SUCCESS,  // 成功 - 绿色，勾选图标
        ERROR,    // 错误 - 红色，错误图标
        INFO      // 信息 - 默认色，信息图标
    }
    
    private val _snackbarMessage = MutableStateFlow<SnackbarData?>(null)
    val snackbarMessage: StateFlow<SnackbarData?> = _snackbarMessage.asStateFlow()
    
    /**
     * 显示 Snackbar 消息
     */
    fun showSnackbar(message: String, type: SnackbarType = SnackbarType.INFO) {
        _snackbarMessage.value = SnackbarData(message, type)
    }
    
    /**
     * 清除 Snackbar 消息
     */
    fun clearSnackbar() {
        _snackbarMessage.value = null
    }
    
    fun setWeatherAlertEnabled(enabled: Boolean) {
        _weatherAlertEnabled.value = enabled
        prefs.edit().putBoolean("weather_alert_enabled", enabled).apply()
        if (!enabled) {
            stopWaps()
            _wapsResult.value = null
            _lastGpsAltitudeMeters.value = null
        }
    }
    
    private val historySession = HistorySyncSession()
    private var historyFetchJob: Job? = null
    private var historyFinishJob: Job? = null

    init {
        try {
            // 监听连接状态变化
            viewModelScope.launch {
                try {
                    connectionState.collect { state ->
                        when (state) {
                            BleManager.ConnectionState.ServicesDiscovered -> {
                                addLog("设备连接成功，服务已发现", LogType.SUCCESS)
                                
                                // ⭐ 关键修复：连接成功时，设置当前查看的设备地址
                                _viewingDeviceAddress.value = bleManager.deviceAddress.value
                                
                                // 启动前台服务，熄屏/后台时降低被系统杀进程概率
                                BleConnectionForegroundService.start(
                                    getApplication(),
                                    bleManager.deviceName.value
                                )
                                
                                // 自动执行初始化操作
                                initializeDevice()
                            }
                            BleManager.ConnectionState.Disconnected -> {
                                addLog("设备已断开连接", LogType.INFO)
                                BleConnectionForegroundService.stop(getApplication())
                                // 清理状态
                                resetConnectionState()
                            }
                            else -> {}
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "连接状态监听错误", e)
                }
            }
            
            // 移除 loadLocalHistory() 调用，历史数据现在通过 Flow 自动加载
        } catch (e: Exception) {
            Log.e("MainViewModel", "ViewModel 初始化错误", e)
        }
    }
    
    /**
     * 添加日志
     */
    private fun addLog(message: String, type: LogType = LogType.INFO) {
        val log = LogEntry(message = message, type = type)
        _logs.value = (_logs.value + log).takeLast(100) // 只保留最近100条
        Log.d("MainViewModel", "[${type.name}] $message")
    }
    
    /**
     * 打开设备选择对话框
     */
    fun openDeviceDialog() {
        _showDeviceDialog.value = true
        // 打开对话框时自动开始扫描（用于更新在线状态）
        startScan()
    }
    
    /**
     * 关闭设备选择对话框
     */
    fun closeDeviceDialog() {
        _showDeviceDialog.value = false
        stopScan()
    }

    /**
     * 扫描设备（内部使用）
     */
    private fun startScan() {
        if (!bleManager.isBluetoothAvailable()) {
            addLog("蓝牙未启用", LogType.ERROR)
            return
        }
        
        if (_isScanning.value) return
        
        _isScanning.value = true
        
        // 清除旧的扫描结果
        _scannedDevices.value = emptySet()
        _scannedDeviceList.value = emptyList()
        
        // 移除自动停止：用户在"添加新设备"界面时持续扫描，直到退出
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        
        addLog("开始扫描设备...", LogType.INFO)
        bleManager.startScan { device ->
            val name = device.name ?: device.address
            
            // 更新扫描到的设备集合（用于在线状态）
            val currentSet = _scannedDevices.value.toMutableSet()
            if (currentSet.add(device.address)) {
                _scannedDevices.value = currentSet
            }
            
            // 更新扫描到的设备列表（用于UI显示）
            // 检查是否已存在
            val currentList = _scannedDeviceList.value.toMutableList()
            if (currentList.none { it.address == device.address }) {
                currentList.add(device)
                _scannedDeviceList.value = currentList
            }
            
            addLog("发现设备: $name", LogType.INFO)
        }
    }
    
    /**
     * 扫描设备（公开方法）
     */
    fun startScanPublic() {
        startScan()
    }

    /**
     * 停止扫描
     */
    fun stopScan() {
        if (!_isScanning.value) return
        
        bleManager.stopScan()
        _isScanning.value = false
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        
        addLog("停止扫描", LogType.INFO)
    }
    
    /**
     * 保存设备
     */
    fun saveDevice(device: BluetoothDevice) {
        viewModelScope.launch {
            val name = device.name ?: "未知设备"
            val existing = deviceDao.getDevice(device.address)
            val newDevice = Device(
                macAddress = device.address,
                name = name,
                type = existing?.type ?: DeviceType.THERMOMETER,
                createTime = existing?.createTime ?: System.currentTimeMillis(),
                firmwareVersion = existing?.firmwareVersion,
                nickname = existing?.nickname,
                latestBatteryVoltage = existing?.latestBatteryVoltage
            )
            deviceDao.insertDevice(newDevice)
            // addLog("已保存设备: $name", LogType.SUCCESS) // 减少日志干扰
        }
    }
    
    /**
     * 删除设备
     */
    fun removeDevice(device: Device) {
        viewModelScope.launch {
            deviceDao.deleteDevice(device)
            addLog("已删除设备: ${device.name}", LogType.INFO)
        }
    }

    /**
     * App 启动且蓝牙权限/开关就绪后调用一次：连接添加时间最新的已保存设备。
     * 没有已保存设备时才打开原有设备选择流程；自动连接本身不触发扫描弹窗。
     */
    fun autoConnectLatestSavedDevice() {
        if (startupAutoConnectAttempted) return
        startupAutoConnectAttempted = true

        viewModelScope.launch {
            val latestDevice = withContext(Dispatchers.IO) {
                deviceDao.getLatestDevice()
            }
            if (latestDevice == null) {
                openDeviceDialog()
                return@launch
            }

            _viewingDeviceAddress.value = latestDevice.macAddress
            _taskStatus.value = TaskStatus("正在连接中...", 0, 0)
            addLog("正在自动连接最近设备: ${latestDevice.name}...", LogType.INFO)
            val success = bleManager.connect(latestDevice.macAddress)
            if (!success) {
                addLog("自动连接失败，请手动选择设备", LogType.ERROR)
                _taskStatus.value = null
                // 保留原有入口；首页断开遮罩仍可让用户主动打开选择流程。
            }
        }
    }

    /**
     * 连接设备
     */
    fun connectDevice(device: BluetoothDevice) {
        viewModelScope.launch {
            // 关闭对话框
            closeDeviceDialog()
            
            // 立即设置正在查看的设备，以便显示历史数据
            _viewingDeviceAddress.value = device.address

            // 连接时自动保存设备
            saveDevice(device)
            
            _taskStatus.value = TaskStatus("正在连接中...", 0, 0)
            addLog("正在连接设备: ${device.name ?: device.address}...", LogType.INFO)
            val success = bleManager.connect(device)
            if (!success) {
                addLog("连接失败", LogType.ERROR)
                _taskStatus.value = null
            }
        }
    }

    /**
     * 通过地址连接设备
     */
    fun connectDevice(address: String) {
        viewModelScope.launch {
            // 关闭对话框
            closeDeviceDialog()
            
            // 立即设置正在查看的设备，以便显示历史数据
            _viewingDeviceAddress.value = address

            _taskStatus.value = TaskStatus("正在连接中...", 0, 0)
            addLog("正在连接设备: $address...", LogType.INFO)
            val success = bleManager.connect(address)
            if (!success) {
                addLog("连接失败", LogType.ERROR)
                _taskStatus.value = null
            }
        }
    }
    
    /**
     * 断开连接
     */
    fun disconnectDevice() {
        bleManager.disconnect()
        addLog("已断开连接", LogType.INFO)
    }
    
    /**
     * 初始化设备（连接成功后自动执行）
     */
    private fun initializeDevice() {
        viewModelScope.launch {
            try {
                // 兼容老固件：可能没有实现最高最低温度特性
                val supportsMaxMinTemp = bleManager.isCharacteristicAvailable(BleConstants.MAX_MIN_TEMP_CHAR)
                _isMaxMinTempSupported.value = supportsMaxMinTemp

                val totalSteps = if (supportsMaxMinTemp) 7 else 6
                var step = 0
                fun setStep(message: String) {
                    step += 1
                    _taskStatus.value = TaskStatus(message, step, totalSteps)
                }
                addLog("开始自动初始化...", LogType.INFO)
                
                // 等待服务完全就绪
                kotlinx.coroutines.delay(200)

                // 0. 请求 MTU（优先执行，确保大数据包传输效率）
                setStep("正在优化连接(MTU)")
                val mtuSuccess = requestMtu()
                if (!mtuSuccess) {
                    addLog("MTU 请求失败，传输效率可能受限", LogType.INFO)
                }
                kotlinx.coroutines.delay(200)
                
                // 1. 同步时间（等待完成）
                setStep("正在同步时间")
                addLog("正在同步设备时间...", LogType.INFO)
                val timeSyncSuccess = syncTime()
                if (!timeSyncSuccess) {
                    addLog("时间同步失败，但继续执行其他初始化操作", LogType.ERROR)
                }
                kotlinx.coroutines.delay(300)
                
                // 2. 读取采集间隔
                setStep("正在同步配置")
                readInterval()
                kotlinx.coroutines.delay(300)
                
                // 3. 读取设备状态（并根据固件版本创建 Profile）
                setStep("正在读取设备状态")
                val firmwareVersion = readStatusAndGetFirmwareVersion()
                kotlinx.coroutines.delay(300)
                
                // ⭐ 根据固件版本创建设备配置
                currentDeviceProfile = DeviceProfileFactory.createThermometerProfile(
                    firmwareVersion = firmwareVersion,
                    capabilityFlags = _deviceStatus.value?.capabilityFlags ?: 0
                )
                val profileType = when {
                    _deviceStatus.value?.supportsHistoryVoltage == true -> "V3(含电压历史)"
                    currentDeviceProfile?.usesCombinedRealtimeData == true -> "V2(新固件)"
                    else -> "V1(老固件)"
                }
                addLog("已选择设备配置: $profileType", LogType.INFO)
                kotlinx.coroutines.delay(200)
                
                // 4. 读取一次实时数据（根据 Profile 选择读取方式）
                setStep("正在同步实时数据")
                readRealtimeData()
                kotlinx.coroutines.delay(800) // 增加延迟以适应可能的重试（最大重试3次）
                
                // 5. 读取最高最低温度（仅在支持时执行，只读取一次，不订阅通知）
                if (supportsMaxMinTemp) {
                    setStep("正在读取最高最低温度")
                    val ok = readMaxMinTemperatureSync()
                    if (!ok) maxMinTempReadFailed = true
                    kotlinx.coroutines.delay(800) // 增加延迟以适应可能的重试
                } else {
                    addLog("设备不支持最高最低温度特性，已跳过", LogType.INFO)
                }
                
                // 订阅实时数据
                setStep("正在订阅实时数据")
                subscribeRealtimeData()
                kotlinx.coroutines.delay(500)
                
                // 自动同步历史数据（增量获取）
                setStep("正在同步历史数据")
                addLog("开始自动同步历史数据...", LogType.INFO)
                fetchHistory()
                
                // 注意：任务状态会在 finishHistoryFetch() 中清除，这里不需要清除
                
                addLog("自动初始化完成", LogType.SUCCESS)
                
                // ⭐ 启动定时数据记录
                startDataRecording()
                // ⭐ 启动 WAPS 天气预警（仅当开关打开且设备支持气压时由循环内判断）
                if (_weatherAlertEnabled.value) startWaps()
            } catch (e: Exception) {
                addLog("初始化失败: ${e.message}", LogType.ERROR)
                _taskStatus.value = null
            }
        }
    }
    
    /**
     * 请求 MTU
     */
    private suspend fun requestMtu(): Boolean {
        return withContext(Dispatchers.Default) {
            val deferred = CompletableDeferred<Boolean>()
            bleManager.requestMtu(247) { success, mtu ->
                if (success) {
                    addLog("MTU 已更新为: $mtu", LogType.SUCCESS)
                }
                deferred.complete(success)
            }
            // 等待结果，超时2秒
            try {
                withTimeout(2000) {
                    deferred.await()
                }
            } catch (e: Exception) {
                // 超时或出错
                false
            }
        }
    }

    /**
     * 重置连接状态
     */
    private fun resetConnectionState() {
        _temperature.value = null
        _humidity.value = null
        _pressure.value = null  // ⭐ v1.1 新增
        _lastGpsAltitudeMeters.value = null
        _maxTemperature.value = null
        _minTemperature.value = null
        _maxTemperatureTimestamp.value = null  // 协议 v1.2
        _minTemperatureTimestamp.value = null  // 协议 v1.2
        _isMaxMinTempSupported.value = false
        maxMinTempReadFailed = false
        _maxMinTempNeedManualSync.value = false
        _batteryVoltage.value = null
        _batteryPercent.value = null
        _lastUpdateTime.value = null
        _interval.value = null
        _deviceStatus.value = null
        _isLoadingRealtimeData.value = false
        _taskStatus.value = null
        isHistorySubscribed = false
        // 断开后清空诊断去重状态，使下一次连接重新报告一次基线帧长。
        BleFrameDiagnostics.reset()
        // 重置设备配置
        currentDeviceProfile = null
        // 重置历史进度相关状态
        historySession.invalidate()
        historyFetchJob?.cancel()
        historyFetchJob = null
        historyFinishJob?.cancel()
        historyFinishJob = null
        _isFetchingHistory.value = false
        expectedHistoryTotal = null
        historyStartSector = null
        historyStartIndex = null
        totalHistoryReceivedCount = 0
        historyPacketIndex = 0
        historyPacketTotalEstimate = null
        historyRecordsPerPacketEstimate = null
        // 清除进度状态
        _historyProgress.value = null
        // ⭐ v1.2 新增：重置历史总数和清空状态
        _historyTotalRecords.value = null
        _isClearingData.value = false
        
        // 清理历史数据处理任务
        historyProcessingJob?.cancel()
        historyProcessingJob = null
        // 清空通道
        while (historyPacketChannel.tryReceive().isSuccess) {}
        
        // ⭐ 停止定时数据记录
        stopDataRecording()
        // 停止 WAPS 天气预警
        stopWaps()
        wapsMonitor.clear()
        _lastHistoryEvaluationResult.value = null
    }

    /**
     * 更新实时电压状态，并异步持久化到当前设备。
     *
     * 三态语义（归约见 [resolveDisplayedBatteryVoltage]）：
     * - 帧未携带电压字段（旧 6 字节固件）：保持既有显示，也不写库；
     * - 帧携带字段但值无效（哨兵 `0xFFFF` / 超出量程）：清空为 null，界面显示 `--`；
     * - 帧携带字段且值有效：更新为最新电压。
     */
    private fun updateBatteryVoltage(voltage: Float?, reported: Boolean) {
        _batteryVoltage.value = resolveDisplayedBatteryVoltage(_batteryVoltage.value, voltage, reported)
        // 未携带电压字段的帧没有电压信息可写库（既有约定）。
        if (!reported) return
        val address = deviceAddress.value ?: _viewingDeviceAddress.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                deviceDao.updateLatestBatteryVoltage(address, voltage)
            } catch (e: Exception) {
                Log.w("MainViewModel", "保存设备电压失败", e)
            }
        }
    }
    
    /**
     * 读取实时数据（根据设备配置自动选择读取方式）
     * - 新固件（V2）：使用 REALTIME_DATA_CHAR 一次性读取温湿度+气压
     * - 老固件（V1）：使用 TEMP_CHAR + HUMIDITY_CHAR 分别读取
     */
    fun readRealtimeData() {
        viewModelScope.launch {
            try {
                _isLoadingRealtimeData.value = true
                
                val profile = currentDeviceProfile
                
                // 根据 Profile 选择读取方式
                if (profile?.usesCombinedRealtimeData == true) {
                    // 新固件：使用合并的实时数据特征
                    readRealtimeDataV2()
                } else {
                    // 老固件：分别读取温度和湿度
                    readRealtimeDataV1()
                }
                
                _isLoadingRealtimeData.value = false
            } catch (e: Exception) {
                _isLoadingRealtimeData.value = false
                addLog("读取实时数据失败: ${e.message}", LogType.ERROR)
            }
        }
    }
    
    /**
     * 读取实时数据 - 新固件（V2）方式
     * 使用合并的 REALTIME_DATA_CHAR 特征
     */
    private suspend fun readRealtimeDataV2() {
        val parser = RealtimeDataParserV2()
        
        // 重试机制：最多尝试3次
        var retryCount = 0
        val maxRetries = 3
        var success = false
        
        while (retryCount < maxRetries && !success) {
            if (retryCount > 0) {
                kotlinx.coroutines.delay(200)
            }
            
            val deferred = CompletableDeferred<Boolean>()
            bleManager.readCharacteristic(BleConstants.REALTIME_DATA_CHAR) { data ->
                if (data != null) {
                    // 旁路诊断：与订阅路径共用同一去重通道，避免重复输出。
                    BleFrameDiagnostics.observeRealtime(
                        data = data,
                        minLength = RealtimeDataParserV2.FRAME_SIZE_BASE,
                        voltageOffset = RealtimeDataParserV2.BATTERY_VOLTAGE_OFFSET
                    )?.let { addLog(it, LogType.INFO) }

                    val realtimeData = parser.parse(data)
                    if (realtimeData != null) {
                        _temperature.value = realtimeData.temperature
                        _humidity.value = realtimeData.humidity
                        _pressure.value = realtimeData.pressure
                        updateBatteryVoltage(realtimeData.batteryVoltage, realtimeData.batteryVoltageReported)
                        _lastUpdateTime.value = System.currentTimeMillis()
                        
                        val tempStr = realtimeData.temperature?.let { String.format("%.2f", it) } ?: "N/A"
                        val humStr = realtimeData.humidity?.let { String.format("%.2f", it) } ?: "N/A"
                        val pressStr = realtimeData.pressure?.let { String.format("%.1f", it) } ?: "N/A"
                        val voltageStr = realtimeData.batteryVoltage?.let { " / ${String.format("%.1f", it)}V" } ?: ""
                        addLog("读取实时数据(V2): ${tempStr}°C / ${humStr}% / ${pressStr}hPa$voltageStr", LogType.SUCCESS)
                        deferred.complete(true)
                    } else {
                        deferred.complete(false)
                    }
                } else {
                    deferred.complete(false)
                }
            }
            
            success = deferred.await()
            if (!success) {
                retryCount++
            }
        }
        
        if (!success) {
            addLog("读取实时数据失败: 数据无效或超时", LogType.ERROR)
        }
    }
    
    /**
     * 读取实时数据 - 老固件（V1）方式
     * 分别读取温度和湿度特征
     */
    private suspend fun readRealtimeDataV1() {
        val parser = RealtimeDataParserV1()
        
        // 读取温度
        var temperature: Float? = null
        var humidity: Float? = null
        
        // 重试机制：最多尝试3次
        var retryCount = 0
        val maxRetries = 3
        
        // 读取温度
        while (retryCount < maxRetries && temperature == null) {
            if (retryCount > 0) {
                kotlinx.coroutines.delay(200)
            }
            
            val deferred = CompletableDeferred<Float?>()
            bleManager.readCharacteristic(BleConstants.TEMP_CHAR) { data ->
                if (data != null) {
                    deferred.complete(parser.parseTemperature(data))
                } else {
                    deferred.complete(null)
                }
            }
            temperature = deferred.await()
            retryCount++
        }
        
        // 读取湿度
        retryCount = 0
        while (retryCount < maxRetries && humidity == null) {
            if (retryCount > 0) {
                kotlinx.coroutines.delay(200)
            }
            
            val deferred = CompletableDeferred<Float?>()
            bleManager.readCharacteristic(BleConstants.HUMIDITY_CHAR) { data ->
                if (data != null) {
                    deferred.complete(parser.parseHumidity(data))
                } else {
                    deferred.complete(null)
                }
            }
            humidity = deferred.await()
            retryCount++
        }
        
        // 更新状态
        if (temperature != null || humidity != null) {
            _temperature.value = temperature
            _humidity.value = humidity
            _pressure.value = null  // 老固件不支持气压
            _lastUpdateTime.value = System.currentTimeMillis()
            
            val tempStr = temperature?.let { String.format("%.2f", it) } ?: "N/A"
            val humStr = humidity?.let { String.format("%.2f", it) } ?: "N/A"
            addLog("读取实时数据(V1): ${tempStr}°C / ${humStr}%", LogType.SUCCESS)
        } else {
            addLog("读取实时数据失败: 数据无效或超时", LogType.ERROR)
        }
    }
    
    /**
     * 读取温度（已废弃，保留用于向后兼容）
     * @deprecated 使用 readRealtimeData() 替代
     */
    @Deprecated("使用 readRealtimeData() 替代", ReplaceWith("readRealtimeData()"))
    fun readTemperature() {
        readRealtimeData()
    }
    
    /**
     * 读取湿度（已废弃，保留用于向后兼容）
     * @deprecated 使用 readRealtimeData() 替代
     */
    @Deprecated("使用 readRealtimeData() 替代", ReplaceWith("readRealtimeData()"))
    fun readHumidity() {
        readRealtimeData()
    }
    
    /**
     * 内部：同步读取最高最低温度（带重试），返回是否成功。
     * 成功时会清除「需手动同步」状态。
     */
    private suspend fun readMaxMinTemperatureSync(): Boolean {
        return try {
            val parser = MaxMinTempParser()
            var retryCount = 0
            val maxRetries = 3
            var success = false
            while (retryCount < maxRetries && !success) {
                if (retryCount > 0) kotlinx.coroutines.delay(200)
                val deferred = CompletableDeferred<Boolean>()
                bleManager.readCharacteristic(BleConstants.MAX_MIN_TEMP_CHAR) { data ->
                    if (data != null) {
                        val maxMinTemp = parser.parse(data)
                        if (maxMinTemp != null) {
                            _maxTemperature.value = if (maxMinTemp.hasMaxRecord) maxMinTemp.maxTemperature else null
                            _minTemperature.value = if (maxMinTemp.hasMinRecord) maxMinTemp.minTemperature else null
                            _maxTemperatureTimestamp.value = maxMinTemp.maxTemperatureTimestamp
                            _minTemperatureTimestamp.value = maxMinTemp.minTemperatureTimestamp
                            addLog("读取最高最低温度: ${parser.formatForLog(maxMinTemp)}", LogType.SUCCESS)
                            deferred.complete(true)
                        } else {
                            deferred.complete(false)
                        }
                    } else {
                        deferred.complete(false)
                    }
                }
                success = deferred.await()
                if (!success) {
                    retryCount++
                    if (retryCount >= maxRetries) {
                        addLog("读取最高最低温度失败: 数据无效或超时", LogType.ERROR)
                    }
                }
            }
            if (success) _maxMinTempNeedManualSync.value = false
            success
        } catch (e: Exception) {
            addLog("读取最高最低温度失败: ${e.message}", LogType.ERROR)
            false
        }
    }
    
    /**
     * 读取最高最低温度（带重试机制）。成功时会清除「需手动同步」提示。
     */
    fun readMaxMinTemperature() {
        viewModelScope.launch {
            readMaxMinTemperatureSync()
        }
    }
    
    /**
     * 重置最高最低温度
     */
    fun resetMaxMinTemperature() {
        viewModelScope.launch {
            try {
                // 兼容老固件：可能没有实现重置特性
                if (!bleManager.isCharacteristicAvailable(BleConstants.RESET_MAX_MIN_TEMP_CHAR)) {
                    addLog("设备不支持重置最高最低温度特性，已忽略", LogType.INFO)
                    showSnackbar("设备不支持重置最高最低温度功能", SnackbarType.INFO)
                    return@launch
                }

                val success = bleManager.writeCharacteristic(
                    BleConstants.RESET_MAX_MIN_TEMP_CHAR,
                    byteArrayOf(0x00)
                )
                if (success) {
                    addLog("重置最高最低温度成功", LogType.SUCCESS)
                    showSnackbar("最高最低温度已重置", SnackbarType.SUCCESS)
                    // 重置后设备会自动通知，无需手动读取
                } else {
                    addLog("重置最高最低温度失败", LogType.ERROR)
                    showSnackbar("重置最高最低温度失败", SnackbarType.ERROR)
                }
            } catch (e: Exception) {
                addLog("重置最高最低温度失败: ${e.message}", LogType.ERROR)
                showSnackbar("重置最高最低温度失败", SnackbarType.ERROR)
            }
        }
    }
    
    /**
     * ⭐ v1.2 新增：清空设备所有历史数据
     * 写入任意值到 CLEAR_DATA_CHAR 触发清空（异步执行）
     */
    fun clearAllDeviceData() {
        viewModelScope.launch {
            try {
                // 检查是否已连接
                if (connectionState.value != BleManager.ConnectionState.ServicesDiscovered) {
                    addLog("设备未连接，无法清空数据", LogType.ERROR)
                    showSnackbar("设备未连接，无法清空数据", SnackbarType.ERROR)
                    return@launch
                }
                
                // 检查特性是否可用
                if (!bleManager.isCharacteristicAvailable(BleConstants.CLEAR_DATA_CHAR)) {
                    addLog("设备不支持清空数据功能", LogType.ERROR)
                    showSnackbar("设备不支持清空数据功能", SnackbarType.ERROR)
                    return@launch
                }
                
                _isClearingData.value = true
                addLog("正在清空设备历史数据...", LogType.INFO)
                
                val success = bleManager.writeCharacteristic(
                    BleConstants.CLEAR_DATA_CHAR,
                    byteArrayOf(0x01)  // 写入任意值触发清空
                )
                
                if (success) {
                    addLog("清空数据命令已发送，设备正在执行清空操作", LogType.SUCCESS)
                    showSnackbar("历史数据已清空", SnackbarType.SUCCESS)
                    // 清空本地缓存的历史数据
                    _viewingDeviceAddress.value?.let { address ->
                        recordDao.deleteAll(address)
                        addLog("本地历史数据已清空", LogType.INFO)
                    }
                    // 重置历史信息
                    _historyTotalRecords.value = 0
                    expectedHistoryTotal = 0
                } else {
                    addLog("清空数据命令发送失败", LogType.ERROR)
                    showSnackbar("清空数据失败", SnackbarType.ERROR)
                }
            } catch (e: Exception) {
                addLog("清空数据失败: ${e.message}", LogType.ERROR)
                showSnackbar("清空数据失败", SnackbarType.ERROR)
            } finally {
                _isClearingData.value = false
            }
        }
    }
    
    /**
     * ⭐ v1.2 新增：检查清空数据功能是否可用
     */
    fun isClearDataAvailable(): Boolean {
        return bleManager.isCharacteristicAvailable(BleConstants.CLEAR_DATA_CHAR)
    }
    
    /**
     * 设置实时数据通知状态（根据设备配置自动选择方式）
     */
    private suspend fun setRealtimeDataNotification(enable: Boolean): Boolean {
        val profile = currentDeviceProfile
        
        return if (profile?.usesCombinedRealtimeData == true) {
            // 新固件：使用合并的实时数据特征
            setRealtimeDataNotificationV2(enable)
        } else {
            // 老固件：分别订阅温度和湿度特征
            setRealtimeDataNotificationV1(enable)
        }
    }
    
    /**
     * 设置实时数据通知 - 新固件（V2）方式
     */
    private suspend fun setRealtimeDataNotificationV2(enable: Boolean): Boolean {
        return withContext(Dispatchers.Default) {
            try {
                val parser = RealtimeDataParserV2()
                
                // 重试机制：最多尝试3次
                var retryCount = 0
                val maxRetries = 3
                var success = false
                
                while (retryCount < maxRetries && !success) {
                    if (retryCount > 0) {
                        if (enable) {
                            addLog("订阅实时数据重试中... (${retryCount}/${maxRetries})", LogType.INFO)
                        }
                        kotlinx.coroutines.delay(100)
                    }
                    
                    val deferred = CompletableDeferred<Boolean>()
                    bleManager.enableNotificationAsync(
                        uuid = BleConstants.REALTIME_DATA_CHAR,
                        enable = enable,
                        onNotification = { data ->
                            if (enable) {
                                // 旁路诊断：仅在帧长首次出现或发生变化时报告，不参与解析。
                                BleFrameDiagnostics.observeRealtime(
                                    data = data,
                                    minLength = RealtimeDataParserV2.FRAME_SIZE_BASE,
                                    voltageOffset = RealtimeDataParserV2.BATTERY_VOLTAGE_OFFSET
                                )?.let { addLog(it, LogType.INFO) }

                                val realtimeData = parser.parse(data)
                                if (realtimeData != null) {
                                    _temperature.value = realtimeData.temperature
                                    _humidity.value = realtimeData.humidity
                                    _pressure.value = realtimeData.pressure
                                    updateBatteryVoltage(realtimeData.batteryVoltage, realtimeData.batteryVoltageReported)
                                    _lastUpdateTime.value = System.currentTimeMillis()
                                }
                            }
                        },
                        onComplete = { result ->
                            deferred.complete(result)
                        }
                    )
                    
                    success = withTimeoutOrNull(BLE_NOTIFY_TIMEOUT_MS) { deferred.await() } == true
                    if (!success) {
                        retryCount++
                    }
                }
                
                if (success) {
                    if (enable) {
                        addLog("已订阅实时数据(V2)（温度+湿度+气压）", LogType.SUCCESS)
                    } else {
                        addLog("已暂停实时数据订阅", LogType.INFO)
                    }
                } else {
                    if (enable) {
                        addLog("订阅实时数据失败，已达最大重试次数", LogType.ERROR)
                    } else {
                        addLog("暂停实时数据订阅失败", LogType.ERROR)
                    }
                }
                success
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog("设置实时数据通知失败: ${e.message}", LogType.ERROR)
                false
            }
        }
    }
    
    /**
     * 设置实时数据通知 - 老固件（V1）方式
     * 分别订阅温度和湿度特征
     */
    private suspend fun setRealtimeDataNotificationV1(enable: Boolean): Boolean {
        return withContext(Dispatchers.Default) {
            try {
                val tempParser = TemperatureParser()
                val humParser = HumidityParser()
                
                var tempSuccess = false
                var humSuccess = false
                
                // 订阅温度特征
                val tempDeferred = CompletableDeferred<Boolean>()
                bleManager.enableNotificationAsync(
                    uuid = BleConstants.TEMP_CHAR,
                    enable = enable,
                    onNotification = { data ->
                        if (enable) {
                            val temp = tempParser.parse(data)
                            if (temp != null) {
                                _temperature.value = temp
                                _lastUpdateTime.value = System.currentTimeMillis()
                            }
                        }
                    },
                    onComplete = { result ->
                        tempDeferred.complete(result)
                    }
                )
                tempSuccess = withTimeoutOrNull(BLE_NOTIFY_TIMEOUT_MS) { tempDeferred.await() } == true
                
                kotlinx.coroutines.delay(100)  // 等待一下再订阅湿度
                
                // 订阅湿度特征
                val humDeferred = CompletableDeferred<Boolean>()
                bleManager.enableNotificationAsync(
                    uuid = BleConstants.HUMIDITY_CHAR,
                    enable = enable,
                    onNotification = { data ->
                        if (enable) {
                            val hum = humParser.parse(data)
                            if (hum != null) {
                                _humidity.value = hum
                                _lastUpdateTime.value = System.currentTimeMillis()
                            }
                        }
                    },
                    onComplete = { result ->
                        humDeferred.complete(result)
                    }
                )
                humSuccess = withTimeoutOrNull(BLE_NOTIFY_TIMEOUT_MS) { humDeferred.await() } == true
                
                val success = tempSuccess && humSuccess
                
                if (success) {
                    if (enable) {
                        addLog("已订阅实时数据(V1)（温度+湿度）", LogType.SUCCESS)
                    } else {
                        addLog("已暂停实时数据订阅", LogType.INFO)
                    }
                } else {
                    if (enable) {
                        addLog("订阅实时数据部分失败: 温度=${tempSuccess}, 湿度=${humSuccess}", LogType.ERROR)
                    } else {
                        addLog("暂停实时数据订阅部分失败", LogType.ERROR)
                    }
                }
                success
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog("设置实时数据通知失败: ${e.message}", LogType.ERROR)
                false
            }
        }
    }

    /**
     * 订阅实时数据（根据设备配置自动选择方式）
     * 注意：最高最低温度不订阅通知，只在连接时读取一次
     */
    fun subscribeRealtimeData() {
        viewModelScope.launch {
            try {
                // 在订阅之前，如果没有任何数据，先手动读取一次
                if (_temperature.value == null && _humidity.value == null) {
                    addLog("订阅前无数据，先手动读取一次...", LogType.INFO)
                    readRealtimeData()
                    kotlinx.coroutines.delay(200)
                }
                
                setRealtimeDataNotification(true)
            } catch (e: Exception) {
                addLog("订阅实时数据失败: ${e.message}", LogType.ERROR)
            }
        }
    }
    
    /**
     * 读取历史记录间隔
     *
     * 注意：固件 v3 起 12340021 的语义是"历史记录落盘间隔"，实时采样固定 1 秒。
     * 旧固件（v2 及更早）该值表示"采集间隔"，采样与落盘共用周期。
     */
    fun readInterval() {
        viewModelScope.launch {
            try {
                bleManager.readCharacteristic(BleConstants.INTERVAL_CHAR) { data ->
                    if (data != null && data.size >= 2) {
                        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                        val interval = buffer.short.toInt() and 0xFFFF
                        _interval.value = interval
                        val label = if (_deviceStatus.value?.supportsHistoryInterval == true)
                            "历史记录间隔" else "采集间隔"
                        addLog("读取${label}: ${interval}秒", LogType.SUCCESS)
                    } else {
                        addLog("读取历史记录间隔失败: 数据无效", LogType.ERROR)
                    }
                }
            } catch (e: Exception) {
                addLog("读取历史记录间隔失败: ${e.message}", LogType.ERROR)
            }
        }
    }
    
    /**
     * 设置历史记录间隔（固件侧范围 60-3600 秒；下限是容量红线，低于 60 秒无法保证 30 天保留）
     */
    fun setInterval(interval: Int) {
        viewModelScope.launch {
            try {
                if (interval < BleConstants.HISTORY_INTERVAL_MIN || interval > BleConstants.HISTORY_INTERVAL_MAX) {
                    val msg = "历史记录间隔必须在 ${BleConstants.HISTORY_INTERVAL_MIN}-${BleConstants.HISTORY_INTERVAL_MAX} 秒之间"
                    addLog(msg, LogType.ERROR)
                    showSnackbar(msg, SnackbarType.ERROR)
                    return@launch
                }
                
                val buffer = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                buffer.putShort(interval.toShort())
                val success = bleManager.writeCharacteristic(BleConstants.INTERVAL_CHAR, buffer.array())
                
                if (success) {
                    _interval.value = interval
                    val retention = BleConstants.estimateRetentionDays(interval)
                    addLog("设置历史记录间隔: ${interval}秒（约可保留 ${retention} 天）", LogType.SUCCESS)
                    showSnackbar("历史记录间隔已设置为 ${interval}秒，约可保留 ${retention} 天", SnackbarType.SUCCESS)
                } else {
                    addLog("设置历史记录间隔失败", LogType.ERROR)
                    showSnackbar("设置历史记录间隔失败", SnackbarType.ERROR)
                }
            } catch (e: Exception) {
                addLog("设置历史记录间隔失败: ${e.message}", LogType.ERROR)
                showSnackbar("设置历史记录间隔失败: ${e.message}", SnackbarType.ERROR)
            }
        }
    }
    
    /**
     * 更新设备昵称（按蓝牙地址匹配）
     */
    fun updateDeviceNickname(address: String, nickname: String?) {
        viewModelScope.launch {
            try {
                deviceDao.updateNickname(address, nickname)
                if (nickname.isNullOrBlank()) {
                    addLog("已清除设备昵称", LogType.SUCCESS)
                    showSnackbar("已清除设备昵称", SnackbarType.SUCCESS)
                } else {
                    addLog("设备昵称已更新为: $nickname", LogType.SUCCESS)
                    showSnackbar("设备昵称已更新为: $nickname", SnackbarType.SUCCESS)
                }
            } catch (e: Exception) {
                addLog("更新设备昵称失败: ${e.message}", LogType.ERROR)
                showSnackbar("更新设备昵称失败", SnackbarType.ERROR)
            }
        }
    }
    
    /**
     * 同步时间（suspend 函数，支持等待完成和重试）
     */
    private suspend fun syncTime(): Boolean {
        return withContext(Dispatchers.Default) {
            val maxRetries = 2
            var retryCount = 0
            var success = false
            
            while (retryCount <= maxRetries && !success) {
                try {
                    if (retryCount > 0) {
                        addLog("时间同步重试中... (${retryCount}/${maxRetries})", LogType.INFO)
                        kotlinx.coroutines.delay(500) // 重试前等待500ms
                    }
                    
                    // ⭐ 修复：与WEB端保持一致，发送秒级时间戳（uint32，小端序）
                    // WEB端：const timestamp = Math.floor(Date.now() / 1000);
                    // WEB端：dataView.setUint32(0, timestamp, true);
                    val timestamp = (System.currentTimeMillis() / 1000).toLong()  // 秒级时间戳
                    val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    buffer.putInt((timestamp and 0xFFFFFFFFL).toInt())  // 作为uint32发送（小端序）
                    
                    // 使用 CompletableDeferred 等待写入完成
                    val deferred = CompletableDeferred<Boolean>()
                    bleManager.writeCharacteristic(
                        uuid = BleConstants.TIME_SYNC_CHAR,
                        value = buffer.array(),
                        onWrite = { writeSuccess ->
                            deferred.complete(writeSuccess)
                        }
                    )
                    success = deferred.await()
                    
                    // 等待写入完成确认（额外延迟确保设备处理完成）
                    kotlinx.coroutines.delay(200)
                    
                    if (success) {
                        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                            .format(java.util.Date(timestamp * 1000L))
                        addLog("时间同步成功: $dateStr", LogType.SUCCESS)
                    } else {
                        retryCount++
                        if (retryCount <= maxRetries) {
                            addLog("时间同步失败，将重试...", LogType.ERROR)
                        } else {
                            addLog("时间同步失败，已达最大重试次数", LogType.ERROR)
                        }
                    }
                } catch (e: Exception) {
                    retryCount++
                    addLog("时间同步异常: ${e.message}", LogType.ERROR)
                    if (retryCount > maxRetries) {
                        addLog("时间同步失败，已达最大重试次数", LogType.ERROR)
                    }
                }
            }
            
            success
        }
    }
    
    /**
     * 同步时间（公开方法，供外部调用）
     */
    fun syncTimePublic() {
        viewModelScope.launch {
            syncTime()
        }
    }
    
    /**
     * 读取设备状态（带重试机制）
     */
    fun readStatus() {
        viewModelScope.launch {
            readStatusAndGetFirmwareVersion()
        }
    }
    
    /**
     * 读取设备状态并返回固件版本号（用于初始化时创建 Profile）
     * @return 固件版本号，失败返回 null
     */
    private suspend fun readStatusAndGetFirmwareVersion(): Int? {
        val parser = DeviceStatusParser()
        var firmwareVersion: Int? = null
        
        try {
            // 重试机制：最多尝试3次
            var retryCount = 0
            val maxRetries = 3
            var success = false
            
            while (retryCount < maxRetries && !success) {
                if (retryCount > 0) {
                    kotlinx.coroutines.delay(200) // 重试前等待
                }
                
                val deferred = CompletableDeferred<DeviceStatus?>()
                bleManager.readCharacteristic(BleConstants.STATUS_CHAR) { data ->
                    if (data != null) {
                        deferred.complete(parser.parse(data))
                    } else {
                        deferred.complete(null)
                    }
                }
                
                val status = deferred.await()
                if (status != null) {
                    _deviceStatus.value = status
                    firmwareVersion = status.firmwareVersion
                    addLog("读取设备状态成功 - ${parser.formatForLog(status)}", LogType.SUCCESS)
                    success = true
                    
                    // ⭐ 将固件版本号持久化到设备表
                    if (firmwareVersion != null && firmwareVersion > 0) {
                        val address = deviceAddress.value
                        if (address != null) {
                            deviceDao.updateFirmwareVersion(address, firmwareVersion)
                        }
                    }
                } else {
                    retryCount++
                }
            }
            
            if (!success) {
                addLog("读取设备状态失败: 数据无效或超时", LogType.ERROR)
            }
        } catch (e: Exception) {
            addLog("读取设备状态失败: ${e.message}", LogType.ERROR)
        }
        
        return firmwareVersion
    }
    
    /**
     * 订阅设备状态
     */
    fun subscribeStatus() {
        viewModelScope.launch {
            try {
                val parser = DeviceStatusParser()
                
                // ⭐ 修复ENOMEM：使用异步版本，支持重试
                var retryCount = 0
                val maxRetries = 3
                var success = false
                
                while (retryCount < maxRetries && !success) {
                    if (retryCount > 0) {
                        addLog("订阅设备状态重试中... (${retryCount}/${maxRetries})", LogType.INFO)
                        kotlinx.coroutines.delay(100) // 100ms后重试
                    }
                    
                    val deferred = CompletableDeferred<Boolean>()
                    bleManager.enableNotificationAsync(
                        uuid = BleConstants.STATUS_CHAR,
                        enable = true,
                        onNotification = { data ->
                            val status = parser.parse(data)
                            if (status != null) {
                                _deviceStatus.value = status
                            }
                        },
                        onComplete = { result ->
                            deferred.complete(result)
                        }
                    )
                    
                    success = deferred.await()
                    if (!success) {
                        retryCount++
                    }
                }
                
                if (success) {
                    addLog("已订阅设备状态更新", LogType.SUCCESS)
                } else {
                    addLog("订阅设备状态失败，已达最大重试次数", LogType.ERROR)
                }
            } catch (e: Exception) {
                addLog("订阅设备状态失败: ${e.message}", LogType.ERROR)
            }
        }
    }
    
    /**
     * 获取当前查看设备在「本地数据库」中可作为「设备历史增量起点」的最新时间戳。
     * 只统计无 GPS 的记录（设备同步下来的）；带 GPS 的是手机定时写入的，不能作为向设备请求历史的基准。
     * @return 该设备最新一条模块历史（排除手机采样）的 timestamp；无此类记录或异常时返回 0（表示应发 0 拉全量）
     */

    private suspend fun getLastHistoryTimestamp(deviceId: String): Long {
        return withContext(Dispatchers.IO) {
            try {
                val latestFromDevice = recordDao.getLatestRecordWithoutGps(deviceId)
                val latestTimestamp = latestFromDevice?.timestamp
                val resumeTimestamp = historyResumeTimestamp(latestTimestamp, System.currentTimeMillis() / 1000)
                if (latestTimestamp != null && latestTimestamp > 0 && resumeTimestamp == 0L) {
                    addLog("本地历史存在未来时间戳，将重新全量同步，保留已有数据", LogType.INFO)
                }
                resumeTimestamp
            } catch (e: Exception) {
                Log.e("MainViewModel", "获取最后时间戳失败", e)
                0L // 出错时获取全部数据
            }
        }
    }
    
    /**
     * 获取历史数据（支持增量获取）
     * 发给设备的时间戳：0 = 全量拉取，正数 = 仅拉取该时间戳之后的记录（增量）。
     * 时间戳来源仅两种：无本地数据时发 0；有数据时发「该设备在数据库中最新的那条记录的时间戳」。
     * @param lastTimestamp 用于本次请求的起始时间戳；0 表示由内部根据本地数据库决定（无数据发 0，有数据发 DB 最新）
     * @param forceFullSync true 时强制发 0，拉取设备全部历史（用于增量失败后的全量重试）
     */
    /** Shares the history-maintenance lock; callers must release it in finally. */
    suspend fun beginExclusiveOta(): Boolean = withContext(Dispatchers.Main) {
        if (isMaintainingHistory || _isFetchingHistory.value || historyFinishJob?.isActive == true ||
            _deviceStatus.value?.isDataClearInProgress == true ||
            connectionState.value != BleManager.ConnectionState.ServicesDiscovered) return@withContext false
        isMaintainingHistory = true
        try {
            if (setRealtimeDataNotification(false)) true else {
                setRealtimeDataNotification(true)
                isMaintainingHistory = false
                false
            }
        } catch (error: Exception) {
            isMaintainingHistory = false
            throw error
        }
    }

    suspend fun endExclusiveOta() = withContext(Dispatchers.Main) {
        try {
            if (connectionState.value == BleManager.ConnectionState.ServicesDiscovered) {
                setRealtimeDataNotification(true)
            }
        } finally {
            isMaintainingHistory = false
        }
    }

    /** Explicit module maintenance only. Does not delete or replace the App database. */
    suspend fun retainLatestDeviceHistory(count: Int): Boolean = withContext(Dispatchers.Main) {
        val commandDeviceId = deviceAddress.value ?: return@withContext false
        val request = RetentionRequestBuilder.build(_deviceStatus.value?.capabilityFlags ?: 0, count)
        if (request == null) {
            addLog("设备未声明历史保留能力，未发送维护命令", LogType.ERROR)
            return@withContext false
        }
        if (isMaintainingHistory || _isFetchingHistory.value || historyFinishJob?.isActive == true ||
            _deviceStatus.value?.isDataClearInProgress == true ||
            connectionState.value != BleManager.ConnectionState.ServicesDiscovered ||
            !bleManager.isCharacteristicAvailable(BleConstants.HISTORY_CHAR) ||
            !bleManager.isCharacteristicAvailable(BleConstants.HISTORY_INFO_CHAR) ||
            !bleManager.isCharacteristicAvailable(BleConstants.STATUS_CHAR)) {
            addLog("历史任务忙碌或设备未就绪，未发送维护命令", LogType.ERROR)
            return@withContext false
        }
        isMaintainingHistory = true
        try {
            val written = CompletableDeferred<Boolean>()
            bleManager.writeCharacteristic(BleConstants.HISTORY_CHAR, request, onWrite = { written.complete(it) })
            if (withTimeoutOrNull(BLE_WRITE_TIMEOUT_MS) { written.await() } != true) {
                addLog("历史保留命令未确认", LogType.ERROR)
                return@withContext false
            }
            val confirmed = withTimeoutOrNull(120_000L) {
                while (connectionState.value == BleManager.ConnectionState.ServicesDiscovered) {
                    val info = CompletableDeferred<ByteArray?>()
                    bleManager.readCharacteristic(BleConstants.HISTORY_INFO_CHAR) { info.complete(it) }
                    val data = withTimeoutOrNull(BLE_WRITE_TIMEOUT_MS) { info.await() }
                    if (data != null && data.size >= 8) {
                        val total = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                        _historyTotalRecords.value = total
                        if (total == count.toLong()) {
                            val statusRead = CompletableDeferred<ByteArray?>()
                            bleManager.readCharacteristic(BleConstants.STATUS_CHAR) { statusRead.complete(it) }
                            val statusData = withTimeoutOrNull(BLE_WRITE_TIMEOUT_MS) { statusRead.await() }
                            val status = statusData?.let { DeviceStatusParser().parse(it) }
                            if (status != null) {
                                _deviceStatus.value = status
                                if (!status.isDataClearInProgress && status.recordCount == count) {
                                    return@withTimeoutOrNull true
                                }
                            }
                        }
                    }
                    kotlinx.coroutines.delay(500)
                }
                false
            } == true
            addLog(if (confirmed) "设备已确认保留最新 $count 条历史" else "历史保留结果未确认", if (confirmed) LogType.SUCCESS else LogType.ERROR)
            if (confirmed && count == 3000) {
                if (deviceAddress.value != commandDeviceId) return@withContext false
                val persisted = withContext(Dispatchers.IO) {
                    prefs.edit().putBoolean(retainedHistoryKey(commandDeviceId), true).commit()
                }
                if (!persisted) {
                    addLog("设备保留已确认，但本地持续策略未持久化", LogType.ERROR)
                    return@withContext false
                }
            }
            confirmed
        } finally {
            isMaintainingHistory = false
        }
    }

    fun fetchHistory(lastTimestamp: Long = 0, forceFullSync: Boolean = false, retentionRetryAttempt: Int = 0) {
        if (isMaintainingHistory) {
            addLog("历史维护正在执行，请稍后同步", LogType.INFO)
            return
        }
        if (!bleManager.isCharacteristicAvailable(BleConstants.HISTORY_CHAR)) {
            addLog("设备不支持历史数据功能（HISTORY_CHAR 特征不可用）", LogType.ERROR)
            return
        }
        val sessionDeviceId = deviceAddress.value ?: run {
            addLog("设备尚未连接，未开始历史同步", LogType.ERROR)
            return
        }
        val fetchSession = historySession.begin()
        if (fetchSession == null) {
            addLog("历史数据正在获取中，请勿重复点击", LogType.INFO)
            return
        }
        
        historyFetchDeviceId = sessionDeviceId
        retainedSync = if (_deviceStatus.value?.supportsHistoryRetention == true &&
            isPersistentHistoryRetentionEnabled(sessionDeviceId)) {
            RetainedSync(fetchSession, sessionDeviceId, retentionRetryAttempt)
        } else null
        _isFetchingHistory.value = true
        historyFetchJob = viewModelScope.launch {
            try {
                
                // ⭐ 提前获取 Profile 配置，用于后续判断
                val profile = currentDeviceProfile
                val supportsHistoryInfo = profile?.supportsHistoryInfo ?: false
                val useAsyncNotification = profile?.useAsyncNotificationForHistory ?: false
                
                // ⭐ 只有新固件才暂停实时数据订阅
                // 老固件（useAsyncNotification = false）跳过此步骤，与 release/1.0.0 保持一致
                // 避免 enableNotificationAsync 在老设备上可能导致的问题
                if (useAsyncNotification) {
                    addLog("正在暂停实时数据订阅，以优化传输通道...", LogType.INFO)
                    setRealtimeDataNotification(false)
                    // 增加延迟，让单片机有时间清理资源
                    kotlinx.coroutines.delay(500)
                    if (!historySession.acceptsPackets(fetchSession)) return@launch
                }
                
                // ⭐ 优化：不再将所有历史记录加载到内存，避免OOM和性能问题
                // 只清空内存缓冲
                historyMutex.withLock {
                    historyDataList.clear()
                }
                
                // 只计模块历史；手机 GPS 记录不得进入设备同步进度
                val dbCount = withContext(Dispatchers.IO) {
                    val deviceId = sessionDeviceId
                    recordDao.getHistoryRecordCount(deviceId)
                }

                if (!historySession.acceptsPackets(fetchSession)) return@launch
                // 确定发给设备的起始时间戳（仅允许 0 或「该设备 DB 最新一条」）
                // 全新安装/无本地数据 → 必须发 0 拉全量；有数据 → 发 DB 最新做增量；强制全量重试 → 发 0
                var timestamp = when {
                    retainedSync?.session == fetchSession || forceFullSync -> 0L
                    lastTimestamp > 0 -> lastTimestamp
                    else -> getLastHistoryTimestamp(sessionDeviceId)
                }
                if (!historySession.acceptsPackets(fetchSession)) return@launch
                // 防御：该设备本地记录数为 0 时一律发 0（避免竞态/备份恢复等导致误用增量）
                if (dbCount == 0) timestamp = 0L
                
                // 重置进度状态
                // ⭐ 修复：根据同步模式设置初始计数
                // 如果是增量同步（timestamp > 0），初始进度为本地已有记录数
                // 如果是全量同步（timestamp == 0），初始进度为 0
                totalHistoryReceivedCount = if (timestamp > 0) dbCount else 0
                recordsReceivedInSession = 0
                
                historyPacketIndex = 0
                historyPacketTotalEstimate = null
                historyRecordsPerPacketEstimate = null
                
                // 更新UI进度状态
                _historyProgress.value = HistoryProgress(
                    receivedCount = totalHistoryReceivedCount,
                    totalCount = null,
                    isFetching = true
                )
                
                if (timestamp > 0) {
                    val timestampDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date(timestamp * 1000))
                    addLog("开始增量获取历史数据（起始时间戳: $timestamp，对应时间: $timestampDate）...", LogType.INFO)
                } else {
                    addLog("开始获取全部历史数据...", LogType.INFO)
                }
                
                // 记录请求的时间戳
                lastRequestedTimestamp = timestamp
                retainedSync?.takeIf { it.session == fetchSession }?.requestTimestamp = timestamp
                
                // ⭐ 设置任务状态：正在同步历史数据
                _taskStatus.value = TaskStatus("正在同步历史数据", 0, 0)
                
                // ⭐ 启动历史数据消费者协程
                startHistoryConsumer(fetchSession)
                
                // ⭐ 第一步：先读取历史记录信息特征（总记录数 + 起始位置）
                // 参考 Web 端：先调取总记录数、起始位置，如果总记录数为0则直接返回
                // ⭐ 只有支持历史记录信息的固件才读取 HISTORY_INFO_CHAR
                if (supportsHistoryInfo && bleManager.isCharacteristicAvailable(BleConstants.HISTORY_INFO_CHAR)) {
                    addLog("正在读取历史记录信息（总记录数、起始扇区、起始索引）...", LogType.INFO)
                    val infoDeferred = CompletableDeferred<Unit>()
                    var hasInfo = false
                    bleManager.readCharacteristic(BleConstants.HISTORY_INFO_CHAR) { data ->
                        if (!historySession.acceptsPackets(fetchSession)) return@readCharacteristic
                        try {
                            if (data != null && data.size >= 8) {
                                retainedSync?.takeIf { it.session == fetchSession }?.before = data.copyOf()
                                val infoBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                                val total = infoBuffer.int.toLong() and 0xFFFFFFFFL
                                val startSector = infoBuffer.short.toInt() and 0xFFFF
                                val startIndex = infoBuffer.short.toInt() and 0xFFFF
                                
                                expectedHistoryTotal = total
                                historyStartSector = startSector
                                historyStartIndex = startIndex
                                
                                // ⭐ v1.2 更新：同步更新暴露给UI的历史总数
                                _historyTotalRecords.value = total
                                
                                // 更新UI进度状态（包含总数）
                                _historyProgress.value = HistoryProgress(
                                    receivedCount = totalHistoryReceivedCount,
                                    totalCount = total,
                                    isFetching = true
                                )
                                
                                // ⭐ 与WEB端保持一致：显示总记录数、起始扇区、起始索引
                                addLog(
                                    "存储总记录数: $total，起始扇区: $startSector，起始索引: $startIndex",
                                    LogType.SUCCESS
                                )
                                
                                // ⭐ 如果总记录数为0，直接返回（参考 Web 端）
                                if (total == 0L) {
                                    addLog("存储中无历史记录，无需拉取", LogType.INFO)
                                    _historyProgress.value = null
                                    hasInfo = true
                                    if (!infoDeferred.isCompleted) {
                                        infoDeferred.complete(Unit)
                                    }
                                    return@readCharacteristic
                                }
                                
                                hasInfo = true
                            } else {
                                addLog("读取历史记录信息失败：数据无效，继续尝试拉取数据", LogType.INFO)
                                expectedHistoryTotal = null
                                historyStartSector = null
                                historyStartIndex = null
                                hasInfo = true
                            }
                        } catch (e: Exception) {
                            addLog("解析历史记录信息失败: ${e.message}，继续尝试拉取数据", LogType.INFO)
                            expectedHistoryTotal = null
                            historyStartSector = null
                            historyStartIndex = null
                            hasInfo = true
                        } finally {
                            if (!infoDeferred.isCompleted) {
                                infoDeferred.complete(Unit)
                            }
                        }
                    }
                    // 等待读取完成（带超时）
                    val infoResult = withTimeoutOrNull(BLE_READ_TIMEOUT_MS) {
                        infoDeferred.await()
                    }
                    if (!historySession.acceptsPackets(fetchSession)) return@launch
                    if (infoResult == null) {
                        addLog("读取历史记录信息超时，继续尝试拉取数据", LogType.INFO)
                        expectedHistoryTotal = null
                        historyStartSector = null
                        historyStartIndex = null
                    }
                    
                    // 如果总记录数为0，已经返回，这里不需要继续
                    if (expectedHistoryTotal == 0L) {
                        finishHistoryFetch(fetchSession)
                        return@launch
                    }
                } else {
                    expectedHistoryTotal = null
                    historyStartSector = null
                    historyStartIndex = null
                    if (!supportsHistoryInfo) {
                        addLog("设备不支持历史记录信息特征，进度将以接收数量为准", LogType.INFO)
                        // 不支持历史信息的设备：立即更新任务状态显示初始进度
                        _taskStatus.value = TaskStatus(
                            "已同步${totalHistoryReceivedCount}条",
                            0,
                            0
                        )
                    } else {
                        addLog("历史记录信息特征不可用，进度将以接收数量为准", LogType.INFO)
                    }
                }
                
                // ⭐ 第二步：先停止通知（如果已启动），确保状态清理
                // ⭐ 根据 Profile 配置选择同步/异步方法
                if (isHistorySubscribed) {
                    if (useAsyncNotification) {
                        // 使用异步方法（支持回调和重试）
                        try {
                            val stopDeferred = CompletableDeferred<Boolean>()
                            bleManager.enableNotificationAsync(
                                uuid = BleConstants.HISTORY_CHAR,
                                enable = false,
                                onNotification = { },
                                onComplete = { result ->
                                    stopDeferred.complete(result)
                                }
                            )
                            val stopResult = withTimeoutOrNull(BLE_NOTIFY_TIMEOUT_MS) {
                                stopDeferred.await()
                            }
                            if (stopResult != null) {
                                addLog("已停止历史数据通知（清理状态）", LogType.INFO)
                            } else {
                                addLog("停止通知超时，继续尝试", LogType.INFO)
                            }
                        } catch (e: Exception) {
                            addLog("停止通知时出错（可忽略）: ${e.message}", LogType.INFO)
                        }
                    } else {
                        // 使用同步方法（兼容性更好）
                        bleManager.enableNotification(BleConstants.HISTORY_CHAR, false) { }
                        addLog("已停止历史数据通知（清理状态）", LogType.INFO)
                    }
                    kotlinx.coroutines.delay(200)
                    if (!historySession.acceptsPackets(fetchSession)) return@launch
                    isHistorySubscribed = false
                }
                
                // Profile pairs the parser with its explicit request: V3 adds 0x03.
                val timestampBytes = currentDeviceProfile?.buildHistoryRequest(timestamp)
                    ?: run {
                        addLog("设备配置未就绪，停止获取历史数据", LogType.ERROR)
                        finishHistoryFetch(fetchSession)
                        return@launch
                    }
                
                // 使用 CompletableDeferred 等待写入完成
                val writeDeferred = CompletableDeferred<Boolean>()
                bleManager.writeCharacteristic(
                    uuid = BleConstants.HISTORY_CHAR,
                    value = timestampBytes,
                    onWrite = { success ->
                        writeDeferred.complete(success)
                    }
                )
                
                // 等待写入完成（带超时）
                val writeSuccess = withTimeoutOrNull(BLE_WRITE_TIMEOUT_MS) {
                    writeDeferred.await()
                } ?: false
                if (!historySession.acceptsPackets(fetchSession)) return@launch
                if (!writeSuccess) {
                    addLog("写入时间戳失败或超时，停止获取历史数据", LogType.ERROR)
                    finishHistoryFetch(fetchSession)
                    return@launch
                }
                
                if (timestamp > 0) {
                    addLog("已设置起始时间戳为 $timestamp（增量更新，获取此时间之后的数据）", LogType.INFO)
                } else {
                    addLog("已设置起始时间戳为0（发送全部数据）", LogType.INFO)
                }
                
                // ⭐ 增加延迟，确保时间戳写入操作在单片机端完全处理完毕
                kotlinx.coroutines.delay(500)
                
                if (!historySession.acceptsPackets(fetchSession)) return@launch
                // ⭐ 第四步：订阅历史数据（重新启动通知，触发单片机端重新开始传输）
                // ⭐ 根据 Profile 配置选择同步/异步方法
                var success = false
                
                if (useAsyncNotification) {
                    // 使用异步方法（支持回调和重试）
                    var retryCount = 0
                    val maxRetries = 3
                    
                    kotlinx.coroutines.delay(100)
                    
                    while (retryCount < maxRetries && !success) {
                        if (retryCount > 0) {
                            addLog("启动历史数据通知重试中... (${retryCount}/${maxRetries})", LogType.INFO)
                            kotlinx.coroutines.delay(200)
                        }
                        
                        if (!historySession.acceptsPackets(fetchSession)) return@launch
                        val deferred = CompletableDeferred<Boolean>()
                        bleManager.enableNotificationAsync(
                            uuid = BleConstants.HISTORY_CHAR,
                            enable = true,
                            onNotification = { data ->
                                if (historySession.acceptsPackets(fetchSession)) historyPacketChannel.trySend(data)
                            },
                            onComplete = { result ->
                                deferred.complete(result)
                            }
                        )
                        
                        val result = withTimeoutOrNull(BLE_NOTIFY_TIMEOUT_MS) {
                            deferred.await()
                        }
                        if (!historySession.acceptsPackets(fetchSession)) return@launch
                        success = result == true
                        if (!success) {
                            if (result == null) {
                                addLog("启动通知超时，重试中...", LogType.INFO)
                            }
                            retryCount++
                        } else {
                            kotlinx.coroutines.delay(100)
                        }
                    }
                } else {
                    // 使用同步方法（兼容性更好）
                    success = bleManager.enableNotification(BleConstants.HISTORY_CHAR, true) { data ->
                        if (historySession.acceptsPackets(fetchSession)) historyPacketChannel.trySend(data)
                    }
                }
                
                if (!historySession.acceptsPackets(fetchSession)) return@launch
                if (success) {
                    isHistorySubscribed = true
                    addLog("已启动历史数据通知，等待数据...", LogType.INFO)
                    
                    // 仅在连续 60 秒没有记录进展时结束；持续传输不受总时长限制。
                    val progress = HistorySyncProgress(
                        HISTORY_IDLE_TIMEOUT_MS, SystemClock.elapsedRealtime(), totalHistoryReceivedCount
                    )
                    while (_isFetchingHistory.value && historySession.acceptsPackets(fetchSession)) {
                        kotlinx.coroutines.delay(1000)
                        if (progress.hasTimedOut(SystemClock.elapsedRealtime(), totalHistoryReceivedCount)) break
                    }
                    if (_isFetchingHistory.value && historySession.acceptsPackets(fetchSession)) {
                        val received = totalHistoryReceivedCount
                        val total = expectedHistoryTotal
                        if (total != null && total > 0) {
                            addLog("历史数据连续60秒无进展，已接收 $received/$total 条记录，尝试显示...", LogType.INFO)
                        } else {
                            addLog("历史数据连续60秒无进展，已接收 $received 条记录，尝试显示...", LogType.INFO)
                        }
                        finishHistoryFetch(fetchSession)
                    }
                } else {
                    addLog("获取历史数据失败（启动通知失败）", LogType.ERROR)
                    finishHistoryFetch(fetchSession)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (historySession.owns(fetchSession)) {
                    addLog("获取历史数据失败: ${e.message}", LogType.ERROR)
                    finishHistoryFetch(fetchSession)
                }
            }
        }
    }
    
    /**
     * 启动历史数据消费者协程
     */
    private fun startHistoryConsumer(fetchSession: Long) {
        historyProcessingJob?.cancel()
        // ⭐ 使用 Channel.UNLIMITED 确保通道有足够缓冲区
        // 如果 Channel 是默认的 (Rendezvous)，发送者会被挂起直到接收者准备好
        // 但我们在 trySend，如果 buffer 满了会失败
        // 所以我们之前初始化时已经用了 UNLIMITED
        
        historyProcessingJob = viewModelScope.launch(Dispatchers.IO) {
            var lastUiUpdateTime = 0L
            val uiUpdateInterval = 500L
            
            addLog("历史数据消费者协程已启动", LogType.INFO)
            
            try {
                // ⭐ 使用 for 循环代替 consumeEach，这样更可控
                for (data in historyPacketChannel) {
                    if (!historySession.acceptsPackets(fetchSession)) break
                    processHistoryPacket(data, fetchSession, lastUiUpdateTime, uiUpdateInterval) {
                        lastUiUpdateTime = it
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    withContext(Dispatchers.Main) {
                        addLog("历史数据处理错误: ${e.message}", LogType.ERROR)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    addLog("历史数据消费者协程已结束", LogType.INFO)
                }
            }
        }
    }

    /**
     * 处理单个历史数据包（在 IO 线程运行）
     * 使用 HistoryDataParser 解析数据
     */
    private suspend fun processHistoryPacket(
        data: ByteArray,
        fetchSession: Long,
        lastUiUpdateTime: Long, 
        uiUpdateInterval: Long,
        updateUiTimeCallback: (Long) -> Unit
    ) {
        try {
            // 检查结束标志
            if (data.size == 1 && data[0] == BleConstants.HISTORY_END_FLAG) {
                val retained = retainedSync?.takeIf { it.session == fetchSession }
                if (retained != null) {
                    retained.endReceived = true
                    val pendingInfo = CompletableDeferred<ByteArray?>()
                    bleManager.readCharacteristic(BleConstants.HISTORY_INFO_CHAR) { pendingInfo.complete(it?.copyOf()) }
                    retained.after = withTimeoutOrNull(BLE_READ_TIMEOUT_MS) { pendingInfo.await() }
                }
                val total = expectedHistoryTotal
                val received = totalHistoryReceivedCount
                
                // ⭐ 自动全量重试逻辑
                if (total != null && total > 0 && recordsReceivedInSession == 0 && lastRequestedTimestamp > 0 && received < total) {
                    withContext(Dispatchers.Main) {
                        if (!historySession.acceptsPackets(fetchSession)) return@withContext
                        addLog("增量同步未获取到数据，但在设备中检测到记录。可能是设备已重置，正在尝试全量同步...", LogType.INFO)
                        finishHistoryFetch(fetchSession, shouldClearState = false, retryFullSync = true)
                    }
                    return
                }

                withContext(Dispatchers.Main) {
                    if (!historySession.acceptsPackets(fetchSession)) return@withContext
                    if (total != null && total > 0) {
                        addLog("历史数据传输完成，共接收 $received/$total 条记录", LogType.SUCCESS)
                    } else {
                        addLog("历史数据传输完成，共接收 $received 条记录", LogType.SUCCESS)
                    }
                    // 更新最终进度状态
                    _historyProgress.value = HistoryProgress(
                        receivedCount = received,
                        totalCount = total,
                        isFetching = false
                    )
                    
                    // ⭐ 更新任务状态：显示同步完成
                    val statusText = if (total != null && total > 0) {
                        "历史数据同步完成，共${received}条"
                    } else {
                        "历史数据同步完成，共${received}条"
                    }
                    _taskStatus.value = TaskStatus(statusText, 0, 0)
                    
                    finishHistoryFetch(fetchSession)
                }
                return
            }
            
            // 使用解析器解析数据包
            // 使用本次同步捕获的连接设备，避免查看其他设备时串写记录。
            val deviceId = historyFetchDeviceId ?: return
            if (deviceAddress.value != deviceId) {
                retainedSync?.takeIf { it.session == fetchSession }?.savesSucceeded = false
                return
            }
            val parser = currentDeviceProfile?.getHistoryParser(deviceId) ?: run {
                addLog("历史数据解析失败：当前设备配置尚未就绪", LogType.ERROR)
                return
            }

            // 旁路诊断：报告固件实际帧长与 App 选中的历史格式，便于核对固件版本映射。
            val formatAware = parser as? HistoryFormatAware
            BleFrameDiagnostics.observeHistory(
                data = data,
                recordSize = formatAware?.historyRecordSize ?: parser.expectedMinLength,
                formatName = formatAware?.historyFormatName ?: "未知"
            )?.let { addLog(it, LogType.INFO) }

            val newRecords = parser.parse(data)
            
            if (newRecords.isNullOrEmpty()) return
            if (newRecords.any { it.deviceId != deviceId }) {
                retainedSync?.takeIf { it.session == fetchSession }?.savesSucceeded = false
                return
            }
            
            if (!historySession.acceptsPackets(fetchSession)) return
            retainedSync?.takeIf { it.session == fetchSession }?.timestamps?.addAll(newRecords.map { it.timestamp })
            var currentBufferSize = 0
            historyMutex.withLock {
                historyDataList.addAll(newRecords)
                currentBufferSize = historyDataList.size
            }
            
            currentCoroutineContext().ensureActive()
            if (!historySession.acceptsPackets(fetchSession)) return
            val recordsInPacket = newRecords.size
            totalHistoryReceivedCount += recordsInPacket
            recordsReceivedInSession += recordsInPacket
            
            // 节流更新 UI
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastUiUpdateTime >= uiUpdateInterval) {
                withContext(Dispatchers.Main) {
                    if (!historySession.acceptsPackets(fetchSession)) return@withContext
                    // 更新UI进度状态
                    _historyProgress.value = HistoryProgress(
                        receivedCount = totalHistoryReceivedCount,
                        totalCount = expectedHistoryTotal,
                        isFetching = true
                    )
                    
                    // ⭐ 更新任务状态
                    val total = expectedHistoryTotal
                    if (total != null && total > 0) {
                        val remaining = (total - totalHistoryReceivedCount).coerceAtLeast(0)
                        _taskStatus.value = TaskStatus(
                            "已同步${totalHistoryReceivedCount}条，剩余${remaining}条",
                            0,
                            0
                        )
                    } else {
                        _taskStatus.value = TaskStatus(
                            "已同步${totalHistoryReceivedCount}条",
                            0,
                            0
                        )
                    }
                }
                updateUiTimeCallback(currentTime)
            }
            
            if (recordsInPacket > 0) {
                // 更新估算的每包记录数和总包数（仅在有总记录数时）
                if (historyRecordsPerPacketEstimate == null) {
                    historyRecordsPerPacketEstimate = recordsInPacket
                    val total = expectedHistoryTotal
                    if (total != null && total > 0 && recordsInPacket > 0) {
                        val estimatedPackets = ((total + recordsInPacket - 1) / recordsInPacket).toInt()
                        historyPacketTotalEstimate = estimatedPackets.coerceAtLeast(1)
                    }
                }
                
                // 每收到一包，更新包序号
                historyPacketIndex++
                
                // ⭐ 优化：当缓冲区达到100条记录时保存一次到数据库，并清空缓冲区（原为20）
                // 增加批量大小以减少数据库事务开销，解决 ENOMEM 问题
                if (currentBufferSize >= 100) {
                    saveHistoryToDatabase(clearAfterSave = true)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                addLog("解析历史数据错误: ${e.message}", LogType.ERROR)
            }
        }
    }

    /**
     * 处理历史数据（已废弃，改用 Channel 模式）
     */
    private fun processHistoryData(data: ByteArray) {
        // 保留此方法仅作为兼容性占位，实际逻辑已移至 processHistoryPacket
        historyPacketChannel.trySend(data)
    }
    
    /**
     * 完成历史数据获取
     * @param shouldClearState 是否清除全局状态（默认为 true）。如果是重试前的清理，设为 false。
     */
    private fun finishHistoryFetch(
        fetchSession: Long,
        shouldClearState: Boolean = true,
        retryFullSync: Boolean = false
    ) {
        if (!historySession.beginFinish(fetchSession)) return
        val retainedAtFinish = retainedSync?.takeIf { it.session == fetchSession }
        historyFinishJob = viewModelScope.launch {
            // ⭐ 根据 Profile 配置选择同步/异步方法（提前获取，供 try 和 catch 使用）
            var retryRetained = false
            val useAsyncNotification = currentDeviceProfile?.useAsyncNotificationForHistory ?: false
            
            try {
                historyProcessingJob?.cancelAndJoin()
                historyProcessingJob = null
                while (historyPacketChannel.tryReceive().isSuccess) {}
                currentCoroutineContext().ensureActive()
                if (!historySession.owns(fetchSession)) return@launch
                // ⭐ 保存剩余数据到数据库，并在保存后清空内存列表
                saveHistoryToDatabase(clearAfterSave = true)
                currentCoroutineContext().ensureActive()
                if (!historySession.owns(fetchSession)) return@launch
                val retained = retainedAtFinish
                if (retained != null) {
                    val eligible = HistoryRetentionGate.canTrim(retained.requestTimestamp, retained.endReceived,
                        recordsReceivedInSession, retained.timestamps, retained.before, retained.after,
                        retained.savesSucceeded, historySession.owns(fetchSession) &&
                            deviceAddress.value == retained.deviceId &&
                            connectionState.value == BleManager.ConnectionState.ServicesDiscovered)
                    if (eligible) {
                        withContext(Dispatchers.IO) {
                            currentCoroutineContext().ensureActive()
                            if (historySession.owns(fetchSession) && deviceAddress.value == retained.deviceId) {
                                val removed = recordDao.trimHistoryToWireSet(retained.deviceId, retained.timestamps.toSet()) {
                                    historySession.owns(fetchSession) && deviceAddress.value == retained.deviceId &&
                                        connectionState.value == BleManager.ConnectionState.ServicesDiscovered
                                }
                                addLog("本地持续保留已核对3000条，移除${removed}条窗口外模块历史", LogType.SUCCESS)
                            }
                        }
                    } else {
                        addLog("保留窗口未通过完整END/3000唯一记录/稳定信息/保存验证，本地记录未删除", LogType.ERROR)
                        retryRetained = retained.retryAttempt == 0
                    }
                }
                // ⭐ 历史同步完成后写一笔明确标记来源的手机实时数据（GPS可缺失），衔接时间线
                val location = if (_hasLocationPermission.value) {
                    locationManager.getCachedLocation() ?: locationManager.getCurrentLocation(5000)
                } else null
                currentCoroutineContext().ensureActive()
                if (!historySession.owns(fetchSession)) return@launch
                writeRealtimeDataToDatabase(location)
                currentCoroutineContext().ensureActive()
                if (!historySession.owns(fetchSession)) return@launch
                
                // 停止通知
                try {
                    if (useAsyncNotification) {
                        // 使用异步方法（支持回调和重试）
                        val stopDeferred = CompletableDeferred<Boolean>()
                        bleManager.enableNotificationAsync(
                            uuid = BleConstants.HISTORY_CHAR,
                            enable = false,
                            onNotification = { },
                            onComplete = { result ->
                                stopDeferred.complete(result)
                            }
                        )
                        withTimeoutOrNull(BLE_NOTIFY_TIMEOUT_MS) {
                            stopDeferred.await()
                        }
                    } else {
                        // 使用同步方法（兼容性更好）
                        bleManager.enableNotification(BleConstants.HISTORY_CHAR, false) { }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 忽略停止通知时的错误
                }
                isHistorySubscribed = false

                
                if (shouldClearState) {
                    // ⭐ 同步完成后做一次历史评估（3h 内 GPS 一致则用该区间气压评估）
                    val deviceId = _viewingDeviceAddress.value
                    if (!deviceId.isNullOrEmpty()) {
                        withContext(Dispatchers.IO) {
                            val nowSec = System.currentTimeMillis() / 1000
                            val records = recordDao.getRecordsByTimeRange(
                                deviceId,
                                nowSec - HISTORY_EVAL_WINDOW_SEC,
                                nowSec
                            )
                            val result = evaluateHistorySegment(records)
                            _lastHistoryEvaluationResult.value = result
                            addLog("历史评估: ${result.message}", LogType.INFO)
                        }
                    }
                    // 延迟清除进度状态（给UI一点时间显示最终状态）
                    kotlinx.coroutines.delay(500)
                    _historyProgress.value = null
                    
                    // ⭐ 延迟清除任务状态（给用户一点时间看到完成信息）
                    kotlinx.coroutines.delay(2000) // 2秒后清除
                    if (_taskStatus.value?.taskName?.contains("历史数据") == true || 
                        _taskStatus.value?.taskName?.contains("同步") == true) {
                        _taskStatus.value = null
                    }
                }
                
                // ⭐ 恢复连接优先级
                // bleManager.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                
                // ⭐ 恢复实时数据订阅（只有新固件需要，老固件没有暂停）
                if (useAsyncNotification) {
                    addLog("历史数据同步结束，正在恢复实时数据订阅...", LogType.INFO)
                    setRealtimeDataNotification(true)
                }
                
                // ⭐ 自动刷新 UI 数据
                if (shouldClearState) {
                    addLog("正在刷新图表和列表...", LogType.INFO)
                    loadLast24HoursRecords() // 刷新首页 24h 趋势图
                    loadHistoryDetail()      // 刷新历史记录列表
                    // 历史同步结束后，若首次读取最高最低温度失败则重试一次
                    if (maxMinTempReadFailed) {
                        kotlinx.coroutines.delay(300)
                        val retryOk = readMaxMinTemperatureSync()
                        _maxMinTempNeedManualSync.value = !retryOk
                        maxMinTempReadFailed = false
                    }
                }
                
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (!historySession.owns(fetchSession)) return@launch
                addLog("完成历史数据获取时出错: ${e.message}", LogType.ERROR)

                // 清除进度状态
                _historyProgress.value = null
                // 出错时也清除任务状态
                if (_taskStatus.value?.taskName?.contains("历史数据") == true) {
                    _taskStatus.value = null
                }
                
                // ⭐ 恢复连接优先级
                // bleManager.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)

                // ⭐ 出错也要恢复实时数据订阅（只有新固件需要）
                if (useAsyncNotification) {
                    setRealtimeDataNotification(true)
                }
            } finally {
                if (historySession.complete(fetchSession)) {
                    _isFetchingHistory.value = false
                    if (retainedAtFinish != null) {
                        if ((retryFullSync || retryRetained) && retainedAtFinish.retryAttempt < 1) {
                            fetchHistory(forceFullSync = true, retentionRetryAttempt = retainedAtFinish.retryAttempt + 1)
                        }
                    } else if (retryFullSync) fetchHistory(forceFullSync = true)
                }
            }
        }
    }
    
    /**
     * 保存历史数据到数据库
     * @param clearAfterSave 保存后是否清空内存列表，默认 false（获取过程中不清空，只在获取完成后清空）
     */
    private suspend fun saveHistoryToDatabase(clearAfterSave: Boolean = true) {
        try {
            historyMutex.withLock {
                if (historyDataList.isEmpty()) return@withLock
                // Keep the buffer until Room commits. Cancelling a consumer mid-transaction
                // must leave its pending records available to the session's final save.
                recordDao.upsertHistoryRecords(historyDataList.toList())
                if (clearAfterSave) historyDataList.clear()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            retainedSync?.savesSucceeded = false
            addLog("保存历史数据到数据库失败: ${e.message}", LogType.ERROR)
        }
    }
    
    /**
     * 清空历史数据
     */
    fun clearHistory() {
        val address = _viewingDeviceAddress.value ?: return
        
        viewModelScope.launch {
            try {
                recordDao.deleteAll(address)
                _historyDetailRecords.value = emptyList()
                currentHistoryPage = 0
                _hasMoreHistory.value = true
                addLog("历史数据已清空", LogType.INFO)
            } catch (e: Exception) {
                addLog("清空历史数据失败: ${e.message}", LogType.ERROR)
            }
        }
    }
    
    /**
     * 加载历史数据详情页面（初始化）
     */
    fun loadHistoryDetail() {
        val address = _viewingDeviceAddress.value ?: return
        
        viewModelScope.launch {
            try {
                currentHistoryPage = 0
                val startTime = _filterStartTime.value
                val endTime = _filterEndTime.value
                
                val records = if (startTime != null && endTime != null) {
                    // 带时间筛选的查询
                    recordDao.getRecordsWithOffsetAndTimeRange(address, startTime, endTime, historyPageSize, 0)
                } else {
                    // 不带筛选的查询
                    recordDao.getLatestRecords(address, historyPageSize)
                }
                
                _historyDetailRecords.value = records
                _hasMoreHistory.value = records.size >= historyPageSize
            } catch (e: Exception) {
                addLog("加载历史数据失败: ${e.message}", LogType.ERROR)
            }
        }
    }
    
    /**
     * 加载更多历史数据（分页）
     */
    fun loadMoreHistoryDetail() {
        if (_isLoadingMoreHistory.value || !_hasMoreHistory.value) {
            return
        }
        
        val address = _viewingDeviceAddress.value ?: return
        
        viewModelScope.launch {
            try {
                _isLoadingMoreHistory.value = true
                currentHistoryPage++
                val offset = currentHistoryPage * historyPageSize
                val startTime = _filterStartTime.value
                val endTime = _filterEndTime.value
                
                val records = if (startTime != null && endTime != null) {
                    // 带时间筛选的分页查询
                    recordDao.getRecordsWithOffsetAndTimeRange(address, startTime, endTime, historyPageSize, offset)
                } else {
                    // 不带筛选的分页查询
                    recordDao.getRecordsWithOffset(address, historyPageSize, offset)
                }
                
                if (records.isNotEmpty()) {
                    _historyDetailRecords.value = _historyDetailRecords.value + records
                    _hasMoreHistory.value = records.size >= historyPageSize
                } else {
                    _hasMoreHistory.value = false
                }
            } catch (e: Exception) {
                addLog("加载更多历史数据失败: ${e.message}", LogType.ERROR)
            } finally {
                _isLoadingMoreHistory.value = false
            }
        }
    }
    
    /**
     * 设置历史数据时间筛选范围
     * @param startTime 起始时间（秒级时间戳），null 表示不限制
     * @param endTime 结束时间（秒级时间戳），null 表示不限制
     */
    fun setHistoryTimeFilter(startTime: Long?, endTime: Long?) {
        _filterStartTime.value = startTime
        _filterEndTime.value = endTime
        
        // 重新加载数据
        loadHistoryDetail()
        loadFilteredChartRecords()
    }
    
    /**
     * 重置时间筛选
     */
    fun resetHistoryTimeFilter() {
        _filterStartTime.value = null
        _filterEndTime.value = null
        
        // 重新加载数据
        loadHistoryDetail()
        loadLast24HoursRecords()
    }
    
    /**
     * 加载筛选后的图表数据
     */
    private fun loadFilteredChartRecords() {
        val address = _viewingDeviceAddress.value ?: return
        val startTime = _filterStartTime.value
        val endTime = _filterEndTime.value
        
        if (startTime == null || endTime == null) {
            // 没有筛选时，加载最近24小时数据
            loadLast24HoursRecords()
            return
        }
        
        viewModelScope.launch {
            try {
                val records = recordDao.getRecordsByTimeRange(address, startTime, endTime)
                _last24HoursRecords.value = records
                addLog("已加载筛选数据，共 ${records.size} 条记录", LogType.INFO)
            } catch (e: Exception) {
                addLog("加载筛选数据失败: ${e.message}", LogType.ERROR)
                _last24HoursRecords.value = emptyList()
            }
        }
    }
    
    /**
     * 加载最近24小时的数据
     */
    fun loadLast24HoursRecords() {
        val address = _viewingDeviceAddress.value ?: return
        
        // 如果有时间筛选，则使用筛选的时间范围
        val startTime = _filterStartTime.value
        val endTime = _filterEndTime.value
        
        viewModelScope.launch {
            try {
                val records = if (startTime != null && endTime != null) {
                    // 使用筛选的时间范围
                    recordDao.getRecordsByTimeRange(address, startTime, endTime)
                } else {
                    // 默认加载最近24小时
                    val currentTime = System.currentTimeMillis() / 1000 // 当前时间戳（秒）
                    val defaultStartTime = currentTime - 86400 // 24小时前（86400秒）
                    recordDao.getRecordsByTimeRange(address, defaultStartTime, currentTime)
                }
                
                _last24HoursRecords.value = records
                addLog("已加载图表数据，共 ${records.size} 条记录", LogType.INFO)

                // 同步生成首页气象洞察（纯本地计算）
                try {
                    val nowSec = System.currentTimeMillis() / 1000
                    _weatherInsights.value = WeatherInsightsEngine.generateInsights(records, nowSec)
                } catch (e: Exception) {
                    addLog("生成气象洞察失败: ${e.message}", LogType.INFO)
                    _weatherInsights.value = null
                }
            } catch (e: Exception) {
                addLog("加载图表数据失败: ${e.message}", LogType.ERROR)
                _last24HoursRecords.value = emptyList()
                _weatherInsights.value = null
            }
        }
    }
    
    /**
     * 清空日志
     */
    fun clearLogs() {
        _logs.value = emptyList()
        addLog("日志已清空", LogType.INFO)
    }
    
    // ==================== WAPS 天气预警 ====================
    // 采样循环已提取到 WapsMonitor（data/weather），这里只负责随连接生命周期启停。

    private fun stopWaps() {
        wapsMonitor.stop()
    }

    private fun startWaps() {
        wapsMonitor.start(viewModelScope)
    }
    
    // ==================== 定时数据记录功能 ====================
    
    /**
     * 定位权限已授予时调用
     */
    fun onLocationPermissionGranted() {
        _hasLocationPermission.value = true
        addLog("定位权限已授予", LogType.INFO)
    }
    
    /**
     * 启动定时数据记录
     * 1. 连接后等待GPS（最多30秒），获取到或超时后立刻写入第一条
     * 2. 之后每30秒写入一次
     */
    private fun startDataRecording() {
        // 如果已经在记录，先停止
        stopDataRecording()
        
        _isRecordingData.value = true
        addLog("启动定时数据记录...", LogType.INFO)
        
        dataRecordJob = viewModelScope.launch {
            try {
                // 第一步：等待GPS（最多30秒）
                addLog("正在获取GPS位置（最多等待30秒）...", LogType.INFO)
                val startTime = System.currentTimeMillis()
                var location: android.location.Location? = null
                
                if (_hasLocationPermission.value) {
                    location = locationManager.getCurrentLocation(GPS_WAIT_TIMEOUT_MS)
                    if (location != null) {
                        val elapsed = System.currentTimeMillis() - startTime
                        addLog("GPS获取成功（耗时${elapsed}ms）: ${String.format("%.6f", location.latitude)}, ${String.format("%.6f", location.longitude)}", LogType.SUCCESS)
                    } else {
                        addLog("GPS获取超时或失败，将写入不含GPS的数据", LogType.INFO)
                    }
                } else {
                    addLog("无定位权限，将写入不含GPS的数据", LogType.INFO)
                }
                
                // 第二步：立刻写入第一条数据
                writeRealtimeDataToDatabase(location)
                
                // 第三步：每30秒写入一次
                while (currentCoroutineContext().isActive) {
                    delay(DATA_RECORD_INTERVAL_MS)
                    
                    // 检查连接状态
                    if (connectionState.value != BleManager.ConnectionState.ServicesDiscovered) {
                        addLog("设备已断开，停止数据记录", LogType.INFO)
                        break
                    }
                    
                    // 获取当前位置（如果有权限）
                    val currentLocation = if (_hasLocationPermission.value) {
                        locationManager.getCachedLocation() ?: locationManager.getCurrentLocation(5000)
                    } else {
                        null
                    }
                    
                    writeRealtimeDataToDatabase(currentLocation)
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    addLog("数据记录出错: ${e.message}", LogType.ERROR)
                }
            } finally {
                _isRecordingData.value = false
            }
        }
    }
    
    /**
     * 停止定时数据记录
     */
    private fun stopDataRecording() {
        dataRecordJob?.cancel()
        dataRecordJob = null
        _isRecordingData.value = false
    }
    
    /**
     * 将当前实时数据写入数据库
     * @param location 当前位置（可选）
     */
    private suspend fun writeRealtimeDataToDatabase(location: android.location.Location?) {
        try {
            val temp = _temperature.value
            val hum = _humidity.value
            val press = _pressure.value
            val deviceAddress = _viewingDeviceAddress.value
            
            // 检查必要数据
            if (temp == null || hum == null) {
                addLog("实时数据不完整，跳过写入", LogType.INFO)
                return
            }
            
            if (deviceAddress.isNullOrEmpty()) {
                addLog("设备地址为空，跳过写入", LogType.INFO)
                return
            }
            
            // 创建记录
            val record = TemperatureRecord(
                timestamp = System.currentTimeMillis() / 1000, // 秒级时间戳
                temperature = temp,
                humidity = hum,
                pressure = press,
                batteryVoltage = _batteryVoltage.value,
                deviceId = deviceAddress,
                latitude = location?.latitude,
                longitude = location?.longitude,
                isPhoneSample = true
            )
            
            // 写入数据库
            withContext(Dispatchers.IO) {
                recordDao.insert(record)
            }
            
            val gpsInfo = if (location != null) {
                " GPS: ${String.format("%.4f", location.latitude)}, ${String.format("%.4f", location.longitude)}"
            } else {
                ""
            }
            addLog("已记录数据: ${String.format("%.1f", temp)}°C, ${hum.toInt()}%$gpsInfo", LogType.SUCCESS)
        } catch (e: Exception) {
            addLog("写入数据失败: ${e.message}", LogType.ERROR)
        }
    }

    // ==================== 导出功能 ====================

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    enum class ExportFormat {
        CSV, PDF
    }

    fun exportHistoryData(context: Context, format: ExportFormat) {
        val address = _viewingDeviceAddress.value
        if (address == null) {
            showSnackbar("未选择设备", SnackbarType.ERROR)
            return
        }

        val startTime = _filterStartTime.value
        val endTime = _filterEndTime.value

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isExporting.value = true
                addLog("开始导出数据...", LogType.INFO)

                val records = if (startTime != null && endTime != null) {
                    recordDao.getRecordsByTimeRange(address, startTime, endTime)
                } else {
                    // 默认导出最近24小时
                    val currentTime = System.currentTimeMillis() / 1000
                    val defaultStartTime = currentTime - 86400
                    recordDao.getRecordsByTimeRange(address, defaultStartTime, currentTime)
                }

                if (records.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        showSnackbar("没有数据可导出", SnackbarType.INFO)
                    }
                    return@launch
                }

                val file = when (format) {
                    ExportFormat.CSV -> com.example.pandatemperature.utils.ExportUtils.exportToCsv(context, records)
                    ExportFormat.PDF -> com.example.pandatemperature.utils.ExportUtils.exportToPdf(context, records)
                }

                withContext(Dispatchers.Main) {
                    addLog("数据导出成功: ${file.name}", LogType.SUCCESS)
                    showSnackbar("导出成功", SnackbarType.SUCCESS)
                    
                    // 分享文件
                    shareFile(context, file)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addLog("导出失败: ${e.message}", LogType.ERROR)
                    showSnackbar("导出失败", SnackbarType.ERROR)
                }
            } finally {
                _isExporting.value = false
            }
        }
    }

    private fun shareFile(context: Context, file: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = if (file.name.endsWith(".pdf")) "application/pdf" else "text/csv"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = android.content.Intent.createChooser(intent, "分享导出文件")
            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            addLog("分享文件失败: ${e.message}", LogType.ERROR)
            showSnackbar("无法分享文件", SnackbarType.ERROR)
        }
    }
}
