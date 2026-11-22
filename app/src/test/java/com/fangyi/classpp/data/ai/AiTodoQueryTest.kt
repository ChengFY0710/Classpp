package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatEndType
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.outstandingOverdueOccurrences
import com.fangyi.classpp.data.testTodo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 侧共享口径（[isOverdueOn] / [outstandingOverdueOccurrences]）的纯函数测试：
 * 逾期跟「仍欠着的期次」走——迟到补完成不再算逾期、跨天未写库也能补算新漏期、
 * 随重复的载体截止不参与整条逾期判定。
 */
class AiTodoQueryTest {

    /** 每周（拆分口径展示无碍，逾期集合口径与合并无关） */
    private val weekly = RepeatRule(type = RepeatType.Weekly)

    @Test
    fun `late completion drops the occurrence from outstanding overdue`() {
        // 3/9 漏了、仓库已落账，事后补完成：落账集合不动（完成情况统计口径），
        // 但「当前仍欠」不再包含它——修复前 AI 会一直把这条列为逾期
        val base = testTodo(
            name = "周报",
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = weekly,
            completedOccurrences = listOf(IsoDate.parse("2026-03-02")),
        ).copy(overdueOccurrences = listOf(IsoDate.parse("2026-03-09")))
        assertTrue(IsoDate.parse("2026-03-09") in base.outstandingOverdueOccurrences(IsoDate.parse("2026-03-16")))
        assertTrue(base.isOverdueOn(IsoDate.parse("2026-03-16")))

        val lateCompleted = base.copy(
            completedOccurrences = base.completedOccurrences + IsoDate.parse("2026-03-09"),
        )
        assertTrue(
            lateCompleted.outstandingOverdueOccurrences(IsoDate.parse("2026-03-16")).isEmpty(),
        )
        assertFalse(lateCompleted.isOverdueOn(IsoDate.parse("2026-03-16")))
    }

    @Test
    fun `missed occurrences are computed in real time without a repository write`() {
        // 仓库只在写库时结算：锚 3/2 每周、今天 3/16，未落账的 3/2、3/9 由实时补算得出
        val todo = testTodo(
            name = "周报",
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = weekly,
        )
        assertEquals(
            listOf("2026-03-02", "2026-03-09"),
            todo.outstandingOverdueOccurrences(IsoDate.parse("2026-03-16")).map { it.toString() },
        )
        assertTrue(todo.isOverdueOn(IsoDate.parse("2026-03-16")))
        // 当天的应完成日期还不算错过（与仓库结算同为日期粒度）：3/2 已完成时，问到 3/9
        //（当天的期）为止没有仍欠的期次
        val upToDate = todo.copy(completedOccurrences = listOf(IsoDate.parse("2026-03-02")))
        assertFalse(upToDate.isOverdueOn(IsoDate.parse("2026-03-09")))
    }

    @Test
    fun `check-in mode only counts the settled set`() {
        // 打卡漏期不记逾期：不做实时补算，只认落账集合减去已完成
        val rule = RepeatRule(
            type = RepeatType.Daily,
            endType = RepeatEndType.Count,
            endCount = 3,
            checkIn = true,
        )
        val todo = testTodo(
            name = "喝水",
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = rule,
            completedOccurrences = listOf(IsoDate.parse("2026-03-18")),
        )
        // 锚 3/2、今天 3/20：漏掉的 18 期不补算（打卡只数完成次数），无落账 → 不逾期
        assertTrue(todo.outstandingOverdueOccurrences(IsoDate.parse("2026-03-20")).isEmpty())
        assertFalse(todo.isOverdueOn(IsoDate.parse("2026-03-20")))

        // 历史落账 3/5（脏数据防御口径）：补完成后也不再算「当前仍欠」
        val settled = todo.copy(overdueOccurrences = listOf(IsoDate.parse("2026-03-05")))
        assertEquals(
            listOf("2026-03-05"),
            settled.outstandingOverdueOccurrences(IsoDate.parse("2026-03-20")).map { it.toString() },
        )
        assertTrue(
            settled
                .copy(completedOccurrences = settled.completedOccurrences + IsoDate.parse("2026-03-05"))
                .outstandingOverdueOccurrences(IsoDate.parse("2026-03-20"))
                .isEmpty(),
        )
    }

    @Test
    fun `deadline before repeat ignores the carrier date for overdue`() {
        // 随重复的截止：载体 3/5 早已过期，但系列还没开始（锚 3/23 在未来）——不按载体判逾期
        val futureSeries = testTodo(
            name = "预习",
            dates = listOf(IsoDate.parse("2026-03-23")),
            repeat = weekly,
            deadlineDate = IsoDate.parse("2026-03-05"),
            deadlineMinute = 600,
            deadlineBeforeRepeat = true,
            deadlineBeforeDays = 2,
        )
        assertFalse(futureSeries.isOverdueOn(IsoDate.parse("2026-03-16")))

        // 对照：不随重复的固定截止仍按整条判定（过期 → 逾期）
        val fixedDeadline = testTodo(
            name = "固定截止",
            dates = listOf(IsoDate.parse("2026-03-23")),
            repeat = weekly,
            deadlineDate = IsoDate.parse("2026-03-05"),
            deadlineMinute = 600,
        )
        assertTrue(fixedDeadline.isOverdueOn(IsoDate.parse("2026-03-16")))
    }

    @Test
    fun `non repeat todos keep the deadline-only overdue semantics`() {
        val overdue = testTodo(name = "过期", deadlineDate = IsoDate.parse("2026-03-01"), deadlineMinute = 600)
        assertTrue(overdue.isOverdueOn(IsoDate.parse("2026-03-16")))
        // 已完成不逾期；无截止不逾期
        assertFalse(overdue.copy(completed = true).isOverdueOn(IsoDate.parse("2026-03-16")))
        assertFalse(testTodo(name = "无截止").isOverdueOn(IsoDate.parse("2026-03-16")))
        // 逾期期次集合对非重复恒为空
        assertTrue(overdue.outstandingOverdueOccurrences(IsoDate.parse("2026-03-16")).isEmpty())
    }
}
