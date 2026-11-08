package com.fangyi.classpp.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SchoolText] 的语言解析口径（纯 JVM）：zh → 中文、en → 英文、en 缺失/其它语言 → 中文。
 * 与资源回退一致（默认资源目录 = 中文），远端声明学校不必为每种语言提供文案。
 */
class SchoolTextTest {

    private val text = SchoolText.Inline(zh = "示例大学", en = "Demo University")

    @Test
    fun `zh resolves chinese`() {
        assertEquals("示例大学", text.resolve("zh"))
    }

    @Test
    fun `en resolves english`() {
        assertEquals("Demo University", text.resolve("en"))
    }

    @Test
    fun `missing en falls back to zh`() {
        assertEquals("示例大学", SchoolText.Inline(zh = "示例大学").resolve("en"))
    }

    @Test
    fun `other languages fall back to zh`() {
        assertEquals("示例大学", text.resolve("ja"))
        assertEquals("示例大学", text.resolve(""))
    }
}
