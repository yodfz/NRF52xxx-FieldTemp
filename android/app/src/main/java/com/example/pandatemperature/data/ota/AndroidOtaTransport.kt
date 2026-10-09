package com.example.pandatemperature.data.ota

import android.bluetooth.BluetoothGattCharacteristic
import com.example.pandatemperature.data.bluetooth.BleConstants
import com.example.pandatemperature.data.bluetooth.BleManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [OtaTransport] 的 Android 实现：复用 [BleManager] 已建立的 GATT 连接。
 *
 * 注意：OTA 期间不要并行做其他 BLE 读写；会话本身是顺序的，`BleManager` 的
 * 单回调映射（按特征 UUID）也要求同一时刻一个特征只有一个在途操作。
 */
class AndroidOtaTransport(private val bleManager: BleManager) : OtaTransport {

    override fun isConnected(): Boolean =
        bleManager.connectionState.value == BleManager.ConnectionState.ServicesDiscovered ||
            bleManager.connectionState.value == BleManager.ConnectionState.Connected

    override suspend fun requestMtu(preferred: Int, fallback: Int): Int {
        if (!isConnected()) return fallback
        val deferred = CompletableDeferred<Int>()
        bleManager.requestMtu(preferred) { success, mtu ->
            deferred.complete(if (success && mtu > 0) mtu else fallback)
        }
        return withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() } ?: fallback
    }

    override suspend fun subscribeStatus(onStatus: (OtaStatus) -> Unit): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        bleManager.enableNotificationAsync(
            uuid = BleConstants.OTA_STATUS_CHAR,
            enable = true,
            onNotification = { data ->
                OtaStatusParser.parse(data)?.let(onStatus)
            },
            onComplete = { success -> deferred.complete(success) }
        )
        return withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() } ?: false
    }

    override suspend fun writeControl(bytes: ByteArray): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        bleManager.writeCharacteristic(
            uuid = BleConstants.OTA_CONTROL_CHAR,
            value = bytes,
            onWrite = { success -> deferred.complete(success) },
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
        return withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() } ?: false
    }

    override suspend fun writeData(bytes: ByteArray): Boolean {
        // WRITE_TYPE_NO_RESPONSE：设备侧只保证"已排队"，正确性最终由 END 的字节数/SHA-256 校验兜底。
        //
        // 但**必须一片一片等写完再发下一片**（真机实测，MIUI/Android 13 + QTI 栈）：
        // 同一特征同时只允许一个在途写，上一片还没收到 onCharacteristicWrite 就发下一片时，
        // `BluetoothGatt.writeCharacteristic()` 会直接返回 false。这是瞬时"忙"，不是链路错误，
        // 之前把它当成永久失败，导致第 2 片（偏移 125）就整体报错。
        // 代价是速率≈1 片/连接间隔（125B/16ms ≈ 7.8 KB/s），与 PC 侧客户端实测一致。
        repeat(DATA_WRITE_ATTEMPTS) { attempt ->
            val deferred = CompletableDeferred<Boolean>()
            bleManager.writeCharacteristic(
                uuid = BleConstants.OTA_DATA_CHAR,
                value = bytes,
                onWrite = { success -> deferred.complete(success) },
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
            when (withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() }) {
                true -> return true
                // 超时说明链路真的有问题（不是忙），继续重试没有意义
                null -> return false
                // 被"忙"拒绝：短暂退避后重试同一片
                else -> if (attempt < DATA_WRITE_ATTEMPTS - 1) delay(DATA_WRITE_RETRY_MS)
            }
        }
        return false
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 5_000L

        /** Data 片写入的最大尝试次数（含首次）。 */
        const val DATA_WRITE_ATTEMPTS = 5

        /** 被"忙"拒绝后的退避时延。 */
        const val DATA_WRITE_RETRY_MS = 8L
    }
}
