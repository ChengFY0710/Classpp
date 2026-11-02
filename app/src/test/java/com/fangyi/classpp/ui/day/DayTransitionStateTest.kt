package com.fangyi.classpp.ui.day

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 02↔04 跟手没有起手阈值：状态机收到第一像素位移就按 DayDragDistance 换算进度。
 * 松手吸附在 02→04 按进度/速度阈值；04→02 按松手时的最终进度判阈值，途中
 * 下滑又上滑回来的位移照样写回 collapse。
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
        // 04 仍在端点时上滑只属于内容 Overscroll；状态机本身也不应该被写。
        val state = state(DayStage.Schedule)

        val consumed = state.dragCollapseBy(deltaY = -1f, distancePx = 1_000f)

        assertEquals(0f, consumed, 0.001f)
        assertEquals(1f, state.collapse, 0.0001f)
    }

    @Test
    fun scheduleUpwardPullsCollapseBackAfterDownwardDrag() {
        // 04 下滑后反向拖动照样跟手：阈值只在松手时判，途中不锁定。
        val state = state(DayStage.Schedule)
        val distance = 1_000f

        state.dragCollapseBy(deltaY = 500f, distancePx = distance)
        assertEquals(0.5f, state.collapse, 0.0001f)

        val consumed = state.dragCollapseBy(deltaY = -200f, distancePx = distance)

        assertEquals(-200f, consumed, 0.001f)
        assertEquals(0.7f, state.collapse, 0.0001f)
    }

    @Test
    fun dayAtRestAtStableEndpoints() {
        // 01 / 02 / 04 三个停留态都是冻结层可用的静止态
        assertTrue(isDayAtRest(open = 0f, collapse = 0f, isAnimating = false, deviceCornerAlpha = 0f))
        assertTrue(isDayAtRest(open = 1f, collapse = 0f, isAnimating = false, deviceCornerAlpha = 0f))
        assertTrue(isDayAtRest(open = 1f, collapse = 1f, isAnimating = false, deviceCornerAlpha = 0f))
    }

    @Test
    fun dayNotAtRestDuringDragOrSnap() {
        assertFalse(isDayAtRest(open = 0.5f, collapse = 0f, isAnimating = false, deviceCornerAlpha = 0f))
        assertFalse(isDayAtRest(open = 1f, collapse = 0.5f, isAnimating = false, deviceCornerAlpha = 0f))
        assertFalse(isDayAtRest(open = 0f, collapse = 0f, isAnimating = true, deviceCornerAlpha = 0f))
    }

    @Test
    fun dayNotAtRestWhileCalendarCornerFades() {
        // 01 的收角尾巴仍在动：几何（机身圆角）尚未落定
        assertFalse(isDayAtRest(open = 0f, collapse = 0f, isAnimating = false, deviceCornerAlpha = 0.5f))
    }

    @Test
    fun compactIgnoresCornerAlpha() {
        // 收角尾巴只属于 01；02/04 的几何不读它，不能因此让 02 永久无法冻结
        assertTrue(isDayAtRest(open = 1f, collapse = 0f, isAnimating = false, deviceCornerAlpha = 0.5f))
        assertTrue(isDayAtRest(open = 1f, collapse = 1f, isAnimating = false, deviceCornerAlpha = 1f))
    }
}
