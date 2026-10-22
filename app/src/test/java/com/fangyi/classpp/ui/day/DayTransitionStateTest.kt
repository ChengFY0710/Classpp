package com.fangyi.classpp.ui.day

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 02↔04 跟手没有起手阈值：状态机收到第一像素位移就按 DayDragDistance 换算进度。
 * 松手吸附在 02→04 按进度/速度阈值，04→02 则只要下滑产生位移就回 02。
 */
class DayTransitionStateTest {

    private fun state(stage: DayStage) = DayTransitionState(
        initialStage = stage,
        scope = CoroutineScope(Dispatchers.Unconfined),
        onCommit = {},
    )

    @Test
    fun compactFollowsFirstPixelUpward() {
        val state = state(DayStage.Compact)
        val distance = 1_000f

        val consumed = state.dragCollapseBy(deltaY = -1f, distancePx = distance)

        assertEquals(-1f, consumed, 0.001f)
        assertEquals(0.001f, state.collapse, 0.0001f)
    }

    @Test
    fun scheduleFollowsFirstPixelDownward() {
        val state = state(DayStage.Schedule)
        val distance = 1_000f

        val consumed = state.dragCollapseBy(deltaY = 1f, distancePx = distance)

        assertEquals(1f, consumed, 0.001f)
        assertEquals(0.999f, state.collapse, 0.0001f)
    }

    @Test
    fun scheduleUpwardDoesNotMoveCollapse() {
        // 04 的上滑只属于内容 Overscroll；状态机本身也不应该被写。
        val state = state(DayStage.Schedule)

        val consumed = state.dragCollapseBy(deltaY = -1f, distancePx = 1_000f)

        assertEquals(0f, consumed, 0.001f)
        assertEquals(1f, state.collapse, 0.0001f)
    }
}
