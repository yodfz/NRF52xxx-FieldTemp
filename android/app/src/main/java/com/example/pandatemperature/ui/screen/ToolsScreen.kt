package com.example.pandatemperature.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 工具页面 - 包含营地助手等工具入口
 */
@Composable
fun ToolsScreen(
    modifier: Modifier = Modifier
) {
    var showCampAssistant by remember { mutableStateOf(false) }
    var showTrailBack by remember { mutableStateOf(false) }
    
    when {
        showCampAssistant -> {
            CampAssistantScreen(
                onBack = { showCampAssistant = false },
                modifier = modifier
            )
        }
        showTrailBack -> {
            TrailBackScreen(
                onBack = { showTrailBack = false },
                modifier = modifier
            )
        }
        else -> {
            ToolsListScreen(
                onOpenCampAssistant = { showCampAssistant = true },
                onOpenTrailBack = { showTrailBack = true },
                modifier = modifier
            )
        }
    }
}

/**
 * 工具列表页面
 */
@Composable
private fun ToolsListScreen(
    onOpenCampAssistant: () -> Unit,
    onOpenTrailBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(modifier = Modifier.height(statusBarHeight + 16.dp))
        
        Text(
            text = "工具箱",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        // 营地助手工具卡片
        ToolCard(
            title = "营地助手",
            description = "智能选址、风向判断、帐篷搭建指导",
            icon = Icons.Default.Explore,
            onClick = onOpenCampAssistant
        )
        
        // 营地回溯工具卡片
        ToolCard(
            title = "营地回溯",
            description = "拍照锚定营地，2D循迹+AR导航回归",
            icon = Icons.Default.Navigation,
            onClick = onOpenTrailBack
        )
        
        Spacer(modifier = Modifier.height(12.dp))
    }
}

/**
 * 工具卡片组件
 */
@Composable
private fun ToolCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标背景
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF4A90E2),
                                Color(0xFF357ABD)
                            )
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            
            // 文字信息
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        }
    }
}
