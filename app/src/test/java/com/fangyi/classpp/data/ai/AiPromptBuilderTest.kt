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

    @Test
    fun `native tools mode swaps json contracts for tool contract`() {
        val fallback = AiPromptBuilder.build(now, null, null, emptyList(), emptyList(), emptyList())
        val native = AiPromptBuilder.build(
            now = now,
            schedule = null,
            currentWeek = null,
            todos = emptyList(),
            groups = emptyList(),
            tags = emptyList(),
            nativeTools = true,
        )

        assertFalse(fallback.contains("已启用工具调用"))
        assertTrue(native.contains("已启用工具调用"))
        listOf(
            AiToolRegistry.GET_DAY_PLAN,
            AiToolRegistry.SEARCH_TODOS,
            AiToolRegistry.GET_WEEK_SCHEDULE,
            AiToolRegistry.GET_TODO,
            AiToolRegistry.CREATE_TODOS,
            AiToolRegistry.UPDATE_TODOS,
            AiToolRegistry.DELETE_TODOS,
            AiToolRegistry.COMPLETE_TODOS,
            AiToolRegistry.ARCHIVE_TODOS,
        ).forEach { name -> assertTrue("native prompt must mention $name", native.contains(name)) }
        // 原生模式不再给出 JSON 信封契约：动作由工具执行，代码块会被忽略（避免双写）
        assertTrue(native.contains("不要输出 ```json 代码块"))
        assertFalse(native.contains(AiToolRegistry.fallbackContracts.createTodos))
        assertFalse(native.contains(AiToolRegistry.fallbackContracts.updateAndDelete))
        assertFalse(native.contains(AiToolRegistry.fallbackContracts.complete))
        assertFalse(native.contains(AiToolRegistry.fallbackContracts.lifecycle))
        // 回退模式仍是完整 JSON 契约（BYOK 兼容面不缩小）
        assertTrue(fallback.contains(AiToolRegistry.fallbackContracts.createTodos))
    }

    // ---------- P3：今日摘要（原生工具模式） ----------

    @Test
    fun `native mode injects today summary instead of full lists`() {
        val prompt = AiPromptBuilder.build(
            now = now, // 2026-11-01 周日
            schedule = schedule(
                course("今天的课", dayOfWeek = 7, startSlot = 3, span = 2, location = "教学楼A-301"),
                course("明天的课", dayOfWeek = 1, startSlot = 1),
            ),
            currentWeek = 9,
            todos = listOf(
                todo("今天的待办", dates = listOf(now)),
                todo("别的日子的待办", dates = listOf(now + 3)),
                todo("已完成的待办", completed = true),
            ),
            groups = listOf("学习"),
            tags = listOf("期末"),
            archivedTodos = listOf(todo("归档任务").copy(archivedAtMillis = 1L)),
            deletedTodos = listOf(todo("误删任务").copy(deletedAtMillis = 2L)),
            nativeTools = true,
        )

        // 今日课程：只列当天的，且带节次/时间/地点
        assertTrue(prompt.contains("[今日课程]"))
        assertTrue(prompt.contains("- 第3-4节 14:30-18:20 今天的课 @教学楼A-301"))
        assertFalse(prompt.contains("明天的课"))
        // 今日待办：只列今天命中的，带 id 供完成/归档引用
        assertTrue(prompt.contains("[今日待办]"))
        assertTrue(prompt.contains("[t-今天的待办] 今天的待办"))
        assertFalse(prompt.contains("别的日子的待办"))
        // 归档/回收站只给计数（明细交给 search_todos）
        assertFalse(prompt.contains("归档任务"))
        assertFalse(prompt.contains("误删任务"))
        assertTrue(prompt.contains("已归档 1 条"))
        assertTrue(prompt.contains("回收站 1 条"))
        // 全量区块整体消失
        assertFalse(prompt.contains("[课表] "))
        assertFalse(prompt.contains("[待办] "))
        assertFalse(prompt.contains("[已归档] "))
        assertFalse(prompt.contains("[回收站] "))
    }

    @Test
    fun `native prompt costs much less than the full prompt`() {
        val courses = (1..20).map { i ->
            course("课程$i", dayOfWeek = (i % 7) + 1, startSlot = (i % 6) + 1, location = "教学楼$i")
        }
        val todos = (1..40).map { i ->
            todo(
                "待办$i",
                deadlineDate = now + i,
                group = "学习",
                tags = listOf("标签$i"),
                steps = listOf("步骤A", "步骤B"),
            )
        }
        val archived = (1..8).map { i -> todo("归档$i").copy(archivedAtMillis = i.toLong()) }
        val deleted = (1..5).map { i -> todo("回收$i").copy(deletedAtMillis = i.toLong()) }
        val schedule = schedule(*courses.toTypedArray())

        val full = AiPromptBuilder.build(
            now, schedule, 9, todos, listOf("学习"), listOf("期末"), archived, deleted,
        )
        val native = AiPromptBuilder.build(
            now, schedule, 9, todos, listOf("学习"), listOf("期末"), archived, deleted,
            nativeTools = true,
        )

        // 验收口径（设计稿 §13 P3）：常见场景 token 明显下降——至少砍掉一半
        assertTrue(
            "native=${native.length} full=${full.length}",
            native.length * 2 < full.length,
        )
        // 无工具调用时仍能正常对话：日期/星期/周数/可选分组标签都在
        assertTrue(native.contains("今天是 2026-11-01 周日，学期第 9 周"))
        assertTrue(native.contains("[可选分组] 学习"))
        assertTrue(native.contains("[可选标签] 期末"))
    }

    @Test
    fun `today summary caps listed todos and points at search_todos`() {
        val todos = (1..AiPromptBuilder.MAX_TODAY_TODO_LINES + 4).map { i ->
            todo("今日$i", dates = listOf(now))
        }

        val prompt = AiPromptBuilder.build(
            now, null, null, todos, emptyList(), emptyList(), nativeTools = true,
        )

        assertTrue(prompt.contains("[t-今日1]"))
        assertTrue(prompt.contains("另有 4 条今日待办未列出，用 search_todos 查"))
        assertTrue(prompt.contains("未完成 ${todos.size} 条"))
        assertTrue(prompt.contains("今日 ${todos.size} 条"))
    }

    @Test
    fun `today summary reports overdue and empty states`() {
        val prompt = AiPromptBuilder.build(
            now = now,
            schedule = null,
            currentWeek = null,
            todos = listOf(
                todo("逾期任务", deadlineDate = now - 2),
                todo("今天任务", dates = listOf(now)),
            ),
            groups = emptyList(),
            tags = emptyList(),
            nativeTools = true,
        )

        assertTrue(prompt.contains("未完成 2 条（今日 1 条、逾期 1 条）"))
        assertTrue(prompt.contains("[今日课程] （未设置课表）"))

        val empty = AiPromptBuilder.build(now, null, null, emptyList(), emptyList(), emptyList(), nativeTools = true)
        assertTrue(empty.contains("[今日待办] （今天没有待办）"))
        assertTrue(empty.contains("[待办概况] 未完成 0 条（今日 0 条、逾期 0 条）"))
    }
}
