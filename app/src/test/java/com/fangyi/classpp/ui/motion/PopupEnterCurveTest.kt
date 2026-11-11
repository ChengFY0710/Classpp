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
 * - 默认时间轴（全部玻璃弹层共用）：峰值 200ms、回程 240ms（总 440ms）；
 * - 回程拉长不影响峰值：同一 peakMillis 下改 returnMillis，峰值时刻与峰值幅度都不动；
 * - 峰值之后单调回落（无二次下冲），落点精确 1.0。
 */
class PopupEnterCurveTest {

    private val startScale = 0.3f

    private fun curve(timing: PopupEnterTiming) = TargetBasedAnimation(
        animationSpec = popupEnterJelly(startScale, timing),
        typeConverter = Float.VectorConverter,
        initialValue = startScale,
        targetValue = 1f,
    )

    private fun TargetBasedAnimation<Float, *>.durationMs(): Long = durationNanos / 1_000_000L

    private fun TargetBasedAnimation<Float, *>.valueAt(ms: Long): Float =
        getValueFromNanos(ms * 1_000_000L)

    private fun TargetBasedAnimation<Float, *>.peakAt(): Long =
        (0..durationMs()).maxBy { valueAt(it) }

    private val animation = curve(PopupEnterDefaultTiming)

    private val durationMs = animation.durationMs()

    private fun valueAt(ms: Long): Float = animation.valueAt(ms)

    private fun peakAt(): Long = animation.peakAt()

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
    fun peakLandsOnTheConfiguredPeakMillis() {
        assertEquals(PopupEnterDefaultTiming.peakMillis.toLong(), peakAt())
    }

    @Test
    fun lastsTheSumOfPeakAndReturn() {
        assertEquals(
            (PopupEnterDefaultTiming.peakMillis + PopupEnterDefaultTiming.returnMillis).toLong(),
            durationMs,
        )
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

    @Test
    fun longerReturnLegKeepsThePeakMomentAndOvershoot() {
        val peakMillis = PopupEnterDefaultTiming.peakMillis
        val shortReturn = curve(PopupEnterTiming(peakMillis = peakMillis, returnMillis = 136))
        val longReturn = curve(PopupEnterDefaultTiming)

        assertEquals(peakMillis.toLong(), shortReturn.peakAt())
        assertEquals(peakMillis.toLong(), longReturn.peakAt())
        assertEquals(
            shortReturn.valueAt(shortReturn.peakAt()),
            longReturn.valueAt(longReturn.peakAt()),
            1e-6f,
        )
        assertEquals((peakMillis + 136).toLong(), shortReturn.durationMs())
        assertEquals(
            (peakMillis + PopupEnterDefaultTiming.returnMillis).toLong(),
            longReturn.durationMs(),
        )
    }

    @Test
    fun longerReturnLegNeverDipsBelowTarget() {
        for (ms in (peakAt() + 1)..durationMs) {
            assertTrue("dipped below target at ${ms}ms: ${valueAt(ms)}", valueAt(ms) >= 1f)
        }
    }
}
