package com.example.pandatemperature.data.ota

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OTA 设备状态机取值（固件 `enum ota_state`）。
 */
enum class OtaState(val value: Int) {
    IDLE(0),
    READY(1),
    RECEIVING(2),
    VERIFY(3),
    PENDING(4),
    ERROR(5);

    companion object {
        fun fromValue(value: Int): OtaState? = entries.firstOrNull { it.value == value }
    }
}

/**
 * OTA 错误码（固件 `enum ota_err`），附带可直接展示的中文文案。
 */
enum class OtaError(val value: Int, val label: String) {
    NONE(0, "无错误"),
    AUTH(1, "授权密钥不匹配"),
    STATE(2, "当前状态不允许该操作"),
    TOO_LARGE(3, "镜像超过次级槽容量"),
    FLASH(4, "设备 Flash 擦写失败"),
    SIZE(5, "接收字节数与声明不符"),
    MAGIC(6, "镜像 magic 校验失败"),
    VERSION(7, "镜像版本与声明不符"),
    HASH(8, "SHA-256 校验失败"),
    LEN(9, "Control 报文长度非法"),
    INTERNAL(10, "设备内部错误"),
    /**
     * 设备接收缓冲溢出（512B 环形缓冲 + 双缓冲来不及排空）。
     *
     * 这是**保护性中止**：设备把 state 置 ERROR、err=11 并停止接收，
     * 已写入 Flash 的部分是完整的，不会写坏镜像。
     * 客户端应当提示后**从 START 重发**（设备会用已确认偏移续传），而不是继续发 Data。
     */
    OVERRUN(11, "设备接收缓冲溢出，已保护性中止（可从已确认偏移重发 START 续传）");

    companion object {
        fun fromValue(value: Int): OtaError? = entries.firstOrNull { it.value == value }
    }
}

/**
 * OTA Status 通知（12 字节）。
 *
 * | 偏移 | 长度 | 内容 |
 * |---|---|---|
 * | 0 | 4 | 已写入 Flash 的连续偏移（LE） |
 * | 4 | 4 | 镜像总字节数（LE） |
 * | 8 | 1 | state |
 * | 9 | 1 | err |
 * | 10 | 1 | 进度百分比 |
 * | 11 | 1 | flags（预留，恒 0） |
 *
 * 注意 [confirmedOffset] 是**设备已写入 Flash** 的偏移，刻意滞后于已接收字节数
 * （数据先进 256B 缓冲，凑满一页才落盘）。流控必须以它为准。
 */
data class OtaStatus(
    val confirmedOffset: Long,
    val total: Long,
    val state: OtaState,
    val error: OtaError,
    val progressPercent: Int,
    val flags: Int
) {
    val isError: Boolean get() = state == OtaState.ERROR
}

/** OTA Status 报文解析器（纯逻辑，无 Android 依赖）。 */
object OtaStatusParser {

    fun parse(data: ByteArray): OtaStatus? {
        if (data.size < OtaConstants.STATUS_MESSAGE_SIZE) return null
        return try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val offset = buffer.getInt(0).toLong() and 0xFFFFFFFFL
            val total = buffer.getInt(4).toLong() and 0xFFFFFFFFL
            val state = OtaState.fromValue(buffer.get(8).toInt() and 0xFF) ?: return null
            val error = OtaError.fromValue(buffer.get(9).toInt() and 0xFF) ?: OtaError.INTERNAL
            val progress = buffer.get(10).toInt() and 0xFF
            val flags = buffer.get(11).toInt() and 0xFF
            OtaStatus(offset, total, state, error, progress, flags)
        } catch (_: Exception) {
            null
        }
    }
}
