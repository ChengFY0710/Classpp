package com.fangyi.classpp.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视差状态数学链路的 JVM 单测。架构是两段式：[TiltParallaxState.consume] 由传感器
 * 回调调用、只更新目标；[TiltParallaxState.pursueStep] 由帧循环调用、逐帧把渲染值
 * 追向目标（测试用固定 dt 模拟 120Hz 渲染帧）。方向断言对应实现注释里的约定：
 * 横轴取反（右倾 → 图左移）、纵轴取正（平放抬顶边 → 图上浮）。
 */
class TiltParallaxTest {

    /** 按固定帧长驱动追踪到收敛（600 帧封顶，τ=0.2s 实际约百帧内收敛）。 */
    private fun TiltParallaxState.settle(dt: Float = 1f / 120f) {
        var frames = 0
        while (pursueStep(dt)) {
            if (++frames > 600) break
        }
    }

    /** 恒定姿态（含饱和姿态）不出位移：首帧基准定格当前姿态，此后采样与基准重合。 */
    @Test
    fun constantPostureProducesNoShift() {
        // 竖持：纵分量天然饱和（≈ -1），基准定格后纵向位移应为 0 而非钉死在饱和区
        TiltParallaxState().let { state ->
            repeat(100) { state.consume(0f, -9.6f) }
            state.settle()
            assertEquals(0f, state.shiftX, 0.001f)
            assertEquals(0f, state.shiftY, 0.001f)
        }
        // 横滚饱和姿态同理（换新状态：上一姿态的基准不带入）
        TiltParallaxState().let { state ->
            repeat(100) { state.consume(9.81f, 0f) }
            state.settle()
            assertEquals(0f, state.shiftX, 0.001f)
            assertEquals(0f, state.shiftY, 0.001f)
        }
    }

    /** 从静止姿态起倾斜：横轴反向响应，随后基准缓慢回中、位移趋零（气泡趋平）。 */
    @Test
    fun horizontalTiltRespondsOppositeThenRecenters() {
        val state = TiltParallaxState()
        repeat(5) { state.consume(0f, 0f) } // 平放静止，基准归零
        state.settle()
        // 右缘下沉（gx > 0）：远处的图向左平移 → shiftX < 0
        repeat(5) { state.consume(2f, 0f) }
        state.settle()
        assertTrue("右倾应左移，实际 ${state.shiftX}", state.shiftX < -0.3f)
        // 保持该姿态足够久：基准追平姿态，位移回中趋零
        repeat(400) { state.consume(2f, 0f) }
        state.settle()
        assertTrue("久持应回中，实际 ${state.shiftX}", kotlin.math.abs(state.shiftX) < 0.05f)
    }

    /** 纵轴取正：平放抬顶边（gy 负向增大）图上浮（shiftY < 0），饱和姿态封顶。 */
    @Test
    fun verticalTiltLiftsImageTowardRaisedEdge() {
        val state = TiltParallaxState()
        repeat(5) { state.consume(0f, 0f) }
        // 抬顶边 30°：gy = -G·sin30° = -4.9，归一化 -0.5 恰好饱和 → 上浮满量 -1
        repeat(5) { state.consume(0f, -4.905f) }
        state.settle()
        assertTrue("抬顶边应上浮，实际 ${state.shiftY}", state.shiftY < -0.9f)
    }

    /**
     * 追踪顺滑性（防台阶感回归）：一次目标更新后逐帧驱动，渲染值必须连续渐进、
     * 不允许一步跳到目标——这正是传感器事件直接写渲染值会违反的性质。
     */
    @Test
    fun pursuitAdvancesGraduallyWithoutJumpingToTarget() {
        val state = TiltParallaxState()
        repeat(5) { state.consume(0f, 0f) }
        state.settle()
        state.consume(2f, 0f) // 目标 ≈ -0.363（右倾左移）
        val samples = mutableListOf(state.shiftX)
        while (state.pursueStep(1f / 120f)) samples.add(state.shiftX)
        samples.add(state.shiftX)
        // 收敛贴合目标（无残差台阶），全程单调逼近、无过冲
        assertEquals(-0.363f, state.shiftX, 0.01f)
        assertTrue(samples.zipWithNext().all { (a, b) -> b <= a })
        assertTrue(samples.none { it < -0.363f })
    }

    /** 噪声吸收：静止后传感器量级的微抖，收敛值是亚像素级微移，不出可见画。 */
    @Test
    fun tinyWobbleStaysSubPixel() {
        val state = TiltParallaxState()
        repeat(5) { state.consume(0f, 0f) }
        state.settle()
        state.consume(0.05f, 0.03f)
        state.settle()
        assertTrue(
            "微抖应被吸收，实际 (${state.shiftX}, ${state.shiftY})",
            kotlin.math.abs(state.shiftX) < 0.001f && kotlin.math.abs(state.shiftY) < 0.001f,
        )
    }

    /** 暂停定格：注销瞬间画面停在原地（残余追踪也不动）；重挂后按新姿态重新定基准，
     *  旧位移经追踪平滑归位——浮层打开时背景静止不跳变。 */
    @Test
    fun pauseHoldsPositionAndRebaselinesOnNextStart() {
        val state = TiltParallaxState()
        repeat(5) { state.consume(0f, 0f) } // 平放静止，基准归零
        state.settle()
        repeat(5) { state.consume(2f, -4.905f) } // 倾斜出位移
        state.settle()
        state.pause()
        val frozen = state.shiftX
        assertTrue("定格应保留位移，实际 $frozen", frozen < -0.3f)
        while (state.pursueStep(1f / 120f)) { /* 在途目标已贴合当前位置，追到收敛仍原地 */ }
        assertEquals(frozen, state.shiftX, 0f)
        // 重挂后竖持静止：基准按竖持重新定格，旧横滚位移经追踪平滑归位归零
        repeat(5) { state.consume(0f, -9.6f) }
        state.settle()
        assertEquals(0f, state.shiftX, 0.001f)
        assertEquals(0f, state.shiftY, 0.001f)
    }

    /** 关开关归中：目标设回 0，逐帧追踪平滑滑回中心并精确贴合，不留残差。 */
    @Test
    fun glideHomeReturnsShiftToZero() {
        val state = TiltParallaxState()
        repeat(5) { state.consume(0f, 0f) } // 平放静止，基准归零
        state.settle()
        repeat(5) { state.consume(2f, -4.905f) } // 倾斜出位移
        state.settle()
        state.retargetHome()
        var frames = 0
        while (state.pursueStep(1f / 120f)) {
            if (++frames > 600) break
        }
        assertEquals(0f, state.shiftX, 0f)
        assertEquals(0f, state.shiftY, 0f)
    }
}
