package com.example.pandatemperature.ui.animation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 动画工具类 - 提供各种常用动画效果
 */

// 标准动画时长
object AnimationDurations {
    const val FAST = 200
    const val NORMAL = 300
    const val SLOW = 500
    const val VERY_SLOW = 800
}

// 标准缓动曲线（显式类型避免类型检查递归问题；SpringEasing 避免与 Spring 类同名）
object AnimationEasing {
    val FastOutSlowIn: Easing = FastOutSlowInEasing
    val EaseInOut: Easing = EaseInOutCubic
    val EaseOut: Easing = androidx.compose.animation.core.EaseOut
    val SpringEasing: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow
    )
}

/**
 * 卡片进入动画 - 从下方滑入并淡入
 */
fun Modifier.slideInFromBottom(
    visible: Boolean,
    delay: Int = 0,
    duration: Int = AnimationDurations.NORMAL
): Modifier = composed {
    val offsetY by animateDpAsState(
        targetValue = if (visible) 0.dp else 30.dp,
        animationSpec = tween(
            durationMillis = duration,
            delayMillis = delay,
            easing = AnimationEasing.FastOutSlowIn
        ),
        label = "slideInY"
    )
    
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = duration,
            delayMillis = delay,
            easing = AnimationEasing.FastOutSlowIn
        ),
        label = "slideInAlpha"
    )
    
    this.graphicsLayer {
        translationY = offsetY.toPx()
        this.alpha = alpha
    }
}

/**
 * 脉冲动画 - 用于连接状态指示器
 */
@Composable
fun rememberPulseAnimation(
    enabled: Boolean,
    minScale: Float = 0.9f,
    maxScale: Float = 1.1f,
    duration: Int = 1000
): Float {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    
    val scale by infiniteTransition.animateFloat(
        initialValue = minScale,
        targetValue = maxScale,
        animationSpec = infiniteRepeatable(
            animation = tween(duration, easing = AnimationEasing.EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    
    return if (enabled) scale else 1f
}

/**
 * 数值动画 - 平滑过渡数值变化
 */
@Composable
fun animateFloatValue(
    targetValue: Float,
    duration: Int = AnimationDurations.NORMAL
): Float {
    return animateFloatAsState(
        targetValue = targetValue,
        animationSpec = tween(
            durationMillis = duration,
            easing = AnimationEasing.FastOutSlowIn
        ),
        label = "floatValue"
    ).value
}

/**
 * 按钮按下动画
 */
fun Modifier.pressAnimation(): Modifier = composed {
    var isPressed by remember { mutableStateOf(false) }
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "pressScale"
    )
    
    this.scale(scale)
}

/**
 * 淡入淡出动画
 */
fun Modifier.fadeInOut(
    visible: Boolean,
    duration: Int = AnimationDurations.NORMAL,
    delay: Int = 0
): Modifier = composed {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = duration,
            delayMillis = delay,
            easing = AnimationEasing.FastOutSlowIn
        ),
        label = "fadeAlpha"
    )
    
    this.graphicsLayer { this.alpha = alpha }
}

/**
 * 旋转动画
 */
@Composable
fun rememberRotationAnimation(
    enabled: Boolean,
    duration: Int = 2000
): Float {
    val infiniteTransition = rememberInfiniteTransition(label = "rotation")
    
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(duration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotationAngle"
    )
    
    return if (enabled) rotation else 0f
}

/**
 * 弹跳进入动画
 */
fun Modifier.bounceIn(
    visible: Boolean,
    delay: Int = 0
): Modifier = composed {
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
            visibilityThreshold = 0.001f
        ).run {
            if (delay > 0) {
                tween<Float>(
                    durationMillis = delay,
                    easing = LinearEasing
                ).let { delaySpec ->
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                }
            } else this
        },
        label = "bounceScale"
    )
    
    this.scale(scale)
}

/**
 * 页面切换动画规格
 */
fun pageTransitionSpec(): ContentTransform {
    return slideInHorizontally(
        initialOffsetX = { it },
        animationSpec = tween(AnimationDurations.NORMAL, easing = AnimationEasing.FastOutSlowIn)
    ) + fadeIn(
        animationSpec = tween(AnimationDurations.NORMAL)
    ) togetherWith slideOutHorizontally(
        targetOffsetX = { -it },
        animationSpec = tween(AnimationDurations.NORMAL, easing = AnimationEasing.FastOutSlowIn)
    ) + fadeOut(
        animationSpec = tween(AnimationDurations.NORMAL)
    )
}

/**
 * 交错动画 - 用于列表项
 */
@Composable
fun rememberStaggeredAnimation(
    index: Int,
    itemCount: Int,
    baseDelay: Int = 50
): Boolean {
    var visible by remember { mutableStateOf(false) }
    
    LaunchedEffect(itemCount) {
        kotlinx.coroutines.delay((index * baseDelay).toLong())
        visible = true
    }
    
    return visible
}
