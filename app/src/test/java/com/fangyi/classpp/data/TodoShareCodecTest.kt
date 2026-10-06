package com.fangyi.classpp.data

import com.fangyi.classpp.data.export.TodoShareCodec
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.TodoSharePayload
import com.fangyi.classpp.data.model.TodoTimeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoShareCodecTest {

    private val payloadTodos = listOf(
        testTodo(
            id = "t1",
            name = "高数作业",
            dates = listOf(IsoDate.parse("2026-06-18")),
            timeKind = TodoTimeKind.AllDay,
            steps = listOf(testStep("s1"), testStep("s2", title = "写结论", done = true)),
        ),
        testTodo(id = "t2", name = "实验报告", deadlineDate = IsoDate.parse("2026-06-19"), deadlineMinute = 600),
    )

    @Test
    fun `encode then decode roundtrips with fresh ids`() {
        val json = TodoShareCodec.encode(payloadTodos, exportedAtMillis = 42L)
        val result = TodoShareCodec.decodeForImport(json).let {
            assertTrue("expected Ok, got ${(it as? TodoReadResult.Err)?.error?.message}", it is TodoReadResult.Ok)
            (it as TodoReadResult.Ok).value
        }

        assertEquals(2, result.size)
        assertEquals(payloadTodos.map { it.name }, result.map { it.name })
        // 源 id 原样导出，导入一律重铸
        assertNotEquals(payloadTodos[0].id, result[0].id)
        assertNotEquals(payloadTodos[1].id, result[1].id)
        assertNotEquals(payloadTodos[0].steps[0].id, result[0].steps[0].id)
        // 内容字段零变化
        assertEquals(payloadTodos[0].copy(id = result[0].id, steps = result[0].steps), result[0])
        assertEquals(payloadTodos[1].copy(id = result[1].id), result[1])
    }

    @Test
    fun `pretty json carries kind and version`() {
        val json = TodoShareCodec.encode(payloadTodos, exportedAtMillis = 42L)
        val payload = ScheduleJson.decodeShare<TodoSharePayload>(json)
        assertEquals("classpp.todo", payload.kind)
        assertEquals(1, payload.formatVersion)
        assertEquals(42L, payload.exportedAtMillis)
    }

    @Test
    fun `decode rejects malformed json`() {
        val err = TodoShareCodec.decodeForImport("not json {").let {
            assertTrue("expected Err", it is TodoReadResult.Err)
            (it as TodoReadResult.Err).error
        }
        assertTrue(err is TodoError.ImportFormatInvalid)
    }

    @Test
    fun `decode rejects kind mismatch`() {
        val json = ScheduleJson.encodeShare(
            TodoSharePayload(exportedAtMillis = 1, kind = "classpp.schedule", todos = payloadTodos),
        )
        assertTrue(TodoShareCodec.decodeForImport(json).readErr() is TodoError.ImportKindMismatch)
    }

    @Test
    fun `decode rejects future version`() {
        val json = ScheduleJson.encodeShare(
            TodoSharePayload(exportedAtMillis = 1, formatVersion = 99, todos = payloadTodos),
        )
        assertTrue(TodoShareCodec.decodeForImport(json).readErr() is TodoError.ImportVersionUnsupported)
    }

    @Test
    fun `decode validates todos`() {
        val json = TodoShareCodec.encode(listOf(testTodo(id = "t1", name = "  ")), exportedAtMillis = 1)
        assertTrue(TodoShareCodec.decodeForImport(json).readErr() is TodoError.BlankName)
    }

    private fun TodoReadResult<*>.readErr(): TodoError {
        assertTrue("expected Err, got $this", this is TodoReadResult.Err)
        return (this as TodoReadResult.Err).error
    }
}
