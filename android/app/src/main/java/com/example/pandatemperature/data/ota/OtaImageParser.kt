package com.example.pandatemperature.data.ota

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * 待升级镜像的解析结果。
 *
 * @property total 镜像总字节数
 * @property ihVer 镜像头偏移 [OtaConstants.IH_VER_OFFSET_IN_IMAGE]（0x14）起的 8 字节版本
 * @property sha256 整个文件的 SHA-256（START 里声明，设备 END 时比对；前 16 字节兼作续传 image id）
 */
data class OtaImageInfo(
    val total: Long,
    val ihVer: ByteArray,
    val sha256: ByteArray
) {
    /** `ih_ver` 的可读形式：major.minor.rev+build。 */
    val versionLabel: String
        get() {
            if (ihVer.size < OtaConstants.IH_VER_SIZE) return "--"
            val major = ihVer[0].toInt() and 0xFF
            val minor = ihVer[1].toInt() and 0xFF
            val revision = ((ihVer[3].toInt() and 0xFF) shl 8) or (ihVer[2].toInt() and 0xFF)
            val build = ByteBuffer.wrap(ihVer, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            return "$major.$minor.$revision+$build"
        }

    /** 镜像是否超出次级槽容量（超出时固件会在擦除前以 err=3 拒绝）。 */
    val exceedsSlotCapacity: Boolean
        get() = total > OtaConstants.SECONDARY_SLOT_CAPACITY
}

/** 镜像解析失败的具体原因。 */
sealed class OtaImageError(message: String) : Exception(message) {
    class TooShort(size: Int) : OtaImageError("文件过短（$size 字节），不是完整镜像")
    class BadMagic(actual: Long) : OtaImageError(
        "镜像 magic 不正确（0x%08X，期望 0x%08X），请选择 zephyr.signed.bin".format(actual, OtaConstants.IMAGE_MAGIC)
    )
    class TooLarge(total: Long) : OtaImageError(
        "镜像 ${total} 字节超过次级槽容量 ${OtaConstants.SECONDARY_SLOT_CAPACITY} 字节"
    )
}

/**
 * 解析 `zephyr.signed.bin`。
 *
 * 只接受 **含 MCUboot 头与签名** 的 signed bin：裸 `zephyr.bin` / `merged.hex` 的
 * magic 不正确，会被 [OtaImageError.BadMagic] 明确拒绝，而不是上传后再由设备报错。
 */
object OtaImageParser {

    /** 能被解析的最小长度：至少覆盖到 `ih_ver` 末尾（20 + 8）。 */
    private const val MIN_HEADER_SIZE = OtaConstants.IH_VER_OFFSET_IN_IMAGE + OtaConstants.IH_VER_SIZE

    /**
     * @param bytes 完整镜像内容
     * @throws OtaImageError 文件过短 / magic 不符 / 超过槽容量
     */
    fun parse(bytes: ByteArray): OtaImageInfo {
        if (bytes.size < MIN_HEADER_SIZE) {
            throw OtaImageError.TooShort(bytes.size)
        }
        val magic = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        if (magic != OtaConstants.IMAGE_MAGIC) {
            throw OtaImageError.BadMagic(magic)
        }
        val total = bytes.size.toLong()
        if (total > OtaConstants.SECONDARY_SLOT_CAPACITY) {
            throw OtaImageError.TooLarge(total)
        }
        val ihVer = bytes.copyOfRange(
            OtaConstants.IH_VER_OFFSET_IN_IMAGE,
            OtaConstants.IH_VER_OFFSET_IN_IMAGE + OtaConstants.IH_VER_SIZE
        )
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
        return OtaImageInfo(total = total, ihVer = ihVer, sha256 = sha256)
    }
}
