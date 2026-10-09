package com.example.pandatemperature.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * 屏幕常亮控制组件
 * 
 * 当 [enabled] 为 true 时，保持屏幕常亮，防止息屏。
 * 当 [enabled] 为 false 或组件被移除时，恢复正常息屏行为。
 * 
 * 使用场景：
 * - 历史数据传输过程中，防止息屏导致蓝牙传输中断
 * 
 * @param enabled 是否启用屏幕常亮
 */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    
    DisposableEffect(enabled) {
        if (enabled) {
            view.keepScreenOn = true
        }
        onDispose {
            view.keepScreenOn = false
        }
    }
}
