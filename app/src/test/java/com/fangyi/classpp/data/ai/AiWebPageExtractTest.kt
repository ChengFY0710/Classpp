package com.fangyi.classpp.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 页面抓取信封解析：成功内容、空页面标记、坏 JSON 与空输入 */
class AiWebPageExtractTest {

    @Test
    fun `parses successful capture`() {
        val raw = """
            {"ok":true,"reason":"","content":"URL: https://jw.example.edu.cn\n[TABLE 1]\n节次\t星期一\n第1-2节\t高等数学"}
        """.trimIndent()

        val capture = AiWebPageExtract.parse(raw)

        assertTrue(capture!!.ok)
        assertTrue(capture.content.contains("高等数学"))
        assertEquals("", capture.reason)
    }

    @Test
    fun `keeps empty page marker`() {
        val capture = AiWebPageExtract.parse("""{"ok":false,"reason":"empty","content":""}""")

        assertFalse(capture!!.ok)
        assertEquals("empty", capture.reason)
        assertEquals("", capture.content)
    }

    @Test
    fun `returns null for malformed json and blank input`() {
        assertNull(AiWebPageExtract.parse("not json"))
        assertNull(AiWebPageExtract.parse(""))
        assertNull(AiWebPageExtract.parse(null))
    }

    @Test
    fun `script writes to the agreed result variable and avoids user input`() {
        assertTrue(AiWebPageExtract.script.contains(AiWebPageExtract.RESULT_VAR))
        assertEquals("${AiWebPageExtract.RESULT_VAR} = ''", AiWebPageExtract.resetResult())
        assertEquals("(${AiWebPageExtract.RESULT_VAR} || '')", AiWebPageExtract.readResult())
    }
}
