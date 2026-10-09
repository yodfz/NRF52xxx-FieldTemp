package com.example.pandatemperature.data.device.parser

import com.example.pandatemperature.data.device.model.ThermometerData
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 实时数据解析器（新固件 v1.1+）
 * 解析合并的实时数据特征：基础帧 6 字节（温度/湿度/气压）；
 * 固件 1.0.8 起可扩展为 8 字节，末尾追加 uint16 小端毫伏电压，
 * 并对哨兵值（`0xFFFF`）与超出量程（nRF52810 为 1.7~3.6 V）的读数判为无效。
 */
class RealtimeDataParserV2 : DataParser<ThermometerData> {

    companion object {
        /** 基础帧长：温度(2) + 湿度(2) + 气压(2)。旧固件固定上报该长度。 */
        const val FRAME_SIZE_BASE = 6

        /** 电压字段在帧内的起始偏移，紧跟基础帧末尾。 */
        const val BATTERY_VOLTAGE_OFFSET = 6

        /** 电压字段字节数：uint16 小端毫伏。 */
        const val BATTERY_VOLTAGE_SIZE = 2

        /** 含电压字段时的最小帧长。 */
        const val FRAME_SIZE_WITH_VOLTAGE = BATTERY_VOLTAGE_OFFSET + BATTERY_VOLTAGE_SIZE

        /**
         * 电池电压的「无有效数据」哨兵值，单位毫伏。
         *
         * 固件在 ADC 连续失败 / 尚未采到样时上报 `0xFFFF`，表示设备暂时没有有效电压数据。
         * 该值必须映射为 `null`（UI 显示 `--`），**不能**当作 65535 mV = 65.535 V 展示。
         */
        const val BATTERY_VOLTAGE_INVALID_MV = 0xFFFF

        /**
         * 电压量程下限（毫伏）。依据 nRF52810 数据手册：器件工作电压为 **1.7 ~ 3.6 V**，
         * 因此本硬件不可能产出低于 1700 mV 的电池电压读数。
         */
        const val BATTERY_VOLTAGE_MIN_MV = 1700

        /**
         * 电压量程上限（毫伏）。依据同上：nRF52810 工作电压上限 3.6 V。
         */
        const val BATTERY_VOLTAGE_MAX_MV = 3600

        /** 毫伏换算为伏特的除数。 */
        private const val MILLIVOLT_PER_VOLT = 1000.0f
    }

    override val expectedMinLength: Int = FRAME_SIZE_BASE
    
    override fun canParse(data: ByteArray): Boolean {
        return data.size >= expectedMinLength
    }
    
    override fun parse(data: ByteArray): ThermometerData? {
        if (!canParse(data)) return null
        
        return try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            
            // temperature: int16, 单位 0.01°C
            val tempRaw = buffer.short
            // humidity: uint16, 单位 0.01%RH
            val humRaw = buffer.short.toInt() and 0xFFFF
            // pressure: uint16, 单位 0.1 hPa
            val pressureRaw = buffer.short.toInt() and 0xFFFF

            // battery voltage: optional uint16 little-endian, unit: mV.
            // Keep the existing 6-byte v1/v2 packet fully compatible.
            // voltageReported 表示「本帧是否携带了电压字段」，供上层区分
            // 「旧固件没有电压能力」与「新固件上报了但值无效」两种 null。
            val voltageReported = data.size >= FRAME_SIZE_WITH_VOLTAGE
            val batteryVoltage = if (voltageReported) {
                val millivolts = buffer.getShort(BATTERY_VOLTAGE_OFFSET).toInt() and 0xFFFF
                // 两道防线，缺一不可：
                // 1) 0xFFFF 是固件定义的显式哨兵（协议契约），语义优先，先判它；
                // 2) 量程检查是第二道防线（不是替代品）：nRF52810 工作电压 1.7~3.6 V，
                //    越界值物理上不可能，借此一并挡住 0 mV 与 ADC 异常畸形值。
                // 任一命中即判为无效 → null，界面显示 `--`（绝不显示 65.535 V 这类读数）。
                if (millivolts == BATTERY_VOLTAGE_INVALID_MV ||
                    millivolts !in BATTERY_VOLTAGE_MIN_MV..BATTERY_VOLTAGE_MAX_MV
                ) {
                    null
                } else {
                    millivolts / MILLIVOLT_PER_VOLT
                }
            } else {
                null
            }
            
            ThermometerData(
                temperature = tempRaw / 100.0f,
                humidity = humRaw / 100.0f,
                pressure = pressureRaw / 10.0f,
                batteryVoltage = batteryVoltage,
                batteryVoltageReported = voltageReported
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 温度解析器（老固件，ESS 服务）
 * 解析标准 ESS 温度特征（2字节：int16，单位 0.01°C）
 */
class TemperatureParser : DataParser<Float> {
    
    override val expectedMinLength: Int = 2
    
    override fun canParse(data: ByteArray): Boolean {
        return data.size >= expectedMinLength
    }
    
    override fun parse(data: ByteArray): Float? {
        if (!canParse(data)) return null
        
        return try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val tempRaw = buffer.short
            tempRaw / 100.0f
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 湿度解析器（老固件，ESS 服务）
 * 解析标准 ESS 湿度特征（2字节：uint16，单位 0.01%RH）
 */
class HumidityParser : DataParser<Float> {
    
    override val expectedMinLength: Int = 2
    
    override fun canParse(data: ByteArray): Boolean {
        return data.size >= expectedMinLength
    }
    
    override fun parse(data: ByteArray): Float? {
        if (!canParse(data)) return null
        
        return try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val humRaw = buffer.short.toInt() and 0xFFFF
            humRaw / 100.0f
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 实时数据解析器（老固件）
 * 组合温度和湿度解析结果
 */
class RealtimeDataParserV1 : DataParser<ThermometerData> {
    
    private val temperatureParser = TemperatureParser()
    private val humidityParser = HumidityParser()
    
    override val expectedMinLength: Int = 2  // 至少需要温度数据
    
    override fun canParse(data: ByteArray): Boolean {
        return data.size >= expectedMinLength
    }
    
    /**
     * 解析单个特征数据（温度或湿度）
     * 注意：老固件需要分别读取温度和湿度特征，此方法用于解析单个特征
     */
    override fun parse(data: ByteArray): ThermometerData? {
        // 此解析器用于组合解析，不直接使用
        // 实际使用时应该调用 parseTemperature 和 parseHumidity
        return null
    }
    
    /**
     * 解析温度数据
     */
    fun parseTemperature(data: ByteArray): Float? {
        return temperatureParser.parse(data)
    }
    
    /**
     * 解析湿度数据
     */
    fun parseHumidity(data: ByteArray): Float? {
        return humidityParser.parse(data)
    }
    
    /**
     * 组合温度和湿度创建 ThermometerData
     */
    fun combine(temperature: Float?, humidity: Float?): ThermometerData {
        return ThermometerData(
            temperature = temperature,
            humidity = humidity,
            pressure = null  // 老固件不支持气压
        )
    }
}
