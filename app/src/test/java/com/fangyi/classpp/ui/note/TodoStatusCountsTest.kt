package com.fangyi.classpp.ui.note

import com.fangyi.classpp.data.expandRepeatOccurrences
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatEndType
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.testTodo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分组浮层状态行计数的展开口径（[buildTodoStatusCounts]）：输入是
 * [expandRepeatOccurrences] 展开后的卡片——已完成/未完成/已逾期的数字必须与点入
 * 对应专项页看到的卡片数一致，重复待办不再按整条系列的 completed 恒 false 计 0。
 */
class TodoStatusCountsTest {

    /** 固定「今天」= 2026-03-20（周五）、此刻 = 12:00 */
    private val today = IsoDate.parse("2026-03-20")
    private val nowMinute = 12 * 60

    /** 宿主同款管线：先展开再计数 */
    private fun countsOf(vararg todos: Todo): TodoStatusCounts =
        buildTodoStatusCounts(todos.toList().expandRepeatOccurrences(today), today, nowMinute)

    @Test
    fun `non repeating completed todo counts as one completed card`() {
        assertEquals(
            TodoStatusCounts(completed = 1, incomplete = 0, overdue = 0),
            countsOf(testTodo(name = "已完成", completed = true)),
        )
    }

    @Test
    fun `split series counts one completed card per occurrence`() {
        // 拆分口径 3 个完成期逐期成卡：已完成 3 + 未来最近一期 3/23 的未完成卡
        //（旧口径按整条 completed 恒 false 计 0，点入已完成页却有 3 张卡）
        val todo = testTodo(
            name = "周报",
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = RepeatRule(type = RepeatType.Weekly, merge = false),
            completedOccurrences = listOf(
                IsoDate.parse("2026-03-02"),
                IsoDate.parse("2026-03-09"),
                IsoDate.parse("2026-03-16"),
            ),
        )
        assertEquals(
            TodoStatusCounts(completed = 3, incomplete = 1, overdue = 0),
            countsOf(todo),
        )
    }

    @Test
    fun `merged series with completed history still shows only the pending card`() {
        // 合并口径只出一条滚动卡：完成历史不成卡，当前 3/23 未完成 → 已完成 0、未完成 1
        val todo = testTodo(
            name = "周报",
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = RepeatRule(type = RepeatType.Weekly),
            completedOccurrences = listOf(IsoDate.parse("2026-03-02")),
        )
        assertEquals(
            TodoStatusCounts(completed = 0, incomplete = 1, overdue = 0),
            countsOf(todo),
        )
    }

    @Test
    fun `check-in series at target counts as one completed card`() {
        // 打卡达标收口在最后完成期（完成态卡）：已完成 1、未完成 0
        //（旧口径整条 completed 恒 false，未完成计数仍 +1，点进去却看不到卡）
        val todo = testTodo(
            name = "喝水",
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = RepeatRule(
                type = RepeatType.Daily,
                endType = RepeatEndType.Count,
                endCount = 3,
                checkIn = true,
            ),
            completedOccurrences = listOf(
                IsoDate.parse("2026-03-18"),
                IsoDate.parse("2026-03-19"),
                IsoDate.parse("2026-03-20"),
            ),
        )
        assertEquals(
            TodoStatusCounts(completed = 1, incomplete = 0, overdue = 0),
            countsOf(todo),
        )
    }

    @Test
    fun `deadline before repeat judges overdue by the folded deadline`() {
        // 存储载体 3/5 已过期，但合并卡 3/23 的折算截止 = 下一期 3/30 − 2 = 3/28 未过期：
        // 逾期 0（旧口径按载体判恒逾期，与专项页的展开口径对不上）
        val todo = testTodo(
            name = "周报",
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = RepeatRule(type = RepeatType.Weekly),
            deadlineDate = IsoDate.parse("2026-03-05"),
            deadlineMinute = 600,
            deadlineBeforeRepeat = true,
            deadlineBeforeDays = 2,
        )
        assertEquals(
            TodoStatusCounts(completed = 0, incomplete = 1, overdue = 0),
            countsOf(todo),
        )
    }

    @Test
    fun `split series with missed occurrences counts one overdue card per period`() {
        // 拆分、每周锚 3/2、开始前 2 天：拖欠 3/2、3/9、3/16 各一张卡（有拖欠时未来期
        // 不出现），折算截止 3/7、3/14、3/21——前两期已过 → 逾期 2（旧口径按系列只计 1）
        val todo = testTodo(
            name = "周报",
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = RepeatRule(type = RepeatType.Weekly, merge = false),
            deadlineDate = IsoDate.parse("2026-03-05"),
            deadlineMinute = 600,
            deadlineBeforeRepeat = true,
            deadlineBeforeDays = 2,
        )
        assertEquals(
            TodoStatusCounts(completed = 0, incomplete = 3, overdue = 2),
            countsOf(todo),
        )
    }
}
