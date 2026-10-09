package com.example.pandatemperature.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/**
 * 电池指示器组件
 * 显示电池图标，电量百分比在电池内，电压在右侧
 */
@Composable
fun BatteryIndicator(
    batteryPercent: Float?,
    batteryVoltage: Float?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 电池图标（带电量百分比文字）
        Box(
            modifier = Modifier.size(36.dp, 20.dp),
            contentAlignment = Alignment.Center
        ) {
            BatteryIcon(
                percent = batteryPercent,
                modifier = Modifier.fillMaxSize()
            )

            // 电量百分比文字（显示在电池内，居中）
            if (batteryPercent != null) {
                Text(
                    text = "${batteryPercent.toInt()}%",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 1.dp)
                )
            }
        }
        
        // 电压显示（电池右侧）
        if (batteryVoltage != null) {
            Text(
                text = String.format("%.2fV", batteryVoltage),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = "--V",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

/**
 * 电池图标绘制
 */
@Composable
private fun BatteryIcon(
    percent: Float?,
    modifier: Modifier = Modifier
) {
    // 在 @Composable 上下文中获取颜色
    val outlineColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val cornerRadius = 2.5.dp.toPx()
        val terminalWidth = 3.5.dp.toPx()
        val terminalHeight = 7.dp.toPx()
        val strokeWidth = 1.5.dp.toPx()
        
        // 电池正极（右侧突出部分）
        val terminalTop = (height - terminalHeight) / 2
        val terminalBottom = terminalTop + terminalHeight
        val terminalLeft = width - terminalWidth
        val terminalRight = width
        drawRoundRect(
            color = outlineColor,
            topLeft = Offset(0f, 0f),
            size = Size(width - terminalWidth, height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius),
            style = Stroke(width = strokeWidth)
        )
        
        // 绘制正极
        drawRoundRect(
            color = outlineColor,
            topLeft = Offset(terminalLeft, terminalTop),
            size = Size(terminalWidth, terminalHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx(), 1.dp.toPx()),
            style = Stroke(width = strokeWidth)
        )
        
        // 绘制电池填充（根据电量百分比）
        // 能量条高度随电量变化，颜色从绿色->黄色->红色逐步变化
        if (percent != null && percent > 0) {
            val fillPercent = min(percent / 100f, 1f)
            val fillWidth = (width - terminalWidth - strokeWidth * 2) * fillPercent
            val fillHeight = height - strokeWidth * 2
            
            // 根据电量选择颜色：高电量绿色，中电量黄色，低电量红色
            val fillColor = when {
                percent > 60 -> Color(0xFF4CAF50)  // 绿色（高电量 >60%）
                percent > 20 -> Color(0xFFFFC107)  // 黄色（中电量 20%-60%）
                else -> Color(0xFFF44336)          // 红色（低电量 <20%）
            }
            
            if (fillWidth > 0) {
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(strokeWidth, strokeWidth),
                    size = Size(fillWidth, fillHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                        cornerRadius - strokeWidth / 2,
                        cornerRadius - strokeWidth / 2
                    )
                )
            }
        }
        // 电量 <= 0 时不显示能量条
    }
}
