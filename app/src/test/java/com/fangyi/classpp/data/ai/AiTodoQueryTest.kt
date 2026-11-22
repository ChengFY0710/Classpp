package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatEndType
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.testTodo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 侧共享口径（[isOverdueOn]）的纯函数测试。产品口径（3.1 已定）：**逾期 = 已过截止**——
 * 漏掉的期次（漏期）不算逾期（只在完成情况浮层的红球/漏期次数与 get_todo 的
 * missedOccurrences 里体现），判定与列表「已逾期」分组的合并卡同口径。
 */
class AiTodoQueryTest {

    /** 每周（周一锚） */
    private val weekly = RepeatRule(type = RepeatType.Weekly)

    @Test
    fun `missed occurrences alone do not make a repeat todo overdue`() {
        // 锚 3/2 每周、今天 3/20：3/2、3/9、3/16 三期漏了，但没有截止——不算逾期
        //（完成情况浮层按漏期计，红球与「漏期次数」另有归属）
        val todo = testTodo(
            name = "周报",
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = weekly,
        )
        assertFalse(todo.isOverdueOn(IsoDate.parse("2026-03-20")))
        // 迟到补完成与否都不改变判定：逾期口径里没有漏期参与
        val lateCompleted = todo.copy(
            completedOccurrences = listOf(IsoDate.parse("2026-03-02")),
            overdueOccurrences = listOf(IsoDate.parse("2026-03-02")),
        )
        assertFalse(lateCompleted.isOverdueOn(IsoDate.parse("2026-03-20")))
    }

    @Test
    fun `fixed deadline on a repeat todo still judges overdue`() {
        // 不随重复的固定截止是整条系列的截止：过期即逾期（与列表卡同口径）
        val todo = testTodo(
            name = "复习到考试",
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = weekly,
            deadlineDate = IsoDate.parse("2026-03-18"),
            deadlineMinute = 600,
        )
        assertFalse(todo.isOverdueOn(IsoDate.parse("2026-03-16")))
        assertTrue(todo.isOverdueOn(IsoDate.parse("2026-03-20")))
    }

    @Test
    fun `deadline before repeat judges by the folded current deadline`() {
        // 随重复：载体 3/5 早已过期不作数，按当前期折算截止判定。进行中的系列折算截止
        // 恒不早于当前期（当前期 ≥ 今天）→ 不算逾期
        val active = testTodo(
            name = "预习",
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = weekly,
            deadlineDate = IsoDate.parse("2026-03-05"),
            deadlineMinute = 600,
            deadlineBeforeRepeat = true,
            deadlineBeforeDays = 2,
        )
        assertFalse(active.isOverdueOn(IsoDate.parse("2026-03-20")))

        // 系列走完且末期未完成：合并卡停在最后一期 3/16，折算截止随系列末期回落到
        // 载体 3/5 → 已过截止 → 逾期（与列表把这张卡放「已逾期」组一致）
        val walkout = active.copy(
            repeat = RepeatRule(
                type = RepeatType.Weekly,
                endType = RepeatEndType.Count,
                endCount = 3,
            ),
            completedOccurrences = listOf(IsoDate.parse("2026-03-02"), IsoDate.parse("2026-03-09")),
        )
        assertTrue(walkout.isOverdueOn(IsoDate.parse("2026-03-20")))

        // 系列走完且全部提前完成：合并卡是完成态 → 不算逾期
        val finished = walkout.copy(
            completedOccurrences = walkout.completedOccurrences + IsoDate.parse("2026-03-16"),
        )
        assertFalse(finished.isOverdueOn(IsoDate.parse("2026-03-20")))
    }

    @Test
    fun `non repeat todos keep the deadline-only overdue semantics`() {
        val overdue = testTodo(name = "过期", deadlineDate = IsoDate.parse("2026-03-01"), deadlineMinute = 600)
        assertTrue(overdue.isOverdueOn(IsoDate.parse("2026-03-16")))
        // 已完成不逾期；无截止不逾期
        assertFalse(overdue.copy(completed = true).isOverdueOn(IsoDate.parse("2026-03-16")))
        assertFalse(testTodo(name = "无截止").isOverdueOn(IsoDate.parse("2026-03-16")))
    }
}
