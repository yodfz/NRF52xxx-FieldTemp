package com.example.pandatemperature.data.ota

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OtaRunnerTest {

    /**
     * 模拟固件：维护 Flash 已确认偏移、每 8 包通知一次、支持续传与错误码。
     *
     * @param idleStatusesBeforeReady START 后先上报几次 `IDLE(err=0)` 再进 READY
     * （固件把整槽擦除 / 整镜像 SHA 搬出 GATT 回调后，START 是异步的）。
     * @param neverBecomesReady START 后一直停在 IDLE，用于验证 IDLE 不会被当成"可以发数据"。
     * @param overrunAfterChunks 本轮收到第 N 片数据后制造一次 err=11 缓冲溢出（0 基，> N 时触发）。
     * @param overrunRepeats 为 true 时每次重发 START 后仍会溢出（用于验证重试预算）。
     */
    private class FakeDevice(
        private val negotiatedMtu: Int = 128,
        private val resumeOffset: Long = 0L,
        private val authKey: ByteArray = SAMPLE_AUTH_KEY,
        private val idleStatusesBeforeReady: Int = 0,
        private val neverBecomesReady: Boolean = false,
        private var overrunAfterChunks: Int = -1,
        private val overrunRepeats: Boolean = false
    ) : OtaTransport {

        var received = 0L
            private set
        var confirmed = 0L
            private set
        var state = OtaState.IDLE
            private set
        var lastError = OtaError.NONE
            private set
        var dataBytesWritten = 0L
            private set
        var startCount = 0
            private set
        val chunkSizes = mutableListOf<Int>()

        /** 链路是否还在（TRIGGER 会让设备复位，链路随之断开）。 */
        var connected = true
            private set

        /** 模拟"TRIGGER 写失败"（真机上表现为 status=133 GATT_ERROR）。 */
        var triggerWriteFails = false

        /** 模拟"收到 TRIGGER 就复位"：链路断开与写失败同时发生。 */
        var dropLinkOnTrigger = false

        fun markDisconnected() {
            connected = false
        }

        private var statusListener: ((OtaStatus) -> Unit)? = null
        private var total = 0L
        private var sinceAck = 0
        private var store = ByteArray(0)
        /** 设备侧持久化的续传偏移（NVS），每次 START 都从这里继续。 */
        private var persistedResume = resumeOffset
        private var dataChunks = 0

        override fun isConnected(): Boolean = connected

        override suspend fun requestMtu(preferred: Int, fallback: Int): Int = negotiatedMtu

        override suspend fun subscribeStatus(onStatus: (OtaStatus) -> Unit): Boolean {
            statusListener = onStatus
            return true
        }

        override suspend fun writeControl(bytes: ByteArray): Boolean {
            when (bytes[0].toInt()) {
                OtaConstants.OP_START -> handleStart(bytes)
                OtaConstants.OP_END -> handleEnd()
                OtaConstants.OP_CANCEL -> {
                    state = OtaState.IDLE
                    emitStatus()
                }
                OtaConstants.OP_TRIGGER -> {
                    // 真机：设备收到 TRIGGER 立即复位，链路断开与写失败几乎同时发生
                    if (dropLinkOnTrigger) connected = false
                    if (triggerWriteFails) return false
                    if (state == OtaState.VERIFY) state = OtaState.PENDING else raise(OtaError.STATE)
                    emitStatus()
                }
            }
            return true
        }

        override suspend fun writeData(bytes: ByteArray): Boolean {
            if (state != OtaState.READY && state != OtaState.RECEIVING) {
                raise(OtaError.STATE)
                return true
            }
            if (received + bytes.size > total) {
                raise(OtaError.SIZE)
                return true
            }
            System.arraycopy(bytes, 0, store, received.toInt(), bytes.size)
            received += bytes.size
            dataBytesWritten += bytes.size
            chunkSizes.add(bytes.size)
            state = OtaState.RECEIVING
            if (++sinceAck >= OtaConstants.STATUS_EVERY_N_PACKETS) {
                sinceAck = 0
                confirmed = received - (received % OtaConstants.RESUME_ALIGNMENT)
                emitStatus()
            }
            // 模拟设备 512B 环形缓冲 + 双缓冲溢出：保护性中止，保留已确认偏移供续传
            if (overrunAfterChunks >= 0 && ++dataChunks > overrunAfterChunks) {
                persistedResume = confirmed
                if (!overrunRepeats) overrunAfterChunks = -1
                raise(OtaError.OVERRUN)
            }
            return true
        }

        private suspend fun handleStart(bytes: ByteArray) {
            startCount++
            lastError = OtaError.NONE
            if (bytes.size != OtaConstants.START_MESSAGE_SIZE) {
                raise(OtaError.LEN)
                return
            }
            if (!bytes.copyOfRange(1, 1 + OtaConstants.AUTH_KEY_SIZE).contentEquals(authKey)) {
                raise(OtaError.AUTH)
                return
            }
            total = ByteBuffer.wrap(bytes, 17, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            if (total > OtaConstants.SECONDARY_SLOT_CAPACITY) {
                raise(OtaError.TOO_LARGE)
                return
            }
            val start = if (persistedResume in 1 until total) persistedResume else 0L
            store = ByteArray(total.toInt())
            received = start
            confirmed = start
            dataBytesWritten = 0L
            dataChunks = 0
            sinceAck = 0
            chunkSizes.clear()
            // 异步 START：设备先停留在 IDLE(err=0)，擦除/初始化完成后才进 READY
            if (neverBecomesReady) {
                state = OtaState.IDLE
                emitStatus()
                return
            }
            repeat(idleStatusesBeforeReady) {
                state = OtaState.IDLE
                emitStatus()
                delay(10L)
            }
            state = OtaState.READY
            emitStatus()
        }

        private fun handleEnd() {
            confirmed = received
            if (received == total) {
                state = OtaState.VERIFY
            } else {
                raise(OtaError.SIZE)
                return
            }
            emitStatus()
        }

        private fun raise(error: OtaError) {
            lastError = error
            state = OtaState.ERROR
            emitStatus()
        }

        private fun emitStatus() {
            val progress = if (total > 0L) ((confirmed * 100L) / total).toInt() else 0
            statusListener?.invoke(OtaStatus(confirmed, total, state, lastError, progress, 0))
        }
    }

    private fun signedImage(size: Int): ByteArray {
        val bytes = ByteArray(size)
        bytes[0] = 0x3D
        bytes[1] = 0xB8.toByte()
        bytes[2] = 0xF3.toByte()
        bytes[3] = 0x96.toByte()
        bytes[20] = 1
        bytes[22] = 6
        return bytes
    }

    private fun runner(
        device: FakeDevice,
        key: ByteArray = SAMPLE_AUTH_KEY,
        readyTimeoutMs: Long = 1_000L
    ) = OtaRunner(device, key, statusTimeoutMs = 300L, readyTimeoutMs = readyTimeoutMs)

    @Test
    fun happyPathUploadsAllBytesEndsInVerifyAndTriggers() = runBlocking {
        val bytes = signedImage(3000)
        val image = OtaImageParser.parse(bytes)
        val device = FakeDevice()
        val session = runner(device)
        var maxPercent = 0

        val result = session.run(image, bytes, onProgress = { maxPercent = maxOf(maxPercent, it.percent) })

        assertTrue("expected ReadyToInstall but was $result", result is OtaResult.ReadyToInstall)
        assertEquals(3000L, device.received)
        assertEquals(OtaState.VERIFY, device.state)
        assertTrue("progress should be reported", maxPercent >= 90)
        assertTrue("chunks must respect MTU-3", device.chunkSizes.all { it <= 125 })

        // 同一会话（已订阅 Status）才能收到 PENDING 通知
        assertTrue(session.triggerUpgrade())
        assertEquals(OtaState.PENDING, device.state)
    }

    @Test
    fun resumesFromDeviceConfirmedOffsetOnRestart() = runBlocking {
        val bytes = signedImage(3000)
        val image = OtaImageParser.parse(bytes)
        val device = FakeDevice(resumeOffset = 512)

        val result = runner(device).run(image, bytes)

        val ready = result as? OtaResult.ReadyToInstall ?: error("expected ReadyToInstall, got $result")
        assertEquals(512L, ready.resumedFrom)
        assertEquals(3000L, device.received)
        assertEquals("只需补发剩余字节", 3000L - 512L, device.dataBytesWritten)
    }

    @Test
    fun wrongAuthKeySurfacesDeviceAuthError() = runBlocking {
        val bytes = signedImage(600)
        val image = OtaImageParser.parse(bytes)
        val device = FakeDevice(authKey = ByteArray(16) { 0x11 })

        val result = runner(device).run(image, bytes)

        val failed = result as? OtaResult.Failed ?: error("expected failure, got $result")
        assertEquals(OtaError.AUTH, failed.error)
        assertEquals(0L, device.received)
    }

    @Test
    fun oversizeImageIsRejectedByDeviceBeforeErase() = runBlocking {
        val total = (OtaConstants.SECONDARY_SLOT_CAPACITY + 1000L).toInt()
        val bytes = ByteArray(total)
        val image = OtaImageInfo(total.toLong(), ByteArray(8), ByteArray(32))
        val device = FakeDevice()

        val result = runner(device).run(image, bytes)

        assertEquals(OtaError.TOO_LARGE, (result as OtaResult.Failed).error)
        assertEquals(0L, device.received)
    }

    @Test
    fun usesFallbackMtuChunkSizeWhenNegotiationFallsBack() = runBlocking {
        val bytes = signedImage(2000)
        val image = OtaImageParser.parse(bytes)
        val device = FakeDevice(negotiatedMtu = 65)
        var reportedChunk = -1

        runner(device).run(image, bytes, onProgress = { reportedChunk = it.chunkSize })

        assertEquals(62, reportedChunk)
        assertTrue(device.chunkSizes.all { it <= 62 })
    }

    @Test
    fun abortsWhenNegotiatedMtuTooSmallForStart() = runBlocking {
        val bytes = signedImage(600)
        val image = OtaImageParser.parse(bytes)
        val device = FakeDevice(negotiatedMtu = 40)

        val result = runner(device).run(image, bytes)

        val failed = result as? OtaResult.Failed ?: error("expected failure, got $result")
        assertNull(failed.error)
        assertTrue(failed.detail.contains("MTU"))
    }

    @Test
    fun cancelReturnsDeviceToIdle() = runBlocking {
        val device = FakeDevice()
        assertTrue(runner(device).cancel())
        assertEquals(OtaState.IDLE, device.state)
    }

    // ==================== 异步 START（固件把擦除/SHA 搬出 GATT 回调） ====================

    @Test
    fun waitsForReadyAfterStartWhenDeviceReportsIdleFirst() = runBlocking {
        val bytes = signedImage(3000)
        val image = OtaImageParser.parse(bytes)
        // START 后先上报 3 次 IDLE(err=0)，模拟异步擦除/初始化
        val device = FakeDevice(idleStatusesBeforeReady = 3)

        val result = runner(device).run(image, bytes)

        assertTrue("expected ReadyToInstall but was $result", result is OtaResult.ReadyToInstall)
        assertEquals(3000L, device.received)
        assertEquals(OtaState.VERIFY, device.state)
    }

    @Test
    fun idleWithoutErrorIsNotTreatedAsReady() = runBlocking {
        val bytes = signedImage(600)
        val image = OtaImageParser.parse(bytes)
        // 设备一直停在 IDLE 且 err=0：绝不能据此开始发数据
        val device = FakeDevice(neverBecomesReady = true)

        val result = runner(device, readyTimeoutMs = 150L).run(image, bytes)

        val failed = result as? OtaResult.Failed ?: error("expected failure, got $result")
        assertNull(failed.error)
        assertTrue("失败原因应指向 READY 等待: ${failed.detail}", failed.detail.contains("READY"))
        assertEquals("IDLE 期间不得发数据", 0L, device.dataBytesWritten)
        assertEquals(OtaState.IDLE, device.state)
    }

    // ==================== err=11 OVERRUN：提示后重发 START 续传 ====================

    @Test
    fun overrunRestartsFromStartAndResumesAtConfirmedOffset() = runBlocking {
        val bytes = signedImage(4000)
        val image = OtaImageParser.parse(bytes)
        val device = FakeDevice(overrunAfterChunks = 8)
        val notices = mutableListOf<String>()

        val result = runner(device).run(image, bytes, onNotice = { notices += it })

        val ready = result as? OtaResult.ReadyToInstall ?: error("expected ReadyToInstall, got $result")
        assertEquals("应重发一次 START", 2, device.startCount)
        assertTrue("续传偏移应来自设备已确认偏移", ready.resumedFrom > 0L)
        assertEquals(
            "续传偏移必须是整页边界",
            0L,
            ready.resumedFrom % OtaConstants.RESUME_ALIGNMENT.toLong()
        )
        assertEquals(4000L, device.received)
        assertTrue("应向用户提示 err=11: $notices", notices.any { it.contains("err=11") })
    }

    @Test
    fun repeatedOverrunGivesUpAfterRetryBudget() = runBlocking {
        val bytes = signedImage(4000)
        val image = OtaImageParser.parse(bytes)
        val device = FakeDevice(overrunAfterChunks = 4, overrunRepeats = true)

        val result = runner(device).run(image, bytes)

        val failed = result as? OtaResult.Failed ?: error("expected failure, got $result")
        assertEquals(OtaError.OVERRUN, failed.error)
        assertEquals(1 + OtaConstants.MAX_OVERRUN_RESTARTS, device.startCount)
    }

    // ==================== TRIGGER：设备复位导致的写失败不算失败 ====================

    @Test
    fun triggerCountsAsTriggeredWhenDeviceResetsAndLinkDrops() = runBlocking {
        val bytes = signedImage(1000)
        val image = OtaImageParser.parse(bytes)
        val device = FakeDevice()
        val session = runner(device)

        assertTrue(session.run(image, bytes) is OtaResult.ReadyToInstall)
        // 真机行为：TRIGGER 写与设备复位同时发生 -> 写返回失败(133) 且链路断开
        device.triggerWriteFails = true
        device.dropLinkOnTrigger = true

        assertTrue("断连应视为已触发", session.triggerUpgrade())
    }

    @Test
    fun triggerStillFailsWhenWriteErrorHappensWithLinkUp() = runBlocking {
        val bytes = signedImage(1000)
        val image = OtaImageParser.parse(bytes)
        val device = FakeDevice()
        val session = runner(device)

        assertTrue(session.run(image, bytes) is OtaResult.ReadyToInstall)
        device.triggerWriteFails = true // 链路没断 -> 这是真的写失败

        assertFalse(session.triggerUpgrade())
    }

    @Test
    fun triggerFailsFastWhenAlreadyDisconnected() = runBlocking {
        val device = FakeDevice()
        device.markDisconnected()

        assertFalse(runner(device).triggerUpgrade())
    }
    @Test
    fun separateTriggerSessionUsesExistingVerifiedImageWithoutAnotherStart() = runBlocking {
        val device = FakeDevice()
        val bytes = signedImage(1000)
        assertTrue(runner(device).run(OtaImageParser.parse(bytes), bytes) is OtaResult.ReadyToInstall)
        val starts = device.startCount
        val triggerSession = runner(device)
        assertTrue(triggerSession.prepareTrigger())
        assertTrue(triggerSession.triggerUpgrade())
        assertEquals(starts, device.startCount)
        assertEquals(OtaState.PENDING, device.state)
    }

    @Test
    fun separateTriggerSessionRejectsDeviceWithoutVerifiedImage() = runBlocking {
        val device = FakeDevice()
        val session = runner(device)
        assertTrue(session.prepareTrigger())
        assertFalse(session.triggerUpgrade())
        assertEquals(0, device.startCount)
        assertEquals(OtaError.STATE, device.lastError)
    }
}
