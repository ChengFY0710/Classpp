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
    fun `strip removes the todo fence and keeps prose`() {
        val reply = "已创建：\n```json\n{\"todos\":[{\"name\":\"x\"}]}\n```\n明天见。"
        assertEquals("已创建：\n\n明天见。", AiTodoDraftCodec.stripJsonBlock(reply))
        assertEquals("直接回答。", AiTodoDraftCodec.stripJsonBlock(" 直接回答。 "))
    }

    @Test
    fun `parse complete todo parameters`() {
        val payload = AiTodoDraftCodec.parseTodoDrafts(
            """{"todos":[{"name":"晨跑","dates":["2026-11-02"],"allDay":true,"pinned":true,""" +
                """"countdown":true,"deadlineDate":"2026-11-10","deadlineTime":"08:00",""" +
                """"deadlineBeforeRepeat":true,"deadlineBeforeDays":1,""" +
                """"repeat":{"type":"Custom","unit":"Week","interval":2,"marks":[1,3],""" +
                """"endType":"Count","endCount":10,"merge":false,"checkIn":true}}]}""",
        )!!
        val draft = payload.todos.single()
        assertEquals("晨跑", draft.name)
        assertEquals(listOf("2026-11-02"), draft.dates)
        assertEquals(true, draft.allDay)
        assertEquals(true, draft.pinned)
        assertEquals(true, draft.countdown)
        assertEquals(true, draft.deadlineBeforeRepeat)
        assertEquals(1, draft.deadlineBeforeDays)
        assertEquals("Custom", draft.repeat!!.type)
        assertEquals("Week", draft.repeat!!.unit)
        assertEquals(2, draft.repeat!!.interval)
        assertEquals(listOf(1, 3), draft.repeat!!.marks)
        assertEquals("Count", draft.repeat!!.endType)
        assertEquals(10, draft.repeat!!.endCount)
        assertEquals(false, draft.repeat!!.merge)
        assertEquals(true, draft.repeat!!.checkIn)
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
    fun `empty envelope has no actions and is rejected`() {
        assertNull(AiTodoDraftCodec.parseTodoDrafts("""{"todos":[]}"""))
        assertNull(AiTodoDraftCodec.parseTodoDrafts("""{"updates":[],"deletes":[]}"""))
        assertNull(AiTodoDraftCodec.parseTodoDrafts("""{"completes":[],"reopens":[]}"""))
    }

    @Test
    fun `parse mixed envelope with updates and deletes`() {
        val payload = AiTodoDraftCodec.parseTodoDrafts(
            """{"todos":[{"name":"新建"}],""" +
                """"updates":[{"id":"t1","patch":{"name":"改名","deadlineDate":"","deadlineTime":""}}],""" +
                """"deletes":[{"id":"t2"},{"id":"t3"}]}""",
        )!!
        assertEquals(listOf("新建"), payload.todos.map { it.name })
        assertEquals(1, payload.updates.size)
        assertEquals("t1", payload.updates[0].id)
        assertEquals("改名", payload.updates[0].patch.name)
        assertEquals("", payload.updates[0].patch.deadlineDate)
        assertEquals(listOf("t2", "t3"), payload.deletes.map { it.id })
        assertEquals(true, payload.hasActions)
    }

    @Test
    fun `parse updates only`() {
        val payload = AiTodoDraftCodec.parseTodoDrafts(
            """{"updates":[{"id":"t1","patch":{"pinned":true}}]}""",
        )!!
        assertEquals(0, payload.todos.size)
        assertEquals("t1", payload.updates.single().id)
        assertEquals(true, payload.updates.single().patch.pinned)
    }

    @Test
    fun `parse deletes only`() {
        val payload = AiTodoDraftCodec.parseTodoDrafts("""{"deletes":[{"id":"t9"}]}""")!!
        assertEquals(listOf("t9"), payload.deletes.map { it.id })
    }

    @Test
    fun `parse completes and reopens`() {
        val payload = AiTodoDraftCodec.parseTodoDrafts(
            """{"completes":[{"id":"t1","date":"2026-11-09"}],"reopens":[{"id":"t2"}]}""",
        )!!
        assertEquals("t1", payload.completes.single().id)
        assertEquals("2026-11-09", payload.completes.single().date)
        assertEquals("t2", payload.reopens.single().id)
        assertNull(payload.reopens.single().date)
        assertEquals(true, payload.hasActions)
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
