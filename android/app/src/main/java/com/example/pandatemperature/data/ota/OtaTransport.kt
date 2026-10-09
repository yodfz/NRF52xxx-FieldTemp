package com.example.pandatemperature.data.ota

/**
 * OTA 传输抽象。
 *
 * 把「会话逻辑」与「Android BLE 细节」解耦：
 * - 生产实现 [AndroidOtaTransport] 走 `BleManager`（同一 GATT 连接）；
 * - 单元测试用假实现模拟设备（维护 Flash 偏移、每 N 包通知、支持续传与负向用例）。
 */
interface OtaTransport {

    /** 当前是否已连接。 */
    fun isConnected(): Boolean

    /**
     * 请求协商 ATT MTU。
     * @return 协商到的 MTU；失败时返回 [fallback]（由调用方判断是否够用）
     */
    suspend fun requestMtu(preferred: Int, fallback: Int): Int

    /**
     * 订阅 OTA Status 通知；[onStatus] 可能来自 BLE 回调线程。
     * @return 是否订阅成功
     */
    suspend fun subscribeStatus(onStatus: (OtaStatus) -> Unit): Boolean

    /** 写 OTA Control（带响应）。 */
    suspend fun writeControl(bytes: ByteArray): Boolean

    /** 写 OTA Data（Write Without Response）。 */
    suspend fun writeData(bytes: ByteArray): Boolean
}
