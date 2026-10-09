package com.example.pandatemperature.data.ota

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtaAuthKeyTest {

    @Test
    fun parsesSixteenAsciiCharacters() {
        val key = OtaAuthKey.parse(String(SAMPLE_AUTH_KEY, Charsets.US_ASCII))
        assertArrayEquals(SAMPLE_AUTH_KEY, key)
    }

    @Test
    fun parsesThirtyTwoHexCharsToSameBytesAsAscii() {
        val key = OtaAuthKey.parse(SAMPLE_AUTH_KEY_HEX)
        assertArrayEquals(SAMPLE_AUTH_KEY, key)
    }

    @Test
    fun parsesHexWithSeparators() {
        val spaced = SAMPLE_AUTH_KEY_HEX.chunked(2).joinToString(" ")
        assertArrayEquals(SAMPLE_AUTH_KEY, OtaAuthKey.parse(spaced))
    }

    @Test
    fun rejectsWrongLength() {
        assertNull(OtaAuthKey.parse("too-short"))
        assertNull(OtaAuthKey.parse("way-too-long-for-a-16-byte-key"))
    }

    /**
     * 默认密钥由 `ota.properties` 构建期注入，**可能为空**（未配置该文件时）。
     * 因此这里只断言"自洽"：要么为空不预填，要么能原样往返。
     */
    @Test
    fun displayTextRoundTripsDefaultKey() {
        val display = OtaAuthKey.defaultDisplayText()
        if (OtaConstants.DEFAULT_AUTH_KEY.isEmpty()) {
            assertEquals("", display)
        } else {
            assertArrayEquals(
                OtaConstants.DEFAULT_AUTH_KEY,
                OtaAuthKey.parse(display)
            )
        }
    }
}
