package com.example.pandatemperature.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.pandatemperature.data.model.LogEntry
import com.example.pandatemperature.data.model.LogType
import com.example.pandatemperature.ui.theme.SuccessColor
import com.example.pandatemperature.ui.theme.TemperatureColor
import java.text.SimpleDateFormat
import java.util.*

/**
 * 日志卡片组件
 */
@Composable
fun LogCard(
    logs: List<LogEntry>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    text = "📝 操作日志",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                OutlinedButton(
                    onClick = onClearLogs,
                    modifier = Modifier.height(40.dp)
                ) {
                    Text("清空日志")
                }
            }
            
            Divider()
            
            // 日志列表
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.small
            ) {
                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        Text(
                            text = "暂无日志",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(12.dp)
                    ) {
                        items(logs.reversed()) { log ->
                            LogItem(log = log)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogItem(
    log: LogEntry,
    modifier: Modifier = Modifier
) {
    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        .format(Date(log.timestamp))
    
    val logColor = when (log.type) {
        LogType.INFO -> MaterialTheme.colorScheme.onSurface
        LogType.SUCCESS -> SuccessColor
        LogType.ERROR -> TemperatureColor
    }
    
    Text(
        text = "[$timeStr] ${log.message}",
        style = MaterialTheme.typography.bodySmall,
        color = logColor,
        modifier = modifier
    )
}
