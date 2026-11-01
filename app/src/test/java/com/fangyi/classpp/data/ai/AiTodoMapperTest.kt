package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.model.TodoUrgency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 草稿 → Todo 映射：字段换算、成对约束、模糊匹配、脏值标注 */
class AiTodoMapperTest {

    private fun draft(
        name: String = "实验报告",
        dates: List<String> = emptyList(),
        startTime: String? = null,
        endTime: String? = null,
        deadlineDate: String? = null,
        deadlineTime: String? = null,
        urgency: String? = null,
        tags: List<String> = emptyList(),
        group: String? = null,
        steps: List<String> = emptyList(),
    ) = AiTodoDraft(
        name = name,
        dates = dates,
        startTime = startTime,
        endTime = endTime,
        deadlineDate = deadlineDate,
        deadlineTime = deadlineTime,
        urgency = urgency,
        tags = tags,
        group = group,
        steps = steps,
    )

    @Test
    fun `full draft maps all fields`() {
        val mapping = AiTodoMapper.map(
            listOf(
                draft(
                    dates = listOf("2026-11-05"),
                    startTime = "14:30",
                    endTime = "16:10",
                    deadlineDate = "2026-11-05",
                    deadlineTime = "23:59",
                    urgency = "High",
                    tags = listOf("期末"),
                    group = "学习",
                    steps = listOf(" 查资料 ", "", "写正文"),
                ),
            ),
            knownGroups = listOf("学习方法"),
            knownTags = listOf("期末考试"),
        ).single()

        val todo = mapping.todo
        assertTrue(mapping.invalidFields.isEmpty())
        assertEquals("实验报告", todo.name)
        assertEquals(listOf("2026-11-05"), todo.dates.map { it.toString() })
        assertEquals(TodoTimeKind.Period, todo.timeKind)
        assertEquals(14 * 60 + 30, todo.startMinute)
        assertEquals(16 * 60 + 10, todo.endMinute)
        assertEquals("2026-11-05", todo.deadlineDate.toString())
        assertEquals(23 * 60 + 59, todo.deadlineMinute)
        assertEquals(TodoUrgency.High, todo.urgency)
        assertEquals(listOf("期末考试"), todo.tags)
        assertEquals("学习方法", todo.group)
        assertEquals(2, todo.steps.size) // 空步骤剔除，trim 生效
        assertEquals("查资料", todo.steps[0].title)
        assertEquals("ai", todo.source)
    }

    @Test
    fun `blank name is flagged and todo name stays empty`() {
        val blank = AiTodoMapper.map(listOf(draft(name = "   ", dates = listOf("2026-11-06")))).single()
        assertEquals("", blank.todo.name)
        assertEquals(setOf(AiDraftField.Name), blank.invalidFields)
        assertFalse(blank.canSave) // 缺名称会让整批 addTodos 失败，不参与默认勾选

        val named = AiTodoMapper.map(listOf(draft(name = "实验报告"))).single()
        assertTrue(named.canSave)
    }

    @Test
    fun `unparseable date is flagged and good dates kept`() {
        val mapping = AiTodoMapper.map(
            listOf(draft(dates = listOf("2026-11-05", "明天"))),
        ).single()
        assertEquals(listOf("2026-11-05"), mapping.todo.dates.map { it.toString() })
        assertEquals(setOf(AiDraftField.Dates), mapping.invalidFields)
    }

    @Test
    fun `dates dedup and sort`() {
        val mapping = AiTodoMapper.map(
            listOf(draft(dates = listOf("2026-11-06", "2026-11-05", "2026-11-05"))),
        ).single()
        assertEquals(listOf("2026-11-05", "2026-11-06"), mapping.todo.dates.map { it.toString() })
    }

    @Test
    fun `period requires both ends and end after start`() {
        val startOnly = AiTodoMapper.map(listOf(draft(startTime = "14:30"))).single()
        assertEquals(TodoTimeKind.None, startOnly.todo.timeKind)
        assertEquals(setOf(AiDraftField.PeriodTime), startOnly.invalidFields)

        val inverted = AiTodoMapper.map(
            listOf(draft(startTime = "16:10", endTime = "14:30")),
        ).single()
        assertEquals(TodoTimeKind.None, inverted.todo.timeKind)
        assertNull(inverted.todo.startMinute)
        assertEquals(setOf(AiDraftField.PeriodTime), inverted.invalidFields)

        val valid = AiTodoMapper.map(
            listOf(draft(startTime = "8:00", endTime = "9:40")),
        ).single()
        assertEquals(TodoTimeKind.Period, valid.todo.timeKind)
        assertTrue(valid.invalidFields.isEmpty())
    }

    @Test
    fun `deadline must be paired`() {
        val dateOnly = AiTodoMapper.map(listOf(draft(deadlineDate = "2026-11-05"))).single()
        assertNull(dateOnly.todo.deadlineDate)
        assertNull(dateOnly.todo.deadlineMinute)
        assertEquals(setOf(AiDraftField.Deadline), dateOnly.invalidFields)

        val both = AiTodoMapper.map(
            listOf(draft(deadlineDate = "2026-11-05", deadlineTime = "18:00")),
        ).single()
        assertEquals("2026-11-05", both.todo.deadlineDate.toString())
        assertEquals(18 * 60, both.todo.deadlineMinute)
        assertTrue(both.invalidFields.isEmpty())
    }

    @Test
    fun `urgency is case insensitive and invalid falls back to none`() {
        assertEquals(TodoUrgency.Critical, AiTodoMapper.map(listOf(draft(urgency = "critical"))).single().todo.urgency)
        assertEquals(TodoUrgency.None, AiTodoMapper.map(listOf(draft(urgency = "超高"))).single().todo.urgency)
        assertEquals(TodoUrgency.None, AiTodoMapper.map(listOf(draft())).single().todo.urgency)
    }

    @Test
    fun `group fuzzy matches known list and keeps original otherwise`() {
        assertEquals(
            "学习方法",
            AiTodoMapper.map(listOf(draft(group = "学习")), knownGroups = listOf("学习方法")).single().todo.group,
        )
        assertEquals(
            "临时组",
            AiTodoMapper.map(listOf(draft(group = "临时组")), knownGroups = listOf("学习方法")).single().todo.group,
        )
        assertNull(AiTodoMapper.map(listOf(draft(group = " "))).single().todo.group)
    }

    @Test
    fun `parseMinutes covers valid range and rejects garbage`() {
        assertEquals(0, AiTodoMapper.parseMinutes("0:00"))
        assertEquals(8 * 60, AiTodoMapper.parseMinutes("8:00"))
        assertEquals(23 * 60 + 59, AiTodoMapper.parseMinutes("23:59"))
        assertNull(AiTodoMapper.parseMinutes(null))
        assertNull(AiTodoMapper.parseMinutes("24:00"))
        assertNull(AiTodoMapper.parseMinutes("8:60"))
        assertNull(AiTodoMapper.parseMinutes("8点"))
        assertNull(AiTodoMapper.parseMinutes("8:0:0"))
    }
}
