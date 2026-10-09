package com.example.pandatemperature.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.example.pandatemperature.data.device.model.HistoryRecord

/**
 * 温湿度记录数据类
 * 用于存储从设备获取的历史数据
 * 实现 HistoryRecord 接口，支持泛型设备架构
 */
@Entity(tableName = "temperature_records")
data class TemperatureRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /**
     * 时间戳（Unix 时间戳，秒）
     * 0 表示未同步时间
     */
    override val timestamp: Long,
    
    /**
     * 温度值（摄氏度）
     */
    val temperature: Float,
    
    /**
     * 湿度值（百分比）
     */
    val humidity: Float,
    
    /**
     * 气压值（hPa，可选）⭐ v1.1 更新：从电压改为气压
     * null 表示旧数据或未记录气压
     */
    val pressure: Float? = null,

    /**
     * 采集时设备上报的电池电压（V）。老固件/老记录没有该字段时为 null。
     */
    val batteryVoltage: Float? = null,
    
    /**
     * 记录创建时间（本地时间）
     */
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * 设备 MAC 地址（用于区分不同设备）
     * 默认为空字符串，兼容旧数据
     */
    override val deviceId: String = "",
    
    /**
     * GPS纬度（可选）
     * null 表示未记录位置或无定位权限
     */
    val latitude: Double? = null,
    
    /**
     * GPS经度（可选）
     * null 表示未记录位置或无定位权限
     */
    val longitude: Double? = null,

    /** Phone realtime sample, including samples without GPS. Legacy unknown rows remain false. */
    @ColumnInfo(defaultValue = "0")
    val isPhoneSample: Boolean = false
) : HistoryRecord
