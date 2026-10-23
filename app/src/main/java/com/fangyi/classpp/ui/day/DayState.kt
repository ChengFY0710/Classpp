package com.fangyi.classpp.ui.day

import com.fangyi.classpp.data.model.IsoDate

/**
 * 日程页台历的真实停留状态（设计稿 01 / 02 / 04）。
 *
 * 设计稿 03「台历收起隐藏态」不是可停留状态，只是 [Schedule] 与 [Compact] 之间的
 * 转场中间帧；页面只在这三个值之间提交，转场进度另行用 open/collapse 两条 0..1 表示。
 */
internal enum class DayStage {
    /** 01 台历页：全屏背景图 + 上下磨砂 + 日期组。 */
    Calendar,

    /** 02 台历缩小态：顶部图片卡 + 白底内容卡。 */
    Compact,

    /** 04 台历完全收起态：顶栏 + 白底内容卡，台历不可见。 */
    Schedule,
}

/**
 * 日程页持久化记录：哪天开启过（[openedDay]）+ 离开时所处的状态（[stage]）。
 *
 * JSON/SharedPreferences 里只存日历日期与枚举名；[restoredStage] 是进入页面时唯一的
 * 恢复口径——同日才恢复离开态，跨天/无记录一律回 01。把这段纯逻辑放在这里而不是
 * UI 里，单测可以直接覆盖跨天重置。
 */
internal data class DayOpenRecord(
    val openedDay: IsoDate?,
    val stage: DayStage,
) {
    /**
     * 进入日程页时应恢复的状态：只有「记录日期 == 今天」才恢复 Compact/Schedule；
     * 否则（跨天、无记录、损坏记录）回到 01。
     */
    fun restoredStage(today: IsoDate): DayStage =
        if (openedDay == today && stage != DayStage.Calendar) stage else DayStage.Calendar

    companion object {
        val Empty = DayOpenRecord(openedDay = null, stage = DayStage.Calendar)
    }
}

/**
 * 松手吸附方向：true = 往该段终点（01→02 的 02、02→04 的 04）吸附，
 * false = 回弹到该段起点（01 或 02）。
 *
 * 规则与设计稿提示词一致：进度过 [DaySnapThreshold] 或沿前进方向的速度超过
 * fling 阈值即判越过；速度方向优先于进度（快速一甩即使位移不大也直接越过）。
 * 反向速度优先判回弹。
 */
internal fun snapForward(
    progress: Float,
    velocityY: Float,
    flingThresholdPx: Float,
): Boolean = when {
    velocityY < -flingThresholdPx -> true
    velocityY > flingThresholdPx -> false
    else -> progress >= DaySnapThreshold
}

/**
 * 02↔04 段吸附到 04 的判定。不能直接复用 [snapForward] 的绝对 progress：
 * `collapse` 固定以 02 为 0、04 为 1，因此以手势出发态 [startStage] 区分：
 * - 从 02 出发（Compact/Calendar）：按 [snapForward] 的进度/速度阈值判是否去 04；
 * - 从 04 出发（Schedule）：把下滑进度看作 `1 - collapse`，同样过
 *   [DaySnapThreshold] 才判回 02，否则回弹 04。判据只看松手时的最终位置——
 *   下滑越过阈值后又上滑回来、最终没越过的，松手回 04，不会因为途中越过就锁定；
 *   速度方向仍优先（快速下滑直接回 02，快速上甩直接回 04）。
 * 返回 true = 吸附到 04，false = 吸附到 02。
 */
internal fun snapCollapseForward(
    startStage: DayStage,
    collapse: Float,
    velocityY: Float,
    flingThresholdPx: Float,
): Boolean = if (startStage == DayStage.Schedule) {
    when {
        velocityY > flingThresholdPx -> false
        velocityY < -flingThresholdPx -> true
        // 向 02 的进度没到阈值（collapse 仍在阈值位置之上）：松手回弹 04。
        else -> collapse > 1f - DaySnapThreshold
    }
} else {
    snapForward(collapse, velocityY, flingThresholdPx)
}

/**
 * 04 手势起手方向锁：累计位移超过 [slopPx] 才锁定方向，返回 1 = 下滑
 * （驱动 collapse）、-1 = 上滑（只做过滚动）、0 = 还未定。
 *
 * 快速上滑的起手常带一点向下抖动；不设这个锁，第一段正向 dy 就会被当成
 * 04→02 下滑。锁只决定这次手势由谁驱动：上滑起手始终只做过滚动；下滑锁定后
 * 反向拖动仍跟手把 collapse 推回 04，但是否回 02 只在松手时按最终位置判定。
 */
internal fun scheduleCollapseDirection(accumulatedDy: Float, slopPx: Float): Int = when {
    accumulatedDy >= slopPx -> 1
    accumulatedDy <= -slopPx -> -1
    else -> 0
}

/** 吸附进度阈值（设计稿提示词建议 0.4）。 */
internal const val DaySnapThreshold = 0.4f

/**
 * 根级 pointerInput 是否应该接管本次手势并推进 collapse。
 *
 * - 02（Compact）：内容还是空占位、列表上下都不可滚，图片卡/内容卡任意起手都跟手；
 * - 04（Schedule）：列表在顶部（不能再向后滚）时，任何下滑都由根级从第一像素推进
 *   collapse；上滑起手不写 collapse，放给内容 Overscroll（下滑锁定后的回拉仍跟手）；
 *   列表已经下滚时则让给列表滚动；
 * - 01（Calendar）：由整屏 draggable 负责 01→02。
 */
internal fun shouldDriveCollapse(
    stage: DayStage,
    canScrollForward: Boolean,
    canScrollBackward: Boolean,
): Boolean = when (stage) {
    DayStage.Compact -> !canScrollForward && !canScrollBackward
    DayStage.Schedule -> !canScrollBackward
    DayStage.Calendar -> false
}
