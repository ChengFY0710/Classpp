package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.Todo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重复待办列表展开（[expandRepeatOccurrences]）的显示窗口测试：拖欠期全显、清完拖欠才显示
 * 未来的"下一个"、完成自动前移、取消完成按"下一期与当前时间的关系"回退或共存。
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
}
