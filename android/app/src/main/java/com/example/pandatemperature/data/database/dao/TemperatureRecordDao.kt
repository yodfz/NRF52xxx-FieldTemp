package com.example.pandatemperature.data.database.dao

import androidx.room.*
import com.example.pandatemperature.data.model.TemperatureRecord
import kotlinx.coroutines.flow.Flow

/**
 * 温湿度记录数据访问对象
 */
@Dao
interface TemperatureRecordDao {
    
    /**
     * 插入单条记录
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: TemperatureRecord): Long
    
    /**
     * 批量插入记录
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<TemperatureRecord>)
    
    /** Only matches module history; phone samples with or without GPS remain independent. */
    @Query("SELECT * FROM temperature_records WHERE deviceId = :deviceId AND timestamp IN (:timestamps) AND isPhoneSample = 0 AND latitude IS NULL AND longitude IS NULL ORDER BY id ASC")
    suspend fun getHistoryRecordsByTimestamps(deviceId: String, timestamps: List<Long>): List<TemperatureRecord>

    /** Repeated full/incremental downloads update existing module rows instead of adding duplicates. */
    @Transaction
    suspend fun upsertHistoryRecords(records: List<TemperatureRecord>) {
        require(records.all { !it.isPhoneSample && it.latitude == null && it.longitude == null }) {
            "Module history must not contain phone samples or GPS coordinates"
        }
        // Keep the last sample for each device/timestamp within this batch.
        val uniqueRecords = records.associateBy { it.deviceId to it.timestamp }.values
        for ((deviceId, deviceRecords) in uniqueRecords.groupBy { it.deviceId }) {
            // Chunk the IN query to stay below SQLite's bind-parameter limit.
            for (batch in deviceRecords.chunked(500)) {
                val existing = getHistoryRecordsByTimestamps(deviceId, batch.map { it.timestamp })
                    .distinctBy { it.timestamp }
                    .associateBy { it.timestamp }
                insertAll(batch.map { record ->
                    record.copy(id = existing[record.timestamp]?.id ?: 0L)
                })
            }
        }
    }

    @Query("DELETE FROM temperature_records WHERE deviceId = :deviceId AND isPhoneSample = 0 AND latitude IS NULL AND longitude IS NULL AND id IN (:ids)")
    suspend fun deleteModuleIds(deviceId: String, ids: List<Long>): Int

    /** Keep exactly the validated wire set, never a timestamp-sorted approximation. */
    @Transaction
    suspend fun trimHistoryToWireSet(deviceId: String, timestamps: Set<Long>, stillValid: () -> Boolean = { true }): Int {
        check(stillValid()) { "Retention session is no longer current" }
        require(timestamps.size == 3000 && timestamps.all { it > 0 })
        val module = getAllRecordsSync(deviceId).filter {
            !it.isPhoneSample && it.latitude == null && it.longitude == null
        }
        require(module.map { it.timestamp }.toSet().containsAll(timestamps)) {
            "Validated wire rows must already be committed before reconciliation"
        }
        val obsolete = module.filter { it.timestamp !in timestamps }.map { it.id }
        var removed = 0
        for (batch in obsolete.chunked(500)) {
            check(stillValid()) { "Retention session changed before deletion" }
            removed += deleteModuleIds(deviceId, batch)
            check(stillValid()) { "Retention session changed during deletion" }
        }
        check(stillValid()) { "Retention session changed before commit" }
        check(getHistoryRecordCount(deviceId) == 3000) { "Reconciled history must contain exactly 3000 rows" }
        check(stillValid()) { "Retention session changed after final verification" }
        return removed
    }

    /**
     * 查询所有记录（按时间戳倒序，timestamp为0的记录放在最后）
     * 仅查询指定设备的记录
     */
    @Query("SELECT * FROM temperature_records WHERE deviceId = :deviceId ORDER BY CASE WHEN timestamp = 0 THEN 1 ELSE 0 END ASC, timestamp DESC")
    fun getAllRecords(deviceId: String): Flow<List<TemperatureRecord>>
    
    /**
     * 查询所有记录（同步版本，用于一次性获取）
     * 按时间戳倒序，timestamp为0的记录放在最后
     */
    @Query("SELECT * FROM temperature_records WHERE deviceId = :deviceId ORDER BY CASE WHEN timestamp = 0 THEN 1 ELSE 0 END ASC, timestamp DESC")
    suspend fun getAllRecordsSync(deviceId: String): List<TemperatureRecord>
    
    /**
     * 按时间范围查询记录
     */
    @Query("SELECT * FROM temperature_records WHERE deviceId = :deviceId AND timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp ASC")
    suspend fun getRecordsByTimeRange(deviceId: String, startTime: Long, endTime: Long): List<TemperatureRecord>
    
    /**
     * 根据时间戳查询记录（用于去重）
     */
    @Query("SELECT * FROM temperature_records WHERE deviceId = :deviceId AND timestamp = :timestamp LIMIT 1")
    suspend fun getRecordByTimestamp(deviceId: String, timestamp: Long): TemperatureRecord?
    
    /**
     * 删除单条记录
     */
    @Delete
    suspend fun delete(record: TemperatureRecord)
    
    /**
     * 清空指定设备的记录
     */
    @Query("DELETE FROM temperature_records WHERE deviceId = :deviceId")
    suspend fun deleteAll(deviceId: String)
    
    /**
     * 获取指定设备的记录总数
     */
    @Query("SELECT COUNT(*) FROM temperature_records WHERE deviceId = :deviceId")
    suspend fun getRecordCount(deviceId: String): Int

    /** Count only module history when comparing with the device history total. */
    @Query("SELECT COUNT(*) FROM temperature_records WHERE deviceId = :deviceId AND isPhoneSample = 0 AND latitude IS NULL AND longitude IS NULL")
    suspend fun getHistoryRecordCount(deviceId: String): Int
    
    /**
     * 查询最新的N条记录（按时间戳倒序）
     */
    @Query("SELECT * FROM temperature_records WHERE deviceId = :deviceId AND timestamp > 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLatestRecords(deviceId: String, limit: Int): List<TemperatureRecord>
    
    /**
     * 获取最新的一条记录（按时间戳倒序，用于增量获取）
     */
    @Query("SELECT * FROM temperature_records WHERE deviceId = :deviceId AND timestamp > 0 ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestRecord(deviceId: String): TemperatureRecord?
    
    /**
     * 获取最新的一条「无 GPS」记录（设备同步下来的；排除手机定时写入的带 GPS 记录，用于请求设备历史时的起始时间戳）
     */
    @Query("SELECT * FROM temperature_records WHERE deviceId = :deviceId AND timestamp > 0 AND isPhoneSample = 0 AND latitude IS NULL AND longitude IS NULL ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestRecordWithoutGps(deviceId: String): TemperatureRecord?
    
    /**
     * 分页查询记录（按时间戳倒序）
     */
    @Query("SELECT * FROM temperature_records WHERE deviceId = :deviceId AND timestamp > 0 ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getRecordsWithOffset(deviceId: String, limit: Int, offset: Int): List<TemperatureRecord>
    
    /**
     * 分页查询记录（带时间筛选，按时间戳倒序）
     */
    @Query("SELECT * FROM temperature_records WHERE deviceId = :deviceId AND timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getRecordsWithOffsetAndTimeRange(deviceId: String, startTime: Long, endTime: Long, limit: Int, offset: Int): List<TemperatureRecord>
    
    /**
     * 查询指定时间范围内的记录数量
     */
    @Query("SELECT COUNT(*) FROM temperature_records WHERE deviceId = :deviceId AND timestamp >= :startTime AND timestamp <= :endTime")
    suspend fun getRecordCountByTimeRange(deviceId: String, startTime: Long, endTime: Long): Int
    
    /**
     * 查询最新的N条记录（Flow版本，用于首页显示）
     */
    @Query("SELECT * FROM temperature_records WHERE deviceId = :deviceId AND timestamp > 0 ORDER BY timestamp DESC LIMIT :limit")
    fun getLatestRecordsFlow(deviceId: String, limit: Int): Flow<List<TemperatureRecord>>
}
