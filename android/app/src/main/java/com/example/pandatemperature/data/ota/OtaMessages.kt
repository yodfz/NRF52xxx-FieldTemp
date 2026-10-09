package com.example.pandatemperature.data.ota

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OTA Control 报文构造。
 *
 * 严格按固件 `ble_ota.c` 的字段顺序与长度，构造失败时直接抛异常（属于编程错误，
 * 不应静默降级），由调用方在联调期快速暴露。
 */
object OtaMessages {

    /**
     * START（0x01），总长 61 字节：
     * `[op][16B key][4B total LE][8B ih_ver][32B 整文件 SHA-256]`。
     *
     * @param total 镜像总字节数
     * @param ihVer 镜像头偏移 20 起的 8 字节
     * @param sha256 整个 `zephyr.signed.bin` 的 SHA-256
     * @param authKey 16 字节授权密钥
     */
    fun buildStart(total: Long, ihVer: ByteArray, sha256: ByteArray, authKey: ByteArray): ByteArray {
        require(authKey.size == OtaConstants.AUTH_KEY_SIZE) {
            "授权密钥必须为 ${OtaConstants.AUTH_KEY_SIZE} 字节，实际 ${authKey.size}"
        }
        require(ihVer.size == OtaConstants.IH_VER_SIZE) {
            "ih_ver 必须为 ${OtaConstants.IH_VER_SIZE} 字节，实际 ${ihVer.size}"
        }
        require(sha256.size == OtaConstants.SHA256_SIZE) {
            "SHA-256 必须为 ${OtaConstants.SHA256_SIZE} 字节，实际 ${sha256.size}"
        }
        require(total in 0..0xFFFFFFFFL) { "total 超出 uint32 范围: $total" }

        val buffer = ByteBuffer.allocate(OtaConstants.START_MESSAGE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(OP_BYTE_START)
        buffer.put(authKey)
        buffer.putInt((total and 0xFFFFFFFFL).toInt())
        buffer.put(ihVer)
        buffer.put(sha256)
        return buffer.array()
    }

    /** END（0x02）：结束并让设备校验字节数/magic/版本/SHA-256，通过后进入 VERIFY。 */
    fun buildEnd(): ByteArray = byteArrayOf(OP_BYTE_END)

    /** CANCEL（0x03）：取消本次 OTA，释放分区并清除续传状态。 */
    fun buildCancel(): ByteArray = byteArrayOf(OP_BYTE_CANCEL)

    /** TRIGGER（0x04）：仅在 VERIFY 状态有效，请求升级并重启。 */
    fun buildTrigger(): ByteArray = byteArrayOf(OP_BYTE_TRIGGER)

    private val OP_BYTE_START = OtaConstants.OP_START.toByte()
    private val OP_BYTE_END = OtaConstants.OP_END.toByte()
    private val OP_BYTE_CANCEL = OtaConstants.OP_CANCEL.toByte()
    private val OP_BYTE_TRIGGER = OtaConstants.OP_TRIGGER.toByte()
}
