package com.example.pandatemperature.data.device.parser

import com.example.pandatemperature.data.device.model.MaxMinTemperature
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 最高最低温度解析器
 * 
 * 支持两种数据格式：
 * 
 * 旧格式（小端序，4字节）：
 * - 字节 0-1: maxTemperature (int16) - 最高温度，单位 0.01°C
 * - 字节 2-3: minTemperature (int16) - 最低温度，单位 0.01°C
 * 
 * 新格式（小端序，12字节）- 协议 v1.2：
 * - 字节 0-1: maxTemperature (int16) - 最高温度，单位 0.01°C
 * - 字节 2-5: maxTemperatureTimestamp (uint32) - Unix时间戳（秒）
 * - 字节 6-7: minTemperature (int16) - 最低温度，单位 0.01°C
 * - 字节 8-11: minTemperatureTimestamp (uint32) - Unix时间戳（秒）
 * 
 * 特殊值说明：
 * - 最高温度 = -32768 (0x8000) 且时间戳 = 0：未记录/已重置
 * - 最低温度 = 32767 (0x7FFF) 且时间戳 = 0：未记录/已重置
 */
class MaxMinTempParser : DataParser<MaxMinTemperature> {
    
    companion object {
        private const val OLD_FORMAT_LENGTH = 4
        private const val NEW_FORMAT_LENGTH = 12
        
        // 特殊值：表示未记录/已重置
        private const val MAX_TEMP_RESET_VALUE: Short = Short.MIN_VALUE  // -32768
        private const val MIN_TEMP_RESET_VALUE: Short = Short.MAX_VALUE  // 32767
    }
    
    override val expectedMinLength: Int = OLD_FORMAT_LENGTH
    
    override fun canParse(data: ByteArray): Boolean {
        return data.size >= expectedMinLength
    }
    
    override fun parse(data: ByteArray): MaxMinTemperature? {
        if (!canParse(data)) return null
        
        return try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            
            if (data.size >= NEW_FORMAT_LENGTH) {
                // 新格式（12字节）：包含时间戳
                parseNewFormat(buffer)
            } else {
                // 旧格式（4字节）：仅温度
                parseOldFormat(buffer)
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 解析旧格式（4字节）
     */
    private fun parseOldFormat(buffer: ByteBuffer): MaxMinTemperature {
        val maxTempRaw = buffer.short
        val minTempRaw = buffer.short
        
        return MaxMinTemperature(
            maxTemperature = maxTempRaw / 100.0f,
            maxTemperatureTimestamp = null,
            minTemperature = minTempRaw / 100.0f,
            minTemperatureTimestamp = null,
            hasMaxRecord = true,  // 旧格式无法判断，默认有效
            hasMinRecord = true
        )
    }
    
    /**
     * 解析新格式（12字节）
     */
    private fun parseNewFormat(buffer: ByteBuffer): MaxMinTemperature {
        // 最高温度（int16，单位 0.01°C）
        val maxTempRaw = buffer.getShort(0)
        // 最高温度时间戳（uint32）
        val maxTimestamp = buffer.getInt(2).toLong() and 0xFFFFFFFFL
        
        // 最低温度（int16，单位 0.01°C）
        val minTempRaw = buffer.getShort(6)
        // 最低温度时间戳（uint32）
        val minTimestamp = buffer.getInt(8).toLong() and 0xFFFFFFFFL
        
        // 判断是否有有效记录
        // 根据协议：只有温度是重置值 且 时间戳为0 才表示未记录/已重置
        // 如果温度有效但时间戳为0，仍然显示温度（只是不显示时间）
        val isMaxReset = maxTempRaw == MAX_TEMP_RESET_VALUE && maxTimestamp == 0L
        val isMinReset = minTempRaw == MIN_TEMP_RESET_VALUE && minTimestamp == 0L
        val hasMaxRecord = !isMaxReset
        val hasMinRecord = !isMinReset
        
        return MaxMinTemperature(
            maxTemperature = maxTempRaw / 100.0f,
            maxTemperatureTimestamp = if (maxTimestamp != 0L) maxTimestamp else null,
            minTemperature = minTempRaw / 100.0f,
            minTemperatureTimestamp = if (minTimestamp != 0L) minTimestamp else null,
            hasMaxRecord = hasMaxRecord,
            hasMinRecord = hasMinRecord
        )
    }
    
    /**
     * 格式化为日志字符串
     */
    fun formatForLog(data: MaxMinTemperature): String {
        val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        
        val maxPart = if (data.hasMaxRecord) {
            val timeStr = data.maxTemperatureTimestamp?.let { 
                " @ ${timeFormat.format(Date(it * 1000))}" 
            } ?: ""
            "最高${String.format("%.2f", data.maxTemperature)}°C$timeStr"
        } else {
            "最高: 未记录"
        }
        
        val minPart = if (data.hasMinRecord) {
            val timeStr = data.minTemperatureTimestamp?.let { 
                " @ ${timeFormat.format(Date(it * 1000))}" 
            } ?: ""
            "最低${String.format("%.2f", data.minTemperature)}°C$timeStr"
        } else {
            "最低: 未记录"
        }
        
        return "$maxPart / $minPart"
    }
}
