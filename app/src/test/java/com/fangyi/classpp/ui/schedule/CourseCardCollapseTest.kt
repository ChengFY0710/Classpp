package com.fangyi.classpp.ui.schedule

import com.fangyi.classpp.ui.theme.ClassppTextTokens
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 卡片高度不足时逐级收起起止时间：
 * 显示教师时完整内容 87dp / 去掉结束时间 71dp / 只留课程名·教师·地点 55dp；
 * 关闭教师时 71/55/39dp；网格行高下限随教师开关（60/44dp，默认字号），
 * 滑条可调下限（触发当前最低高度的倍率）见 CardHeightMinScaleTest。
 */
class CourseCardCollapseTest {

    private val fullWithTeacher = cardContentHeightDp(1f, true, true, true)   // 87
    private val withoutEndWithTeacher = cardContentHeightDp(1f, true, true, false) // 71
    private val fullWithoutTeacher = cardContentHeightDp(1f, false, true, true) // 71
    private val withoutEndWithoutTeacher = cardContentHeightDp(1f, false, true, false) // 55

    @Test
    fun `content heights follow visible rows`() {
        assertEquals(87f, fullWithTeacher, 0.001f)
        assertEquals(71f, withoutEndWithTeacher, 0.001f)
        assertEquals(55f, cardContentHeightDp(1f, true, false, false), 0.001f)

        assertEquals(71f, fullWithoutTeacher, 0.001f)
        assertEquals(55f, withoutEndWithoutTeacher, 0.001f)
        assertEquals(39f, cardContentHeightDp(1f, false, false, false), 0.001f)
    }

    @Test
    fun `content heights scale with font scale`() {
        assertEquals(111f, cardContentHeightDp(1.3f, true, true, true), 0.001f)
        assertEquals(90.2f, cardContentHeightDp(1.3f, true, true, false), 0.001f)
        assertEquals(69.4f, cardContentHeightDp(1.3f, true, false, false), 0.001f)

        assertEquals(90.2f, cardContentHeightDp(1.3f, false, true, true), 0.001f)
        assertEquals(69.4f, cardContentHeightDp(1.3f, false, true, false), 0.001f)
        assertEquals(48.6f, cardContentHeightDp(1.3f, false, false, false), 0.001f)
    }

    @Test
    fun `end time hides first then start time with teacher`() {
        assertEquals(
            CardRowVisibility(showStartTime = true, showEndTime = true),
            resolveCardVisibility(87f, fullWithTeacher, withoutEndWithTeacher),
        )
        assertEquals(
            CardRowVisibility(showStartTime = true, showEndTime = false),
            resolveCardVisibility(86.9f, fullWithTeacher, withoutEndWithTeacher),
        )
        assertEquals(
            CardRowVisibility(showStartTime = true, showEndTime = false),
            resolveCardVisibility(71f, fullWithTeacher, withoutEndWithTeacher),
        )
        assertEquals(
            CardRowVisibility(showStartTime = false, showEndTime = false),
            resolveCardVisibility(70.9f, fullWithTeacher, withoutEndWithTeacher),
        )
    }

    @Test
    fun `end time hides first then start time without teacher`() {
        assertEquals(
            CardRowVisibility(showStartTime = true, showEndTime = true),
            resolveCardVisibility(71f, fullWithoutTeacher, withoutEndWithoutTeacher),
        )
        assertEquals(
            CardRowVisibility(showStartTime = true, showEndTime = false),
            resolveCardVisibility(70.9f, fullWithoutTeacher, withoutEndWithoutTeacher),
        )
        assertEquals(
            CardRowVisibility(showStartTime = true, showEndTime = false),
            resolveCardVisibility(55f, fullWithoutTeacher, withoutEndWithoutTeacher),
        )
        assertEquals(
            CardRowVisibility(showStartTime = false, showEndTime = false),
            resolveCardVisibility(54.9f, fullWithoutTeacher, withoutEndWithoutTeacher),
        )
    }

    @Test
    fun `row height floor keeps name teacher and location visible`() {
        assertEquals(60f, minCardRowHeight(showTeacher = true, fontScale = 1f).value, 0.001f)
        assertEquals(44f, minCardRowHeight(showTeacher = false, fontScale = 1f).value, 0.001f)
        assertEquals(74.4f, minCardRowHeight(showTeacher = true, fontScale = 1.3f).value, 0.001f)
        assertEquals(53.6f, minCardRowHeight(showTeacher = false, fontScale = 1.3f).value, 0.001f)
    }

    @Test
    fun `final floor allocates one line for name and location`() {
        val cellPadding = (CellPaddingTop + CellPadding).value
        val nameLine = ClassppTextTokens.compactCardTitle.lineHeight.value
        val locationLine = ClassppTextTokens.courseCardLocation.lineHeight.value
        listOf(true, false).forEach { showTeacher ->
            val cardHeight = minCardRowHeight(showTeacher, fontScale = 1f).value - cellPadding
            val bodyHeight = cardContentHeightDp(
                fontScale = 1f,
                showTeacher = showTeacher,
                showStartTime = false,
                showEndTime = false,
            )
            assertEquals(bodyHeight, cardHeight, 0.001f)
            val fixed = bodyHeight - nameLine - locationLine
            assertEquals(
                CardLines(1, 1),
                allocateCardLines(
                    budgetPx = cardHeight - fixed,
                    nameLinePx = nameLine,
                    locationLinePx = locationLine,
                    wantedName = 6,
                    wantedLocation = 6,
                ),
            )
        }
    }

    @Test
    fun `grid row heights clamp to the content floor`() {
        // 30 分钟节 × 0.5 倍远低于内容下限 → 行高仍为 60/44dp
        assertEquals(
            60f,
            slotRowHeight(30, 5, 0.5f, minCardRowHeight(true, fontScale = 1f)).value,
            0.001f,
        )
        assertEquals(
            44f,
            slotRowHeight(30, 5, 0.5f, minCardRowHeight(false, fontScale = 1f)).value,
            0.001f,
        )
    }

    @Test
    fun `add card content heights follow visible time rows`() {
        assertEquals(68f, addCardContentHeightDp(1f, true, true), 0.001f)
        assertEquals(52f, addCardContentHeightDp(1f, true, false), 0.001f)
        assertEquals(36f, addCardContentHeightDp(1f, false, false), 0.001f)
        // 字体放大只影响时间行，加号与内边距不变
        assertEquals(77.6f, addCardContentHeightDp(1.3f, true, true), 0.001f)
        assertEquals(56.8f, addCardContentHeightDp(1.3f, true, false), 0.001f)
        assertEquals(36f, addCardContentHeightDp(1.3f, false, false), 0.001f)
    }

    @Test
    fun `add card uses the same collapse order`() {
        val full = addCardContentHeightDp(1f, true, true)
        val withoutEnd = addCardContentHeightDp(1f, true, false)
        assertEquals(CardRowVisibility(true, true), resolveCardVisibility(68f, full, withoutEnd))
        assertEquals(CardRowVisibility(true, false), resolveCardVisibility(67.9f, full, withoutEnd))
        assertEquals(CardRowVisibility(true, false), resolveCardVisibility(52f, full, withoutEnd))
        assertEquals(CardRowVisibility(false, false), resolveCardVisibility(51.9f, full, withoutEnd))
        // 网格行高下限下：显示教师 55dp 仍留开始时间；关闭教师 39dp 只剩加号
        assertEquals(CardRowVisibility(true, false), resolveCardVisibility(55f, full, withoutEnd))
        assertEquals(CardRowVisibility(false, false), resolveCardVisibility(39f, full, withoutEnd))
    }
}
