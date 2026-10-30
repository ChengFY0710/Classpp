package com.fangyi.classpp.ui.schedule

import com.fangyi.classpp.data.model.CourseColor
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.DEFAULT_SLOTS
import com.fangyi.classpp.data.model.DEFAULT_TIME_SCHEME_ID
import com.fangyi.classpp.data.model.TimeSlotDef
import com.fangyi.classpp.data.model.WeekPattern
import com.fangyi.classpp.data.model.previewSlotMerge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「合并两小节为一大节」按钮的可点判定：合并预览放行 + 内置「默认」方案不支持合并 */
class TimeSchemeMergeGateTest {

    private val smallSlots = listOf(
        TimeSlotDef("8:00", "8:45"),
        TimeSlotDef("8:55", "9:40"),
    )

    private fun course(span: Int) = CourseEntry(
        id = "c",
        name = "c",
        teacher = "",
        location = "",
        dayOfWeek = 1,
        startSlot = 1,
        span = span,
        weeks = WeekPattern.everyWeek(16),
        color = CourseColor.Green,
    )

    /** 有一门占用 2 节的课 → 合并预览本身放行 */
    private val mergeable = previewSlotMerge(smallSlots, listOf(course(span = 2)), 5)

    /** 空课表 → 合并预览阻断（没有课程占用 ≥2 节） */
    private val notMergeable = previewSlotMerge(smallSlots, emptyList(), 5)

    @Test
    fun `custom scheme enables merge when plan allows`() {
        // null = 「自定义」
        assertTrue(canMergeTimeScheme(mergeable, selectedSchemeId = null))
    }

    @Test
    fun `saved scheme enables merge when plan allows`() {
        assertTrue(canMergeTimeScheme(mergeable, selectedSchemeId = "saved-scheme"))
    }

    @Test
    fun `default scheme never supports merge`() {
        assertFalse(canMergeTimeScheme(mergeable, selectedSchemeId = DEFAULT_TIME_SCHEME_ID))
    }

    @Test
    fun `plan without multi slot courses cannot merge even under custom`() {
        assertFalse(canMergeTimeScheme(notMergeable, selectedSchemeId = null))
    }

    @Test
    fun `default scheme stays disabled when plan is not mergeable`() {
        assertFalse(canMergeTimeScheme(notMergeable, selectedSchemeId = DEFAULT_TIME_SCHEME_ID))
    }

    @Test
    fun `reported repro default slots with no courses stays grey`() {
        // 实测问题：默认 5 节 × 100 分钟 + 没有任何课程 → 按钮曾可点并崩溃。
        // 现在两条门控都拦：没有课程占用 ≥2 节，且选中的是「默认」方案
        val plan = previewSlotMerge(DEFAULT_SLOTS, emptyList(), 5)

        assertFalse(plan.hasMultiSlotCourse)
        assertFalse(canMergeTimeScheme(plan, selectedSchemeId = DEFAULT_TIME_SCHEME_ID))
        // 即使切到「自定义」，空课表仍然没有可重映射的课程
        assertFalse(canMergeTimeScheme(plan, selectedSchemeId = null))
    }

    @Test
    fun `default schedule with multi slot courses is blocked only by the default option`() {
        // 默认作息里 1-2 节可配对（100+30+100 = 230 分钟）——预览本身可合并，
        // 但「默认」方案不支持合并；切到「自定义」后才放行
        val plan = previewSlotMerge(DEFAULT_SLOTS, listOf(course(span = 2)), 5)

        assertTrue(plan.canMerge)
        assertFalse(canMergeTimeScheme(plan, selectedSchemeId = DEFAULT_TIME_SCHEME_ID))
        assertTrue(canMergeTimeScheme(plan, selectedSchemeId = null))
    }
}
