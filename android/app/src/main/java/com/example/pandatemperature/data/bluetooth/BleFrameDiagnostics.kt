package com.example.pandatemperature.data.bluetooth

import com.example.pandatemperature.data.device.parser.RealtimeDataParserV2
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE 原始帧诊断（旁路观察，不参与协议判定）。
 *
 * 背景：交接文档 P0 第一条要求确认「固件是否真的上报实时 8 字节 / 历史 14 字节」，
 * 但 App 侧此前没有任何原始帧输出，联调只能依赖外部抓包工具。
 *
 * 本对象只提供最小观测能力，规则刻意保守：
 * - 同一通道（[Channel]）首次出现时报告一次；
 * - 之后仅当帧长发生变化时再次报告 —— 帧长变化正是固件升级的关键信号；
 * - 不解析、不修改、不缓存业务数据，因此不会影响既有解析结果。
 */
object BleFrameDiagnostics {

    /** 诊断通道标识，用于分别去重实时帧与历史帧。 */
    enum class Channel(val label: String) {
        REALTIME("实时数据"),
        HISTORY("历史数据")
    }

    /** 十六进制预览的最大字节数，避免长历史帧刷屏。 */
    private const val MAX_PREVIEW_BYTES = 16

    /** uint16 电压字段占用的字节数。 */
    private const val VOLTAGE_FIELD_SIZE = 2

    /** 通道 -> 上一次已报告的帧长，用于「仅在长度变化时再次报告」。 */
    private val reportedLengths = ConcurrentHashMap<Channel, Int>()

    /**
     * 观察实时数据帧。
     *
     * @param data 原始帧
     * @param minLength 协议要求的最小长度（含电压字段的帧会更长）
     * @param voltageOffset 电压字段起始偏移，null 表示该协议版本没有电压字段
     * @return 需要写入日志的文本；与上次帧长相同则返回 null
     */
    fun observeRealtime(
        data: ByteArray,
        minLength: Int,
        voltageOffset: Int?
    ): String? {
        if (!shouldReport(Channel.REALTIME, data.size)) return null

        val voltageState = when {
            voltageOffset == null -> "该协议版本无电压字段"
            data.size >= voltageOffset + VOLTAGE_FIELD_SIZE -> {
                // 含电压字段时顺带区分有效性：0xFFFF 是固件哨兵，超出量程的读数同样不可能成立。
                val millivolts = readUint16Le(data, voltageOffset)
                val outOfRange = millivolts < RealtimeDataParserV2.BATTERY_VOLTAGE_MIN_MV ||
                    millivolts > RealtimeDataParserV2.BATTERY_VOLTAGE_MAX_MV
                when {
                    millivolts == RealtimeDataParserV2.BATTERY_VOLTAGE_INVALID_MV ->
                        "含电压字段但值为无效哨兵 0xFFFF（offset=$voltageOffset）"
                    outOfRange ->
                        "含电压字段但值 ${millivolts}mV 超出量程（offset=$voltageOffset）"
                    else -> "含电压字段（offset=$voltageOffset）"
                }
            }
            else -> "不含电压字段，长度未达 ${voltageOffset + VOLTAGE_FIELD_SIZE}B"
        }
        val lengthNote = if (data.size < minLength) "，长度小于协议最小 ${minLength}B" else ""

        return buildString {
            append("BLE诊断 实时数据: 帧长=${data.size}B（$voltageState$lengthNote）")
            append("，Hex=").append(previewHex(data))
        }
    }

    /**
     * 观察历史数据帧。
     *
     * @param data 原始帧
     * @param recordSize 由设备 Profile 选定的单条记录字节数
     * @param formatName 选定的历史记录格式名，便于核对固件版本映射
     * @return 需要写入日志的文本；与上次帧长相同则返回 null
     */
    fun observeHistory(
        data: ByteArray,
        recordSize: Int,
        formatName: String
    ): String? {
        if (!shouldReport(Channel.HISTORY, data.size)) return null

        val completeRecords = data.size / recordSize
        val remainder = data.size % recordSize

        return buildString {
            append("BLE诊断 历史数据: 帧长=${data.size}B，格式=$formatName（单条 ${recordSize}B）")
            append("，完整记录=$completeRecords 条，尾部余 $remainder B")
            append("，Hex=").append(previewHex(data))
        }
    }

    /**
     * 清空去重状态。设备切换或重新连接时调用，确保新连接能重新报告一次基线帧长。
     */
    fun reset() {
        reportedLengths.clear()
    }

    /**
     * 仅当该通道未报告过，或帧长与上次不同时返回 true。
     * 使用 put 的返回值实现单次原子读写，避免并发下重复报告。
     */
    private fun shouldReport(channel: Channel, length: Int): Boolean {
        val previous = reportedLengths.put(channel, length)
        return previous == null || previous != length
    }

    private fun previewHex(data: ByteArray): String {
        val shown = data.take(MAX_PREVIEW_BYTES).joinToString(" ") { "%02X".format(it) }
        return if (data.size > MAX_PREVIEW_BYTES) "$shown …（共 ${data.size}B）" else shown
    }

    /** 读取小端 uint16。仅用于诊断文案的哨兵值判定，不参与解析。 */
    private fun readUint16Le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
