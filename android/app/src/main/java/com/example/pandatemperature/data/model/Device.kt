package com.example.pandatemperature.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 设备类型枚举
 */
enum class DeviceType {
    THERMOMETER, // 温度计
    UNKNOWN
}

/**
 * 保存的设备实体
 */
@Entity(tableName = "devices")
data class Device(
    @PrimaryKey
    val macAddress: String,
    
    val name: String,
    
    val type: DeviceType = DeviceType.THERMOMETER,
    
    val createTime: Long = System.currentTimeMillis(),
    
    /**
     * 固件版本号（连接时从设备读取并更新）
     */
    val firmwareVersion: Int? = null,

    /**
     * 用户设置的设备昵称（按蓝牙地址匹配，选填）
     */
    val nickname: String? = null,

    /**
     * 设备最近一次随实时数据上报的电池电压（V）。
     */
    val latestBatteryVoltage: Float? = null
)
