package com.fangyi.classpp.ui.schedule

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行高随列数变：5 天视图用基础行高，7 天视图每行再加一个可调增量
 * （列一窄课名换行更多，卡片需要更长）。页高、每行高度、跨节卡高度全部由
 * [gridRowHeight] 派生，故"7 日视图比 5 日视图多高"只需改 [SevenDayRowExtraHeight]。
 *
 * 这些是纯 dp 数值比较，不涉及组合，可在 JVM 单测里直接断言。
 */
class CourseGridRowHeightTest {

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

    /** 页高按行高 × 节数算：7 天视图整页相应变高，否则卡片会被页尾截断 */
    @Test
    fun `page height grows with the row height`() {
        val slots = 5
        val fiveDayPage = gridRowHeight(5) * slots
        val sevenDayPage = gridRowHeight(7) * slots

        assertEquals(SevenDayRowExtraHeight * slots, sevenDayPage - fiveDayPage)
    }
}
