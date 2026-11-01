package com.fangyi.classpp.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 围栏提取 + 草稿解析：多块取最后、宽容形态、脏输入 */
class AiTodoDraftCodecTest {

    @Test
    fun `extract takes the last fence`() {
        val reply = """
            先给你个初稿：
            ```json
            {"todos":[{"name":"初稿"}]}
            ```
            修正一下：
            ```json
            {"todos":[{"name":"修正版"}]}
            ```
        """.trimIndent()

        assertEquals("""{"todos":[{"name":"修正版"}]}""", AiTodoDraftCodec.extractJsonBlock(reply))
    }

    @Test
    fun `extract without fence returns null`() {
        assertNull(AiTodoDraftCodec.extractJsonBlock("直接回答，没有代码块。"))
        assertNull(AiTodoDraftCodec.extractJsonBlock(""))
    }

    @Test
    fun `extract tolerates missing json tag`() {
        val reply = "```\n{\"todos\":[{\"name\":\"x\"}]}\n```"
        assertEquals("""{"todos":[{"name":"x"}]}""", AiTodoDraftCodec.extractJsonBlock(reply))
    }

    @Test
    fun `parse standard envelope`() {
        val payload = AiTodoDraftCodec.parseTodoDrafts(
            """{"todos":[{"name":"实验报告","dates":["2026-11-05"],"steps":["查资料","写正文"]}]}""",
        )!!
        assertEquals(1, payload.todos.size)
        assertEquals("实验报告", payload.todos[0].name)
        assertEquals(listOf("2026-11-05"), payload.todos[0].dates)
        assertEquals(listOf("查资料", "写正文"), payload.todos[0].steps)
    }

    @Test
    fun `parse empty envelope stays empty`() {
        assertEquals(0, AiTodoDraftCodec.parseTodoDrafts("""{"todos":[]}""")!!.todos.size)
    }

    @Test
    fun `parse bare array`() {
        val payload = AiTodoDraftCodec.parseTodoDrafts("""[{"name":"裸数组"}]""")!!
        assertEquals(listOf("裸数组"), payload.todos.map { it.name })
    }

    @Test
    fun `parse bare single object with name`() {
        val payload = AiTodoDraftCodec.parseTodoDrafts("""{"name":"裸对象","urgency":"High"}""")!!
        assertEquals(1, payload.todos.size)
        assertEquals("裸对象", payload.todos[0].name)
        assertEquals("High", payload.todos[0].urgency)
    }

    @Test
    fun `object without todos or name is rejected`() {
        assertNull(AiTodoDraftCodec.parseTodoDrafts("""{"error":"boom"}"""))
        assertNull(AiTodoDraftCodec.parseTodoDrafts("just text"))
        assertNull(AiTodoDraftCodec.parseTodoDrafts("{ broken"))
    }

    @Test
    fun `unknown fields and missing fields tolerated`() {
        val payload = AiTodoDraftCodec.parseTodoDrafts(
            """{"todos":[{"name":"宽容","futureField":{"x":1}}]}""",
        )!!
        assertEquals("宽容", payload.todos[0].name)
        assertEquals(emptyList<String>(), payload.todos[0].dates)
    }
}
