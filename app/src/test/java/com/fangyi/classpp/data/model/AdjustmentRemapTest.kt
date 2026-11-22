package com.fangyi.classpp.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 节表变更后的调课目标口径：纯时长/节数变化不动记录；合并按「起止时刻不变」重映射，
 * 对不齐整体拒绝。此前对任何变更都做时间匹配，改「每节课时间」必被误拦。
 */
class AdjustmentRemapTest {

    private val smallSlots = listOf(
        TimeSlotDef("8:00", "8:45"),
        TimeSlotDef("8:55", "9:40"),
        TimeSlotDef("10:10", "10:55"),
        TimeSlotDef("11:05", "11:50"),
    )

    private fun mergedSlots() = listOf(
        TimeSlotDef("8:00", "9:40"),
        TimeSlotDef("10:10", "11:50"),
    )

    private fun adjustment(
        toStartSlot: Int = 3,
        toSpan: Int = 2,
    ) = CourseAdjustment(
        id = "a1",
        courseId = "c1",
        fromWeek = 1,
        toWeek = 2,
        toDayOfWeek = 4,
        toStartSlot = toStartSlot,
        toSpan = toSpan,
    )

    @Test
    fun `pure duration change keeps adjustment records untouched`() {
        // 改「每节课时间」只重算各节时刻，下标语义不变：调课记录原样保留，
        // 越界与否交给整表校验——此前这里会被时间匹配误拦（旧结束时刻找不到即拒绝）
        val durationChanged = slotsWithDuration(smallSlots, 55)!!
        val original = listOf(adjustment(toStartSlot = 3, toSpan = 1))

        val result = adjustmentsForSlotChange(
            oldSlots = smallSlots,
            newSlots = durationChanged,
            adjustments = original,
            coursesRewritten = false,
        )

        assertSame(original, result)
    }

    @Test
    fun `merge remaps an alignable target by start and end times`() {
        // 目标第 3-4 节（10:10~11:50）：合并后精确落在新的第 2 节上
        val result = adjustmentsForSlotChange(
            oldSlots = smallSlots,
            newSlots = mergedSlots(),
            adjustments = listOf(adjustment(toStartSlot = 3, toSpan = 2)),
            coursesRewritten = true,
        )

        val remapped = result!!.single()
        assertEquals(2, remapped.toStartSlot)
        assertEquals(1, remapped.toSpan)
    }

    @Test
    fun `merge rejects a target falling inside a merged pair`() {
        // 目标起点在合并对的次节（8:55）：新表里没有这一时刻，无法精确对齐 → 整体拒绝
        val result = adjustmentsForSlotChange(
            oldSlots = smallSlots,
            newSlots = mergedSlots(),
            adjustments = listOf(adjustment(toStartSlot = 2, toSpan = 1)),
            coursesRewritten = true,
        )

        assertNull(result)
    }

    @Test
    fun `merge rejects an out of range legacy target`() {
        val result = adjustmentsForSlotChange(
            oldSlots = smallSlots,
            newSlots = mergedSlots(),
            adjustments = listOf(adjustment(toStartSlot = 99, toSpan = 2)),
            coursesRewritten = true,
        )

        assertNull(result)
    }

    @Test
    fun `identical slot tables keep the records as is`() {
        val original = listOf(adjustment())
        val result = remapAdjustmentsByTime(smallSlots, smallSlots, original)
        assertSame(original, result)
    }

    @Test
    fun `empty adjustments pass through regardless of the change`() {
        // 没有调课就不需要重映射，任何节表变更都不该被拦下
        val result = adjustmentsForSlotChange(
            oldSlots = smallSlots,
            newSlots = mergedSlots(),
            adjustments = emptyList(),
            coursesRewritten = true,
        )
        assertEquals(emptyList<CourseAdjustment>(), result)
    }
}
