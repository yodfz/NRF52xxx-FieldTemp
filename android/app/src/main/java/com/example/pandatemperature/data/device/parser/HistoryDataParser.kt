package com.example.pandatemperature.data.device.parser

import com.example.pandatemperature.data.model.TemperatureRecord
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 历史记录的固定协议格式。格式由 DeviceProfile 根据固件能力显式选择，
 * 不根据通知包长度猜测，避免 v1/v2/v3 数据混读。
 */
enum class HistoryRecordFormat(val recordSize: Int) {
    /** timestamp(4) + temperature(2) + humidity(2) */
    V1(8),

    /** timestamp(4) + temperature(2) + humidity(2) + pressure(4) */
    V2(12),

    /** V2 + battery voltage in uint16 little-endian millivolts */
    V3(14)
}

/**
 * 能自述历史记录格式的解析器。
 *
 * 存在的意义：历史记录格式由设备 Profile/固件显式能力决定（见 [DeviceProfileFactory]），
 * 联调时需要确认「固件实际上报的长度」与「App 选中的格式」是否一致。
 * 调用方通过本接口读取格式名称，无需向下强转到具体解析器实现。
 */
interface HistoryFormatAware {
    /** 当前选中的历史记录格式名，例如 V1/V2/V3。 */
    val historyFormatName: String

    /** 当前选中的单条记录字节数。 */
    val historyRecordSize: Int
}

/**
 * 历史数据解析器。
 *
 * 一个通知包可包含多条完整记录；包尾不足一条完整记录的字节会被忽略，
 * 但不会切换到其他协议格式。
 *
 * @param deviceId 设备 MAC 地址，用于创建记录
 * @param format 由设备固件版本明确选择的历史记录格式
 */
class HistoryDataParser(
    private val deviceId: String,
    private val format: HistoryRecordFormat = HistoryRecordFormat.V2
) : DataParser<List<TemperatureRecord>>, HistoryFormatAware {

    override val historyFormatName: String = format.name
    override val historyRecordSize: Int = format.recordSize

    companion object {
        const val RECORD_SIZE_V1 = 8
        const val RECORD_SIZE_V2 = 12
        const val RECORD_SIZE_V3 = 14

        /** V3 记录内电压字段的起始偏移，紧随 V2 的气压字段之后。 */
        const val VOLTAGE_OFFSET_IN_V3 = RECORD_SIZE_V2

        /** 电压字段字节数：uint16 小端毫伏。 */
        const val VOLTAGE_SIZE = 2

        /** 毫伏换算为伏特的除数。 */
        private const val MILLIVOLT_PER_VOLT = 1000.0f
    }

    override val expectedMinLength: Int = format.recordSize

    override fun canParse(data: ByteArray): Boolean {
        return data.size >= expectedMinLength
    }

    override fun parse(data: ByteArray): List<TemperatureRecord>? {
        if (!canParse(data)) return null

        return try {
            val records = mutableListOf<TemperatureRecord>()
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            var offset = 0

            while (offset + format.recordSize <= data.size) {
                parseRecord(buffer, offset)?.let(records::add)
                offset += format.recordSize
            }

            records.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRecord(buffer: ByteBuffer, offset: Int): TemperatureRecord? {
        return try {
            val timestamp = buffer.getInt(offset).toLong() and 0xFFFFFFFFL
            val temperature = buffer.getShort(offset + 4) / 100.0f
            val humidity = (buffer.getShort(offset + 6).toInt() and 0xFFFF) / 100.0f

            val pressure = if (format != HistoryRecordFormat.V1) {
                val pressureRaw = buffer.getInt(offset + 8).toLong() and 0xFFFFFFFFL
                pressureRaw / 100.0f
            } else {
                null
            }

            val batteryVoltage = if (format == HistoryRecordFormat.V3) {
                val batteryMv = buffer.getShort(offset + VOLTAGE_OFFSET_IN_V3).toInt() and 0xFFFF
                if (batteryMv == 0xFFFF) null else batteryMv / MILLIVOLT_PER_VOLT
            } else {
                null
            }

            TemperatureRecord(
                timestamp = timestamp,
                temperature = temperature,
                humidity = humidity,
                pressure = pressure,
                batteryVoltage = batteryVoltage,
                deviceId = deviceId
            )
        } catch (_: Exception) {
            null
        }
    }
}
