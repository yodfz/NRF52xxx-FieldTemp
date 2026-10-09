package com.example.pandatemperature.data.database.dao

import androidx.room.*
import com.example.pandatemperature.data.model.Device
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY createTime DESC")
    fun getAllDevices(): Flow<List<Device>>

    /** 查询添加时间最新的已保存设备，用于 App 启动时自动连接。 */
    @Query("SELECT * FROM devices ORDER BY createTime DESC LIMIT 1")
    suspend fun getLatestDevice(): Device?

    @Query("SELECT * FROM devices WHERE macAddress = :address")
    suspend fun getDevice(address: String): Device?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: Device)

    @Delete
    suspend fun deleteDevice(device: Device)
    
    @Query("DELETE FROM devices WHERE macAddress = :address")
    suspend fun deleteDeviceByAddress(address: String)
    
    /**
     * 更新设备固件版本号
     */
    @Query("UPDATE devices SET firmwareVersion = :version WHERE macAddress = :address")
    suspend fun updateFirmwareVersion(address: String, version: Int)

    /**
     * 更新设备昵称（蓝牙地址匹配）
     */
    @Query("UPDATE devices SET nickname = :nickname WHERE macAddress = :address")
    suspend fun updateNickname(address: String, nickname: String?)

    /** 更新设备最近一次随实时数据上报的电池电压。 */
    @Query("UPDATE devices SET latestBatteryVoltage = :voltage WHERE macAddress = :address")
    suspend fun updateLatestBatteryVoltage(address: String, voltage: Float?)
}
