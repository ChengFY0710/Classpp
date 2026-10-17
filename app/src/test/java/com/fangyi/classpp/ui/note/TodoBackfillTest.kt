package com.fangyi.classpp.ui.note

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.RepeatUnit
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.testTodo
import com.fangyi.classpp.ui.components.DateSelection
import org.junit.Assert.assertEquals
import org.junit.Test

/** 编辑回填（[toDateSelection]）的口径测试：相对档位折叠不得吃掉重复语义 */
class TodoBackfillTest {

    private val daily = RepeatRule(type = RepeatType.Daily)

    private val customWeekly = RepeatRule(
        type = RepeatType.Custom,
        unit = RepeatUnit.Week,
        interval = 2,
        marks = setOf(1),
    )

    @Test
    fun `null and empty dates backfill to none`() {
        assertEquals(DateSelection.None, (null as Todo?).toDateSelection())
        assertEquals(DateSelection.None, testTodo().toDateSelection())
    }

    @Test
    fun `no-repeat dates fold to today tomorrow presets`() {
        val today = IsoDate.today()
        assertEquals(DateSelection.Today, testTodo(dates = listOf(today)).toDateSelection())
        assertEquals(DateSelection.Tomorrow, testTodo(dates = listOf(today + 1)).toDateSelection())
        // 其它日期回填自定义（重复为默认无）
        assertEquals(
            DateSelection.Custom(today + 5, RepeatRule()),
            testTodo(dates = listOf(today + 5)).toDateSelection(),
        )
    }

    @Test
    fun `repeat rule survives backfill even when anchored today or tomorrow`() {
        val today = IsoDate.today()
        // 回归：锚在今天/明天的重复待办编辑回填不得折叠成相对档位（否则确认后落成无重复 + 具体日期）
        assertEquals(
            DateSelection.Custom(today, daily),
            testTodo(dates = listOf(today), repeat = daily).toDateSelection(),
        )
        assertEquals(
            DateSelection.Custom(today + 1, customWeekly),
            testTodo(dates = listOf(today + 1), repeat = customWeekly).toDateSelection(),
        )
    }

    @Test
    fun `multi-date import backfills first date`() {
        val today = IsoDate.today()
        val todo = testTodo(dates = listOf(today, today + 3))
        assertEquals(DateSelection.Custom(today, RepeatRule()), todo.toDateSelection())
    }
}
