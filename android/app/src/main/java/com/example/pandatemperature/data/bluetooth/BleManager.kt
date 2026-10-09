package com.example.pandatemperature.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*

/**
 * 蓝牙低功耗管理器（单例）
 * 负责管理蓝牙设备的扫描、连接、数据读写等操作
 */
@SuppressLint("MissingPermission")
class BleManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "BleManager"
        
        @Volatile
        private var INSTANCE: BleManager? = null
        
        fun getInstance(context: Context): BleManager {
            return INSTANCE ?: synchronized(this) {
                val instance = BleManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
    
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothManager?.adapter
    }
    
    private val bluetoothLeScanner: BluetoothLeScanner? by lazy {
        bluetoothAdapter?.bluetoothLeScanner
    }
    
    private var gatt: BluetoothGatt? = null
    private var currentDevice: BluetoothDevice? = null
    
    // 服务和特征缓存
    private var essService: BluetoothGattService? = null
    private var configService: BluetoothGattService? = null
    private var realtimeDataService: BluetoothGattService? = null  // ⭐ v1.1 新增：实时数据服务
    private var clearDataService: BluetoothGattService? = null  // ⭐ v1.2 新增：清空数据服务
    private var tempCharacteristic: BluetoothGattCharacteristic? = null
    private var humidityCharacteristic: BluetoothGattCharacteristic? = null
    private var realtimeDataCharacteristic: BluetoothGattCharacteristic? = null  // ⭐ v1.1 新增：实时数据特征
    private var timeSyncCharacteristic: BluetoothGattCharacteristic? = null
    private var intervalCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var historyCharacteristic: BluetoothGattCharacteristic? = null
    private var historyInfoCharacteristic: BluetoothGattCharacteristic? = null
    private var maxMinTempCharacteristic: BluetoothGattCharacteristic? = null
    private var resetMaxMinTempCharacteristic: BluetoothGattCharacteristic? = null
    private var clearDataCharacteristic: BluetoothGattCharacteristic? = null  // ⭐ v1.2 新增：清空数据特征

    // ⭐ BLE OTA（固件 1.0.6+0 新增）：自定义升级服务 1234005x
    private var otaService: BluetoothGattService? = null
    private var otaControlCharacteristic: BluetoothGattCharacteristic? = null
    private var otaDataCharacteristic: BluetoothGattCharacteristic? = null
    private var otaStatusCharacteristic: BluetoothGattCharacteristic? = null
    
    // 状态流
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()
    
    // 扫描相关
    private var isScanning = false
    private var scanCallbackInstance: ScanCallback? = null

    // MTU 回调
    private var mtuCallback: ((Boolean, Int) -> Unit)? = null

    private val _deviceAddress = MutableStateFlow<String?>(null)
    val deviceAddress: StateFlow<String?> = _deviceAddress.asStateFlow()
    
    // 特征值读取回调映射
    private val readCallbacks = mutableMapOf<UUID, (ByteArray?) -> Unit>()
    
    // 特征值写入回调映射
    private val writeCallbacks = mutableMapOf<UUID, (Boolean) -> Unit>()
    
    // 描述符写入回调映射（用于通知启用/禁用）
    private val descriptorWriteCallbacks = mutableMapOf<UUID, (Boolean) -> Unit>()
    
    // 特征值通知回调映射
    private val notificationCallbacks = mutableMapOf<UUID, (ByteArray) -> Unit>()
    
    // GATT 回调
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "设备已连接")
                    _connectionState.value = ConnectionState.Connected
                    // 发现服务
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "设备已断开")
                    _connectionState.value = ConnectionState.Disconnected
                    cleanup()
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "服务发现成功")
                _connectionState.value = ConnectionState.ServicesDiscovered
                cacheServices(gatt)
            } else {
                Log.e(TAG, "服务发现失败: $status")
            }
        }
        
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val callback = readCallbacks.remove(characteristic.uuid)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val value = characteristic.value
                Log.d(TAG, "特征读取成功: ${characteristic.uuid}, 数据长度: ${value?.size ?: 0}")
                callback?.invoke(value)
            } else {
                Log.e(TAG, "特征读取失败: $status")
                callback?.invoke(null)
            }
        }
        
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val callback = writeCallbacks.remove(characteristic.uuid)
            val success = status == BluetoothGatt.GATT_SUCCESS
            if (success) {
                Log.d(TAG, "特征写入成功: ${characteristic.uuid}")
            } else {
                Log.e(TAG, "特征写入失败: $status")
            }
            callback?.invoke(success)
        }
        
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value
            Log.d(TAG, "特征值变化: ${characteristic.uuid}, 数据长度: ${value?.size ?: 0}")
            notificationCallbacks[characteristic.uuid]?.invoke(value ?: ByteArray(0))
        }
        
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // 修复：此前 requestMtu 只保存了回调，却没有任何 GATT 回调触发它，
            // 导致 MTU 请求永远等不到结果。BLE OTA 的 START 报文（61B）依赖协商 MTU。
            val success = status == BluetoothGatt.GATT_SUCCESS
            Log.d(TAG, "MTU 变更: $mtu, 状态: $status")
            val callback = mtuCallback
            mtuCallback = null
            callback?.invoke(success, mtu)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val success = status == BluetoothGatt.GATT_SUCCESS
            if (success) {
                Log.d(TAG, "描述符写入成功: ${descriptor.uuid}")
            } else {
                Log.e(TAG, "描述符写入失败: $status (${if (status == 0x85) "ENOMEM" else "其他错误"})")
            }
            // 通知对应的回调（使用特征UUID作为key）
            val characteristicUuid = descriptor.characteristic.uuid
            descriptorWriteCallbacks.remove(characteristicUuid)?.invoke(success)
        }
    }
    
    /**
     * 请求更改 MTU
     */
    fun requestMtu(mtu: Int, onComplete: (Boolean, Int) -> Unit) {
        if (gatt == null) {
            Log.e(TAG, "请求 MTU 失败: 设备未连接")
            onComplete(false, 0)
            return
        }

        mtuCallback = onComplete
        if (gatt?.requestMtu(mtu) != true) {
            Log.e(TAG, "请求 MTU 失败")
            mtuCallback = null
            onComplete(false, 0)
        } else {
            Log.d(TAG, "正在请求 MTU: $mtu")
        }
    }

    /**
     * 请求连接优先级
     * @param priority BluetoothGatt.CONNECTION_PRIORITY_BALANCED (0)
     *                 BluetoothGatt.CONNECTION_PRIORITY_HIGH (1)
     *                 BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER (2)
     */
    fun requestConnectionPriority(priority: Int): Boolean {
        if (gatt == null) {
            Log.e(TAG, "请求连接优先级失败: 设备未连接")
            return false
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val success = gatt?.requestConnectionPriority(priority) == true
            if (success) {
                Log.d(TAG, "请求连接优先级: $priority")
            } else {
                Log.e(TAG, "请求连接优先级失败")
            }
            return success
        } else {
            Log.w(TAG, "请求连接优先级不支持: Android 版本过低")
            return false
        }
    }

    /**
     * 检查蓝牙是否可用
     */
    fun isBluetoothAvailable(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
    
    /**
     * 开始扫描设备
     * 扫描所有以 "Panda" 开头的蓝牙设备
     */
    fun startScan(onDeviceFound: (BluetoothDevice) -> Unit) {
        if (!isBluetoothAvailable()) {
            Log.e(TAG, "蓝牙未启用")
            return
        }
        
        if (isScanning) {
            Log.d(TAG, "已在扫描中")
            return
        }
        
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = device.name ?: result.scanRecord?.deviceName
                
                // 只接受以 "Panda" 开头的设备
                if (name?.startsWith(BleConstants.DEVICE_NAME_PREFIX_PANDA) == true) {
                    Log.d(TAG, "扫描到设备: $name (${device.address})")
                    onDeviceFound(device)
                }
            }
            
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "扫描失败: $errorCode")
                isScanning = false
                scanCallbackInstance = null
            }
        }
        
        scanCallbackInstance = callback
        try {
            // 不使用过滤器，扫描所有设备然后在回调中手动过滤
            bluetoothLeScanner?.startScan(null, settings, callback)
            isScanning = true
            Log.d(TAG, "开始扫描设备（前缀匹配: Panda*）")
        } catch (e: Exception) {
            Log.e(TAG, "启动扫描失败", e)
            isScanning = false
            scanCallbackInstance = null
        }
    }
    
    /**
     * 停止扫描
     */
    fun stopScan() {
        if (isScanning && scanCallbackInstance != null) {
            bluetoothLeScanner?.stopScan(scanCallbackInstance)
            isScanning = false
            scanCallbackInstance = null
            Log.d(TAG, "停止扫描")
        }
    }
    
    /**
     * 连接设备
     */
    fun connect(device: BluetoothDevice): Boolean {
        if (gatt != null) {
            Log.w(TAG, "已有连接，先断开")
            disconnect()
        }
        
        _connectionState.value = ConnectionState.Connecting
        _deviceName.value = device.name ?: device.address
        _deviceAddress.value = device.address
        currentDevice = device
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                gatt = device.connectGatt(context, false, gattCallback)
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "连接失败", e)
            _connectionState.value = ConnectionState.Disconnected
            return false
        }
    }

    /**
     * 通过地址连接设备
     */
    fun connect(address: String): Boolean {
        val device = bluetoothAdapter?.getRemoteDevice(address)
        return if (device != null) {
            connect(device)
        } else {
            Log.e(TAG, "无法获取设备对象: $address")
            false
        }
    }
    
    /**
     * 断开连接
     */
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        cleanup()
        _connectionState.value = ConnectionState.Disconnected
        _deviceName.value = null
        _deviceAddress.value = null
    }
    
    /**
     * 缓存服务和特征
     */
    private fun cacheServices(gatt: BluetoothGatt) {
        val essServiceUuid = UUID.fromString(BleConstants.ESS_SERVICE)
        val configServiceUuid = UUID.fromString(BleConstants.CONFIG_SERVICE)
        val realtimeDataServiceUuid = UUID.fromString(BleConstants.REALTIME_DATA_SERVICE)  // ⭐ v1.1 新增
        val clearDataServiceUuid = UUID.fromString(BleConstants.CLEAR_DATA_SERVICE)  // ⭐ v1.2 新增
        val otaServiceUuid = UUID.fromString(BleConstants.OTA_SERVICE)  // ⭐ BLE OTA 新增
        
        essService = gatt.getService(essServiceUuid)
        configService = gatt.getService(configServiceUuid)
        realtimeDataService = gatt.getService(realtimeDataServiceUuid)  // ⭐ v1.1 新增
        clearDataService = gatt.getService(clearDataServiceUuid)  // ⭐ v1.2 新增
        otaService = gatt.getService(otaServiceUuid)  // ⭐ BLE OTA 新增
        
        essService?.let { service ->
            tempCharacteristic = service.getCharacteristic(UUID.fromString(BleConstants.TEMP_CHAR))
            humidityCharacteristic = service.getCharacteristic(UUID.fromString(BleConstants.HUMIDITY_CHAR))
        }
        
        realtimeDataService?.let { service ->  // ⭐ v1.1 新增
            realtimeDataCharacteristic = service.getCharacteristic(UUID.fromString(BleConstants.REALTIME_DATA_CHAR))
        }
        
        configService?.let { service ->
            timeSyncCharacteristic = service.getCharacteristic(UUID.fromString(BleConstants.TIME_SYNC_CHAR))
            intervalCharacteristic = service.getCharacteristic(UUID.fromString(BleConstants.INTERVAL_CHAR))
            statusCharacteristic = service.getCharacteristic(UUID.fromString(BleConstants.STATUS_CHAR))
            historyCharacteristic = service.getCharacteristic(UUID.fromString(BleConstants.HISTORY_CHAR))
            historyInfoCharacteristic = service.getCharacteristic(UUID.fromString(BleConstants.HISTORY_INFO_CHAR))
            maxMinTempCharacteristic = service.getCharacteristic(UUID.fromString(BleConstants.MAX_MIN_TEMP_CHAR))
            resetMaxMinTempCharacteristic = service.getCharacteristic(UUID.fromString(BleConstants.RESET_MAX_MIN_TEMP_CHAR))
        }
        
        clearDataService?.let { service ->  // ⭐ v1.2 新增
            clearDataCharacteristic = service.getCharacteristic(UUID.fromString(BleConstants.CLEAR_DATA_CHAR))
        }
        
        otaService?.let { service ->  // ⭐ BLE OTA 新增
            otaControlCharacteristic = service.getCharacteristic(UUID.fromString(BleConstants.OTA_CONTROL_CHAR))
            otaDataCharacteristic = service.getCharacteristic(UUID.fromString(BleConstants.OTA_DATA_CHAR))
            otaStatusCharacteristic = service.getCharacteristic(UUID.fromString(BleConstants.OTA_STATUS_CHAR))
        }
        
        Log.d(TAG, "服务和特征已缓存")
    }
    
    /**
     * 读取特征值
     */
    fun readCharacteristic(uuid: String, onRead: (ByteArray?) -> Unit) {
        val characteristic = getCharacteristicByUuid(uuid) ?: run {
            Log.e(TAG, "特征不存在: $uuid")
            onRead(null)
            return
        }
        
        val uuidObj = UUID.fromString(uuid)
        readCallbacks[uuidObj] = onRead
        
        if (gatt?.readCharacteristic(characteristic) == true) {
            Log.d(TAG, "读取特征: $uuid")
        } else {
            Log.e(TAG, "读取特征失败: $uuid")
            readCallbacks.remove(uuidObj)
            onRead(null)
        }
    }

    /**
     * 判断特征是否可用（用于兼容不同固件版本）
     */
    fun isCharacteristicAvailable(uuid: String): Boolean {
        return getCharacteristicByUuid(uuid) != null
    }
    
    /**
     * 写入特征值（同步方式，立即返回）
     */
    fun writeCharacteristic(uuid: String, value: ByteArray, writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT): Boolean {
        val characteristic = getCharacteristicByUuid(uuid) ?: run {
            Log.e(TAG, "特征不存在: $uuid")
            return false
        }
        
        characteristic.value = value
        characteristic.writeType = writeType
        
        return gatt?.writeCharacteristic(characteristic) == true
    }
    
    /**
     * 写入特征值（异步方式，带回调）
     */
    fun writeCharacteristic(uuid: String, value: ByteArray, onWrite: (Boolean) -> Unit, writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
        val characteristic = getCharacteristicByUuid(uuid) ?: run {
            Log.e(TAG, "特征不存在: $uuid")
            onWrite(false)
            return
        }
        
        val uuidObj = UUID.fromString(uuid)
        writeCallbacks[uuidObj] = onWrite
        
        characteristic.value = value
        characteristic.writeType = writeType
        
        if (gatt?.writeCharacteristic(characteristic) != true) {
            Log.e(TAG, "写入特征失败: $uuid")
            writeCallbacks.remove(uuidObj)
            onWrite(false)
        } else {
            Log.d(TAG, "写入特征: $uuid")
        }
    }
    
    /**
     * 启用通知（同步方式，立即返回）
     */
    fun enableNotification(uuid: String, enable: Boolean, onNotification: (ByteArray) -> Unit): Boolean {
        val characteristic = getCharacteristicByUuid(uuid) ?: run {
            Log.e(TAG, "特征不存在: $uuid")
            return false
        }
        
        val uuidObj = UUID.fromString(uuid)
        
        // 设置通知回调
        if (enable) {
            notificationCallbacks[uuidObj] = onNotification
        } else {
            notificationCallbacks.remove(uuidObj)
        }
        
        // 启用/禁用本地通知
        if (gatt?.setCharacteristicNotification(characteristic, enable) != true) {
            Log.e(TAG, "设置通知失败")
            if (enable) {
                notificationCallbacks.remove(uuidObj)
            }
            return false
        }
        
        // 写入 CCCD 描述符
        val descriptor = characteristic.getDescriptor(UUID.fromString(BleConstants.CCCD_DESCRIPTOR))
            ?: run {
                Log.e(TAG, "CCCD 描述符不存在")
                if (enable) {
                    notificationCallbacks.remove(uuidObj)
                }
                return false
            }
        
        val value = if (enable) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }
        
        descriptor.value = value
        if (gatt?.writeDescriptor(descriptor) != true) {
            Log.e(TAG, "写入描述符失败")
            if (enable) {
                notificationCallbacks.remove(uuidObj)
            }
            return false
        }
        
        Log.d(TAG, "通知${if (enable) "已启用" else "已禁用"}: $uuid")
        return true
    }
    
    /**
     * 启用通知（异步方式，带回调，支持重试）
     * 修复ENOMEM错误：添加延迟和重试机制
     */
    fun enableNotificationAsync(
        uuid: String, 
        enable: Boolean, 
        onNotification: (ByteArray) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        val characteristic = getCharacteristicByUuid(uuid) ?: run {
            Log.e(TAG, "特征不存在: $uuid")
            onComplete(false)
            return
        }
        
        val uuidObj = UUID.fromString(uuid)
        
        // ⭐ 修复：如果启用通知，先清理旧的回调，确保状态完全重置
        if (enable) {
            // 先移除旧的回调（如果存在），确保状态完全清理
            notificationCallbacks.remove(uuidObj)
            // 然后设置新的回调
            notificationCallbacks[uuidObj] = onNotification
        } else {
            // 禁用通知时，移除回调
            notificationCallbacks.remove(uuidObj)
        }
        
        // 启用/禁用本地通知
        if (gatt?.setCharacteristicNotification(characteristic, enable) != true) {
            Log.e(TAG, "设置通知失败")
            if (enable) {
                notificationCallbacks.remove(uuidObj)
            }
            onComplete(false)
            return
        }
        
        // 写入 CCCD 描述符（带回调）
        val descriptor = characteristic.getDescriptor(UUID.fromString(BleConstants.CCCD_DESCRIPTOR))
            ?: run {
                Log.e(TAG, "CCCD 描述符不存在")
                if (enable) {
                    notificationCallbacks.remove(uuidObj)
                }
                onComplete(false)
                return
            }
        
        val value = if (enable) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }
        
        descriptor.value = value
        
        // 注册描述符写入回调
        descriptorWriteCallbacks[uuidObj] = { success ->
            if (success) {
                Log.d(TAG, "通知${if (enable) "已启用" else "已禁用"}: $uuid")
                // ⭐ 修复ENOMEM：添加延迟，确保设备端处理完成（参考WEB端：100ms后重试）
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    onComplete(true)
                }, 100) // 100ms延迟，与WEB端保持一致
            } else {
                Log.e(TAG, "描述符写入失败，通知${if (enable) "启用" else "禁用"}失败: $uuid")
                if (enable) {
                    notificationCallbacks.remove(uuidObj)
                }
                onComplete(false)
            }
        }
        
        if (gatt?.writeDescriptor(descriptor) != true) {
            Log.e(TAG, "写入描述符失败")
            descriptorWriteCallbacks.remove(uuidObj)
            if (enable) {
                notificationCallbacks.remove(uuidObj)
            }
            onComplete(false)
        }
    }
    
    /**
     * 根据 UUID 获取特征
     */
    private fun getCharacteristicByUuid(uuid: String): BluetoothGattCharacteristic? {
        val uuidObj = UUID.fromString(uuid)
        return when (uuid) {
            BleConstants.TEMP_CHAR -> tempCharacteristic
            BleConstants.HUMIDITY_CHAR -> humidityCharacteristic
            BleConstants.REALTIME_DATA_CHAR -> realtimeDataCharacteristic  // ⭐ v1.1 新增
            BleConstants.TIME_SYNC_CHAR -> timeSyncCharacteristic
            BleConstants.INTERVAL_CHAR -> intervalCharacteristic
            BleConstants.STATUS_CHAR -> statusCharacteristic
            BleConstants.HISTORY_CHAR -> historyCharacteristic
            BleConstants.HISTORY_INFO_CHAR -> historyInfoCharacteristic
            BleConstants.MAX_MIN_TEMP_CHAR -> maxMinTempCharacteristic
            BleConstants.RESET_MAX_MIN_TEMP_CHAR -> resetMaxMinTempCharacteristic
            BleConstants.CLEAR_DATA_CHAR -> clearDataCharacteristic  // ⭐ v1.2 新增
            BleConstants.OTA_CONTROL_CHAR -> otaControlCharacteristic  // ⭐ BLE OTA 新增
            BleConstants.OTA_DATA_CHAR -> otaDataCharacteristic  // ⭐ BLE OTA 新增
            BleConstants.OTA_STATUS_CHAR -> otaStatusCharacteristic  // ⭐ BLE OTA 新增
            else -> {
                // 尝试从服务中查找
                realtimeDataService?.getCharacteristic(uuidObj)  // ⭐ v1.1 新增：优先从实时数据服务查找
                    ?: essService?.getCharacteristic(uuidObj)
                    ?: configService?.getCharacteristic(uuidObj)
                    ?: clearDataService?.getCharacteristic(uuidObj)  // ⭐ v1.2 新增
                    ?: otaService?.getCharacteristic(uuidObj)  // ⭐ BLE OTA 新增
            }
        }
    }
    
    /**
     * 清理资源
     */
    private fun cleanup() {
        gatt = null
        currentDevice = null
        essService = null
        configService = null
        realtimeDataService = null  // ⭐ v1.1 新增
        clearDataService = null  // ⭐ v1.2 新增
        tempCharacteristic = null
        humidityCharacteristic = null
        realtimeDataCharacteristic = null  // ⭐ v1.1 新增
        timeSyncCharacteristic = null
        intervalCharacteristic = null
        statusCharacteristic = null
        historyCharacteristic = null
        historyInfoCharacteristic = null
        maxMinTempCharacteristic = null
        resetMaxMinTempCharacteristic = null
        clearDataCharacteristic = null  // ⭐ v1.2 新增
        otaService = null  // ⭐ BLE OTA 新增
        otaControlCharacteristic = null
        otaDataCharacteristic = null
        otaStatusCharacteristic = null
        readCallbacks.clear()
        writeCallbacks.clear()
        descriptorWriteCallbacks.clear()
        notificationCallbacks.clear()
    }
    
    /**
     * 连接状态枚举
     */
    enum class ConnectionState {
        Disconnected,
        Connecting,
        Connected,
        ServicesDiscovered
    }
}
