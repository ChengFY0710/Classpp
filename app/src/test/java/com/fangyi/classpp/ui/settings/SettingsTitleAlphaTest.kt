package com.fangyi.classpp.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 设置顶栏标题渐显（纯 JVM）：有效位移在 100dp 前完全隐藏，100dp→150dp 用 Hermite
 * smoothstep 渐显，150dp 及之后完全显现；回滚对称消失。
 * 有效位移 = 滚动值 − 过滚动原始偏移（见 SettingsUi 的 rememberSettingsTitleAlpha）。
 */
class SettingsTitleAlphaTest {

    // 生产窗口：100dp 开始、150dp 完全；测试直接用 px 值，不依赖 Android Density
    private val startPx = 100f
    private val endPx = 150f

    private fun alpha(scrollPx: Float): Float =
        settingsTitleAlpha(scrollPx, startPx, endPx)

    @Test
    fun hiddenBeforeWindowStart() {
        assertEquals(0f, alpha(0f), 0f)
        assertEquals(0f, alpha(99f), 0f)
        // 恰好到起点：仍是全隐，之后才开始出现
        assertEquals(0f, alpha(100f), 0f)
    }

    @Test
    fun smoothstepMidpointIsHalf() {
        // 窗口正中：smoothstep(0.5) = 0.5
        assertEquals(0.5f, alpha(125f), 1e-4f)
    }

    @Test
    fun fullyVisibleAtWindowEndAndBeyond() {
        assertEquals(1f, alpha(150f), 0f)
        assertEquals(1f, alpha(500f), 0f)
    }

    @Test
    fun negativeScrollStaysHidden() {
        // 橡皮筋/边界回弹把有效位移推成负数时仍全隐
        assertEquals(0f, alpha(-24f), 0f)
    }

    @Test
    fun rawOverscrollUpCountsAsContentMovement() {
        // 内容不足一屏：scroll 恒 0，向上过滚动（原始偏移为负）等效于内容多滚了这么多
        assertEquals(0f, alpha(0f - (-100f)), 0f)
        assertEquals(0.5f, alpha(0f - (-125f)), 1e-4f)
        assertEquals(1f, alpha(0f - (-150f)), 0f)
        // 向下过滚动（原始偏移为正）抵消滚动，不会让标题提早出现
        assertEquals(0f, alpha(0f - 40f), 0f)
    }

    @Test
    fun nonPositiveWindowBecomesStep() {
        assertEquals(0f, settingsTitleAlpha(99f, startPx, startPx), 0f)
        assertEquals(1f, settingsTitleAlpha(100f, startPx, startPx), 0f)
        assertEquals(1f, settingsTitleAlpha(200f, startPx, 91f), 0f)
    }
}
