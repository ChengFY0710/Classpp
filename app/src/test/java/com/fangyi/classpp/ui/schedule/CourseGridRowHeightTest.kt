package com.fangyi.classpp.ui.schedule

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行高两层逻辑的纯 dp 数值断言（不涉及组合，JVM 单测可跑）：
 * - 基准行高随列数变：5 天视图用基础行高，7 天视图每行再加一个可调增量（[gridRowHeight]）；
 * - 实际行高随该节时长线性伸缩、再乘个性化倍率，超短节次钳到下限（[slotRowHeight]）。
 * 页高、每行高度、跨节卡高度全部由这两者派生。
 */
class CourseGridRowHeightTest {

    // ——— 基准行高（100 分钟节、1.0 倍） ———

    @Test
    fun `five day view uses the base row height`() {
        assertEquals(GridRowHeight, gridRowHeight(daysPerWeek = 5))
    }

    @Test
    fun `seven day view adds exactly the configurable extra`() {
        assertEquals(GridRowHeight + SevenDayRowExtraHeight, gridRowHeight(daysPerWeek = 7))
        assertEquals(
            "7 日视图应比 5 日视图高 SevenDayRowExtraHeight",
            SevenDayRowExtraHeight,
            gridRowHeight(7) - gridRowHeight(5),
        )
        // 需求指定的默认差值与变量取值一致（改变量即改这里的期望值来源，不会各写一份数字）
        assertEquals(10.dp, SevenDayRowExtraHeight)
    }

    /** 增量本身仍要参与"卡片能不能装下内容"的余量，不能是 0 或负值 */
    @Test
    fun `extra height stays positive`() {
        assertTrue(SevenDayRowExtraHeight.value > 0f)
        assertTrue(gridRowHeight(7) > gridRowHeight(5))
    }

    /** 页高按各行高之和算：7 天视图整页相应变高，否则卡片会被页尾截断 */
    @Test
    fun `page height grows with the row height`() {
        val slots = 5
        val fiveDayPage = gridRowHeight(5) * slots
        val sevenDayPage = gridRowHeight(7) * slots

        assertEquals(SevenDayRowExtraHeight * slots, sevenDayPage - fiveDayPage)
    }

    // ——— 实际行高：随时长伸缩 × 倍率（slotRowHeight） ———

    /** 锚点：100 分钟节、1.0 倍 = 基准行高（需求指定 100min → 150dp / 160dp，须无浮点误差） */
    @Test
    fun `anchor duration at default scale equals the base row height`() {
        assertEquals(GridRowHeight, slotRowHeight(BaseSlotMinutes, daysPerWeek = 5, scale = 1f))
        assertEquals(gridRowHeight(7), slotRowHeight(BaseSlotMinutes, daysPerWeek = 7, scale = 1f))
    }

    /** 线性：时长减半行高减半（50 分钟 → 75dp / 80dp），固定行高的旧逻辑会在这里失败 */
    @Test
    fun `row height scales linearly with duration`() {
        assertEquals(75.dp, slotRowHeight(50, daysPerWeek = 5, scale = 1f))
        assertEquals(80.dp, slotRowHeight(50, daysPerWeek = 7, scale = 1f))
    }

    /** 个性化倍率整体缩放：1.2 倍的 100 分钟节 ≈ 180dp（旧版 dp 偏好迁移到 1.2 倍后渲染一致） */
    @Test
    fun `scale multiplies the proportional height`() {
        assertEquals(
            180f,
            slotRowHeight(BaseSlotMinutes, daysPerWeek = 5, scale = 1.2f).value,
            0.01f,
        )
        assertEquals(
            gridRowHeight(7).value * 1.2f,
            slotRowHeight(BaseSlotMinutes, daysPerWeek = 7, scale = 1.2f).value,
            0.01f,
        )
    }

    /** 超短节次钳到下限，保证课程名等基本内容可读；正常时长不受影响 */
    @Test
    fun `very short durations clamp to the minimum row height`() {
        assertEquals(MinRowHeight, slotRowHeight(10, daysPerWeek = 5, scale = 1f))
        assertEquals(MinRowHeight, slotRowHeight(10, daysPerWeek = 7, scale = 0.5f))
        assertTrue(slotRowHeight(BaseSlotMinutes, daysPerWeek = 5, scale = 1f) > MinRowHeight)
    }
}
