package com.fangyi.classpp.data.xmu

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 头像响应的两个安全闸：体积上限与图片校验——决定「登录页 HTML 不会当头像落盘」，
 * 也是 [XmuHttpAvatarLoader] 里唯一值得单测的纯逻辑（其余是网络 I/O）。
 */
class XmuAvatarLoaderTest {

    private fun jpegBytes(size: Int = 8): ByteArray =
        ByteArray(size).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }

    @Test
    fun `读到上限以内原样返回`() {
        val bytes = ByteArray(10) { it.toByte() }
        assertArrayEquals(bytes, readAvatarBytes(ByteArrayInputStream(bytes), maxBytes = 10))
    }

    @Test
    fun `超过上限返回 null 且不整段读完`() {
        val bytes = ByteArray(100) { it.toByte() }
        assertNull(readAvatarBytes(ByteArrayInputStream(bytes), maxBytes = 10))
    }

    @Test
    fun `空流返回空数组`() {
        assertArrayEquals(ByteArray(0), readAvatarBytes(ByteArrayInputStream(ByteArray(0)), 10))
    }

    @Test
    fun `Content-Type 声明图片即通过`() {
        assertTrue(looksLikeAvatarImage("image/png", ByteArray(0)))
        assertTrue(looksLikeAvatarImage("IMAGE/JPEG; charset=utf-8", ByteArray(0)))
    }

    @Test
    fun `无 Content-Type 时按魔数识别常见图片`() {
        assertTrue(looksLikeAvatarImage(null, jpegBytes()))
        assertTrue(looksLikeAvatarImage(null, byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0)))
        assertTrue(looksLikeAvatarImage(null, "GIF89a".toByteArray()))
        assertTrue(looksLikeAvatarImage(null, "BM1234".toByteArray()))
        assertTrue(
            looksLikeAvatarImage(
                null,
                "RIFF0000WEBPVP8 ".toByteArray(),
            ),
        )
    }

    @Test
    fun `登录页 HTML 与短数据一律拒绝`() {
        assertFalse(looksLikeAvatarImage("text/html; charset=utf-8", "<html>login</html>".toByteArray()))
        assertFalse(looksLikeAvatarImage("application/json", """{"error":"expired"}""".toByteArray()))
        assertFalse(looksLikeAvatarImage(null, byteArrayOf(1, 2)))
        assertFalse(looksLikeAvatarImage(null, ByteArray(0)))
    }
}
