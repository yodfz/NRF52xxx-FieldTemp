package com.example.pandatemperature.data.device.parser

import org.junit.Assert.*
import org.junit.Test

class RetentionRequestBuilderTest {
    @Test fun noCapabilityProducesNoWritePayload() {
        for (caps in listOf(0, 0x3F, 0x40, 0x7F)) assertNull(RetentionRequestBuilder.build(caps, 3000))
    }
    @Test fun explicitCapabilityProducesFiveByteHistoryCommand() {
        assertArrayEquals(byteArrayOf(0xB8.toByte(), 0x0B, 0, 0, 0x04), RetentionRequestBuilder.build(0x80, 3000))
        assertArrayEquals(byteArrayOf(0xB8.toByte(), 0x0B, 0, 0, 4), RetentionRequestBuilder.build(0xFF, 3000))
        // Five bytes plus discriminator cannot be confused with legacy four-byte timestamp writes.
        assertEquals(5, RetentionRequestBuilder.build(0x80, 3000)!!.size)
    }
    @Test(expected = IllegalArgumentException::class) fun zeroCountIsRejected() {
        RetentionRequestBuilder.build(0x80, 0)
    }
    @Test(expected = IllegalArgumentException::class) fun overLimitIsRejected() {
        RetentionRequestBuilder.build(0x80, 3001)
    }
    @Test(expected = IllegalArgumentException::class) fun smallerCountIsRejected() {
        RetentionRequestBuilder.build(0x80, 1)
    }
}
