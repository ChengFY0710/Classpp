package com.fangyi.classpp.ui.motion

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进场果冻曲线的守卫（直接采样 [popupEnterJelly] 的 keyframes）：
 * - 从起始缩放 0.3 出发，先穿过目标 1.0，再冲过峰值（约 1.05），最后精确落回 1.0；
 * - 峰值幅度 ≈ 1.05（`PopupJellyPeakScale`，毛玻璃快照扩采按它推算——过冲变大必须同步常量）；
 * - 峰值约 272ms；回程约 208ms，总时长 480ms；
 * - 峰值之后单调回落（无二次下冲），落点零速。
 */
class PopupEnterCurveTest {

    private val startScale = 0.3f

    private val animation = TargetBasedAnimation(
        animationSpec = popupEnterJelly(startScale),
        typeConverter = Float.VectorConverter,
        initialValue = startScale,
        targetValue = 1f,
    )

    private val durationMs = animation.durationNanos / 1_000_000L

    private fun valueAt(ms: Long): Float = animation.getValueFromNanos(ms * 1_000_000L)

    private fun peakAt(): Long = (0..durationMs).maxBy { valueAt(it) }

    @Test
    fun startsAtStartScaleAndEndsExactlyAtTarget() {
        assertEquals(startScale, valueAt(0), 1e-6f)
        assertEquals(1f, animation.getValueFromNanos(animation.durationNanos), 0f)
    }

    @Test
    fun crossesTargetBeforeReachingThePeak() {
        val firstCross = (0..durationMs).first { valueAt(it) >= 1f }
        assertTrue(
            "firstCross=${firstCross}ms peakAt=${peakAt()}ms",
            firstCross < peakAt(),
        )
    }

    @Test
    fun overshootMatchesTheSnapshotOverscanConstant() {
        val peak = valueAt(peakAt())
        assertEquals(1.05f, peak, 0.02f)
    }

    @Test
    fun peakArrivesAroundThreeHundredMilliseconds() {
        assertTrue("peakAt=${peakAt()}ms", peakAt() in 250L..380L)
    }

    @Test
    fun settlesInAboutHalfASecond() {
        assertTrue("duration=${durationMs}ms", durationMs in 400L..650L)
    }

    @Test
    fun returnLegIsMonotonicAfterThePeak() {
        val peak = peakAt()
        var previous = valueAt(peak)
        for (ms in (peak + 1)..durationMs) {
            val current = valueAt(ms)
            assertTrue(
                "non-monotonic at ${ms}ms: $previous -> $current",
                current <= previous + 1e-4f,
            )
            previous = current
        }
    }
}
