package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.TodoValidator
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatEndType
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.RepeatUnit
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoStep
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.model.TodoUrgency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 修改补丁 → Todo：部分更新语义、清空/级联降级、严格校验、状态字段保留 */
class AiTodoPatchMapperTest {

    private val date = IsoDate.of(2026, 11, 5)

    private fun todo(
        name: String = "实验报告",
        dates: List<IsoDate> = listOf(date),
        timeKind: TodoTimeKind = TodoTimeKind.None,
        startMinute: Int? = null,
        endMinute: Int? = null,
        deadlineDate: IsoDate? = IsoDate.of(2026, 11, 6),
        deadlineMinute: Int? = 18 * 60,
        urgency: TodoUrgency = TodoUrgency.Medium,
        tags: List<String> = listOf("期末"),
        group: String? = "学习",
        note: String = "旧备注",
        steps: List<TodoStep> = listOf(TodoStep("s1", "查资料"), TodoStep("s2", "写正文")),
        location: String = "图书馆",
        countdown: Boolean = true,
        pinned: Boolean = false,
        repeat: RepeatRule = RepeatRule(),
        completedOccurrences: List<IsoDate> = emptyList(),
    ) = Todo(
        id = "t1",
        name = name,
        dates = dates,
        repeat = repeat,
        completedOccurrences = completedOccurrences,
        timeKind = timeKind,
        startMinute = startMinute,
        endMinute = endMinute,
        deadlineDate = deadlineDate,
        deadlineMinute = deadlineMinute,
        deadlineBeforeRepeat = false,
        deadlineBeforeDays = 0,
        location = location,
        tags = tags,
        urgency = urgency,
        note = note,
        steps = steps,
        countdown = countdown,
        pinned = pinned,
        group = group,
        createdAtMillis = 123L,
        source = "ai",
    )

    private fun apply(
        existing: Todo,
        patch: AiTodoPatch,
        groups: List<String> = listOf("学习", "生活"),
        tags: List<String> = listOf("期末", "实验"),
    ): AiTodoPatchMapping = AiTodoPatchMapper.apply(existing, patch, groups, tags)

    @Test
    fun `empty patch is unchanged`() {
        val existing = todo()
        val mapping = apply(existing, AiTodoPatch())
        assertEquals(existing, mapping.todo)
        assertFalse(mapping.changed)
    }

    @Test
    fun `single field patch keeps every other field and state`() {
        val existing = todo(
            repeat = RepeatRule(type = RepeatType.Weekly, endType = RepeatEndType.Count, endCount = 4),
            completedOccurrences = listOf(date),
        )
        val mapping = apply(existing, AiTodoPatch(name = "  新名字  "))
        val next = mapping.todo!!
        assertTrue(mapping.changed)
        assertEquals("新名字", next.name)
        assertEquals(existing.copy(name = "新名字"), next)
        // 状态字段原样保留
        assertEquals("t1", next.id)
        assertEquals(123L, next.createdAtMillis)
        assertEquals("ai", next.source)
        assertEquals(listOf(date), next.completedOccurrences)
        assertTrue(TodoValidator.validate(next).isEmpty())
    }

    @Test
    fun `blank name is invalid`() {
        assertNull(apply(todo(), AiTodoPatch(name = "   ")).todo)
    }

    @Test
    fun `dates are replaced and invalid date fails`() {
        val next = apply(
            todo(),
            AiTodoPatch(dates = listOf("2026-11-10", "2026-11-08", "2026-11-10")),
        ).todo!!
        assertEquals(listOf(IsoDate.of(2026, 11, 8), IsoDate.of(2026, 11, 10)), next.dates)
        assertNull(apply(todo(), AiTodoPatch(dates = listOf("not-a-date"))).todo)
    }

    @Test
    fun `clearing dates degrades time and cancels repeat`() {
        val existing = todo(
            timeKind = TodoTimeKind.Period,
            startMinute = 9 * 60,
            endMinute = 10 * 60,
            repeat = RepeatRule(type = RepeatType.Weekly),
        )
        val next = apply(existing, AiTodoPatch(dates = emptyList())).todo!!
        assertTrue(next.dates.isEmpty())
        assertEquals(TodoTimeKind.None, next.timeKind)
        assertNull(next.startMinute)
        assertNull(next.endMinute)
        assertEquals(RepeatType.None, next.repeat.type)
        assertTrue(TodoValidator.validate(next).isEmpty())
    }

    @Test
    fun `period times require pair and valid order`() {
        val next = apply(
            todo(),
            AiTodoPatch(startTime = "14:30", endTime = "16:10"),
        ).todo!!
        assertEquals(TodoTimeKind.Period, next.timeKind)
        assertEquals(14 * 60 + 30, next.startMinute)
        assertEquals(16 * 60 + 10, next.endMinute)

        assertNull(apply(todo(), AiTodoPatch(startTime = "14:30")).todo)
        assertNull(apply(todo(), AiTodoPatch(startTime = "16:10", endTime = "14:30")).todo)
    }

    @Test
    fun `all day and cancel time`() {
        val allDay = apply(todo(), AiTodoPatch(allDay = true)).todo!!
        assertEquals(TodoTimeKind.AllDay, allDay.timeKind)
        assertNull(allDay.startMinute)

        val existing = todo(timeKind = TodoTimeKind.Period, startMinute = 9 * 60, endMinute = 10 * 60)
        val cancelled = apply(existing, AiTodoPatch(allDay = false)).todo!!
        assertEquals(TodoTimeKind.None, cancelled.timeKind)
        assertNull(cancelled.startMinute)
        assertNull(cancelled.endMinute)
    }

    @Test
    fun `all day together with times is contradictory`() {
        assertNull(apply(todo(), AiTodoPatch(allDay = true, startTime = "09:00", endTime = "10:00")).todo)
    }

    @Test
    fun `deadline set and clear`() {
        val next = apply(
            todo(),
            AiTodoPatch(deadlineDate = "2026-11-20", deadlineTime = "23:59"),
        ).todo!!
        assertEquals(IsoDate.of(2026, 11, 20), next.deadlineDate)
        assertEquals(23 * 60 + 59, next.deadlineMinute)

        val cleared = apply(todo(), AiTodoPatch(deadlineDate = "", deadlineTime = "")).todo!!
        assertNull(cleared.deadlineDate)
        assertNull(cleared.deadlineMinute)
        assertFalse(cleared.countdown)
    }

    @Test
    fun `deadline requires pair`() {
        assertNull(apply(todo(), AiTodoPatch(deadlineDate = "2026-11-20")).todo)
        assertNull(apply(todo(), AiTodoPatch(deadlineTime = "23:59")).todo)
    }

    @Test
    fun `tags group note location and steps are replaceable`() {
        val next = apply(
            todo(),
            AiTodoPatch(
                tags = listOf("期末考"),
                group = "",
                note = "",
                location = "",
                steps = listOf("查资料", "排版", "写正文"),
            ),
        ).todo!!
        // 包含匹配命中清单项「期末」
        assertEquals(listOf("期末"), next.tags)
        assertNull(next.group)
        assertEquals("", next.note)
        assertEquals("", next.location)
        // 同名旧步骤保留 id；新标题生成新 id
        assertEquals("s1", next.steps[0].id)
        assertEquals("写正文", next.steps[2].title)
        assertEquals("s2", next.steps[2].id)
        assertEquals("排版", next.steps[1].title)
        assertNotEquals("s1", next.steps[1].id)
    }

    @Test
    fun `empty steps clear the list`() {
        val next = apply(todo(), AiTodoPatch(steps = emptyList())).todo!!
        assertTrue(next.steps.isEmpty())
    }

    @Test
    fun `urgency maps names and None clears`() {
        assertEquals(TodoUrgency.High, apply(todo(), AiTodoPatch(urgency = "high")).todo!!.urgency)
        assertEquals(TodoUrgency.None, apply(todo(), AiTodoPatch(urgency = "None")).todo!!.urgency)
        assertNull(apply(todo(), AiTodoPatch(urgency = "urgent")).todo)
    }

    @Test
    fun `repeat can be updated and cleared`() {
        val updated = apply(
            todo(),
            AiTodoPatch(
                repeat = AiRepeatDraft(
                    type = "Custom",
                    unit = "Week",
                    interval = 2,
                    marks = listOf(1, 3),
                    endType = "Count",
                    endCount = 6,
                ),
            ),
        ).todo!!
        assertEquals(RepeatType.Custom, updated.repeat.type)
        assertEquals(RepeatUnit.Week, updated.repeat.unit)
        assertEquals(2, updated.repeat.interval)
        assertEquals(setOf(1, 3), updated.repeat.marks)
        assertEquals(6, updated.repeat.endCount)

        val cleared = apply(todo(), AiTodoPatch(repeat = AiRepeatDraft(type = "None"))).todo!!
        assertEquals(RepeatType.None, cleared.repeat.type)
    }

    @Test
    fun `repeat without dates is invalid when explicitly requested`() {
        val existing = todo(dates = emptyList())
        assertNull(apply(existing, AiTodoPatch(repeat = AiRepeatDraft(type = "Weekly"))).todo)
    }

    @Test
    fun `invalid repeat fields fail the whole patch`() {
        assertNull(apply(todo(), AiTodoPatch(repeat = AiRepeatDraft(type = "EveryOtherDay"))).todo)
        assertNull(
            apply(
                todo(),
                AiTodoPatch(
                    repeat = AiRepeatDraft(type = "Custom", unit = "Week", marks = listOf(1), endType = "Date"),
                ),
            ).todo,
        )
    }

    @Test
    fun `deadline before repeat is normalized against deadline and repeat`() {
        val existing = todo(repeat = RepeatRule(type = RepeatType.Daily))
        val next = apply(
            existing,
            AiTodoPatch(deadlineBeforeRepeat = true, deadlineBeforeDays = 2),
        ).todo!!
        assertTrue(next.deadlineBeforeRepeat)
        assertEquals(2, next.deadlineBeforeDays)

        val noRepeat = apply(
            todo(),
            AiTodoPatch(deadlineBeforeRepeat = true),
        ).todo!!
        assertFalse(noRepeat.deadlineBeforeRepeat)
    }

    @Test
    fun `pinned and countdown flags apply with deadline constraints`() {
        val next = apply(todo(), AiTodoPatch(pinned = true, countdown = false)).todo!!
        assertTrue(next.pinned)
        assertFalse(next.countdown)

        // 没有截止时倒计时不成立（与仓库归一化同口径）
        val noDeadline = apply(
            todo(deadlineDate = null, deadlineMinute = null, countdown = false),
            AiTodoPatch(countdown = true),
        ).todo!!
        assertNull(noDeadline.deadlineDate)
        assertFalse(noDeadline.countdown)
    }
}
