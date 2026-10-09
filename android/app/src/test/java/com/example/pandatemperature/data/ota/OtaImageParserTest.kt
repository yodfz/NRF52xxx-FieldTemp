package com.example.pandatemperature.data.ota

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class OtaImageParserTest {

    private fun signedImage(size: Int = 600): ByteArray {
        val bytes = ByteArray(size)
        // MCUboot magic，小端字节 3d b8 f3 96
        bytes[0] = 0x3D
        bytes[1] = 0xB8.toByte()
        bytes[2] = 0xF3.toByte()
        bytes[3] = 0x96.toByte()
        // ih_ver（偏移 0x14 = 20）：major=1 minor=0 rev=6 build=0
        bytes[20] = 1
        bytes[21] = 0
        bytes[22] = 6
        bytes[23] = 0
        return bytes
    }

    @Test
    fun parsesSignedImageMetadata() {
        val bytes = signedImage()
        val info = OtaImageParser.parse(bytes)

        assertEquals(bytes.size.toLong(), info.total)
        assertArrayEquals(byteArrayOf(1, 0, 6, 0, 0, 0, 0, 0), info.ihVer)
        assertEquals("1.0.6+0", info.versionLabel)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), info.sha256)
        assertTrue(!info.exceedsSlotCapacity)
    }

    @Test
    fun rejectsRawBinWithoutMcubootMagic() {
        val bytes = ByteArray(600) // magic 为 0
        try {
            OtaImageParser.parse(bytes)
            error("应拒绝缺少 MCUboot magic 的文件")
        } catch (e: OtaImageError.BadMagic) {
            assertTrue(e.message!!.contains("zephyr.signed.bin"))
        }
    }

    @Test(expected = OtaImageError.TooShort::class)
    fun rejectsFileShorterThanHeader() {
        // 只有 magic、没有 ih_ver 的短文件（小于 20 + 8 字节）
        val short = ByteArray(24).also {
            it[0] = 0x3D
            it[1] = 0xB8.toByte()
            it[2] = 0xF3.toByte()
            it[3] = 0x96.toByte()
        }
        OtaImageParser.parse(short)
    }

    @Test(expected = OtaImageError.TooLarge::class)
    fun rejectsImageExceedingSecondarySlot() {
        val tooLarge = signedImage(size = (OtaConstants.SECONDARY_SLOT_CAPACITY + 16).toInt())
        OtaImageParser.parse(tooLarge)
    }
}
