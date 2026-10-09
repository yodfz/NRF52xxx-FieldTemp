package com.example.pandatemperature.data.device.parser

import com.example.pandatemperature.data.model.DeviceStatus
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 设备状态解析器
 * 解析设备状态特征 `12340022` 数据
 *
 * 数据格式（小端序，按**绝对偏移**解析，向后兼容）：
 * - 字节 0-1: historyInterval (uint16) - 历史记录间隔（秒）
 *             固件 1.0.6+0 起为"历史记录落盘周期"（60~3600，默认 60）；旧固件为"采集间隔"
 * - 字节 2-3: recordCount (uint16) - 已存储记录数
 * - 字节 4: statusFlags (uint8) - 状态标志
 *   - bit 0: isConnected
 *   - bit 1: isTimeSynced
 *   - bit 2: isDataClearInProgress（清空或保留维护忙碌）
 *   - bit 3: isWallClockTrusted（固件 1.0.6+0 新增：本次上电收到过对时）
 * - 字节 5: capabilityFlags (uint8) - 能力标志（新固件；旧固件恒为 0）
 *   - bit 0: 支持历史记录间隔配置（interval 语义已变更）
 *   - bit 1: 采样固定 1 秒
 *   - bit 2: 支持事件记录
 *   - bit 3: 历史记录为周期均值
 *   - bit 4: 支持墙钟边界对齐
 *   - bit 5: 对时后首个窗口可能短于一个周期
 *   - bit 6: 显式支持含电压历史请求
 *   - bit 7: 显式支持保留最新历史维护命令
 * - 字节 6-7: firmwareVersion (uint16) - 固件版本号（新固件为 patch 号；旧固件可能没有）
 * - 字节 8-9: sampleInterval (uint16) - 采样间隔（新固件，恒为 1）
 * - 字节 10-11: retentionDays (uint16) - 名义预计保留天数（新固件）
 *
 * 新固件状态帧固定 **12 字节**；8 字节旧帧仍可解析，缺失字段取默认值。
 */
class DeviceStatusParser : DataParser<DeviceStatus> {
    
    override val expectedMinLength: Int = 5  // 最小 5 字节（interval + recordCount + statusFlags）
    
    override fun canParse(data: ByteArray): Boolean {
        return data.size >= expectedMinLength
    }
    
    override fun parse(data: ByteArray): DeviceStatus? {
        if (!canParse(data)) return null
        
        return try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            
            // 历史记录间隔（uint16）
            val interval = buffer.getShort(0).toInt() and 0xFFFF
            
            // 记录数（uint16）
            val recordCount = buffer.getShort(2).toInt() and 0xFFFF
            
            // 状态标志（uint8）
            val statusFlags = buffer.get(4).toInt() and 0xFF
            val isConnected = (statusFlags and 0x01) != 0
            val isTimeSynced = (statusFlags and 0x02) != 0
            val isDataClearInProgress = (statusFlags and 0x04) != 0
            val isWallClockTrusted = (statusFlags and 0x08) != 0
            
            // 能力标志（新固件；长度不足时为 0，等价于"旧固件"）
            val capabilityFlags = if (data.size >= 7) buffer.get(5).toInt() and 0xFF else 0
            
            // 固件版本号（新固件为 patch 号）
            val firmwareVersion = if (data.size >= 8) {
                buffer.getShort(6).toInt() and 0xFFFF
            } else {
                0  // 老固件没有版本号
            }
            
            // 新固件追加字段
            val sampleInterval = if (data.size >= 10) {
                buffer.getShort(8).toInt() and 0xFFFF
            } else {
                null
            }
            val retentionDays = if (data.size >= 12) {
                buffer.getShort(10).toInt() and 0xFFFF
            } else {
                null
            }
            
            DeviceStatus(
                interval = interval,
                recordCount = recordCount,
                isConnected = isConnected,
                isTimeSynced = isTimeSynced,
                firmwareVersion = firmwareVersion,
                isDataClearInProgress = isDataClearInProgress,
                isWallClockTrusted = isWallClockTrusted,
                capabilityFlags = capabilityFlags,
                sampleInterval = sampleInterval,
                retentionDays = retentionDays
            )
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 格式化状态信息为日志字符串。
     *
     * 除既有字段外，补充「墙钟可信」与能力位十六进制：联调时可直接在设备状态日志里
     * 核对固件能力，不必依赖额外抓包。
     */
    fun formatForLog(status: DeviceStatus): String {
        val clearStatusStr = if (status.isDataClearInProgress) "进行中" else "空闲"
        val intervalLabel = if (status.supportsHistoryInterval) "记录间隔" else "采集间隔"
        val retention = status.retentionDays?.let { ", 可保留:${it}天" } ?: ""
        val wallClock = if (status.isWallClockTrusted) "可信" else "不可信"
        val caps = "0x%02X".format(status.capabilityFlags)
        return "$intervalLabel:${status.interval}秒, 记录数:${status.recordCount}, " +
            "清空:${clearStatusStr}, 固件:v${status.firmwareVersion}$retention, " +
            "墙钟:$wallClock, 能力:$caps"
    }
}
