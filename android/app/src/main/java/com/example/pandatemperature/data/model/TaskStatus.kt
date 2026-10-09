package com.example.pandatemperature.data.model

/**
 * 任务状态数据类
 * 用于显示当前正在执行的任务进度
 */
data class TaskStatus(
    /**
     * 任务名称（当前任务描述）
     * null 表示无任务
     */
    val taskName: String?,
    
    /**
     * 当前步骤（从1开始）
     */
    val currentStep: Int = 0,
    
    /**
     * 总步骤数
     */
    val totalSteps: Int = 0
) {
    /**
     * 获取任务状态显示文本
     */
    fun getDisplayText(): String? {
        return if (taskName != null && totalSteps > 0) {
            "$taskName($currentStep/$totalSteps)"
        } else {
            taskName
        }
    }
}
