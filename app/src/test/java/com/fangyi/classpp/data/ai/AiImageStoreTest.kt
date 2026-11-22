package com.fangyi.classpp.data.ai

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片附件库的纯 JVM 部分：文件名 → base64 附件的编码契约与删除/孤儿清理
 * （导入管线涉及位图解码，属 Android 侧，不在单测覆盖）。
 */
class AiImageStoreTest {

    /**
     * 生产默认参数用 `android.util.Base64`（minSdk 24 不支持 `java.util.Base64`），
     * JVM 上不可用——测试必须显式注入 JDK 编码器。
     */
    private fun newStore(): AiImageStore {
        val dir = Files.createTempDirectory("ai_images").toFile()
        return AiImageStore(
            dir,
            base64Encode = { java.util.Base64.getEncoder().encodeToString(it) },
        )
    }

    @Test
    fun `encode reads file bytes into jpeg turn image`() {
        val store = newStore()
        val file = store.file("a.jpg")
        file.parentFile.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))

        val image = store.encode("a.jpg")

        assertEquals("image/jpeg", image!!.mediaType)
        assertEquals("AQID", image.base64)
    }

    @Test
    fun `encode base64 round trips to original bytes`() {
        val store = newStore()
        val payload = byteArrayOf(0, 1, 2, 3, 127, -1, -128, 42)
        val file = store.file("roundtrip.jpg")
        file.parentFile.mkdirs()
        file.writeBytes(payload)

        val image = store.encode("roundtrip.jpg")

        assertTrue(
            "base64 应可还原为原字节",
            java.util.Base64.getDecoder().decode(image!!.base64).contentEquals(payload),
        )
    }

    @Test
    fun `encode skips missing or hostile names`() {
        val store = newStore()
        assertNull(store.encode("missing.jpg"))
        // 路径穿越防护：只接受纯文件名
        assertNull(store.encode("../escape.jpg"))
        assertNull(store.encode("sub/dir.jpg"))
        assertNull(store.encode(""))
    }

    @Test
    fun `delete removes file and ignores missing silently`() {
        val store = newStore()
        val file = store.file("a.jpg")
        file.parentFile.mkdirs()
        file.writeBytes(byteArrayOf(1))

        store.delete("a.jpg")

        assertFalse(file.exists())
        // 不存在的文件静默忽略（不抛）
        store.delete("missing.jpg")
    }

    @Test
    fun `deleteExcept keeps referenced files only`() {
        val store = newStore()
        val keep = store.file("keep.jpg")
        val drop = store.file("drop.jpg")
        keep.parentFile.mkdirs()
        keep.writeBytes(byteArrayOf(1))
        drop.writeBytes(byteArrayOf(2))

        store.deleteExcept(setOf("keep.jpg"))

        assertTrue(keep.exists())
        assertFalse(drop.exists())
    }

    @Test
    fun `deleteExcept respects grace period for newly imported files`() {
        val store = newStore()
        val file = store.file("fresh.jpg")
        file.parentFile.mkdirs()
        file.writeBytes(byteArrayOf(1))

        // 宽限期内（文件太新）不删：豁免「已导入未发送」的待发送图片
        store.deleteExcept(emptySet(), minAgeMillis = 60_000)
        assertTrue(file.exists())

        // 宽限期过后（模拟时钟：文件已老）照常删
        store.deleteExcept(emptySet(), minAgeMillis = 0)
        assertFalse(file.exists())
    }
}
