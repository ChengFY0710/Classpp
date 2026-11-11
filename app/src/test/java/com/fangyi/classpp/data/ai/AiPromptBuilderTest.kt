package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.model.CourseColor
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.WeekPattern
import com.fangyi.classpp.data.model.WeekSegment
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoStep
import com.fangyi.classpp.data.model.TodoUrgency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 上下文注入：课表/作息/待办行的序列化与过滤、契约块的存在性 */
class AiPromptBuilderTest {

    private val now = IsoDate.of(2026, 11, 1) // 周日

    private fun schedule(vararg courses: CourseEntry) = Schedule(
        id = "s1",
        name = "2026 秋",
        termStart = IsoDate.of(2026, 9, 7),
        termEnd = IsoDate.of(2027, 1, 24),
        courses = courses.toList(),
    )

    private fun course(
        name: String,
        dayOfWeek: Int = 1,
        startSlot: Int = 3,
        span: Int = 1,
        weeks: WeekPattern = WeekPattern(listOf(WeekSegment(1, 16))),
        teacher: String = "",
        location: String = "",
    ) = CourseEntry(
        id = "c-$name",
        name = name,
        teacher = teacher,
        location = location,
        dayOfWeek = dayOfWeek,
        startSlot = startSlot,
        span = span,
        weeks = weeks,
        color = CourseColor.Blue,
    )

    private fun todo(
        name: String,
        deadlineDate: IsoDate? = null,
        deadlineMinute: Int? = null,
        dates: List<IsoDate> = emptyList(),
        urgency: TodoUrgency = TodoUrgency.None,
        group: String? = null,
        tags: List<String> = emptyList(),
        steps: List<String> = emptyList(),
        repeat: RepeatRule = RepeatRule(),
        completedOccurrences: List<IsoDate> = emptyList(),
        completed: Boolean = false,
    ) = Todo(
        id = "t-$name",
        name = name,
        dates = dates,
        repeat = repeat,
        completedOccurrences = completedOccurrences,
        completed = completed,
        deadlineDate = deadlineDate,
        deadlineMinute = deadlineMinute,
        urgency = urgency,
        group = group,
        tags = tags,
        steps = steps.map { TodoStep(id = "step-$it", title = it) },
    )

    @Test
    fun `no schedule renders placeholders`() {
        val prompt = AiPromptBuilder.build(now, null, null, emptyList(), emptyList(), emptyList())
        assertTrue(prompt.contains("[课表] （未设置课表）"))
        assertTrue(prompt.contains("[作息] （未设置）"))
        assertTrue(prompt.contains("[待办] （暂无待办）"))
        assertTrue(prompt.contains("[已归档] （无）"))
        assertTrue(prompt.contains("[回收站] （无）"))
        assertTrue(prompt.contains("[可选分组] （无）"))
        assertTrue(prompt.contains("[可选标签] （无）"))
        assertTrue(prompt.contains("今天是 2026-11-01 周日"))
        assertFalse(prompt.contains("学期第"))
    }

    @Test
    fun `date line carries term week`() {
        val prompt = AiPromptBuilder.build(now, schedule(), 9, emptyList(), listOf("学习"), listOf("期末"))
        assertTrue(prompt.contains("今天是 2026-11-01 周日，学期第 9 周"))
        assertTrue(prompt.contains("[可选分组] 学习"))
        assertTrue(prompt.contains("[可选标签] 期末"))
    }

    @Test
    fun `course line includes slot range time name location weeks teacher`() {
        val prompt = AiPromptBuilder.build(
            now,
            schedule(
                course("高等数学", dayOfWeek = 1, startSlot = 3, span = 2, location = "教学楼A-301", teacher = "张三"),
                course("体育", dayOfWeek = 5, startSlot = 2, weeks = WeekPattern(listOf(WeekSegment(1, 8), WeekSegment(10, 16, com.fangyi.classpp.data.model.Parity.EVEN)))),
            ),
            9,
            emptyList(),
            emptyList(),
            emptyList(),
        )
        assertTrue(prompt.contains("周一 第3-4节 14:30-18:20 高等数学 @教学楼A-301 周1-16（教师：张三）"))
        assertTrue(prompt.contains("周五 第2节 10:10-11:50 体育 周1-8,10-16双"))
    }

    @Test
    fun `todo line carries deadline urgency group tags steps`() {
        val prompt = AiPromptBuilder.build(
            now,
            null,
            null,
            listOf(
                todo(
                    "实验报告",
                    deadlineDate = IsoDate.of(2026, 11, 5),
                    deadlineMinute = 18 * 60,
                    urgency = TodoUrgency.High,
                    group = "学习",
                    tags = listOf("期末", "实验"),
                    steps = listOf("查资料", "写正文"),
                ),
            ),
            emptyList(),
            emptyList(),
        )
        assertTrue(prompt.contains("[t-实验报告] 实验报告｜截止 2026-11-05 18:00｜很急｜组：学习｜标签：期末、实验｜步骤：查资料、写正文"))
    }

    @Test
    fun `completed todos are listed after active ones with marker`() {
        val prompt = AiPromptBuilder.build(
            now,
            null,
            null,
            listOf(
                todo("已完成").copy(completed = true, completedAtMillis = 2L),
                todo("已删除").copy(deletedAtMillis = 1L),
                todo("已归档").copy(archivedAtMillis = 1L),
                todo("未完成"),
            ),
            emptyList(),
            emptyList(),
        )
        assertTrue(prompt.contains("[t-未完成] 未完成"))
        assertTrue(prompt.contains("[t-已完成] 已完成｜已完成"))
        // 删除/归档的待办不会混进 [待办]（归档/回收站由各自的注入参数提供）
        assertFalse(prompt.contains("[t-已删除]"))
        assertFalse(prompt.contains("[t-已归档]"))
        // 未完成在前、已完成在后
        assertTrue(prompt.indexOf("[t-未完成]") < prompt.indexOf("[t-已完成]"))
    }

    @Test
    fun `todos sort by deadline then date and cap at limit`() {
        val todos = (1..AiPromptBuilder.MAX_TODO_LINES + 5).map { i ->
            todo("任务$i", deadlineDate = now + i)
        }
        val prompt = AiPromptBuilder.build(now, null, null, todos, emptyList(), emptyList())
        val earliest = todos.minBy { it.deadlineDate!!.epochDay }
        assertTrue(prompt.contains(earliest.name))
        // 升序取最近 30 条，截止最晚的（任务36 等大序号）被截掉
        assertFalse(prompt.contains("任务${AiPromptBuilder.MAX_TODO_LINES + 5}"))
    }

    @Test
    fun `out of range dayOfWeek is skipped without crashing`() {
        // ScheduleValidator 对越界 dayOfWeek 按 hidden 容忍保留，损坏数据会真的走到这里
        val prompt = AiPromptBuilder.build(
            now,
            schedule(
                course("损坏课", dayOfWeek = 0),
                course("越界课", dayOfWeek = 8),
                course("正常课", dayOfWeek = 3),
            ),
            null,
            emptyList(),
            emptyList(),
            emptyList(),
        )
        assertFalse(prompt.contains("损坏课"))
        assertFalse(prompt.contains("越界课"))
        assertTrue(prompt.contains("正常课"))
    }

    @Test
    fun `repeating todo carries repeat rule and occurrence dates`() {
        val task = todo(
            "跑步",
            dates = listOf(IsoDate.of(2026, 11, 2)),
            repeat = RepeatRule(type = RepeatType.Weekly),
            completedOccurrences = listOf(IsoDate.of(2026, 11, 2)),
        )
        val prompt = AiPromptBuilder.build(now, null, null, listOf(task), emptyList(), emptyList())
        assertTrue(prompt.contains("｜重复：每周"))
        assertTrue(prompt.contains("｜待完成期次：2026-11-09"))
        assertTrue(prompt.contains("｜已完成期次：2026-11-02"))
    }

    @Test
    fun `archived and deleted todos are injected in their own sections`() {
        val archived = todo("旧任务").copy(archivedAtMillis = 1L)
        val deleted = todo("误删任务").copy(deletedAtMillis = 2L)
        val prompt = AiPromptBuilder.build(
            now,
            null,
            null,
            emptyList(),
            emptyList(),
            emptyList(),
            archivedTodos = listOf(archived),
            deletedTodos = listOf(deleted),
        )
        assertTrue(prompt.contains("[已归档] （取消归档时引用行首 id"))
        assertTrue(prompt.contains("[t-旧任务] 旧任务｜已归档"))
        assertTrue(prompt.contains("[回收站] （恢复时引用行首 id"))
        assertTrue(prompt.contains("[t-误删任务] 误删任务｜已删除"))
    }

    @Test
    fun `json contract block is present`() {
        val prompt = AiPromptBuilder.build(now, null, null, emptyList(), emptyList(), emptyList())
        assertTrue(prompt.contains("```json"))
        assertTrue(prompt.contains("\"todos\""))
        assertTrue(prompt.contains("\"updates\""))
        assertTrue(prompt.contains("\"deletes\""))
        assertTrue(prompt.contains("\"completes\""))
        assertTrue(prompt.contains("\"reopens\""))
        assertTrue(prompt.contains("\"archives\""))
        assertTrue(prompt.contains("\"unarchives\""))
        assertTrue(prompt.contains("\"restores\""))
        assertTrue(prompt.contains("deadlineDate"))
        assertTrue(prompt.contains("要么都给，要么都不给"))
        // 修改/删除需要确认，正文不得声称已完成
        assertTrue(prompt.contains("不会立即生效"))
        assertTrue(prompt.contains("禁止声称已经改好/删好"))
        // 完成/取消完成与归档/恢复直接生效
        assertTrue(prompt.contains("完成/取消完成会立即生效"))
        assertTrue(prompt.contains("归档/取消归档/恢复会立即生效"))
    }

    @Test
    fun `json contracts come from the tool registry`() {
        val contracts = AiToolRegistry.fallbackContracts
        // 提示词里的四段契约与修复重试引用同一份注册表生成结果，不是各自手写
        assertEquals(contracts.createTodos, AiPromptBuilder.TODO_JSON_SHAPE)
        assertEquals(contracts.updateAndDelete, AiPromptBuilder.TODO_CHANGE_JSON_SHAPE)
        assertEquals(contracts.complete, AiPromptBuilder.TODO_COMPLETE_JSON_SHAPE)
        assertEquals(contracts.lifecycle, AiPromptBuilder.TODO_LIFECYCLE_JSON_SHAPE)

        val prompt = AiPromptBuilder.build(now, null, null, emptyList(), emptyList(), emptyList())
        assertTrue(prompt.contains(contracts.createTodos))
        assertTrue(prompt.contains(contracts.updateAndDelete))
        assertTrue(prompt.contains(contracts.complete))
        assertTrue(prompt.contains(contracts.lifecycle))
    }
}
