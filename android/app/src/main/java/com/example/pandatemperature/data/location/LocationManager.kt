package com.example.pandatemperature.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 定位管理器
 * 封装 FusedLocationProviderClient，提供获取当前位置的功能
 */
class LocationManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "LocationManager"
        
        // GPS 获取超时时间（毫秒）
        const val GPS_TIMEOUT_MS = 30000L
        
        // 定位更新间隔（毫秒）
        private const val LOCATION_UPDATE_INTERVAL_MS = 10000L
        private const val LOCATION_FASTEST_INTERVAL_MS = 5000L
        
        @Volatile
        private var INSTANCE: LocationManager? = null
        
        fun getInstance(context: Context): LocationManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LocationManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
    
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    
    // 当前位置
    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()
    
    // 是否正在获取位置
    private val _isLocating = MutableStateFlow(false)
    val isLocating: StateFlow<Boolean> = _isLocating.asStateFlow()
    
    // 位置更新回调
    private var locationCallback: LocationCallback? = null
    
    /**
     * 检查是否有定位权限
     */
    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * 获取当前位置（带超时）
     * @param timeoutMs 超时时间（毫秒），默认30秒
     * @return Location? 位置信息，超时或无权限返回 null
     */
    suspend fun getCurrentLocation(timeoutMs: Long = GPS_TIMEOUT_MS): Location? {
        if (!hasLocationPermission()) {
            Log.w(TAG, "没有定位权限")
            return null
        }
        
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                try {
                    // 先尝试获取最后已知位置
                    fusedLocationClient.lastLocation
                        .addOnSuccessListener { location ->
                            if (location != null) {
                                Log.d(TAG, "获取到最后已知位置: ${location.latitude}, ${location.longitude}")
                                _currentLocation.value = location
                                if (continuation.isActive) {
                                    continuation.resume(location)
                                }
                            } else {
                                // 最后已知位置为空，请求新位置
                                Log.d(TAG, "最后已知位置为空，请求新位置")
                                requestNewLocation { newLocation ->
                                    if (continuation.isActive) {
                                        continuation.resume(newLocation)
                                    }
                                }
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "获取最后已知位置失败: ${e.message}")
                            // 失败时尝试请求新位置
                            requestNewLocation { newLocation ->
                                if (continuation.isActive) {
                                    continuation.resume(newLocation)
                                }
                            }
                        }
                    
                    continuation.invokeOnCancellation {
                        stopLocationUpdates()
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "安全异常: ${e.message}")
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        }
    }
    
    /**
     * 请求新位置
     */
    private fun requestNewLocation(callback: (Location?) -> Unit) {
        if (!hasLocationPermission()) {
            callback(null)
            return
        }
        
        try {
            _isLocating.value = true
            
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                LOCATION_UPDATE_INTERVAL_MS
            )
                .setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL_MS)
                .setMaxUpdates(1) // 只获取一次
                .build()
            
            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    _isLocating.value = false
                    val location = result.lastLocation
                    if (location != null) {
                        Log.d(TAG, "获取到新位置: ${location.latitude}, ${location.longitude}")
                        _currentLocation.value = location
                    }
                    callback(location)
                    stopLocationUpdates()
                }
            }
            
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "安全异常: ${e.message}")
            _isLocating.value = false
            callback(null)
        }
    }
    
    /**
     * 启动持续位置更新
     */
    fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "没有定位权限，无法启动位置更新")
            return
        }
        
        try {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                LOCATION_UPDATE_INTERVAL_MS
            )
                .setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL_MS)
                .build()
            
            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val location = result.lastLocation
                    if (location != null) {
                        _currentLocation.value = location
                    }
                }
            }
            
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
            
            Log.d(TAG, "已启动位置更新")
        } catch (e: SecurityException) {
            Log.e(TAG, "启动位置更新失败: ${e.message}")
        }
    }
    
    /**
     * 停止位置更新
     */
    fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
        }
        _isLocating.value = false
        Log.d(TAG, "已停止位置更新")
    }
    
    /**
     * 获取当前缓存的位置
     * 不发起新的定位请求，仅返回已缓存的位置
     */
    fun getCachedLocation(): Location? {
        return _currentLocation.value
    }
    
    /**
     * 清除缓存的位置
     */
    fun clearCachedLocation() {
        _currentLocation.value = null
    }
}
