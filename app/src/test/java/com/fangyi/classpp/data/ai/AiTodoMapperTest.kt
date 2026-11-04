package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.TodoValidator
import com.fangyi.classpp.data.model.RepeatEndType
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.RepeatUnit
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.model.TodoUrgency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 草稿 → Todo 映射：字段换算、成对约束、完整参数（全天/重复/倒计时/置顶）、模糊匹配、脏值标注 */
class AiTodoMapperTest {

    private fun draft(
        name: String = "实验报告",
        dates: List<String> = emptyList(),
        allDay: Boolean = false,
        startTime: String? = null,
        endTime: String? = null,
        deadlineDate: String? = null,
        deadlineTime: String? = null,
        deadlineBeforeRepeat: Boolean = false,
        deadlineBeforeDays: Int = 0,
        urgency: String? = null,
        tags: List<String> = emptyList(),
        group: String? = null,
        steps: List<String> = emptyList(),
        countdown: Boolean = false,
        pinned: Boolean = false,
        repeat: AiRepeatDraft? = null,
        location: String = "",
        note: String = "",
    ) = AiTodoDraft(
        name = name,
        dates = dates,
        allDay = allDay,
        startTime = startTime,
        endTime = endTime,
        deadlineDate = deadlineDate,
        deadlineTime = deadlineTime,
        deadlineBeforeRepeat = deadlineBeforeRepeat,
        deadlineBeforeDays = deadlineBeforeDays,
        urgency = urgency,
        tags = tags,
        group = group,
        steps = steps,
        countdown = countdown,
        pinned = pinned,
        repeat = repeat,
        location = location,
        note = note,
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
                    countdown = true,
                    pinned = true,
                    location = " 图书馆 ",
                    note = "带学生证",
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
        assertEquals("图书馆", todo.location)
        assertEquals("带学生证", todo.note)
        assertTrue(todo.countdown)
        assertTrue(todo.pinned)
        assertEquals("ai", todo.source)
    }

    @Test
    fun `blank name is flagged and todo cannot be created`() {
        val blank = AiTodoMapper.map(listOf(draft(name = "   ", dates = listOf("2026-11-06")))).single()
        assertEquals("", blank.todo.name)
        assertEquals(setOf(AiDraftField.Name), blank.invalidFields)
        assertFalse(blank.canSave) // 缺名称会让整批 addTodos 失败，落库前必须滤掉

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
    fun `period requires dates and both ends with end after start`() {
        val startOnly = AiTodoMapper.map(
            listOf(draft(dates = listOf("2026-11-05"), startTime = "14:30")),
        ).single()
        assertEquals(TodoTimeKind.None, startOnly.todo.timeKind)
        assertEquals(setOf(AiDraftField.PeriodTime), startOnly.invalidFields)

        val inverted = AiTodoMapper.map(
            listOf(draft(dates = listOf("2026-11-05"), startTime = "16:10", endTime = "14:30")),
        ).single()
        assertEquals(TodoTimeKind.None, inverted.todo.timeKind)
        assertNull(inverted.todo.startMinute)
        assertEquals(setOf(AiDraftField.PeriodTime), inverted.invalidFields)

        val valid = AiTodoMapper.map(
            listOf(draft(dates = listOf("2026-11-05"), startTime = "8:00", endTime = "9:40")),
        ).single()
        assertEquals(TodoTimeKind.Period, valid.todo.timeKind)
        assertTrue(valid.invalidFields.isEmpty())
    }

    @Test
    fun `time semantics without dates degrade to none`() {
        // TodoValidator 要求有时刻就必须有日期：直接落库必须在这里降级，否则整批零落盘
        val period = AiTodoMapper.map(
            listOf(draft(startTime = "8:00", endTime = "9:40")),
        ).single()
        assertEquals(TodoTimeKind.None, period.todo.timeKind)
        assertNull(period.todo.startMinute)
        assertTrue(AiDraftField.Dates in period.invalidFields)

        val allDay = AiTodoMapper.map(listOf(draft(allDay = true))).single()
        assertEquals(TodoTimeKind.None, allDay.todo.timeKind)
        assertTrue(AiDraftField.Dates in allDay.invalidFields)
    }

    @Test
    fun `all day applies only when no valid period`() {
        val allDay = AiTodoMapper.map(
            listOf(draft(dates = listOf("2026-11-05"), allDay = true)),
        ).single()
        assertEquals(TodoTimeKind.AllDay, allDay.todo.timeKind)
        assertNull(allDay.todo.startMinute)
        assertTrue(allDay.invalidFields.isEmpty())

        // 同时给了合法时段：时段优先
        val period = AiTodoMapper.map(
            listOf(
                draft(
                    dates = listOf("2026-11-05"),
                    allDay = true,
                    startTime = "8:00",
                    endTime = "9:40",
                ),
            ),
        ).single()
        assertEquals(TodoTimeKind.Period, period.todo.timeKind)
        assertEquals(8 * 60, period.todo.startMinute)
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
    fun `deadline before repeat only survives with repeat and deadline`() {
        val withRepeat = AiTodoMapper.map(
            listOf(
                draft(
                    dates = listOf("2026-11-02"),
                    deadlineDate = "2026-11-01",
                    deadlineTime = "20:00",
                    deadlineBeforeRepeat = true,
                    deadlineBeforeDays = 1,
                    repeat = AiRepeatDraft(type = "Weekly"),
                ),
            ),
        ).single()
        assertTrue(withRepeat.todo.deadlineBeforeRepeat)
        assertEquals(1, withRepeat.todo.deadlineBeforeDays)

        val noRepeat = AiTodoMapper.map(
            listOf(
                draft(
                    deadlineDate = "2026-11-01",
                    deadlineTime = "20:00",
                    deadlineBeforeRepeat = true,
                ),
            ),
        ).single()
        assertFalse(noRepeat.todo.deadlineBeforeRepeat)
    }

    @Test
    fun `countdown requires deadline and pinned passes through`() {
        assertTrue(
            AiTodoMapper.map(
                listOf(
                    draft(
                        deadlineDate = "2026-11-05",
                        deadlineTime = "18:00",
                        countdown = true,
                        pinned = true,
                    ),
                ),
            ).single().todo.countdown,
        )
        assertFalse(AiTodoMapper.map(listOf(draft(countdown = true))).single().todo.countdown)
        assertTrue(AiTodoMapper.map(listOf(draft(pinned = true))).single().todo.pinned)
    }

    @Test
    fun `repeat maps presets ends and check in`() {
        val preset = AiTodoMapper.map(
            listOf(
                draft(
                    dates = listOf("2026-11-02"),
                    repeat = AiRepeatDraft(
                        type = "weekly",
                        endType = "count",
                        endCount = 12,
                        checkIn = true,
                    ),
                ),
            ),
        ).single().todo.repeat
        assertEquals(RepeatType.Weekly, preset.type)
        assertEquals(RepeatEndType.Count, preset.endType)
        assertEquals(12, preset.endCount)
        assertTrue(preset.checkIn)

        val byDate = AiTodoMapper.map(
            listOf(
                draft(
                    dates = listOf("2026-11-02"),
                    repeat = AiRepeatDraft(type = "Daily", endType = "Date", endDate = "2026-12-01"),
                ),
            ),
        ).single().todo.repeat
        assertEquals(RepeatEndType.Date, byDate.endType)
        assertEquals("2026-12-01", byDate.endDate.toString())
    }

    @Test
    fun `custom repeat clamps interval and filters marks`() {
        val rule = AiTodoMapper.map(
            listOf(
                draft(
                    dates = listOf("2026-11-02"),
                    repeat = AiRepeatDraft(
                        type = "Custom",
                        unit = "week",
                        interval = 500,
                        marks = listOf(1, 8, -1),
                    ),
                ),
            ),
        ).single().todo.repeat
        assertEquals(RepeatType.Custom, rule.type)
        assertEquals(RepeatUnit.Week, rule.unit)
        assertEquals(99, rule.interval)
        assertEquals(setOf(1), rule.marks)
    }

    @Test
    fun `repeat without dates is dropped`() {
        val mapping = AiTodoMapper.map(
            listOf(draft(repeat = AiRepeatDraft(type = "Daily"))),
        ).single()
        assertEquals(RepeatType.None, mapping.todo.repeat.type)
        assertTrue(AiDraftField.Dates in mapping.invalidFields)
    }

    @Test
    fun `invalid repeat type falls back to none`() {
        val mapping = AiTodoMapper.map(
            listOf(draft(dates = listOf("2026-11-02"), repeat = AiRepeatDraft(type = "Sometimes"))),
        ).single()
        assertEquals(RepeatType.None, mapping.todo.repeat.type)
        assertTrue(AiDraftField.Repeat in mapping.invalidFields)
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
    fun `tag fuzzy matches known list`() {
        assertEquals(
            listOf("期末考试"),
            AiTodoMapper.map(listOf(draft(tags = listOf("期末"))), knownTags = listOf("期末考试"))
                .single().todo.tags,
        )
        assertEquals(
            listOf("新标签"),
            AiTodoMapper.map(listOf(draft(tags = listOf(" 新标签 "))), knownTags = listOf("期末考试"))
                .single().todo.tags,
        )
    }

    @Test
    fun `saveable mappings always pass the todo validator`() {
        // 直接落库没有「改完再存」的机会：映射输出必须能整批通过校验，否则 addTodos 零落盘
        val drafts = listOf(
            draft(dates = listOf("2026-11-05"), startTime = "8:00", endTime = "9:40"),
            draft(dates = listOf("2026-11-05"), allDay = true),
            draft(dates = listOf("2026-11-05"), deadlineDate = "2026-11-05", deadlineTime = "18:00"),
            draft(
                dates = listOf("2026-11-02"),
                repeat = AiRepeatDraft(type = "Custom", unit = "Week", interval = 2, marks = listOf(1, 3)),
            ),
            // 脏值行：无日期的时段 / 只有截止日期 / 不存在的重复类型
            draft(startTime = "8:00", endTime = "9:40"),
            draft(deadlineDate = "2026-11-05"),
            draft(dates = listOf("2026-11-02"), repeat = AiRepeatDraft(type = "Sometimes")),
        )
        val saveable = AiTodoMapper.map(drafts).filter { it.canSave }
        assertEquals(drafts.size, saveable.size)
        saveable.forEach { mapping ->
            assertTrue(
                "${mapping.todo.name}: ${TodoValidator.validate(mapping.todo)}",
                TodoValidator.validate(mapping.todo).isEmpty(),
            )
        }
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
