package com.fangyi.classpp.data

import com.fangyi.classpp.data.export.TodoShareCodec
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatEndType
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.TodoAttachment
import com.fangyi.classpp.data.model.TodoSharePayload
import com.fangyi.classpp.data.model.TodoTimeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    fun `import always lands as live todos`() {
        // 载荷里的软删/归档标记（导出自其它版本 / 手工编辑）一律清空：分享导入永远是活跃待办
        val json = TodoShareCodec.encode(
            listOf(
                testTodo(id = "t1", name = "已删除", deletedAtMillis = 123L)
                    .copy(archivedAtMillis = 456L),
            ),
            exportedAtMillis = 1L,
        )
        val result = TodoShareCodec.decodeForImport(json).let {
            assertTrue("expected Ok, got ${(it as? TodoReadResult.Err)?.error?.message}", it is TodoReadResult.Ok)
            (it as TodoReadResult.Ok).value
        }
        assertNull(result.single().deletedAtMillis)
        assertNull(result.single().archivedAtMillis)
    }

    @Test
    fun `import clears course center source badge`() {
        // 分享导入的是普通待办：来源与课程名不能跟着走，否则另一端会显示「课程中心」徽标
        val json = TodoShareCodec.encode(
            listOf(
                testTodo(id = "xmu-925512", name = "同步待办")
                    .copy(source = "xmu", sourceDetail = "高等数学"),
            ),
            exportedAtMillis = 1L,
        )
        val result = TodoShareCodec.decodeForImport(json).let {
            assertTrue("expected Ok, got ${(it as? TodoReadResult.Err)?.error?.message}", it is TodoReadResult.Ok)
            (it as TodoReadResult.Ok).value
        }
        assertNull(result.single().source)
        assertNull(result.single().sourceDetail)
    }

    @Test
    fun `pretty json carries kind and version`() {        val json = TodoShareCodec.encode(payloadTodos, exportedAtMillis = 42L)
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

    @Test
    fun `decode rejects repeats that cannot expand`() {
        // 空窗口（日期档早于锚日）/ 无锚日期的重复：导入后永远展不出卡（不可见死数据），拒绝
        val emptyWindow = TodoShareCodec.encode(
            listOf(
                testTodo(
                    id = "t1",
                    dates = listOf(IsoDate.parse("2026-06-18")),
                    repeat = RepeatRule(
                        type = RepeatType.Weekly,
                        endType = RepeatEndType.Date,
                        endDate = IsoDate.parse("2026-06-10"),
                    ),
                ),
            ),
            exportedAtMillis = 1,
        )
        assertTrue(TodoShareCodec.decodeForImport(emptyWindow).readErr() is TodoError.RepeatInvalid)

        val missingDates = TodoShareCodec.encode(
            listOf(testTodo(id = "t2", repeat = RepeatRule(type = RepeatType.Weekly))),
            exportedAtMillis = 1,
        )
        assertTrue(TodoShareCodec.decodeForImport(missingDates).readErr() is TodoError.RepeatInvalid)
    }

    @Test
    fun `decode rejects multi date repeats`() {
        // 多日期 + 重复（系列单锚语义，多余日期展不出来）：导入整体拒绝
        val json = TodoShareCodec.encode(
            listOf(
                testTodo(
                    id = "t1",
                    dates = listOf(IsoDate.parse("2026-06-18"), IsoDate.parse("2026-06-20")),
                    repeat = RepeatRule(type = RepeatType.Weekly),
                ),
            ),
            exportedAtMillis = 1,
        )
        assertTrue(TodoShareCodec.decodeForImport(json).readErr() is TodoError.RepeatInvalid)
    }

    @Test
    fun `attachments are stripped from share payload`() {
        val todo = testTodo(
            id = "t1",
            attachments = listOf(
                TodoAttachment(
                    id = "a1",
                    name = "报告.pdf",
                    fileName = "报告.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = 1,
                ),
            ),
        )
        val json = TodoShareCodec.encode(listOf(todo), exportedAtMillis = 1)
        assertFalse(json.contains("报告.pdf"))

        val imported = TodoShareCodec.decodeForImport(json).let {
            assertTrue("expected Ok, got $it", it is TodoReadResult.Ok)
            (it as TodoReadResult.Ok).value.single()
        }
        assertTrue(imported.attachments.isEmpty())
    }

    private fun TodoReadResult<*>.readErr(): TodoError {
        assertTrue("expected Err, got $this", this is TodoReadResult.Err)
        return (this as TodoReadResult.Err).error
    }
}
