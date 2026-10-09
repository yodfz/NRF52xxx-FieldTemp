package com.example.pandatemperature.data.model

/**
 * 日志条目数据类
 */
data class LogEntry(
    /**
     * 日志时间戳
     */
    val timestamp: Long = System.currentTimeMillis(),
    
    /**
     * 日志消息
     */
    val message: String,
    
    /**
     * 日志类型
     */
    val type: LogType
)

/**
 * 日志类型枚举
 */
enum class LogType {
    INFO,    // 信息
    SUCCESS, // 成功
    ERROR    // 错误
}
