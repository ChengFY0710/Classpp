package com.fangyi.classpp.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** APK 下载后的完整性校验：大小与 SHA-256 必须同时匹配，坏输入一律拒绝 */
class ApkVerifierTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val content = "hello".toByteArray(Charsets.UTF_8)
    private val contentSha256 =
        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

    @Test
    fun `matching size and sha256 passes`() {
        val apk = folder.newFile("app.apk").apply { writeBytes(content) }

        assertTrue(ApkVerifier.verify(apk, content.size.toLong(), contentSha256))
    }

    @Test
    fun `size mismatch is rejected`() {
        val apk = folder.newFile("app.apk").apply { writeBytes(content) }

        assertFalse(ApkVerifier.verify(apk, content.size + 1L, contentSha256))
    }

    @Test
    fun `sha256 mismatch is rejected`() {
        val apk = folder.newFile("app.apk").apply { writeBytes(content) }

        assertFalse(ApkVerifier.verify(apk, content.size.toLong(), "0".repeat(64)))
    }

    @Test
    fun `malformed expected sha256 is rejected`() {
        val apk = folder.newFile("app.apk").apply { writeBytes(content) }

        assertFalse(ApkVerifier.verify(apk, content.size.toLong(), "not-a-hash"))
    }
}
