package com.example.pandatemperature.data.device.parser

import com.example.pandatemperature.data.model.DeviceStatus
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Opt-in HISTORY_CHAR command; never falls back to destructive CLEAR_DATA_CHAR. */
object RetentionRequestBuilder {
    const val MAX_RETAINED_RECORDS = 3000
    fun build(capabilityFlags: Int, count: Int): ByteArray? {
        if ((capabilityFlags and DeviceStatus.HISTORY_RETENTION_CAPABILITY) == 0) return null
        require(count == MAX_RETAINED_RECORDS) { "Retention count must be exactly 3000" }
        return ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(count).put(0x04).array()
    }
}
