package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.RepeatUnit
import com.fangyi.classpp.data.model.Todo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重复待办列表展开（[expandRepeatOccurrences]）的显示窗口测试：拖欠期全显、清完拖欠才显示
 * 未来的"下一个"、完成自动前移、取消完成按"下一期与当前时间的关系"回退或共存；
 * 以及「下一次重复开始前」的逐期截止折算。
 */
class TodoRepeatDisplayTest {

    private val weekly = RepeatRule(type = RepeatType.Weekly)

    private fun expand(todo: Todo, today: String): List<Todo> =
        listOf(todo).expandRepeatOccurrences(IsoDate.parse(today))

    private fun active(shown: List<Todo>): List<String> =
        shown.filterNot { it.completed }.map { it.dates.single().toString() }

    private fun done(shown: List<Todo>): List<String> =
        shown.filter { it.completed }.map { it.dates.single().toString() }

    @Test
    fun `overdue occurrences fill the window and hide the upcoming`() {
        // 锚 3/2（周一）每周，今天 3/20：拖欠 3/2、3/9、3/16 全显，未来的 3/23 不出现
        val todo = testTodo(dates = listOf(IsoDate.parse("2026-03-02")), repeat = weekly)
        val shown = expand(todo, "2026-03-20")
        assertEquals(listOf("2026-03-02", "2026-03-09", "2026-03-16"), active(shown))
        assertTrue(done(shown).isEmpty())
    }

    @Test
    fun `completing occurrences in order advances the window`() {
        // 依次完成 3/2、3/9、3/16：清完拖欠后窗口前移到下一个 3/23，完成的三期落"已完成"
        val todo = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = weekly,
            completedOccurrences = listOf(
                IsoDate.parse("2026-03-02"),
                IsoDate.parse("2026-03-09"),
                IsoDate.parse("2026-03-16"),
            ),
        )
        val shown = expand(todo, "2026-03-20")
        assertEquals(listOf("2026-03-23"), active(shown))
        assertEquals(listOf("2026-03-02", "2026-03-09", "2026-03-16"), done(shown))
    }

    @Test
    fun `un-completing the upcoming takes the window back from the next`() {
        // 今天 3/24，完成到 3/23：窗口在 3/30。取消 3/23（上一个，刚完成）——
        // 下一个 3/30 > 当前时间 → 隐藏，窗口回退到 3/23
        val base = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = weekly,
            completedOccurrences = listOf(
                IsoDate.parse("2026-03-02"),
                IsoDate.parse("2026-03-09"),
                IsoDate.parse("2026-03-16"),
                IsoDate.parse("2026-03-23"),
            ),
        )
        assertEquals(listOf("2026-03-30"), active(expand(base, "2026-03-24")))

        val rolledBack = base.copy(completedOccurrences = base.completedOccurrences.dropLast(1))
        val shown = expand(rolledBack, "2026-03-24")
        assertEquals(listOf("2026-03-23"), active(shown))
        assertTrue(shown.none { it.dates.single() == IsoDate.parse("2026-03-30") })
    }

    @Test
    fun `un-completing keeps an already-overdue next visible`() {
        // 今天 3/31：3/30 也已过期（完成到 3/23 时窗口显示的 3/30 被时钟越过）。
        // 取消 3/23（上一个 < 当前时间）——下一个 3/30 不隐藏，两期同显
        val base = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = weekly,
            completedOccurrences = listOf(
                IsoDate.parse("2026-03-02"),
                IsoDate.parse("2026-03-09"),
                IsoDate.parse("2026-03-16"),
                IsoDate.parse("2026-03-23"),
            ),
        )
        assertEquals(listOf("2026-03-30"), active(expand(base, "2026-03-31")))

        val rolledBack = base.copy(completedOccurrences = base.completedOccurrences.dropLast(1))
        assertEquals(
            listOf("2026-03-23", "2026-03-30"),
            active(expand(rolledBack, "2026-03-31")),
        )
    }

    @Test
    fun `non-repeat and whole-completed repeat pass through untouched`() {
        val plain = testTodo(name = "普通")
        val legacy = testTodo(
            name = "遗留",
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = weekly,
            completed = true,
        )
        val shown = listOf(plain, legacy).expandRepeatOccurrences(IsoDate.parse("2026-03-20"))
        assertEquals(listOf(plain, legacy), shown)
    }

    @Test
    fun `before-next-repeat deadline folds per occurrence`() {
        // 每周（锚 3/2 周一）、开始前 2 天：每期截止 = 下一期 − 2 天（3/2→3/7、3/9→3/14、
        // 3/16→3/21），时刻沿用存储值；存储的原始截止只是载体，不被改动
        val todo = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = weekly,
            deadlineDate = IsoDate.parse("2026-03-05"),
            deadlineMinute = 600,
            deadlineBeforeRepeat = true,
            deadlineBeforeDays = 2,
        )
        val shown = expand(todo, "2026-03-20")
        assertEquals(
            listOf("2026-03-07", "2026-03-14", "2026-03-21"),
            shown.map { it.deadlineDate!!.toString() },
        )
        assertTrue(shown.all { it.deadlineMinute == 600 })
        assertEquals(IsoDate.parse("2026-03-05"), todo.deadlineDate)
    }

    @Test
    fun `folded deadline never lands before its own occurrence`() {
        // 每周一+周五（锚 3/2）、开始前 4 天：周五期的下一期是下周一，3/9 − 4 = 3/5 早于
        // 期日 3/6 → 收拢到期日当天（周一期的 3/6 − 4 = 3/2 恰为期日，原样保留）
        val mondayFriday = RepeatRule(
            type = RepeatType.Custom,
            unit = RepeatUnit.Week,
            interval = 1,
            marks = setOf(1, 5),
        )
        val todo = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = mondayFriday,
            deadlineDate = IsoDate.parse("2026-03-05"),
            deadlineMinute = 480,
            deadlineBeforeRepeat = true,
            deadlineBeforeDays = 4,
        )
        val shown = expand(todo, "2026-03-07")
        assertEquals(
            listOf("2026-03-02", "2026-03-06"),
            shown.map { it.deadlineDate!!.toString() },
        )
    }

    @Test
    fun `before-next-repeat off keeps stored deadline on every occurrence`() {
        // 开关关闭：各期副本原样携带存储的截止（现状行为）
        val todo = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = weekly,
            deadlineDate = IsoDate.parse("2026-03-05"),
            deadlineMinute = 600,
        )
        val shown = expand(todo, "2026-03-20")
        assertTrue(shown.isNotEmpty())
        assertTrue(
            shown.all {
                it.deadlineDate == IsoDate.parse("2026-03-05") && it.deadlineMinute == 600
            },
        )
    }
}
