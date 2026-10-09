package com.example.pandatemperature.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.pandatemperature.data.bluetooth.BleManager
import com.example.pandatemperature.data.ota.AndroidOtaTransport
import com.example.pandatemperature.data.ota.OtaAuthKey
import com.example.pandatemperature.data.ota.OtaImageError
import com.example.pandatemperature.data.ota.OtaImageParser
import com.example.pandatemperature.data.ota.OtaProgress
import com.example.pandatemperature.data.ota.OtaResult
import com.example.pandatemperature.data.ota.OtaRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * BLE OTA 固件升级的界面状态与流程编排。
 *
 * 这里只做「读文件 → 调用 [OtaRunner] → 更新 UI 状态」，协议判断全部在 `data/ota` 的纯逻辑层，
 * 避免把协议堆进界面层。
 */
class FirmwareOtaViewModel(application: Application) : AndroidViewModel(application) {

    enum class Phase {
        IDLE,       // 未选文件
        LOADING,    // 读取/校验文件
        READY,      // 文件就绪，等待开始
        UPLOADING,  // 上传中
        VERIFIED,   // 校验通过，等待触发重启
        TRIGGERING, // 已请求升级，等待重启
        DONE,       // 已触发
        ERROR       // 失败
    }

    data class UiState(
        val phase: Phase = Phase.IDLE,
        val fileName: String? = null,
        val totalBytes: Long = 0L,
        val versionLabel: String? = null,
        val sha256Prefix: String? = null,
        val progress: OtaProgress? = null,
        val message: String? = null,
        val authKeyText: String = OtaAuthKey.defaultDisplayText()
    ) {
        val isBusy: Boolean get() = phase == Phase.LOADING || phase == Phase.UPLOADING || phase == Phase.TRIGGERING
    }

    private val bleManager = BleManager.getInstance(application)
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UiState(authKeyText = loadAuthKeyText()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    val connectionState: StateFlow<BleManager.ConnectionState> = bleManager.connectionState

    private var imageBytes: ByteArray? = null
    private var runner: OtaRunner? = null
    private var uploadJob: Job? = null

    /** 选择镜像文件后读取并预校验（magic / 长度 / SHA-256）。 */
    fun loadImage(uri: Uri) {
        if (_state.value.isBusy) return
        _state.value = _state.value.copy(phase = Phase.LOADING, message = "正在读取固件文件…")
        viewModelScope.launch {
            try {
                val resolver = getApplication<Application>().contentResolver
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw IllegalStateException("无法读取所选文件")
                val image = OtaImageParser.parse(bytes) // 失败抛 OtaImageError
                imageBytes = bytes
                _state.value = _state.value.copy(
                    phase = Phase.READY,
                    fileName = uri.lastPathSegment ?: "zephyr.signed.bin",
                    totalBytes = image.total,
                    versionLabel = image.versionLabel,
                    sha256Prefix = image.sha256.take(8).joinToString("") { "%02X".format(it) },
                    progress = null,
                    message = "文件校验通过，可以开始升级"
                )
            } catch (e: OtaImageError) {
                imageBytes = null
                _state.value = _state.value.copy(phase = Phase.ERROR, message = e.message)
            } catch (e: Exception) {
                imageBytes = null
                _state.value = _state.value.copy(phase = Phase.ERROR, message = "读取文件失败: ${e.message}")
            }
        }
    }

    fun onAuthKeyChanged(text: String, persist: Boolean = true) {
        _state.value = _state.value.copy(authKeyText = text)
        if (persist) prefs.edit().putString(KEY_AUTH, text).apply()
    }

    /** 开始上传（START → Data → END）。 */
    fun startUpload() {
        val state = _state.value
        if (state.isBusy) return
        val bytes = imageBytes
        if (bytes == null || state.phase != Phase.READY) {
            _state.value = state.copy(phase = Phase.ERROR, message = "请先选择并校验固件文件")
            return
        }
        if (!isConnected()) {
            _state.value = state.copy(phase = Phase.ERROR, message = "设备未连接，无法升级")
            return
        }
        val authKey = OtaAuthKey.parse(state.authKeyText)
        if (authKey == null) {
            _state.value = state.copy(
                phase = Phase.ERROR,
                message = "授权密钥需为 16 个字符或 32 位十六进制"
            )
            return
        }
        val image = try {
            OtaImageParser.parse(bytes)
        } catch (e: OtaImageError) {
            _state.value = state.copy(phase = Phase.ERROR, message = e.message)
            return
        }

        _state.value = state.copy(phase = Phase.UPLOADING, message = "正在升级…", progress = null)
        uploadJob = viewModelScope.launch {
            val transport = AndroidOtaTransport(bleManager)
            val session = OtaRunner(transport = transport, authKey = authKey)
            runner = session
            val result = try {
                session.run(
                    image = image,
                    bytes = bytes,
                    onProgress = { progress ->
                        _state.value = _state.value.copy(progress = progress, phase = Phase.UPLOADING)
                    },
                    onNotice = { notice ->
                        // 例如设备缓冲溢出后重发 START：流程继续，只是把提示透给用户
                        _state.value = _state.value.copy(phase = Phase.UPLOADING, message = notice)
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "OTA 会话异常", e)
                OtaResult.Failed(null, "升级中断: ${e.message}")
            }
            when (result) {
                is OtaResult.ReadyToInstall -> {
                    val resumed = if (result.resumedFrom > 0) "（自 ${result.resumedFrom} 字节续传）" else ""
                    _state.value = _state.value.copy(
                        phase = Phase.VERIFIED,
                        message = "上传并校验通过$resumed，点「立即重启并安装」完成升级"
                    )
                }
                is OtaResult.Failed -> {
                    _state.value = _state.value.copy(
                        phase = Phase.ERROR,
                        message = buildString {
                            append("升级失败")
                            result.error?.let { append("（err=${it.value} ${it.label}）") }
                            append("：")
                            append(result.detail)
                        }
                    )
                }
                OtaResult.Incomplete -> {
                    _state.value = _state.value.copy(
                        phase = Phase.ERROR,
                        message = "升级未完成（连接断开）。重新开始相同文件可续传。"
                    )
                }
            }
        }
    }

    /** 发送 TRIGGER，请求设备升级并重启。 */
    fun triggerUpgrade() {
        val session = runner
        if (session == null || _state.value.phase != Phase.VERIFIED) {
            _state.value = _state.value.copy(phase = Phase.ERROR, message = "尚未通过校验，无法触发升级")
            return
        }
        _state.value = _state.value.copy(phase = Phase.TRIGGERING, message = "已请求升级，设备即将重启…")
        viewModelScope.launch {
            val ok = try {
                session.triggerUpgrade()
            } catch (e: Exception) {
                Log.w(TAG, "触发升级异常", e)
                false
            }
            _state.value = if (ok) {
                _state.value.copy(phase = Phase.DONE, message = "升级已触发。设备重启后请重新连接确认固件版本。")
            } else {
                _state.value.copy(phase = Phase.ERROR, message = "触发升级失败")
            }
        }
    }

    /** 取消上传：通知设备 CANCEL 并结束本地任务。 */
    fun cancelUpload() {
        uploadJob?.cancel()
        uploadJob = null
        val session = runner
        viewModelScope.launch {
            try {
                session?.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "取消 OTA 异常", e)
            }
        }
        runner = null
        _state.value = _state.value.copy(
            phase = if (imageBytes != null) Phase.READY else Phase.IDLE,
            message = "已取消升级",
            progress = null
        )
    }

    /** 回到可选文件的状态（保留已选文件）。 */
    fun reset() {
        uploadJob?.cancel()
        uploadJob = null
        runner = null
        _state.value = _state.value.copy(
            phase = if (imageBytes != null) Phase.READY else Phase.IDLE,
            message = null,
            progress = null
        )
    }

    private fun isConnected(): Boolean {
        val state = bleManager.connectionState.value
        return state == BleManager.ConnectionState.ServicesDiscovered ||
            state == BleManager.ConnectionState.Connected
    }

    private fun loadAuthKeyText(): String =
        prefs.getString(KEY_AUTH, null) ?: OtaAuthKey.defaultDisplayText()

    companion object {
        private const val TAG = "FirmwareOtaViewModel"
        private const val PREFS_NAME = "firmware_ota"
        private const val KEY_AUTH = "auth_key"
    }
}
