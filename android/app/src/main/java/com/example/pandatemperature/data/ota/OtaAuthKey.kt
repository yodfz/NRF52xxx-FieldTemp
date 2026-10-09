package com.example.pandatemperature.data.ota

/**
 * OTA 授权密钥的文本编解码。
 *
 * 固件比较的是 16 字节原始序列，但用户更可能拿到「字符串」或「十六进制」两种形式，
 * 因此这里同时接受：
 * - 16 个可见 ASCII 字符；
 * - 32 位十六进制（大小写均可，可带空格）。
 *
 * 密钥是凭据，**不入库**：默认值由构建期从 `ota.properties` 注入
 * （见 `ota.properties.example`），未配置时为空。
 * ⚠️ 它只防误触/无脑脚本，固件里能读出，**不防逆向**，不是安全边界。
 */
object OtaAuthKey {

    /** 解析用户输入；格式非法返回 null。 */
    fun parse(text: String): ByteArray? {
        val trimmed = text.trim()
        if (trimmed.length == OtaConstants.AUTH_KEY_SIZE) {
            return trimmed.toByteArray(Charsets.US_ASCII)
        }
        val hex = trimmed.replace(" ", "").replace("-", "")
        if (hex.length == OtaConstants.AUTH_KEY_SIZE * 2 && hex.all { it.isHexDigit() }) {
            return ByteArray(OtaConstants.AUTH_KEY_SIZE) { i ->
                ((hex[i * 2].hexValue() shl 4) or hex[i * 2 + 1].hexValue()).toByte()
            }
        }
        return null
    }

    /** 把密钥转成便于编辑的默认文本：可打印 ASCII 时直接用字符串，否则用十六进制。 */
    fun toDisplayText(key: ByteArray): String {
        if (key.size == OtaConstants.AUTH_KEY_SIZE && key.all { it.toInt() in 0x20..0x7E }) {
            return String(key, Charsets.US_ASCII)
        }
        return key.joinToString("") { "%02X".format(it) }
    }

    /** 默认（联调）密钥的可编辑文本。 */
    fun defaultDisplayText(): String = toDisplayText(OtaConstants.DEFAULT_AUTH_KEY)

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun Char.hexValue(): Int = when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        else -> this - 'A' + 10
    }
}
