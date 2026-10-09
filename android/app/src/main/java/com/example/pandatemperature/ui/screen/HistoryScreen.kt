package com.example.pandatemperature.ui.screen

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pandatemperature.data.bluetooth.BleManager
import com.example.pandatemperature.data.model.TemperatureRecord
import com.example.pandatemperature.data.model.HistoryProgress
import com.example.pandatemperature.ui.components.HistoryItem
import com.example.pandatemperature.ui.components.LineChart
import com.example.pandatemperature.ui.viewmodel.MainViewModel
import com.example.pandatemperature.utils.WeatherChartEvents
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import kotlinx.coroutines.flow.collect
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

/**
 * 历史数据详情页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val historyRecords by viewModel.historyDetailRecords.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMoreHistory.collectAsStateWithLifecycle()
    val hasMore by viewModel.hasMoreHistory.collectAsStateWithLifecycle()
    val isFetchingHistory by viewModel.isFetchingHistory.collectAsStateWithLifecycle()
    val historyProgress by viewModel.historyProgress.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    
    // 时间筛选状态
    val filterStartTime by viewModel.filterStartTime.collectAsStateWithLifecycle()
    val filterEndTime by viewModel.filterEndTime.collectAsStateWithLifecycle()
    
    // 本地时间选择状态（用于UI显示）
    val context = LocalContext.current
    var localStartDate by remember { mutableStateOf<Calendar?>(null) }
    var localEndDate by remember { mutableStateOf<Calendar?>(null) }
    
    // 同步 ViewModel 的筛选状态到本地状态
    LaunchedEffect(filterStartTime, filterEndTime) {
        if (filterStartTime != null) {
            localStartDate = Calendar.getInstance().apply { timeInMillis = filterStartTime!! * 1000 }
        } else {
            localStartDate = null
        }
        if (filterEndTime != null) {
            localEndDate = Calendar.getInstance().apply { timeInMillis = filterEndTime!! * 1000 }
        } else {
            localEndDate = null
        }
    }
    
    // 菜单展开状态
    var menuExpanded by remember { mutableStateOf(false) }
    
    // 过滤弹窗显示状态
    var filterSheetVisible by remember { mutableStateOf(false) }
    
    // 视图状态：false=列表视图，true=图表视图
    var showChartView by remember { mutableStateOf(false) }
    
    // 导出功能状态
    var showExportDialog by remember { mutableStateOf(false) }
    val isExporting by viewModel.isExporting.collectAsStateWithLifecycle()
    
    // 最近24小时数据
    val last24HoursRecords by viewModel.last24HoursRecords.collectAsStateWithLifecycle()
    
    // 判断是否可以刷新（已连接状态）
    val isConnected = connectionState != BleManager.ConnectionState.Disconnected
    
    // 记录上次的获取状态，用于检测完成
    var previousFetchingState by remember { mutableStateOf(false) }
    
    // 是否有筛选条件
    val hasFilter = filterStartTime != null && filterEndTime != null
    
    // 监听滚动到底部，自动加载更多
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val visibleItemsInfo = layoutInfo.visibleItemsInfo
            if (visibleItemsInfo.isEmpty()) {
                false
            } else {
                val lastVisibleItem = visibleItemsInfo.last()
                val totalItems = layoutInfo.totalItemsCount
                lastVisibleItem.index >= totalItems - 3 && hasMore && !isLoadingMore
            }
        }.collect { shouldLoadMore ->
            if (shouldLoadMore) {
                viewModel.loadMoreHistoryDetail()
            }
        }
    }
    
    // 监听刷新完成，重新加载数据
    LaunchedEffect(isFetchingHistory) {
        if (previousFetchingState && !isFetchingHistory) {
            // 从 true 变为 false，表示刷新完成
            viewModel.loadHistoryDetail()
        }
        previousFetchingState = isFetchingHistory
    }
    
    // 获取状态栏高度，用于确保内容不被状态栏遮挡
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    
    // 计算最后同步时间
    val lastSyncTimeText = remember(historyRecords) {
        if (historyRecords.isNotEmpty()) {
            val latestRecord = historyRecords.firstOrNull()
            if (latestRecord != null && latestRecord.timestamp > 0) {
                val now = System.currentTimeMillis() / 1000
                val diff = now - latestRecord.timestamp
                when {
                    diff < 60 -> "${diff}秒前"
                    diff < 3600 -> "${diff / 60}分钟前"
                    diff < 86400 -> "${diff / 3600}小时前"
                    else -> "${diff / 86400}天前"
                }
            } else {
                null
            }
        } else {
            null
        }
    }
    
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部标题栏（带传感器图标）
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = statusBarHeight)
                        .height(56.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 传感器图标（HTML: sensors, FILL=1, text-primary）
                    Icon(
                        imageVector = Icons.Filled.DeviceHub,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = com.example.pandatemperature.ui.theme.PrimaryBlue // HTML: text-primary
                    )
                    
                    // 标题
                    Text(
                        text = "历史数据记录明细",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    // 更多菜单
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "更多选项",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("导出数据") },
                                onClick = {
                                    menuExpanded = false
                                    showExportDialog = true
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.List, // 或者使用其他合适的图标
                                        contentDescription = null
                                    )
                                }
                            )
                            
                            DropdownMenuItem(
                                text = { Text("清空历史数据") },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.clearHistory()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                    }
                }
            }
            
            // 视图切换按钮（始终显示）
            // SegmentedControl 风格的切换按钮
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1a242d) // HTML: dark:bg-[#1a242d]
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // 列表选项
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { showChartView = false },
                        shape = RoundedCornerShape(10.dp),
                        color = if (!showChartView) {
                            Color(0xFF0A0F14) // 激活TAB背景色
                        } else {
                            Color.Transparent // 未选中：透明背景
                        },
                        tonalElevation = if (!showChartView) 1.dp else 0.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.List,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Color(0xFF9CABBA) // 文字颜色
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "列表",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF9CABBA) // 文字颜色
                            )
                        }
                    }
                    
                    // 图表选项
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable {
                                viewModel.loadLast24HoursRecords()
                                showChartView = true
                            },
                        shape = RoundedCornerShape(10.dp),
                        color = if (showChartView) {
                            Color(0xFF0A0F14) // 激活TAB背景色
                        } else {
                            Color.Transparent // 未选中：透明背景
                        },
                        tonalElevation = if (showChartView) 1.dp else 0.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.BarChart,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Color(0xFF9CABBA) // 文字颜色
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "图表",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF9CABBA) // 文字颜色
                            )
                        }
                    }
                }
            }
            
            // 内容区域
            if (showChartView) {
                // 图表视图（在同一页面中显示）
                val chartEvents = remember(last24HoursRecords) { WeatherChartEvents.compute(last24HoursRecords) }
                LineChart(
                    records = last24HoursRecords,
                    chartEvents = chartEvents,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                )
            } else {
                // 列表视图
                SwipeRefresh(
                    state = rememberSwipeRefreshState(isFetchingHistory),
                    onRefresh = {
                        if (isConnected && !isFetchingHistory) {
                            viewModel.fetchHistory()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 140.dp), // 为过滤+刷新按钮留出空间
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        // 历史数据接收进度条（仅在获取时显示）
                        if (historyProgress != null && historyProgress!!.isFetching) {
                            item {
                                HistoryProgressBar(
                                    progress = historyProgress!!,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                        }
                        
                        if (historyRecords.isEmpty()) {
                            // 空状态时显示提示
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillParentMaxHeight(),
                                    contentAlignment = androidx.compose.ui.Alignment.Center
                                ) {
                                    Text(
                                        text = "暂无历史数据",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            itemsIndexed(historyRecords) { index, record ->
                                // 检查是否需要显示分隔标签（检查与前一条记录的时间间隔）
                                if (index > 0) {
                                    val prevRecord = historyRecords[index - 1]
                                    if (prevRecord.timestamp > 0 && record.timestamp > 0) {
                                        val diff = abs(prevRecord.timestamp - record.timestamp)
                                        // 如果时间间隔超过24小时，显示分隔标签
                                        if (diff > 86400) {
                                            // "更早记录"分隔标签
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                            ) {
                                                Text(
                                                    text = "更早记录".uppercase(),
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 11.sp
                                                    ),
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                    letterSpacing = 2.sp
                                                )
                                            }
                                        }
                                    }
                                }
                                
                                // 历史记录项
                                HistoryItem(
                                    record = record,
                                    index = index,
                                    deviceName = deviceName,
                                    isConnected = isConnected
                                )
                            }
                            
                            // 加载更多指示器
                            if (isLoadingMore) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = androidx.compose.ui.Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                            }
                            
                            // 没有更多数据提示
                            if (!hasMore && historyRecords.isNotEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = androidx.compose.ui.Alignment.Center
                                    ) {
                                        Text(
                                            text = "已加载全部数据",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            
                            // 最后同步时间（在下拉刷新时显示）
                            if (isFetchingHistory && lastSyncTimeText != null) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 24.dp, horizontal = 16.dp),
                                        contentAlignment = androidx.compose.ui.Alignment.Center
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.BluetoothConnected,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = "最后同步：$lastSyncTimeText",
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // 底部按钮区域（过滤 + 刷新）- 只在列表视图时显示
        if (!showChartView) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.End
            ) {
                // 过滤按钮（上方）- 有筛选时高亮
                FloatingActionButton(
                    onClick = { filterSheetVisible = true },
                    modifier = Modifier.size(48.dp),
                    containerColor = if (hasFilter) {
                        com.example.pandatemperature.ui.theme.PrimaryBlue.copy(alpha = 0.2f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (hasFilter) {
                        com.example.pandatemperature.ui.theme.PrimaryBlue
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.FilterList,
                        contentDescription = "过滤",
                        modifier = Modifier.size(24.dp)
                    )
                }
                // 刷新按钮（下方）
                FloatingActionButton(
                    onClick = {
                        if (isConnected && !isFetchingHistory) {
                            viewModel.fetchHistory()
                        }
                    },
                    modifier = Modifier.size(48.dp),
                    containerColor = com.example.pandatemperature.ui.theme.PrimaryBlue
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "强制同步数据",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
        
        // 过滤弹窗（ModalBottomSheet）
        if (filterSheetVisible) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { filterSheetVisible = false },
                sheetState = sheetState,
                containerColor = Color(0xFF1a242d)
            ) {
                TimeFilterSection(
                    startDate = localStartDate,
                    endDate = localEndDate,
                    hasFilter = hasFilter,
                    onStartDateClick = {
                        val calendar = localStartDate ?: Calendar.getInstance()
                        DatePickerDialog(
                            context,
                            { _, year, month, dayOfMonth ->
                                val newCal = (localStartDate ?: Calendar.getInstance()).apply {
                                    set(Calendar.YEAR, year)
                                    set(Calendar.MONTH, month)
                                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                                }
                                localStartDate = newCal
                                if (localEndDate != null) {
                                    viewModel.setHistoryTimeFilter(
                                        newCal.timeInMillis / 1000,
                                        localEndDate!!.timeInMillis / 1000
                                    )
                                }
                            },
                            calendar.get(Calendar.YEAR),
                            calendar.get(Calendar.MONTH),
                            calendar.get(Calendar.DAY_OF_MONTH)
                        ).show()
                    },
                    onStartTimeClick = {
                        val calendar = localStartDate ?: Calendar.getInstance()
                        TimePickerDialog(
                            context,
                            { _, hourOfDay, minute ->
                                val newCal = (localStartDate ?: Calendar.getInstance()).apply {
                                    set(Calendar.HOUR_OF_DAY, hourOfDay)
                                    set(Calendar.MINUTE, minute)
                                    set(Calendar.SECOND, 0)
                                }
                                localStartDate = newCal
                                if (localEndDate != null) {
                                    viewModel.setHistoryTimeFilter(
                                        newCal.timeInMillis / 1000,
                                        localEndDate!!.timeInMillis / 1000
                                    )
                                }
                            },
                            calendar.get(Calendar.HOUR_OF_DAY),
                            calendar.get(Calendar.MINUTE),
                            true
                        ).show()
                    },
                    onEndDateClick = {
                        val calendar = localEndDate ?: Calendar.getInstance()
                        DatePickerDialog(
                            context,
                            { _, year, month, dayOfMonth ->
                                val newCal = (localEndDate ?: Calendar.getInstance()).apply {
                                    set(Calendar.YEAR, year)
                                    set(Calendar.MONTH, month)
                                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                                }
                                localEndDate = newCal
                                if (localStartDate != null) {
                                    viewModel.setHistoryTimeFilter(
                                        localStartDate!!.timeInMillis / 1000,
                                        newCal.timeInMillis / 1000
                                    )
                                }
                            },
                            calendar.get(Calendar.YEAR),
                            calendar.get(Calendar.MONTH),
                            calendar.get(Calendar.DAY_OF_MONTH)
                        ).show()
                    },
                    onEndTimeClick = {
                        val calendar = localEndDate ?: Calendar.getInstance()
                        TimePickerDialog(
                            context,
                            { _, hourOfDay, minute ->
                                val newCal = (localEndDate ?: Calendar.getInstance()).apply {
                                    set(Calendar.HOUR_OF_DAY, hourOfDay)
                                    set(Calendar.MINUTE, minute)
                                    set(Calendar.SECOND, 59)
                                }
                                localEndDate = newCal
                                if (localStartDate != null) {
                                    viewModel.setHistoryTimeFilter(
                                        localStartDate!!.timeInMillis / 1000,
                                        newCal.timeInMillis / 1000
                                    )
                                }
                            },
                            calendar.get(Calendar.HOUR_OF_DAY),
                            calendar.get(Calendar.MINUTE),
                            true
                        ).show()
                    },
                    onReset = {
                        localStartDate = null
                        localEndDate = null
                        viewModel.resetHistoryTimeFilter()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 32.dp)
                )
            }
        }

        // 导出格式选择对话框
        if (showExportDialog) {
            val exportDateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
            
            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                title = { Text("导出历史数据") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (hasFilter && localStartDate != null && localEndDate != null) {
                            val startTimeStr = exportDateFormat.format(localStartDate!!.time)
                            val endTimeStr = exportDateFormat.format(localEndDate!!.time)
                            Text(
                                "导出时间段：\n$startTimeStr 至 $endTimeStr",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                "将导出最近24小时的数据",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Button(
                            onClick = {
                                showExportDialog = false
                                viewModel.exportHistoryData(context, MainViewModel.ExportFormat.CSV)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("导出为 CSV (表格)")
                        }
                        
                        Button(
                            onClick = {
                                showExportDialog = false
                                viewModel.exportHistoryData(context, MainViewModel.ExportFormat.PDF)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("导出为 PDF (报告)")
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showExportDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }

        // 导出加载中
        if (isExporting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(enabled = false) {}, // 拦截点击
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Text("正在导出数据...")
                    }
                }
            }
        }
    }
}

/**
 * 历史数据接收进度条组件
 * 显示在历史数据列表上方，展示接收进度
 */
@Composable
private fun HistoryProgressBar(
    progress: HistoryProgress,
    modifier: Modifier = Modifier
) {
    val progressValue = progress.getProgress()
    val progressText = progress.getProgressText()
    
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 进度文本
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "正在接收历史数据",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = progressText,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
        }
        
        // 进度条
        if (progressValue != null) {
            LinearProgressIndicator(
                progress = { progressValue },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = com.example.pandatemperature.ui.theme.PrimaryBlue,
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        } else {
            // 未知总数时显示不确定进度条
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = com.example.pandatemperature.ui.theme.PrimaryBlue,
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        }
    }
}

/**
 * 时间筛选器组件
 * 展开型设计，包含起始时间和结束时间选择
 */
@Composable
private fun TimeFilterSection(
    startDate: Calendar?,
    endDate: Calendar?,
    hasFilter: Boolean,
    onStartDateClick: () -> Unit,
    onStartTimeClick: () -> Unit,
    onEndDateClick: () -> Unit,
    onEndTimeClick: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1a242d) // 与 Switch 背景色一致
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 起始时间行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "起始",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = Color(0xFF9CABBA),
                    modifier = Modifier.width(32.dp)
                )
                
                // 日期选择按钮
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onStartDateClick),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0A0F14)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DateRange,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color(0xFF9CABBA)
                        )
                        Text(
                            text = startDate?.let { dateFormat.format(it.time) } ?: "选择日期",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                            color = if (startDate != null) Color.White else Color(0xFF9CABBA).copy(alpha = 0.6f)
                        )
                    }
                }
                
                // 时间选择按钮
                Surface(
                    modifier = Modifier
                        .weight(0.7f)
                        .clickable(onClick = onStartTimeClick),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0A0F14)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color(0xFF9CABBA)
                        )
                        Text(
                            text = startDate?.let { timeFormat.format(it.time) } ?: "时间",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                            color = if (startDate != null) Color.White else Color(0xFF9CABBA).copy(alpha = 0.6f)
                        )
                    }
                }
            }
            
            // 结束时间行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "结束",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = Color(0xFF9CABBA),
                    modifier = Modifier.width(32.dp)
                )
                
                // 日期选择按钮
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onEndDateClick),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0A0F14)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DateRange,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color(0xFF9CABBA)
                        )
                        Text(
                            text = endDate?.let { dateFormat.format(it.time) } ?: "选择日期",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                            color = if (endDate != null) Color.White else Color(0xFF9CABBA).copy(alpha = 0.6f)
                        )
                    }
                }
                
                // 时间选择按钮
                Surface(
                    modifier = Modifier
                        .weight(0.7f)
                        .clickable(onClick = onEndTimeClick),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0A0F14)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color(0xFF9CABBA)
                        )
                        Text(
                            text = endDate?.let { timeFormat.format(it.time) } ?: "时间",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                            color = if (endDate != null) Color.White else Color(0xFF9CABBA).copy(alpha = 0.6f)
                        )
                    }
                }
                
                // 重置按钮（仅在有筛选时显示）
                if (hasFilter) {
                    Surface(
                        modifier = Modifier
                            .size(36.dp)
                            .clickable(onClick = onReset),
                        shape = CircleShape,
                        color = com.example.pandatemperature.ui.theme.PrimaryBlue.copy(alpha = 0.2f)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "重置筛选",
                                modifier = Modifier.size(18.dp),
                                tint = com.example.pandatemperature.ui.theme.PrimaryBlue
                            )
                        }
                    }
                } else {
                    // 占位，保持布局一致
                    Spacer(modifier = Modifier.size(36.dp))
                }
            }
        }
    }
}