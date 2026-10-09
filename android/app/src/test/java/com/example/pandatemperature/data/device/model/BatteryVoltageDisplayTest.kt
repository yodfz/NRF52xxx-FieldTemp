package com.example.pandatemperature.data.device.model

import com.example.pandatemperature.data.device.parser.RealtimeDataParserV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 锁定「已显示电压」的三态归约行为（[resolveDisplayedBatteryVoltage]）。
 * 该归约是 MainViewModel.updateBatteryVoltage 的唯一判定来源。
 */
class BatteryVoltageDisplayTest {

    private val parser = RealtimeDataParserV2()

    private fun frame(size: Int, voltageMillivolts: Int = 0): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(2534.toShort())
            .putShort(4567.toShort())
            .putShort(10132.toShort())
            .apply { if (size >= 8) putShort(voltageMillivolts.toShort()) }
            .array()

    /** 用真实解析结果驱动归约，测试不重复实现解析或判定规则。 */
    private fun applyFrame(previous: Float?, packet: ByteArray): Float? {
        val data = parser.parse(packet) ?: error("frame should parse")
        return resolveDisplayedBatteryVoltage(previous, data.batteryVoltage, data.batteryVoltageReported)
    }

    /**
     * 决策 B 的核心回归点：同一连接会话内，先收到合法电压、再收到哨兵帧，
     * 最终显示必须是 null（界面 `--`），而不是残留上一次的旧值。
     */
    @Test
    fun validVoltageThenInvalidSentinelEndsAsNull() {
        var displayed: Float? = applyFrame(null, frame(8, 0x0CE4)) // 3300 mV
        assertEquals(3.3f, displayed!!, 0.001f)

        displayed = applyFrame(displayed, frame(8, 0xFFFF)) // 无效哨兵
        assertNull(displayed)
    }

    @Test
    fun validVoltageThenOutOfRangeEndsAsNull() {
        var displayed: Float? = applyFrame(null, frame(8, 3300))
        assertEquals(3.3f, displayed!!, 0.001f)

        displayed = applyFrame(displayed, frame(8, 0)) // 0 mV，低于量程
        assertNull(displayed)
    }

    /** 决策 B 的另一半：旧 6 字节帧没有电压能力，不得清掉已显示的电压。 */
    @Test
    fun legacySixByteFrameKeepsPreviousVoltage() {
        var displayed: Float? = applyFrame(null, frame(8, 3300))
        assertEquals(3.3f, displayed!!, 0.001f)

        displayed = applyFrame(displayed, frame(6))
        assertEquals(3.3f, displayed!!, 0.001f)
    }

    @Test
    fun validVoltageAfterInvalidRecovers() {
        var displayed: Float? = applyFrame(null, frame(8, 0xFFFF))
        assertNull(displayed)

        displayed = applyFrame(displayed, frame(8, 2900))
        assertEquals(2.9f, displayed!!, 0.001f)
    }

    @Test
    fun invalidReportedVoltageWithNoHistoryStaysNull() {
        assertNull(applyFrame(null, frame(8, 0xFFFF)))
    }
}
