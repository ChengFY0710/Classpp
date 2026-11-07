package com.fangyi.classpp.ui.note

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.testTodo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.GregorianCalendar

/**
 * 待办概览的派生口径（[buildTodoOverviewStats]）：
 * - 分布/提醒走 [com.fangyi.classpp.data.expandRepeatOccurrences] 的卡片口径；
 * - 本周完成/连续天数/最近完成走完成记录口径——非重复看 `completedAtMillis`，
 *   重复看 `completedOccurrences`，两者不混算；
 * - 最近完成窗口 7 天、每条待办只取最近一次；
 * - 今日收工口径是两层混合：todayTotal = todayCompleted（完成记录）+ dueToday + overdue
 *   （列表展开），清完所有欠账才算收工。
 */
class TodoOverviewStatsTest {

    private val today = IsoDate.parse("2026-03-20") // 周五，所在周周一 = 2026-03-16
    private val nowMinute = 600 // 10:00

    private fun stats(todos: List<Todo>) =
        buildTodoOverviewStats(todos, today, nowMinute)

    private fun TodoOverviewStats.countOn(date: IsoDate): Int =
        weekDays.first { it.date == date }.count

    /** 构造本地时区某天中午的毫秒时刻（避免 DST 边界与 `IsoDate.today(millis)` 的时区口径打架） */
    private fun localNoonMillis(date: IsoDate): Long {
        val (year, month, day) = date.yearMonthDay()
        return GregorianCalendar(year, month - 1, day, 12, 0, 0).timeInMillis
    }

    @Test
    fun `empty list returns zero stats and seven week days`() {
        val stats = stats(emptyList())
        assertEquals(0, stats.total)
        assertEquals(0, stats.completed)
        assertEquals(0, stats.active)
        assertEquals(0, stats.overdue)
        assertEquals(0, stats.weekCompleted)
        assertEquals(0, stats.weekActiveDays)
        assertEquals(0, stats.todayCompleted)
        assertEquals(0, stats.todayTotal)
        assertEquals(0, stats.streak)
        assertEquals(0, stats.dueToday)
        assertEquals(0, stats.dueNext7)
        assertTrue(stats.recent.isEmpty())
        assertEquals(7, stats.weekDays.size)
        assertEquals(today.mondayOfWeek(), stats.weekDays.first().date)
        assertEquals(today.mondayOfWeek() + 6, stats.weekDays.last().date)
    }

    @Test
    fun `non repeating completion lands on local day and feeds streak`() {
        val todo = testTodo(
            completed = true,
            completedAtMillis = localNoonMillis(today),
        )
        val stats = stats(listOf(todo))
        assertEquals(1, stats.total)
        assertEquals(1, stats.completed)
        assertEquals(0, stats.active)
        assertEquals(1, stats.weekCompleted)
        assertEquals(1, stats.weekActiveDays)
        assertEquals(1, stats.todayCompleted)
        // 今天完成 1 件、无截止无逾期 → 1/1 收工
        assertEquals(1, stats.todayTotal)
        assertEquals(1, stats.countOn(today))
        assertEquals(1, stats.streak)
        assertEquals(1, stats.recent.size)
        assertEquals(today, stats.recent.single().day)
    }

    @Test
    fun `repeating occurrences feed week and streak but merged distribution stays one card`() {
        val todo = testTodo(
            dates = listOf(today - 10),
            repeat = RepeatRule(type = RepeatType.Daily),
            completedOccurrences = listOf(today - 2, today - 1, today),
        )
        val stats = stats(listOf(todo))
        // 合并口径：整条系列只出一张「下一期」滚动卡，当前期未完成 → 分布计 1 张未完成
        assertEquals(1, stats.total)
        assertEquals(0, stats.completed)
        assertEquals(1, stats.active)
        // 完成记录口径：三期各记一次
        assertEquals(3, stats.weekCompleted)
        assertEquals(3, stats.weekActiveDays)
        assertEquals(1, stats.todayCompleted)
        assertEquals(1, stats.countOn(today - 2))
        assertEquals(1, stats.countOn(today - 1))
        assertEquals(1, stats.countOn(today))
        assertEquals(3, stats.streak)
        assertEquals(1, stats.recent.size)
        assertEquals(today, stats.recent.single().day)
    }

    @Test
    fun `due today excludes completed and already overdue deadlines`() {
        val dueToday = testTodo(deadlineDate = today)
        val overdueToday = testTodo(deadlineDate = today, deadlineMinute = 0) // 00:00 < 10:00
        val inThreeDays = testTodo(deadlineDate = today + 3)
        val inSevenDays = testTodo(deadlineDate = today + 7)
        val beyondWindow = testTodo(deadlineDate = today + 8)
        val completedToday = testTodo(
            completed = true,
            completedAtMillis = localNoonMillis(today),
            deadlineDate = today,
        )
        val stats = stats(
            listOf(dueToday, overdueToday, inThreeDays, inSevenDays, beyondWindow, completedToday),
        )
        assertEquals(6, stats.total)
        assertEquals(1, stats.completed)
        assertEquals(1, stats.overdue)
        assertEquals(4, stats.active)
        assertEquals(1, stats.dueToday)
        assertEquals(2, stats.dueNext7)
        // 今日收工口径：今天完成 1 + 今天截止未完成 1 + 逾期 1 = 3
        assertEquals(3, stats.todayTotal)
    }

    @Test
    fun `today total mixes completion records with expanded backlog`() {
        // 今天完成记录 1 件（无截止），欠账：今天截止 1 件 + 历史逾期 1 件 → 1/3
        val doneToday = testTodo(
            completed = true,
            completedAtMillis = localNoonMillis(today),
        )
        val dueToday = testTodo(deadlineDate = today, deadlineMinute = 23 * 60 + 59)
        val overdue = testTodo(deadlineDate = today - 2)
        val stats = stats(listOf(doneToday, dueToday, overdue))
        assertEquals(1, stats.todayCompleted)
        assertEquals(1, stats.dueToday)
        assertEquals(1, stats.overdue)
        assertEquals(3, stats.todayTotal)
    }

    @Test
    fun `today total is zero when nothing done due or overdue today`() {
        // 只有未来的待办：今天没做也没欠 → 全清特例（环形画满 + 绿勾）
        val future = testTodo(
            name = "future",
            deadlineDate = today + 3,
        )
        val stats = stats(listOf(future))
        assertEquals(1, stats.total)
        assertEquals(0, stats.todayCompleted)
        assertEquals(0, stats.dueToday)
        assertEquals(0, stats.overdue)
        assertEquals(0, stats.todayTotal)
    }

    @Test
    fun `recent completions dedupe per series and sort newest first`() {
        val first = testTodo(
            name = "A",
            completed = true,
            completedAtMillis = localNoonMillis(today),
        )
        val old = testTodo(
            name = "B",
            completed = true,
            completedAtMillis = localNoonMillis(today - 3),
        )
        val repeating = testTodo(
            name = "C",
            dates = listOf(today - 10),
            repeat = RepeatRule(type = RepeatType.Daily),
            completedOccurrences = listOf(today - 2, today - 1),
        )
        val outsideWeek = testTodo(
            name = "D",
            completed = true,
            completedAtMillis = localNoonMillis(today - 10),
        )
        val stats = stats(listOf(first, old, repeating, outsideWeek))
        assertEquals(listOf("A", "C", "B"), stats.recent.map { it.title })
        assertEquals(listOf(today, today - 1, today - 3), stats.recent.map { it.day })
        assertEquals(4, stats.weekCompleted)
        assertEquals(4, stats.weekActiveDays)
        assertEquals(4, stats.streak)
    }

    @Test
    fun `recent window includes six days ago and excludes seven days ago`() {
        val inWindow = testTodo(
            name = "in",
            completed = true,
            completedAtMillis = localNoonMillis(today - 6),
        )
        val outOfWindow = testTodo(
            name = "out",
            completed = true,
            completedAtMillis = localNoonMillis(today - 7),
        )
        val stats = stats(listOf(inWindow, outOfWindow))
        assertEquals(1, stats.recent.size)
        assertEquals("in", stats.recent.single().title)
        assertEquals(0, stats.weekCompleted)
    }

    @Test
    fun `streak continues from yesterday when today is not done yet`() {
        val yesterday = testTodo(
            completed = true,
            completedAtMillis = localNoonMillis(today - 1),
        )
        val dayBefore = testTodo(
            completed = true,
            completedAtMillis = localNoonMillis(today - 2),
        )
        assertEquals(2, stats(listOf(yesterday, dayBefore)).streak)
        assertEquals(0, stats(listOf(yesterday, dayBefore)).todayCompleted)
    }

    @Test
    fun `streak breaks when yesterday is missing`() {
        val dayBefore = testTodo(
            completed = true,
            completedAtMillis = localNoonMillis(today - 2),
        )
        assertEquals(0, stats(listOf(dayBefore)).streak)
    }
}
