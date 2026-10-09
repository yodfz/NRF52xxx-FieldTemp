package com.example.pandatemperature.data.ota

import com.example.pandatemperature.BuildConfig

/**
 * 自定义 BLE OTA 协议常量。
 *
 * 依据：固件 `ble_ota.h`（固件 1.0.6+0 起）与 `OTA协议说明.md`。
 * 这里只放纯协议事实，不含任何 Android 依赖，便于单元测试。
 */
object OtaConstants {

    // GATT UUID 统一放在 BleConstants.OTA_*（保持 bluetooth 包是 UUID 的唯一出处，
    // 避免 ota -> bluetooth -> ota 的循环依赖）。

    // ---- Control 操作码 ----
    const val OP_START: Int = 0x01
    const val OP_END: Int = 0x02
    const val OP_CANCEL: Int = 0x03
    const val OP_TRIGGER: Int = 0x04

    // ---- 报文长度 ----
    /** START 总长：1(op) + 16(key) + 4(total) + 8(ih_ver) + 32(sha256)。 */
    const val START_MESSAGE_SIZE: Int = 61
    const val AUTH_KEY_SIZE: Int = 16
    const val SHA256_SIZE: Int = 32
    const val IH_VER_SIZE: Int = 8

    /** OTA Status 通知长度。 */
    const val STATUS_MESSAGE_SIZE: Int = 12

    /** 镜像头中 `ih_ver` 的起始偏移（0x14）。 */
    const val IH_VER_OFFSET_IN_IMAGE: Int = 20

    // ---- 镜像 ----
    /** MCUboot 镜像 magic（小端字节为 3d b8 f3 96）。0x96F3B83D 超出 Int 正数范围，故用 Long。 */
    const val IMAGE_MAGIC: Long = 0x96F3B83DL

    /** 次级槽容量（字节）。固件在擦除前用 total 与之比较，超出返回 err=3。 */
    const val SECONDARY_SLOT_CAPACITY: Long = 163_840L

    /** 设备续传偏移向下的对齐粒度（CONFIG_IMG_BLOCK_BUF_SIZE）。 */
    const val RESUME_ALIGNMENT: Int = 256

    // ---- MTU 与分片 ----
    /** 固件请求的 ATT MTU。 */
    const val PREFERRED_MTU: Int = 128

    /** 协商失败时的回退 MTU（仍可容纳 61 字节 START）。 */
    const val FALLBACK_MTU: Int = 65

    /** START 报文要求的最小 ATT MTU：61 + 3(ATT 头) = 64。 */
    const val MIN_START_MTU: Int = 64

    /** ATT 写操作头长度，分片大小 = MTU - 此值。 */
    const val ATT_HEADER_SIZE: Int = 3

    // ---- 流控 ----
    /** 设备每收到这么多数据包通知一次 Status。 */
    const val STATUS_EVERY_N_PACKETS: Int = 8

    /** 建议流控窗口：未确认字节数不超过此值时继续发送。 */
    const val FLOW_WINDOW_BYTES: Int = 4 * 1024

    // ---- 时序（固件把擦除/写 Flash/整镜像 SHA 搬出 GATT 回调后引入） ----
    /**
     * 写完 START 后等待设备进入 READY 的超时。
     *
     * START 在固件侧是**异步**的（整槽擦除、NVS 续传状态、整镜像 SHA-256 都投递到系统工作队列），
     * 期间设备可能没有状态通知；客户端必须等待 READY。取值要明显大于一次整槽擦除的时间。
     */
    const val START_READY_TIMEOUT_MS: Long = 15_000L

    /**
     * 允许因 [OtaError.OVERRUN] 重新从 START 续传的次数（不含首次）。
     *
     * 每次重发都从设备已确认偏移继续，不会从头再来；超过次数仍溢出说明链路写入速度不足，直接报错。
     */
    const val MAX_OVERRUN_RESTARTS: Int = 2

    // ---- 授权 ----
    /**
     * 构建期注入的默认授权密钥，来源是仓库根目录的 `ota.properties`
     * （模板见 `ota.properties.example`）。
     *
     * 密钥是**凭据**：不入库、不进 src.zip。未配置该文件时这里是空数组，
     * UI 不会预填任何值，需要用户手动输入。
     * 取值必须与固件 `src/ble/ota_auth_key.h` 的 `OTA_AUTH_KEY` 一致。
     *
     * ⚠️ 它只是**防误触 / 防无脑脚本**，固件里能读出来，**不防逆向**；
     * 真正的安全边界是 MCUboot 的 ECDSA-P256 验签。
     * 量产应改为每型号/每批次独立的值。
     */
    val DEFAULT_AUTH_KEY: ByteArray =
        BuildConfig.OTA_DEFAULT_AUTH_KEY.toByteArray(Charsets.US_ASCII)

    /** 固件要求传 `zephyr.signed.bin`（含 MCUboot 头与签名），不接受裸 bin / merged.hex。 */
    const val REQUIRED_FILE_HINT: String = "zephyr.signed.bin"

    /**
     * 数据分片大小 = 协商 MTU − 3。
     *
     * **不得硬编码 125**：MTU 128 -> 125，回退 65 -> 62。
     */
    fun dataChunkSize(negotiatedMtu: Int): Int = (negotiatedMtu - ATT_HEADER_SIZE).coerceAtLeast(1)
}
