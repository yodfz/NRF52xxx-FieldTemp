package com.example.pandatemperature.data.ota

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** OTA 进度快照（以设备已确认偏移为准）。 */
data class OtaProgress(
    val queuedBytes: Long,
    val confirmedBytes: Long,
    val totalBytes: Long,
    val percent: Int,
    val chunkSize: Int
)

/** OTA 会话结果。 */
sealed interface OtaResult {
    /** 数据已全部写入并通过设备校验（`VERIFY`），等待调用 [OtaRunner.triggerUpgrade]。 */
    data class ReadyToInstall(val total: Long, val resumedFrom: Long) : OtaResult

    /** 失败；[error] 为设备错误码（本地前置校验失败时为 null，见 [detail]）。 */
    data class Failed(val error: OtaError?, val detail: String) : OtaResult

    /** 未完成（连接断开或调用方取消）。 */
    data object Incomplete : OtaResult
}

/**
 * OTA 会话状态机。
 *
 * 流程：协商 MTU → 订阅 Status → START → **等 READY**（取续传偏移）→ 按窗口发 Data → 等整页落盘
 * → END → 等 VERIFY。之后由调用方决定是否 [triggerUpgrade]（END 不会自动重启）。
 *
 * 注意 START 在固件侧是**异步**的：整槽擦除、NVS 续传状态、整镜像 SHA-256 都已搬出 GATT 回调，
 * 投递到系统工作队列执行，期间设备可能没有状态通知。
 * **IDLE 且无错误 ≠ 可以发数据**，必须等到 `state == READY` 才开始发 Data（见 [readyTimeoutMs]）。
 *
 * 传输中途若收到 [OtaError.OVERRUN]（设备 512B 环形缓冲 + 双缓冲溢出，保护性中止），
 * 不继续发 Data，而是提示后**重发 START**，由设备用已确认偏移续传（见 [OtaConstants.MAX_OVERRUN_RESTARTS]）。
 *
 * 所有等待都有超时，避免设备静默时挂死；失败统一返回 [OtaResult]。
 *
 * @param transport 传输实现
 * @param authKey 16 字节授权密钥（联调默认值见 [OtaConstants.DEFAULT_AUTH_KEY]，量产需更换）
 * @param windowBytes 流控窗口
 * @param statusTimeoutMs 单次等待 Status 的超时
 * @param readyTimeoutMs 写完 START 后等待 READY 的超时（覆盖异步擦除/初始化的耗时）
 */
class OtaRunner(
    private val transport: OtaTransport,
    private val authKey: ByteArray = OtaConstants.DEFAULT_AUTH_KEY,
    private val windowBytes: Int = OtaConstants.FLOW_WINDOW_BYTES,
    private val statusTimeoutMs: Long = 8_000L,
    private val readyTimeoutMs: Long = OtaConstants.START_READY_TIMEOUT_MS
) {

    private val statusChannel = Channel<OtaStatus>(Channel.UNLIMITED)

    @Volatile
    private var latestStatus: OtaStatus? = null

    /**
     * 执行到 END 校验通过为止。
     *
     * @param image 镜像信息（由 [OtaImageParser] 解析得到）
     * @param bytes 镜像完整内容
     * @param onProgress 进度回调（在调用协程上下文中同步触发）
     * @param onNotice 需要让用户看到的提示（例如因缓冲溢出重发 START），不改变流程
     */
    suspend fun run(
        image: OtaImageInfo,
        bytes: ByteArray,
        onProgress: (OtaProgress) -> Unit = {},
        onNotice: (String) -> Unit = {}
    ): OtaResult {
        require(bytes.size.toLong() == image.total) { "镜像内容与解析结果不一致" }

        // 1. 协商 MTU
        val mtu = transport.requestMtu(OtaConstants.PREFERRED_MTU, OtaConstants.FALLBACK_MTU)
        if (mtu < OtaConstants.MIN_START_MTU) {
            return OtaResult.Failed(null, "协商 MTU=$mtu 过小，无法发送 ${OtaConstants.START_MESSAGE_SIZE} 字节 START")
        }
        val chunkSize = OtaConstants.dataChunkSize(mtu)

        // 2. 订阅 Status（必须在 START 之前，否则收不到确认偏移）
        if (!transport.subscribeStatus { status -> statusChannel.trySend(status) }) {
            return OtaResult.Failed(null, "订阅 OTA Status 失败")
        }

        // 3. 一次完整会话；只有 OVERRUN 才允许重发 START 续传，其余错误直接上抛
        var restartsLeft = OtaConstants.MAX_OVERRUN_RESTARTS
        while (true) {
            val result = uploadOnce(image, bytes, chunkSize, onProgress)
            if (result !is OtaResult.Failed) return result
            if (result.error != OtaError.OVERRUN || restartsLeft <= 0) return result
            restartsLeft--
            dropStaleStatuses()
            onNotice(
                "设备接收缓冲溢出（err=11，已保护性中止），正在从已确认偏移重发 START 续传" +
                    "（剩余重试 $restartsLeft 次）"
            )
        }
    }

    /**
     * START → 等 READY → Data → END → 等 VERIFY 的单次会话。
     *
     * @return [OtaResult.ReadyToInstall] / [OtaResult.Failed] / [OtaResult.Incomplete]
     */
    private suspend fun uploadOnce(
        image: OtaImageInfo,
        bytes: ByteArray,
        chunkSize: Int,
        onProgress: (OtaProgress) -> Unit
    ): OtaResult {
        latestStatus = null

        // START（续传时设备会回报已对齐的起始偏移）
        if (!transport.writeControl(
                OtaMessages.buildStart(image.total, image.ihVer, image.sha256, authKey)
            )
        ) {
            return OtaResult.Failed(null, "发送 START 失败")
        }
        // 固件把整槽擦除 / 整镜像 SHA 搬到系统工作队列后，START 是异步的：
        // 完成前设备一直上报 state=IDLE, err=0。**IDLE 且无错误不能当成"可以发数据"**，
        // 只有 state == READY 才代表设备准备好接收；超时按失败处理，避免静默挂死。
        val ready = awaitStatus(timeoutMs = readyTimeoutMs) { it.state == OtaState.READY }
            ?: return OtaResult.Failed(
                null,
                "等待设备 READY 超时（${readyTimeoutMs}ms）：START 为异步执行，期间设备上报 IDLE 而非 READY"
            )
        if (ready.isError) return OtaResult.Failed(ready.error, ready.error.label)

        val flow = OtaFlowController(image.total, windowBytes)
        val resumedFrom = minOf(ready.confirmedOffset, image.total)
        flow.resumeFromDeviceOffset(resumedFrom)
        emitProgress(flow, chunkSize, onProgress)

        // 2. 按窗口发送数据
        while (!flow.isFullyQueued()) {
            drainStatuses(flow)
            latestStatus?.takeIf { it.isError }?.let { return OtaResult.Failed(it.error, it.error.label) }

            if (!flow.canQueueMore()) {
                val status = awaitStatus(onEach = { flow.onConfirmed(it.confirmedOffset) }) {
                    flow.canQueueMore() || it.isError
                } ?: return OtaResult.Failed(null, "等待设备确认偏移超时（流控窗口已满）")
                if (status.isError) return OtaResult.Failed(status.error, status.error.label)
                emitProgress(flow, chunkSize, onProgress)
                continue
            }

            if (!transport.isConnected()) return OtaResult.Incomplete

            val size = flow.nextChunkSize(chunkSize)
            if (size == 0) break
            val start = flow.queuedOffset.toInt()
            val chunk = bytes.copyOfRange(start, start + size)
            if (!transport.writeData(chunk)) {
                return OtaResult.Failed(null, "写入 OTA Data 失败（偏移 $start）")
            }
            flow.onQueued(size)
            emitProgress(flow, chunkSize, onProgress)
        }

        // 3. 等待所有整页落盘（尾部不足一页由 END 触发 flush）
        while (!flow.isAllPagesWritten()) {
            drainStatuses(flow)
            latestStatus?.takeIf { it.isError }?.let { return OtaResult.Failed(it.error, it.error.label) }
            if (flow.isAllPagesWritten()) break
            val status = awaitStatus(onEach = { flow.onConfirmed(it.confirmedOffset) }) {
                flow.isAllPagesWritten() || it.isError
            } ?: break // 超时也继续发 END，由设备 END 校验兜底
            if (status.isError) return OtaResult.Failed(status.error, status.error.label)
            emitProgress(flow, chunkSize, onProgress)
        }
        emitProgress(flow, chunkSize, onProgress)

        // 4. END 并等待校验结果
        if (!transport.writeControl(OtaMessages.buildEnd())) {
            return OtaResult.Failed(null, "发送 END 失败")
        }
        val verified = awaitStatus { it.state == OtaState.VERIFY }
            ?: return OtaResult.Failed(null, "等待设备校验超时")
        if (verified.isError) return OtaResult.Failed(verified.error, verified.error.label)
        if (verified.state != OtaState.VERIFY) {
            return OtaResult.Failed(null, "设备未进入 VERIFY（state=${verified.state}）")
        }
        return OtaResult.ReadyToInstall(image.total, resumedFrom)
    }

    /**
     * 发送 TRIGGER，请求升级并重启（仅 VERIFY 状态有效）。
     *
     * 设备会先通知 `PENDING` 再重启；若通知尚未到达就断连，视为已触发（无法区分重启与超时）。
     *
     * 真机实测补充：设备收到 TRIGGER 后**立即**开始 MCUboot 复制并复位，BLE 链路在写完完成前
     * 就被拉断，Android 会以 `status=133 (GATT_ERROR)` 上报这次写失败 —— 这是"触发成功"的
     * 伴随现象，不是失败。因此写失败后要再等一小会儿看链路是否断开：断开 ⇒ 已触发。
     */
    /** Reattach the existing status path for an explicitly authorized trigger-only session. */
    suspend fun prepareTrigger(): Boolean = transport.isConnected() &&
        transport.subscribeStatus { status -> statusChannel.trySend(status) }

    suspend fun triggerUpgrade(): Boolean {
        // 链路本来就已经断了：不能把"写了但没连通"当成已触发
        if (!transport.isConnected()) return false
        if (!transport.writeControl(OtaMessages.buildTrigger())) {
            repeat(TRIGGER_DISCONNECT_GRACE_STEPS) {
                if (!transport.isConnected()) return true
                delay(TRIGGER_DISCONNECT_GRACE_MS)
            }
            return false
        }
        val pending = awaitStatus(3_000L) { it.state == OtaState.PENDING || it.isError }
        return pending?.isError != true
    }

    /** 发送 CANCEL，释放设备分区并清除续传状态。 */
    suspend fun cancel(): Boolean = transport.writeControl(OtaMessages.buildCancel())

    private fun emitProgress(flow: OtaFlowController, chunkSize: Int, onProgress: (OtaProgress) -> Unit) {
        onProgress(
            OtaProgress(
                queuedBytes = flow.queuedOffset,
                confirmedBytes = flow.confirmedOffset,
                totalBytes = flow.total,
                percent = flow.progressPercent(),
                chunkSize = chunkSize
            )
        )
    }

    /**
     * 消费队列里已到达的 Status（不阻塞），把确认偏移写回 [flow] 并更新 [latestStatus]。
     * 这是设备确认偏移的主要来源 —— 不能只依赖窗口满时的等待，否则设备快于窗口时进度会一直不动。
     */
    private fun drainStatuses(flow: OtaFlowController) {
        while (true) {
            val status = statusChannel.tryReceive().getOrNull() ?: return
            latestStatus = status
            flow.onConfirmed(status.confirmedOffset)
        }
    }

    /**
     * 丢弃重发 START 之前残留的 Status。
     *
     * 上一次会话的 ERROR/OVERRUN 通知可能还在队列里，若不清理会让新一轮会话"看起来又失败了"。
     */
    private fun dropStaleStatuses() {
        latestStatus = null
        while (statusChannel.tryReceive().getOrNull() != null) {
            // 丢弃过期状态
        }
    }

    /**
     * 阻塞等待满足 [predicate] 的 Status；遇到设备错误立即返回该错误状态。
     *
     * [onEach] 在判定 [predicate] **之前**对每个到达的状态执行，便于把确认偏移写回流控器。
     *
     * @return 命中的状态；超时返回 null
     */
    private suspend fun awaitStatus(
        timeoutMs: Long = statusTimeoutMs,
        onEach: (OtaStatus) -> Unit = {},
        predicate: (OtaStatus) -> Boolean
    ): OtaStatus? = withTimeoutOrNull(timeoutMs) {
        var found: OtaStatus? = null
        while (found == null) {
            val status = statusChannel.receive()
            latestStatus = status
            onEach(status)
            if (status.isError || predicate(status)) found = status
        }
        found
    }

    private companion object {
        /** TRIGGER 写失败后等待链路断开的轮询步数（15 × 100ms = 最长 1.5s）。 */
        const val TRIGGER_DISCONNECT_GRACE_STEPS = 15

        /** 每次轮询的间隔。 */
        const val TRIGGER_DISCONNECT_GRACE_MS = 100L
    }
}
