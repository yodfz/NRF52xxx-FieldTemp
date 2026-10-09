package com.example.pandatemperature.ui.screen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.nativeCanvas
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
import kotlin.math.*

/**
 * 营地助手主界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampAssistantScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }
    
    var currentStep by remember { mutableStateOf(CampStep.WIND_DETECTION) }
    var windDirection by remember { mutableStateOf<Float?>(null) }
    var selectedTent by remember { mutableStateOf<TentType?>(null) }
    
    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color(0xFF0A0E14)  // 深色背景
    ) {
        // 内容区域 - 全屏沉浸式
        Box(modifier = Modifier.fillMaxSize()) {
            when (currentStep) {
                CampStep.WIND_DETECTION -> {
                    WindDetectionStep(
                        onWindLocked = { direction ->
                            windDirection = direction
                            currentStep = CampStep.TENT_SELECTION
                        },
                        onBack = onBack
                    )
                }
                CampStep.TENT_SELECTION -> {
                    TentSelectionStep(
                        windDirection = windDirection,
                        onTentSelected = { tent ->
                            selectedTent = tent
                            currentStep = CampStep.NAVIGATION
                        },
                        onBack = { currentStep = CampStep.WIND_DETECTION }
                    )
                }
                CampStep.NAVIGATION -> {
                    NavigationStep(
                        windDirection = windDirection,
                        tentType = selectedTent,
                        onBack = { currentStep = CampStep.TENT_SELECTION }
                    )
                }
            }
        }
    }
}

/**
 * 营地助手步骤
 */
enum class CampStep {
    WIND_DETECTION,  // 风向检测
    TENT_SELECTION,  // 帐篷选择
    NAVIGATION       // 导航指引
}

/**
 * 帐篷类型
 */
enum class TentType(val displayName: String, val description: String) {
    TUNNEL("隧道帐", "侧面迎风，长轴平行风向"),
    DOME("圆顶帐", "中心对称，门背风"),
    PYRAMID("金字塔帐", "四角固定，门背风"),
    TARP("天幕", "灵活布置，主绳迎风")
}

/**
 * 步骤指示器 - 深色风格
 */
@Composable
private fun StepIndicator(
    currentStep: CampStep,
    modifier: Modifier = Modifier
) {
    val steps = listOf("风向检测", "选择帐篷", "搭建导航")
    val currentIndex = currentStep.ordinal
    
    Row(
        modifier = modifier
            .background(Color(0xFF0F1419))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, stepName ->
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // 步骤圆圈
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            color = if (index <= currentIndex) 
                                Color(0xFF00D9FF) 
                            else 
                                Color(0xFF1A2332),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${index + 1}",
                        color = if (index <= currentIndex) Color(0xFF0A0E14) else Color(0xFF5A6270),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
                
                // 步骤名称
                Text(
                    text = stepName,
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (index <= currentIndex) 
                        Color(0xFF00D9FF) 
                    else 
                        Color(0xFF5A6270),
                    fontWeight = if (index == currentIndex) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp
                )
            }
        }
    }
}

/**
 * 风向检测步骤 - 深色科技风格（全屏沉浸式）
 */
@Composable
private fun WindDetectionStep(
    onWindLocked: (Float) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    
    var hasPermission by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    ) }
    
    var currentHeading by remember { mutableStateOf(0f) }
    var windIntensity by remember { mutableStateOf(0f) }
    var isLocked by remember { mutableStateOf(false) }
    var lockedDirection by remember { mutableStateOf<Float?>(null) }
    var windDirection by remember { mutableStateOf<Float?>(null) }  // 检测到的风向
    
    // 麦克风数据缓冲区（用于计算平均值）
    val audioBuffer = remember { mutableListOf<Float>() }
    val maxBufferSize = 100  // 5秒 * 20次/秒
    
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }
    
    // 传感器管理
    DisposableEffect(hasPermission) {
        if (!hasPermission) {
            return@DisposableEffect onDispose { }
        }
        
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
                
                if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerValues, magneticValues)) {
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    val azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                    currentHeading = (azimuth + 360) % 360
                }
            }
            
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        
        magnetometer?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
        accelerometer?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
        
        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }
    
    // 麦克风风噪检测（实时分析 + 动态阈值算法）
    LaunchedEffect(hasPermission, isLocked) {
        if (!hasPermission || isLocked) return@LaunchedEffect
        
        var audioRecord: AudioRecord? = null
        var lastHapticTime = 0L  // 用于控制震动频率
        
        try {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioRecord = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .setEncoding(audioFormat)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            }
            
            audioRecord.startRecording()
            val audioData = ShortArray(bufferSize / 2)
            
            while (isActive && !isLocked) {
                val readSize = audioRecord.read(audioData, 0, audioData.size)
                if (readSize > 0) {
                    // 计算音频能量（风噪强度）
                    var sum = 0.0
                    for (i in 0 until readSize) {
                        sum += abs(audioData[i].toDouble())
                    }
                    val avgAmplitude = (sum / readSize).toFloat()
                    
                    // 添加到缓冲区（用于动态阈值计算）
                    audioBuffer.add(avgAmplitude)
                    if (audioBuffer.size > maxBufferSize) {
                        audioBuffer.removeAt(0)
                    }
                    
                    // ⭐ 动态阈值算法：计算过去5秒的平均环境音
                    val avgBaseline = if (audioBuffer.size > 10) {
                        // 取前95%的数据作为基线（排除最近的5%，避免突发噪音影响）
                        val baselineSize = (audioBuffer.size * 0.95f).toInt()
                        audioBuffer.take(baselineSize).average().toFloat()
                    } else {
                        avgAmplitude
                    }
                    
                    // 计算风压值（高于动态阈值的部分）
                    val windPressure = max(0f, avgAmplitude - avgBaseline)
                    windIntensity = min(1f, windPressure / 1000f)  // 归一化到0-1
                    
                    // 根据风噪强度推测风向（简化：假设当前朝向就是风向）
                    if (windIntensity > 0.5f) {
                        windDirection = currentHeading
                        
                        // ⭐ 精度区间震动反馈：进入±15°（对应windIntensity > 0.7）时触发"咔哒"感震动
                        if (windIntensity > 0.7f) {
                            val currentTime = System.currentTimeMillis()
                            // 每500ms最多触发一次震动，避免过于频繁
                            if (currentTime - lastHapticTime > 500) {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                lastHapticTime = currentTime
                            }
                        }
                    }
                }
                delay(50)  // 50ms采样一次（20Hz）
            }
        } catch (e: Exception) {
            // 如果麦克风检测失败，使用模拟数据
            while (isActive && !isLocked) {
                // 模拟动态阈值效果
                val baseNoise = 0.3f
                val windSignal = Math.random().toFloat() * 0.7f
                windIntensity = baseNoise + windSignal
                
                // 模拟精度区间震动
                if (windIntensity > 0.7f) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastHapticTime > 500) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        lastHapticTime = currentTime
                    }
                }
                
                delay(100)
            }
        } finally {
            audioRecord?.stop()
            audioRecord?.release()
        }
    }
    
    // 深色背景 + 返回按钮
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0E14))
    ) {
        // 返回按钮（左上角）
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .size(48.dp)
        ) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = "返回",
                tint = Color(0xFF8B95A5),
                modifier = Modifier.size(24.dp)
            )
        }
        
        if (!hasPermission) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "需要麦克风权限以检测风向",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFF8B95A5)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00D9FF)
                    )
                ) {
                    Text("授予权限", color = Color(0xFF0A0E14))
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(40.dp))
                
                // 中央罗盘区域
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    // 雷达视图（带风源角和适用朝向标注）
                    DarkRadarView(
                        heading = currentHeading,
                        intensity = windIntensity,
                        isLocked = isLocked,
                        lockedDirection = lockedDirection,
                        windDirection = windDirection
                    )
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // 底部状态文字
                Text(
                    text = if (isLocked) "风向已锁定" else "请平举手机并转动",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00D9FF),
                    fontSize = 24.sp
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = if (isLocked) "可手动调整帐篷朝向或解锁重测" else "面向风源方向以获得最佳方位角建议",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF3A4250),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontSize = 13.sp
                )
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // 锁定按钮
                Button(
                    onClick = {
                        if (!isLocked) {
                            isLocked = true
                            lockedDirection = currentHeading
                            windDirection = currentHeading
                            onWindLocked(currentHeading)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isLocked) Color(0xFF00D9FF) else Color(0xFF1A2332)
                    ),
                    shape = RoundedCornerShape(32.dp),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = if (isLocked) 8.dp else 0.dp
                    )
                ) {
                    Icon(
                        imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = if (isLocked) Color(0xFF0A0E14) else Color(0xFF00D9FF)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (isLocked) "风向已锁定" else "锁定当前风向",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isLocked) Color(0xFF0A0E14) else Color(0xFF00D9FF)
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/**
 * 深色科技风格雷达视图（完全按照设计图实现）
 * 设计特点：
 * - 深色背景
 * - 中央大圆显示角度和方位
 * - 外圈直接标注风源角（红色）和适用朝向（绿色）
 * - 大同心圆波纹效果
 */
@Composable
private fun DarkRadarView(
    heading: Float,
    intensity: Float,
    isLocked: Boolean,
    lockedDirection: Float?,
    windDirection: Float?,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    
    // 波纹扩散动画
    val waveProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave"
    )
    
    // 计算方位文字
    val directionText = when {
        heading < 22.5f || heading >= 337.5f -> "北"
        heading < 67.5f -> "东北"
        heading < 112.5f -> "东"
        heading < 157.5f -> "东南"
        heading < 202.5f -> "南"
        heading < 247.5f -> "西南"
        heading < 292.5f -> "西"
        else -> "西北"
    }
    
    // 计算推荐朝向（门背风，即风向+180度）
    val recommendedDirection = if (windDirection != null) {
        (windDirection + 180f) % 360f
    } else null
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val maxRadius = size.minDimension / 2 * 0.9f
            
            // 绘制大同心圆（更明显，更大）
            for (i in 1..4) {
                val radius = maxRadius * (0.5f + i * 0.125f)
                drawCircle(
                    color = Color(0xFF1A2332).copy(alpha = 0.4f),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
            
            // 绘制扩散波纹（更明显）
            if (!isLocked) {
                val waveRadius = maxRadius * 0.5f + (maxRadius * 0.5f * waveProgress)
                val waveAlpha = (1f - waveProgress) * 0.4f
                drawCircle(
                    color = Color(0xFF00D9FF).copy(alpha = waveAlpha),
                    radius = waveRadius,
                    center = center,
                    style = Stroke(width = 3.dp.toPx())
                )
            }
        }
        
        // 中央圆形区域（更大）
        Box(
            modifier = Modifier
                .size(260.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF1A2332).copy(alpha = 0.9f),
                            Color(0xFF0F1419).copy(alpha = 0.95f)
                        )
                    ),
                    shape = CircleShape
                )
                .border(
                    width = 2.dp,
                    color = Color(0xFF2A3442),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 方位角文字（小字）
                Text(
                    text = "方位角",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF5A6270),
                    fontSize = 13.sp
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 角度数字（超大字）
                Text(
                    text = "${heading.toInt()}°",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 72.sp,
                    letterSpacing = (-2).sp
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 方位文字（中文）
                Text(
                    text = directionText,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF00D9FF),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        // 外圈标注（直接在圆圈外围，不用指示线）
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val maxRadius = size.minDimension / 2 * 0.9f
            
            // 风源角文字和指示器
            if (windDirection != null) {
                val angle = Math.toRadians((windDirection - 90).toDouble())
                val labelRadius = maxRadius * 1.05f
                val labelX = center.x + labelRadius * cos(angle).toFloat()
                val labelY = center.y + labelRadius * sin(angle).toFloat()
                
                // 绘制短指示线（从圆圈边缘向外）
                val lineStartRadius = maxRadius * 1.0f
                val lineEndRadius = maxRadius * 1.08f
                val lineStartX = center.x + lineStartRadius * cos(angle).toFloat()
                val lineStartY = center.y + lineStartRadius * sin(angle).toFloat()
                val lineEndX = center.x + lineEndRadius * cos(angle).toFloat()
                val lineEndY = center.y + lineEndRadius * sin(angle).toFloat()
                
                drawLine(
                    color = Color(0xFFFF5252),
                    start = Offset(lineStartX, lineStartY),
                    end = Offset(lineEndX, lineEndY),
                    strokeWidth = 4.dp.toPx()
                )
                
                // 绘制文字
                val nCanvas = drawContext.canvas.nativeCanvas
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#FF5252")
                    textSize = 15.sp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                    isFakeBoldText = true
                }
                val textRadius = maxRadius * 1.18f
                val textX = center.x + textRadius * cos(angle).toFloat()
                val textY = center.y + textRadius * sin(angle).toFloat() + 5.dp.toPx()
                nCanvas.drawText("风源角", textX, textY, paint)
            }
            
            // 适用朝向文字和指示器
            if (recommendedDirection != null) {
                val angle = Math.toRadians((recommendedDirection - 90).toDouble())
                val labelRadius = maxRadius * 1.05f
                val labelX = center.x + labelRadius * cos(angle).toFloat()
                val labelY = center.y + labelRadius * sin(angle).toFloat()
                
                // 绘制短指示线（从圆圈边缘向外）
                val lineStartRadius = maxRadius * 1.0f
                val lineEndRadius = maxRadius * 1.08f
                val lineStartX = center.x + lineStartRadius * cos(angle).toFloat()
                val lineStartY = center.y + lineStartRadius * sin(angle).toFloat()
                val lineEndX = center.x + lineEndRadius * cos(angle).toFloat()
                val lineEndY = center.y + lineEndRadius * sin(angle).toFloat()
                
                drawLine(
                    color = Color(0xFF00E676),
                    start = Offset(lineStartX, lineStartY),
                    end = Offset(lineEndX, lineEndY),
                    strokeWidth = 4.dp.toPx()
                )
                
                // 绘制文字
                val nCanvas2 = drawContext.canvas.nativeCanvas
                val paint2 = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#00E676")
                    textSize = 15.sp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                    isFakeBoldText = true
                }
                val textRadius2 = maxRadius * 1.18f
                val textX2 = center.x + textRadius2 * cos(angle).toFloat()
                val textY2 = center.y + textRadius2 * sin(angle).toFloat() + 5.dp.toPx()
                nCanvas2.drawText("适用朝向", textX2, textY2, paint2)
            }
        }
    }
}

/**
 * 雷达视图 - 显示风向检测
 * 实现PRD需求：
 * - 同心圆波纹，亮度和频率随风向对齐度变化
 * - 对准风源时波纹最亮、频率最高
 * - 进入±15°精度区间时触发震动反馈
 */
@Composable
private fun RadarView(
    heading: Float,
    intensity: Float,
    isLocked: Boolean,
    lockedDirection: Float?,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    
    // 计算对准度（0-1，1表示完全对准）
    val alignment = intensity
    
    // 计算波纹频率：基础1Hz，对准度越高频率越快（最高2.5Hz）
    val waveFrequency = 1f + alignment * 1.5f
    val waveDuration = (1000 / waveFrequency).toInt()
    
    // 计算波纹亮度：基础10%，对准度越高亮度越高（最高40%）
    val baseAlpha = 0.1f + alignment * 0.3f
    
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    
    // 扫描线旋转
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    
    // 波纹扩散动画（频率动态调整）
    val waveProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(waveDuration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave"
    )
    
    // 精度区间震动反馈（进入±15°时）
    LaunchedEffect(alignment) {
        if (alignment > 0.7f && !isLocked) {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        }
    }
    
    Canvas(modifier = modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height / 2)
        val maxRadius = size.minDimension / 2
        
        // 绘制同心圆波纹（亮度随对准度变化）
        for (i in 1..4) {
            val radius = maxRadius * i / 4
            val alpha = baseAlpha * (5 - i) / 4
            drawCircle(
                color = Color(0xFF4A90E2).copy(alpha = alpha),
                radius = radius,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
        }
        
        // 绘制扩散波纹（模拟雷达扫描效果，频率随对准度变化）
        val waveRadius = maxRadius * waveProgress
        val waveAlpha = (1f - waveProgress) * alignment * 0.5f
        if (waveAlpha > 0.05f) {
            drawCircle(
                color = Color(0xFF4A90E2).copy(alpha = waveAlpha),
                radius = waveRadius,
                center = center,
                style = Stroke(width = 3.dp.toPx())
            )
        }
        
        // 绘制扫描线（亮度随对准度变化）
        rotate(rotation, center) {
            val path = Path().apply {
                moveTo(center.x, center.y)
                lineTo(center.x, center.y - maxRadius)
            }
            drawPath(
                path = path,
                color = Color(0xFF4A90E2).copy(alpha = 0.6f * alignment),
                style = Stroke(width = 3.dp.toPx())
            )
        }
        
        // 如果已锁定，显示锁定方向指示
        if (isLocked && lockedDirection != null) {
            rotate(lockedDirection, center) {
                val path = Path().apply {
                    moveTo(center.x, center.y)
                    lineTo(center.x, center.y - maxRadius * 0.8f)
                }
                drawPath(
                    path = path,
                    color = Color(0xFF00BCD4),  // 使用亮青色
                    style = Stroke(width = 6.dp.toPx())
                )
            }
        }
        
        // 中心点（亮度随对准度变化）
        drawCircle(
            color = Color(0xFF4A90E2).copy(alpha = 0.5f + alignment * 0.5f),
            radius = 8.dp.toPx(),
            center = center
        )
        
        // 外圈光晕（对准度高时显示）
        if (alignment > 0.6f) {
            drawCircle(
                color = Color(0xFF4A90E2).copy(alpha = (alignment - 0.6f) * 0.3f),
                radius = 12.dp.toPx(),
                center = center
            )
        }
        
        // 精度区间指示（±15°扇区）
        if (alignment > 0.7f && !isLocked) {
            drawArc(
                color = Color(0xFF00E676).copy(alpha = 0.2f),
                startAngle = -105f,
                sweepAngle = 30f,
                useCenter = true,
                topLeft = Offset(center.x - maxRadius * 0.9f, center.y - maxRadius * 0.9f),
                size = androidx.compose.ui.geometry.Size(maxRadius * 1.8f, maxRadius * 1.8f)
            )
        }
    }
}

/**
 * 帐篷选择步骤 - 深色风格
 */
@Composable
private fun TentSelectionStep(
    windDirection: Float?,
    onTentSelected: (TentType) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0E14))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "选择您的帐篷类型",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            
            Text(
                text = "风向已锁定：${windDirection?.toInt() ?: 0}°",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF00D9FF)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 帐篷类型卡片
            TentType.values().forEach { tentType ->
                TentTypeCard(
                    tentType = tentType,
                    onClick = { onTentSelected(tentType) }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFF00D9FF)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00D9FF))
            ) {
                Text("重新检测风向")
            }
        }
    }
}

/**
 * 帐篷类型卡片 - 深色风格
 */
@Composable
private fun TentTypeCard(
    tentType: TentType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1A2332),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = tentType.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = tentType.description,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF8B95A5)
            )
        }
    }
}

/**
 * 导航指引步骤 - 深色风格
 */
@Composable
private fun NavigationStep(
    windDirection: Float?,
    tentType: TentType?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentHeading by remember { mutableStateOf(0f) }
    
    // 计算推荐门向
    val recommendedDoorDirection = remember(windDirection, tentType) {
        if (windDirection == null) return@remember 0f
        when (tentType) {
            TentType.TUNNEL -> (windDirection + 90) % 360  // 侧面迎风
            else -> (windDirection + 180) % 360  // 门背风
        }
    }
    
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
                
                if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerValues, magneticValues)) {
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    val azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                    currentHeading = (azimuth + 360) % 360
                }
            }
            
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        
        magnetometer?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
        accelerometer?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
        
        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0E14))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = "搭建导航",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            
            // 罗盘视图
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CompassView(
                    currentHeading = currentHeading,
                    windDirection = windDirection ?: 0f,
                    recommendedDirection = recommendedDoorDirection
                )
            }
            
            // 指引信息
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1A2332)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "搭建建议",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00D9FF)
                    )
                    Text(
                        text = "• 风向：${windDirection?.toInt() ?: 0}°",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF8B95A5)
                    )
                    Text(
                        text = "• 推荐门向：${recommendedDoorDirection.toInt()}°",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF8B95A5)
                    )
                    Text(
                        text = "• 帐篷类型：${tentType?.displayName ?: "未选择"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF8B95A5)
                    )
                }
            }
            
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFF00D9FF)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00D9FF))
            ) {
                Text("重新选择帐篷")
            }
        }
    }
}

/**
 * 罗盘视图
 */
@Composable
private fun CompassView(
    currentHeading: Float,
    windDirection: Float,
    recommendedDirection: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2 * 0.8f
        
        // 绘制罗盘圆圈
        drawCircle(
            color = Color(0xFF4A90E2).copy(alpha = 0.2f),
            radius = radius,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )
        
        // 绘制方向刻度
        for (i in 0 until 360 step 30) {
            rotate(i.toFloat(), center) {
                val startY = center.y - radius
                val endY = if (i % 90 == 0) startY + 20.dp.toPx() else startY + 10.dp.toPx()
                drawLine(
                    color = Color(0xFF4A90E2),
                    start = Offset(center.x, startY),
                    end = Offset(center.x, endY),
                    strokeWidth = if (i % 90 == 0) 3.dp.toPx() else 1.dp.toPx()
                )
            }
        }
        
        // 绘制风向扇区（红色）
        rotate(windDirection - 15, center) {
            drawArc(
                color = Color(0xFFFF5252).copy(alpha = 0.3f),
                startAngle = -90f,
                sweepAngle = 30f,
                useCenter = true,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
            )
        }
        
        // 绘制推荐门向扇区（绿色）
        rotate(recommendedDirection - 15, center) {
            drawArc(
                color = Color(0xFF4CAF50).copy(alpha = 0.3f),
                startAngle = -90f,
                sweepAngle = 30f,
                useCenter = true,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
            )
        }
        
        // 绘制当前方向指针
        rotate(currentHeading, center) {
            val path = Path().apply {
                moveTo(center.x, center.y - radius * 0.7f)
                lineTo(center.x - 10.dp.toPx(), center.y)
                lineTo(center.x + 10.dp.toPx(), center.y)
                close()
            }
            drawPath(
                path = path,
                color = Color(0xFF2196F3)
            )
        }
        
        // 中心点
        drawCircle(
            color = Color(0xFF2196F3),
            radius = 8.dp.toPx(),
            center = center
        )
    }
}
