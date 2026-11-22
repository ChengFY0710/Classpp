package com.fangyi.classpp.data.model

/**
 * 节表变更后的调课目标口径（纯函数，「课程时间方案」确认出口与单测共用）。
 *
 * 调课目标只引用**节次下标**：纯时长/单节开始时间/节数增减不动下标语义，调课记录
 * 原样保留，越界与否交由校验器把关；只有合并（课程映射被改写、下标整体平移）才按
 * 「起止时刻不变」对齐到新节表——对不齐返回 null 整体拒绝，不让调课悄悄指到别的时段。
 *
 * 此前对**任何**节表变更都做时间匹配：改「每节课时间」会重算所有节的结束时刻，
 * 旧结束时刻在新表里找不到就整体误拦，导致「只要课表里有调课就改不了每节课时间」。
 */
fun adjustmentsForSlotChange(
    oldSlots: List<TimeSlotDef>,
    newSlots: List<TimeSlotDef>,
    adjustments: List<CourseAdjustment>,
    coursesRewritten: Boolean,
): List<CourseAdjustment>? =
    if (coursesRewritten) remapAdjustmentsByTime(oldSlots, newSlots, adjustments) else adjustments

/**
 * 节表结构变化（合并/删节）时把调课目标按「起止时刻不变」对齐到新节表；任一条对不齐
 * 返回 null（调用方整体拒绝本次节表变更）。
 *
 * 与 [previewSlotMerge] 对课程的口径一致：合并后的新节必须保留原来的开始/结束时刻。
 */
fun remapAdjustmentsByTime(
    oldSlots: List<TimeSlotDef>,
    newSlots: List<TimeSlotDef>,
    adjustments: List<CourseAdjustment>,
): List<CourseAdjustment>? {
    if (oldSlots == newSlots || adjustments.isEmpty()) return adjustments
    return adjustments.map { adjustment ->
        val oldStart = oldSlots.getOrNull(adjustment.toStartSlot - 1) ?: return null
        val oldEnd = oldSlots.getOrNull(adjustment.toStartSlot + adjustment.toSpan - 2) ?: return null
        val newStart = newSlots.indexOfFirst { it.startTime == oldStart.startTime }
        val newEnd = newSlots.indexOfFirst { it.endTime == oldEnd.endTime }
        if (newStart < 0 || newEnd < newStart) return null
        adjustment.copy(
            toStartSlot = newStart + 1,
            toSpan = newEnd - newStart + 1,
        )
    }
}
