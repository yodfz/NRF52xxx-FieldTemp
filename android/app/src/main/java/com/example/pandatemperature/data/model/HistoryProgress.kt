package com.example.pandatemperature.data.model

/**
 * 历史数据接收进度
 * 用于在UI中显示历史数据接收的进度条
 */
data class HistoryProgress(
    /**
     * 已接收的记录数
     */
    val receivedCount: Int,
    
    /**
     * 总记录数（可能为null，表示未知总数）
     */
    val totalCount: Long?,
    
    /**
     * 是否正在获取
     */
    val isFetching: Boolean
) {
    /**
     * 获取进度百分比（0.0 - 1.0）
     * 如果总数为null，返回null
     */
    fun getProgress(): Float? {
        return totalCount?.let { total ->
            if (total > 0) {
                (receivedCount.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
    }
    
    /**
     * 获取进度显示文本
     */
    fun getProgressText(): String {
        return totalCount?.let { total ->
            "$receivedCount / $total"
        } ?: "$receivedCount"
    }
}
