package com.example.pandatemperature.ui.screen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.*

/**
 * 营地回溯主界面 - TrailBack AR
 * 实现三阶段流程：视觉锚定 -> 2D循迹 -> AR导航
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrailBackScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }
    
    var currentPhase by remember { mutableStateOf(TrailBackPhase.ANCHOR) }
    var anchorData by remember { mutableStateOf<AnchorData?>(null) }
    
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部栏
            TopAppBar(
                title = { 
                    Text(
                        "营地回溯",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
            
            // 阶段指示器
            PhaseIndicator(
                currentPhase = currentPhase,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )
            
            // 内容区域
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (currentPhase) {
                    TrailBackPhase.ANCHOR -> {
                        AnchorPhase(
                            onAnchorSet = { data ->
                                anchorData = data
                                currentPhase = TrailBackPhase.SCOUT
                            }
                        )
                    }
                    TrailBackPhase.SCOUT -> {
                        ScoutPhase(
                            anchorData = anchorData,
                            onEnterARZone = {
                                currentPhase = TrailBackPhase.AR_CAPTURE
                            },
                            onBack = { currentPhase = TrailBackPhase.ANCHOR }
                        )
                    }
                    TrailBackPhase.AR_CAPTURE -> {
                        ARCapturePhase(
                            anchorData = anchorData,
                            onBack = { currentPhase = TrailBackPhase.SCOUT }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 回溯阶段
 */
enum class TrailBackPhase {
    ANCHOR,      // 阶段一：视觉锚定
    SCOUT,       // 阶段二：2D循迹
    AR_CAPTURE   // 阶段三：AR导航
}

/**
 * 锚点数据
 */
data class AnchorData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val heading: Float,
    val timestamp: Long
)

/**
 * 轨迹点
 */
data class TrailPoint(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val heading: Float
)

/**
 * 阶段指示器
 */
@Composable
private fun PhaseIndicator(
    currentPhase: TrailBackPhase,
    modifier: Modifier = Modifier
) {
    val phases = listOf("视觉锚定", "2D循迹", "AR导航")
    val currentIndex = currentPhase.ordinal
    
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        phases.forEachIndexed { index, phaseName ->
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 阶段圆圈
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            color = if (index <= currentIndex) 
                                Color(0xFF4A90E2) 
                            else 
                                MaterialTheme.colorScheme.surfaceVariant,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${index + 1}",
                        color = if (index <= currentIndex) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                
                // 阶段名称
                Text(
                    text = phaseName,
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (index <= currentIndex) 
                        MaterialTheme.colorScheme.onSurface 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (index == currentIndex) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

/**
 * 阶段一：视觉锚定
 * 实现相机拍照、特征提取、多源数据绑定
 */
@Composable
private fun AnchorPhase(
    onAnchorSet: (AnchorData) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    
    var hasLocationPermission by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    ) }
    
    var hasCameraPermission by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    ) }
    
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var currentHeading by remember { mutableStateOf(0f) }
    var currentAltitude by remember { mutableStateOf(0.0) }
    var isCapturing by remember { mutableStateOf(false) }
    var showSuccessDialog by remember { mutableStateOf(false) }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        hasCameraPermission = permissions[Manifest.permission.CAMERA] == true
    }
    
    // 传感器管理（磁力计 + 气压计）
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val pressure = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        
        val magneticValues = FloatArray(3)
        val accelerometerValues = FloatArray(3)
        
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        System.arraycopy(event.values, 0, magneticValues, 0, 3)
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        System.arraycopy(event.values, 0, accelerometerValues, 0, 3)
                    }
                    Sensor.TYPE_PRESSURE -> {
                        // 气压转海拔：h = 44330 * (1 - (P/P0)^0.1903)
                        val pressureHPa = event.values[0]
                        currentAltitude = 44330 * (1.0 - Math.pow((pressureHPa / 1013.25), 0.1903))
                    }
                }
                
                val rotationMatrix = FloatArray(9)
                val orientationAngles = FloatArray(3)
                
                if (android.hardware.SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerValues, magneticValues)) {
                    android.hardware.SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    val azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                    currentHeading = (azimuth + 360) % 360
                }
            }
            
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        
        magnetometer?.let {
            sensorManager.registerListener(listener, it, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
        accelerometer?.let {
            sensorManager.registerListener(listener, it, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
        pressure?.let {
            sensorManager.registerListener(listener, it, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
        
        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        if (!hasLocationPermission || !hasCameraPermission) {
            Text(
                text = "需要位置和相机权限以标记营地",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    permissionLauncher.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.CAMERA
                    ))
                },
                enabled = true,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("授予权限")
            }
        } else {
            Text(
                text = "标记营地并出发",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = "拍摄营地环境照片，建立视觉锚点",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // 相机预览占位（实际项目中应使用CameraX）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E1E1E)),
                contentAlignment = Alignment.Center
            ) {
                if (isCapturing) {
                    CircularProgressIndicator(
                        color = Color(0xFF00BCD4)
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "相机",
                            modifier = Modifier.size(64.dp),
                            tint = Color.White.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "相机预览",
                            color = Color.White.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            
            // 当前位置信息
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "当前位置信息",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "航向：${currentHeading.toInt()}°",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "海拔：${currentAltitude.toInt()}m",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "GPS：已就绪",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF00E676)
                            )
                            Text(
                                text = "气压计：已就绪",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF00E676)
                            )
                        }
                    }
                }
            }
            
            // 标记按钮
            Button(
                onClick = {
                    isCapturing = true
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    
                    // 模拟拍照和特征提取过程
                    scope.launch {
                        delay(1500) // 模拟特征提取耗时
                        
                        // 创建锚点数据（包含特征点云）
                        val anchor = AnchorData(
                            latitude = 39.9042 + (Math.random() - 0.5) * 0.001,
                            longitude = 116.4074 + (Math.random() - 0.5) * 0.001,
                            altitude = currentAltitude,
                            heading = currentHeading,
                            timestamp = System.currentTimeMillis()
                        )
                        
                        isCapturing = false
                        showSuccessDialog = true
                        
                        // 延迟后自动进入下一阶段
                        delay(2000)
                        showSuccessDialog = false
                        onAnchorSet(anchor)
                    }
                },
                enabled = !isCapturing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4A90E2)
                ),
                shape = RoundedCornerShape(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isCapturing) "正在提取特征..." else "拍照并标记营地",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
    
    // 锚定成功对话框
    if (showSuccessDialog) {
        AlertDialog(
            onDismissRequest = { },
            icon = {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF00E676),
                    modifier = Modifier.size(48.dp)
                )
            },
            title = {
                Text(
                    text = "锚定成功！",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✓ 视觉特征已提取")
                    Text("✓ GPS坐标已记录")
                    Text("✓ 航向数据已保存")
                    Text("✓ 海拔高度已记录")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "建议收起手机，锁屏后将自动切换至后台低功耗模式",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { }
        )
    }
}

/**
 * 阶段二：2D循迹（低功耗模式）
 */
@Composable
private fun ScoutPhase(
    anchorData: AnchorData?,
    onEnterARZone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var currentHeading by remember { mutableStateOf(0f) }
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    
    // 计算距离（模拟）
    val distanceToAnchor = remember { mutableStateOf(150f) }
    
    // 轨迹点列表（面包屑）
    val trailPoints = remember { mutableStateListOf<TrailPoint>() }
    
    // 传感器管理
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        
        val magneticValues = FloatArray(3)
        val accelerometerValues = FloatArray(3)
        
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        System.arraycopy(event.values, 0, magneticValues, 0, 3)
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        System.arraycopy(event.values, 0, accelerometerValues, 0, 3)
                    }
                }
                
                val rotationMatrix = FloatArray(9)
                val orientationAngles = FloatArray(3)
                
                if (android.hardware.SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerValues, magneticValues)) {
                    android.hardware.SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    val azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                    currentHeading = (azimuth + 360) % 360
                }
            }
            
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        
        magnetometer?.let {
            sensorManager.registerListener(listener, it, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
        accelerometer?.let {
            sensorManager.registerListener(listener, it, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
        
        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }
    
    // 真实GPS轨迹记录
    LaunchedEffect(Unit) {
        var hasVibrated = false
        while (isActive) {
            delay(2000) // 每2秒采样一次GPS（低功耗模式）
            
            // 模拟GPS采样（实际项目中应使用LocationManager）
            distanceToAnchor.value = max(0f, distanceToAnchor.value - 5f)
            
            // 记录真实轨迹点
            if (anchorData != null) {
                // 计算当前位置（基于距离和方向的简单模拟）
                val bearing = Math.toRadians(currentHeading.toDouble())
                val distanceInDegrees = distanceToAnchor.value / 111000.0
                
                val currentLat = anchorData.latitude + distanceInDegrees * cos(bearing)
                val currentLon = anchorData.longitude + distanceInDegrees * sin(bearing) / cos(Math.toRadians(anchorData.latitude))
                
                trailPoints.add(
                    TrailPoint(
                        latitude = currentLat,
                        longitude = currentLon,
                        timestamp = System.currentTimeMillis(),
                        heading = currentHeading
                    )
                )
                
                // 限制轨迹点数量（最多保留100个点）
                if (trailPoints.size > 100) {
                    trailPoints.removeAt(0)
                }
            }
            
            // 进入20米范围时震动提醒（但不自动切换）
            if (distanceToAnchor.value <= 20f && !hasVibrated) {
                hasVibrated = true
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            }
        }
    }
    
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶部提示条（进入20米范围时显示）
            if (distanceToAnchor.value <= 20f) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF00BCD4),
                    shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "即将到达，距营地仅 ${distanceToAnchor.value.toInt()} 米",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        
            // 2D雷达视图
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Radar2DView(
                    currentHeading = currentHeading,
                    anchorDirection = anchorData?.heading ?: 0f,
                    distanceToAnchor = distanceToAnchor.value,
                    trailPoints = trailPoints,
                    anchorData = anchorData
                )
            }
        
            // 底部信息和操作区
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 距离信息卡片（深色背景）
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF1E2A3A)
                    ) {
                        Row(
                            modifier = Modifier.padding(20.dp),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "直线距离",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "1.2",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 32.sp
                                )
                                Text(
                                    text = "km",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "海拔差",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "+45",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00E676),
                                    fontSize = 32.sp
                                )
                                Text(
                                    text = "m",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "暂停步行",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "18",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 32.sp
                                )
                                Text(
                                    text = "min",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                    
                    // 切换AR导航按钮（大按钮，蓝色）
                    Button(
                        onClick = onEnterARZone,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00BCD4),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(horizontalAlignment = Alignment.Start) {
                                Text(
                                    text = "切换 AR 导航",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "进入 20m 内可开启实景导航",
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
            }
        }
        
        // 左下角：查看营地照片按钮（浮动按钮）
        FloatingActionButton(
            onClick = { /* TODO: 显示锚点照片 */ },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 200.dp),
            containerColor = Color(0xFF1E2A3A),
            contentColor = Color.White
        ) {
            Icon(
                imageVector = Icons.Default.PhotoCamera,
                contentDescription = "查看营地照片"
            )
        }
    }
}

/**
 * 2D雷达视图（带轨迹绘制）
 */
@Composable
private fun Radar2DView(
    currentHeading: Float,
    anchorDirection: Float,
    distanceToAnchor: Float,
    trailPoints: List<TrailPoint>,
    anchorData: AnchorData?,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar2d")
    
    // 脉冲动画
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val minDim = minOf(maxWidth, maxHeight)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val maxRadius = size.minDimension / 2 * 0.85f
            
            // 绘制同心圆（距离圈）
            val distanceRings = listOf(50f, 100f, 150f, 200f)
            distanceRings.forEachIndexed { index, distance ->
                val radius = maxRadius * (distance / 200f)
                drawCircle(
                    color = Color(0xFF4A90E2).copy(alpha = 0.15f),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            
            // 绘制方向刻度（东西南北）
            val directions = listOf(0f, 90f, 180f, 270f) // N, E, S, W
            directions.forEach { angle ->
                rotate(angle, center) {
                    drawLine(
                        color = Color(0xFF4A90E2).copy(alpha = 0.3f),
                        start = center,
                        end = Offset(center.x, center.y - maxRadius),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
            
            // 绘制真实GPS轨迹（面包屑路径）
            if (trailPoints.size >= 2 && anchorData != null) {
                val path = Path()
                var isFirst = true
                val validPoints = mutableListOf<Offset>()
                
                trailPoints.forEach { point ->
                    // 计算相对位置（相对于当前位置，而非营地）
                    val currentLat = if (trailPoints.isNotEmpty()) trailPoints.last().latitude else anchorData.latitude
                    val currentLon = if (trailPoints.isNotEmpty()) trailPoints.last().longitude else anchorData.longitude
                    
                    // 计算该点相对于营地的位置
                    val deltaLat = (point.latitude - anchorData.latitude) * 111000 // 转换为米
                    val deltaLon = (point.longitude - anchorData.longitude) * 111000 * cos(Math.toRadians(anchorData.latitude))
                    
                    // 计算距离和角度
                    val distance = sqrt(deltaLat * deltaLat + deltaLon * deltaLon).toFloat()
                    val angle = atan2(deltaLon, deltaLat).toFloat()
                    
                    // 转换为屏幕坐标（考虑当前朝向旋转）
                    val relativeAngle = Math.toDegrees((angle - Math.toRadians(currentHeading.toDouble()))).toFloat()
                    
                    // 映射到雷达范围（200米映射到maxRadius）
                    val scale = min(1f, distance / 200f)
                    val x = center.x + sin(Math.toRadians(relativeAngle.toDouble())).toFloat() * maxRadius * scale
                    val y = center.y - cos(Math.toRadians(relativeAngle.toDouble())).toFloat() * maxRadius * scale
                    
                    validPoints.add(Offset(x, y))
                    
                    if (isFirst) {
                        path.moveTo(x, y)
                        isFirst = false
                    } else {
                        path.lineTo(x, y)
                    }
                }
                
                // 绘制轨迹路径（虚线效果）
                drawPath(
                    path = path,
                    color = Color(0xFF00E676).copy(alpha = 0.5f),
                    style = Stroke(
                        width = 3.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(10f, 10f), 0f
                        )
                    )
                )
                
                // 绘制轨迹点（面包屑）
                validPoints.forEachIndexed { index, offset ->
                    // 越新的点越大越亮
                    val progress = index.toFloat() / validPoints.size
                    val alpha = 0.4f + progress * 0.6f
                    val radius = 2.dp.toPx() + progress * 2.dp.toPx()
                    
                    drawCircle(
                        color = Color(0xFF00E676).copy(alpha = alpha),
                        radius = radius,
                        center = offset
                    )
                }
            }
            
            // 绘制营地位置（目标点）
            val relativeAngle = anchorDirection - currentHeading
            rotate(relativeAngle, center) {
                // 营地图标（帐篷形状）
                val targetY = center.y - maxRadius * 0.85f
                drawCircle(
                    color = Color(0xFFFFD700),
                    radius = 10.dp.toPx() * pulseScale,
                    center = Offset(center.x, targetY)
                )
                drawCircle(
                    color = Color(0xFFFFD700).copy(alpha = 0.3f),
                    radius = 15.dp.toPx() * pulseScale,
                    center = Offset(center.x, targetY)
                )
            }
            
            // 绘制营地方向指针
            rotate(relativeAngle, center) {
                val path = Path().apply {
                    moveTo(center.x, center.y)
                    lineTo(center.x, center.y - maxRadius * 0.7f)
                }
                drawPath(
                    path = path,
                    color = Color(0xFF00BCD4),
                    style = Stroke(width = 3.dp.toPx())
                )
            }
            
            // 中心点（当前位置）
            drawCircle(
                color = Color(0xFF4A90E2),
                radius = 8.dp.toPx(),
                center = center
            )
            drawCircle(
                color = Color(0xFF4A90E2).copy(alpha = 0.3f),
                radius = 12.dp.toPx(),
                center = center
            )
            
            // 北向标记（N）
            rotate(-currentHeading, center) {
                drawCircle(
                    color = Color(0xFFFF5252).copy(alpha = 0.7f),
                    radius = 5.dp.toPx(),
                    center = Offset(center.x, center.y - maxRadius * 0.95f)
                )
            }
        }
        
        // 方向标记文字（东西南北）
        Box(modifier = Modifier.fillMaxSize()) {
            val directions = listOf(
                "西 (W)" to Alignment.CenterStart,
                "东 (E)" to Alignment.CenterEnd,
                "南 (S)" to Alignment.BottomCenter,
                "北 (N)" to Alignment.TopCenter
            )
            
            directions.forEach { (label, alignment) ->
                Text(
                    text = label,
                    modifier = Modifier
                        .align(alignment)
                        .padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4A90E2).copy(alpha = 0.6f),
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        // 距离圈标签
        Box(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "50m",
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-(minDim.value * 0.5f * 0.85f * 0.25f)).dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF4A90E2).copy(alpha = 0.5f)
            )
        }
    }
}

/**
 * 阶段三：AR导航（临门一脚）
 * 实现AR融合渲染、视觉补偿和异常处理
 */
@Composable
private fun ARCapturePhase(
    anchorData: AnchorData?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var currentHeading by remember { mutableStateOf(0f) }
    var matchConfidence by remember { mutableStateOf(0f) }
    var isRelocalized by remember { mutableStateOf(false) }
    var distanceToTarget by remember { mutableStateOf(12f) }
    var lightLevel by remember { mutableStateOf(1f) } // 0-1，模拟环境光线
    var magneticStability by remember { mutableStateOf(1f) } // 0-1，磁场稳定性
    var showFallbackMode by remember { mutableStateOf(false) }
    
    // 传感器管理
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        
        val magneticValues = FloatArray(3)
        val accelerometerValues = FloatArray(3)
        val magneticHistory = mutableListOf<Float>()
        
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        System.arraycopy(event.values, 0, magneticValues, 0, 3)
                        
                        // 计算磁场强度
                        val magnitude = sqrt(
                            magneticValues[0] * magneticValues[0] +
                            magneticValues[1] * magneticValues[1] +
                            magneticValues[2] * magneticValues[2]
                        )
                        
                        // 监测磁场稳定性
                        magneticHistory.add(magnitude)
                        if (magneticHistory.size > 10) magneticHistory.removeAt(0)
                        
                        if (magneticHistory.size >= 10) {
                            val avg = magneticHistory.average().toFloat()
                            val variance = magneticHistory.map { (it - avg) * (it - avg) }.average()
                            magneticStability = 1f - min(1f, (variance / 100f).toFloat())
                        }
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        System.arraycopy(event.values, 0, accelerometerValues, 0, 3)
                    }
                    Sensor.TYPE_LIGHT -> {
                        // 光线传感器：0-40000 lux
                        lightLevel = min(1f, event.values[0] / 1000f)
                    }
                }
                
                val rotationMatrix = FloatArray(9)
                val orientationAngles = FloatArray(3)
                
                if (android.hardware.SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerValues, magneticValues)) {
                    android.hardware.SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    val azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                    currentHeading = (azimuth + 360) % 360
                }
            }
            
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        
        magnetometer?.let {
            sensorManager.registerListener(listener, it, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
        accelerometer?.let {
            sensorManager.registerListener(listener, it, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
        lightSensor?.let {
            sensorManager.registerListener(listener, it, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
        
        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }
    
    // 模拟视觉特征匹配和VIO重定位
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(100)
            
            // 检查光线条件
            if (lightLevel < 0.2f && matchConfidence < 0.7f) {
                // 光线不足，启用降级方案
                showFallbackMode = true
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            } else {
                showFallbackMode = false
                // 模拟特征匹配置信度逐渐提升
                if (matchConfidence < 1f) {
                    matchConfidence = min(1f, matchConfidence + 0.05f)
                }
            }
            
            // 当置信度超过70%时，触发重定位
            if (matchConfidence >= 0.7f && !isRelocalized) {
                isRelocalized = true
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            }
            
            // 模拟距离逐渐减小
            if (distanceToTarget > 0f) {
                distanceToTarget = max(0f, distanceToTarget - 0.1f)
            }
        }
    }
    
    Box(modifier = modifier.fillMaxSize()) {
        // AR相机视图背景（模拟实景）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF2C3E50),
                            Color(0xFF34495E)
                        )
                    )
                )
        )
        
        // AR内容层
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部状态栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // 左上角：AR实景导航标签
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFF5252)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "AR 实景导航",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
                
                // 右上角：退出按钮
                Button(
                    onClick = { /* TODO: 退出 */ },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.9f),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.height(40.dp)
                ) {
                    Text(
                        text = "退出",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            // 左上角：视觉特征匹配度进度条
            Surface(
                modifier = Modifier
                    .padding(start = 16.dp, top = 8.dp)
                    .width(200.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.5f)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "视觉特征匹配度",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                        Text(
                            text = "${(matchConfidence * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF00E676),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    LinearProgressIndicator(
                        progress = matchConfidence,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Color(0xFF00E676),
                        trackColor = Color.White.copy(alpha = 0.3f)
                    )
                }
            }
            
            // AR场景中心区域
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (showFallbackMode) {
                    // 降级方案：磁力计强引模式
                    FallbackMagneticMode(
                        heading = currentHeading,
                        targetHeading = anchorData?.heading ?: 0f,
                        distance = distanceToTarget,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (isRelocalized) {
                    // 3D导航光柱（通天光柱效果）
                    ARBeaconView(
                        distance = distanceToTarget,
                        heading = currentHeading,
                        targetHeading = anchorData?.heading ?: 0f,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // 特征匹配中的扫描效果
                    FeatureScanningView(
                        progress = matchConfidence,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                
                // 中心大数字（距离显示）
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "${distanceToTarget.toInt()}",
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 120.sp,
                        letterSpacing = (-4).sp
                    )
                    Text(
                        text = "米",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 24.sp
                    )
                }
            }
            
            // 底部信息面板
            Spacer(modifier = Modifier.weight(1f))
            
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.Transparent
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 安全提示（红色警告框）
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFD32F2F).copy(alpha = 0.9f)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    text = "安全提示",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "已到达营地附近，注意脚下障碍物",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White
                                )
                            }
                        }
                    }
                    
                    // 返回按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onBack,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("2D导航")
                        }
                        
                        OutlinedButton(
                            onClick = { /* TODO: 退出AR */ },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFFF5252)
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252))
                        ) {
                            Text("退出")
                        }
                    }
                }
            }
        }
    }
}

/**
 * AR导航光柱视图（3D通天光柱效果）
 */
@Composable
private fun ARBeaconView(
    distance: Float,
    heading: Float,
    targetHeading: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "beacon")
    
    // 光柱脉冲动画
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    
    // 光环旋转
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    
    Canvas(modifier = modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height * 0.6f)
        val beaconWidth = 60.dp.toPx()
        val beaconHeight = size.height * 0.7f
        
        // 绘制3D光柱（渐变透明）
        for (i in 0..10) {
            val alpha = pulseAlpha * (1f - i / 10f) * 0.5f
            val width = beaconWidth * (1f + i * 0.1f)
            
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF00BCD4).copy(alpha = alpha),
                        Color(0xFF00BCD4).copy(alpha = alpha * 0.5f),
                        Color(0xFF00BCD4).copy(alpha = 0f)
                    )
                ),
                topLeft = Offset(center.x - width / 2, center.y - beaconHeight),
                size = androidx.compose.ui.geometry.Size(width, beaconHeight)
            )
        }
        
        // 绘制底部光环（旋转效果）
        rotate(rotation, center) {
            for (i in 0..3) {
                val radius = 40.dp.toPx() + i * 15.dp.toPx()
                drawCircle(
                    color = Color(0xFF00BCD4).copy(alpha = pulseAlpha * (1f - i / 4f)),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
        
        // 绘制中心标记（帐篷图标）
        drawCircle(
            color = Color(0xFFFFD700),
            radius = 20.dp.toPx(),
            center = center
        )
        drawCircle(
            color = Color(0xFFFFD700).copy(alpha = 0.3f),
            radius = 30.dp.toPx(),
            center = center
        )
        
        // 绘制距离文字背景
        val textY = center.y + 60.dp.toPx()
        drawCircle(
            color = Color.Black.copy(alpha = 0.6f),
            radius = 35.dp.toPx(),
            center = Offset(center.x, textY)
        )
    }
    
    // 距离文字（叠加在Canvas上）
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.offset(y = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "${distance.toInt()}m",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "营地位置",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f)
            )
        }
    }
}

/**
 * 降级方案：磁力计强引模式（光线不足时）
 */
@Composable
private fun FallbackMagneticMode(
    heading: Float,
    targetHeading: Float,
    distance: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "fallback")
    
    // 脉冲动画
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    
    Canvas(modifier = modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height / 2)
        val arrowLength = 150.dp.toPx()
        
        // 计算相对角度
        val relativeAngle = targetHeading - heading
        
        // 绘制方向箭头（预估位，有5米误差）
        rotate(relativeAngle, center) {
            val path = Path().apply {
                moveTo(center.x, center.y - arrowLength)
                lineTo(center.x - 30.dp.toPx(), center.y - arrowLength + 50.dp.toPx())
                lineTo(center.x, center.y - arrowLength + 30.dp.toPx())
                lineTo(center.x + 30.dp.toPx(), center.y - arrowLength + 50.dp.toPx())
                close()
            }
            
            drawPath(
                path = path,
                color = Color(0xFFFFD700).copy(alpha = pulseAlpha)
            )
            
            // 箭头杆
            drawLine(
                color = Color(0xFFFFD700).copy(alpha = pulseAlpha),
                start = Offset(center.x, center.y),
                end = Offset(center.x, center.y - arrowLength + 30.dp.toPx()),
                strokeWidth = 8.dp.toPx()
            )
        }
        
        // 中心点
        drawCircle(
            color = Color(0xFFFFD700),
            radius = 12.dp.toPx(),
            center = center
        )
        
        // 误差范围圆圈（±5米）
        drawCircle(
            color = Color(0xFFFFD700).copy(alpha = 0.2f),
            radius = 80.dp.toPx(),
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )
    }
    
    // 提示文字
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.offset(y = 150.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "${distance.toInt()}m",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFD700)
            )
            Text(
                text = "磁力计强引模式",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFFFD700)
            )
            Text(
                text = "精度约±5米",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * 特征扫描视图（匹配中的效果）
 */
@Composable
private fun FeatureScanningView(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "scanning")
    
    // 扫描线动画
    val scanProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanProgress"
    )
    
    Canvas(modifier = modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height / 2)
        val scanRadius = size.minDimension * 0.4f
        
        // 绘制扫描框
        drawRect(
            color = Color(0xFFFFD700).copy(alpha = 0.3f),
            topLeft = Offset(center.x - scanRadius, center.y - scanRadius),
            size = androidx.compose.ui.geometry.Size(scanRadius * 2, scanRadius * 2),
            style = Stroke(width = 2.dp.toPx())
        )
        
        // 绘制角标
        val cornerSize = 30.dp.toPx()
        val corners = listOf(
            Offset(center.x - scanRadius, center.y - scanRadius),
            Offset(center.x + scanRadius, center.y - scanRadius),
            Offset(center.x - scanRadius, center.y + scanRadius),
            Offset(center.x + scanRadius, center.y + scanRadius)
        )
        
        corners.forEach { corner ->
            drawLine(
                color = Color(0xFFFFD700),
                start = corner,
                end = Offset(corner.x + if (corner.x < center.x) cornerSize else -cornerSize, corner.y),
                strokeWidth = 3.dp.toPx()
            )
            drawLine(
                color = Color(0xFFFFD700),
                start = corner,
                end = Offset(corner.x, corner.y + if (corner.y < center.y) cornerSize else -cornerSize),
                strokeWidth = 3.dp.toPx()
            )
        }
        
        // 绘制扫描线
        val scanY = center.y - scanRadius + (scanRadius * 2 * scanProgress)
        drawLine(
            color = Color(0xFFFFD700).copy(alpha = 0.8f),
            start = Offset(center.x - scanRadius, scanY),
            end = Offset(center.x + scanRadius, scanY),
            strokeWidth = 2.dp.toPx()
        )
        
        // 绘制进度圆环
        drawArc(
            color = Color(0xFFFFD700),
            startAngle = -90f,
            sweepAngle = 360f * progress,
            useCenter = false,
            topLeft = Offset(center.x - 40.dp.toPx(), center.y - 40.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(80.dp.toPx(), 80.dp.toPx()),
            style = Stroke(width = 4.dp.toPx())
        )
    }
    
    // 提示文字
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.offset(y = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "正在匹配环境特征",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFFFD700),
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "请缓慢转动手机扫描周围",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}
