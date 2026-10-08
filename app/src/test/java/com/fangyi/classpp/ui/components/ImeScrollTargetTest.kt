package com.fangyi.classpp.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键盘弹起后「把正在编辑的输入框带到键盘上方」的换算（[targetScrollForIme]）。
 *
 * 一条主规则 + 一条上界：
 * 1. **下缘对齐**（落点）：把输入框下缘停在键盘上缘上方 [gap] 处；
 * 2. **上缘上界**：推得再高也不能让上缘越过「距可见区顶部 [margin]」那条线——高框放不下
 *    整个卡片时由此接管，表现是上缘贴住可见区顶部、下缘钻进键盘。
 * 取两者较小者：正常框走 1，放不下的高框走 2。
 *
 * 断言用的都是「滚完之后看得见什么」这类不变量，而不是硬编码某个 px 值：
 * - 上缘永远不低于 [margin]（不会被顶栏/屏幕顶切掉）；
 * - 下缘最多推到键盘上方 [gap] 处（该让的空间一定让出来）；
 * - 放得下的框一定整框露在键盘上方；放不下的框上缘贴住可见区顶部；
 * - 已经整个露在键盘上方就不动（不为一点余量来回滚）；
 * - 结果不小于 0（滚动上限由 ScrollState 自己钳，内容尾部余量已保证够滚）。
 */
class ImeScrollTargetTest {

    /** 视口（浮层卡片）高度 */
    private val viewport = 2700

    /** 键盘高度：可见区下缘（键盘上缘）= 2700 − 1008 = 1692 */
    private val ime = 1008

    /** 输入框上缘与可见区顶部的最小余量 */
    private val margin = 36

    /** 输入框下缘与键盘上缘之间要留的间距（12dp @3x） */
    private val gap = 48

    /** 单行输入框高度（SheetTextField 的 60dp @3x 量级） */
    private val fieldHeight = 180

    /** 多行备注框高度（minLines = 3 再长一点） */
    private val noteHeight = 900

    /** 键盘上缘（= 可见区下缘） */
    private val keyboardTop = viewport - ime

    private fun target(
        fieldTop: Int,
        scroll: Int = 0,
        height: Int = fieldHeight,
        gapPx: Int = gap,
    ) = targetScrollForIme(
        fieldTopPx = fieldTop,
        fieldBottomPx = fieldTop + height,
        scrollPx = scroll,
        viewportPx = viewport,
        imeInsetPx = ime,
        marginPx = margin,
        gapPx = gapPx,
    )

    /** 滚完之后输入框在视口里的上/下缘位置 */
    private fun visibleRange(fieldTop: Int, scroll: Int = 0, height: Int = fieldHeight) =
        (fieldTop - scroll) to (fieldTop + height - scroll)

    /** 单行框整个在键盘下方：滚到「下缘贴键盘上方 gap 处」，而不是被顶到最上面 */
    @Test
    fun fieldBelowKeyboardStopsJustAboveKeyboard() {
        val fieldTop = 2000
        val scroll = target(fieldTop)
        val (top, bottom) = visibleRange(fieldTop, scroll)
        assertEquals(keyboardTop - gap, bottom)
        assertTrue("上缘不该被顶到可见区顶部", top > margin)
    }

    /** 单行框被键盘压住：同样只剩「下缘贴键盘上方」这一个落点 */
    @Test
    fun partiallyCoveredFieldStopsJustAboveKeyboard() {
        val fieldTop = 1600 // 下缘 1780 > 1692，被键盘压住
        val (top, bottom) = visibleRange(fieldTop, target(fieldTop))
        assertEquals(keyboardTop - gap, bottom)
        assertTrue(top > margin)
    }

    /** 露着但下缘紧贴键盘（没留间距）：也要往下让出 gap */
    @Test
    fun fieldTouchingKeyboardGetsGap() {
        val fieldTop = keyboardTop - fieldHeight // 下缘正好压在键盘上缘
        val (_, bottom) = visibleRange(fieldTop, target(fieldTop))
        assertEquals(keyboardTop - gap, bottom)
    }

    /** 已经整个露在键盘上方（下缘还留着 gap）：不动 */
    @Test
    fun fullyVisibleFieldDoesNotMove() {
        assertEquals(0, target(800))
        // 当前已滚 400、视口里位置没变：仍不动
        assertEquals(400, target(800 + 400, scroll = 400))
    }

    /** [gap] 是调落点的旋钮：gap 调大 → 往下让得更多、停在更靠上的位置（滚动量更大） */
    @Test
    fun biggerGapStopsTheFieldFurtherFromKeyboard() {
        val fieldTop = 3000 // 用高框：下缘对齐是主导，gap 的差值才体现得出来
        val small = target(fieldTop, height = noteHeight, gapPx = 0)
        val large = target(fieldTop, height = noteHeight, gapPx = 200)
        assertEquals(keyboardTop, visibleRange(fieldTop, small, noteHeight).second)
        assertEquals(keyboardTop - 200, visibleRange(fieldTop, large, noteHeight).second)
        assertTrue("gap 调大后往下让得更多（滚动量更大）", large > small)
        assertEquals(200, large - small)
    }

    /** 高框但可见区放得下：照样下缘贴键盘，整框都露着 */
    @Test
    fun tallFieldStillBottomAlignsWhenItFits() {
        val fieldTop = 2400
        val (top, bottom) = visibleRange(fieldTop, target(fieldTop, height = noteHeight), noteHeight)
        assertEquals(keyboardTop - gap, bottom)
        assertTrue("上缘要留在可见区里", top >= margin)
    }

    /**
     * 高框放不下整框（可见区高度 < 框高 + gap）：上缘仍然留在可见区里、不被推到顶栏处，
     * 下缘允许钻进键盘。实测观感：备注框露出上半截，也好过整框顶到最上面、下半截全被吃掉。
     */
    @Test
    fun tallFieldKeepsItsTopInsideVisibleArea() {
        val fieldTop = 3500
        val (top, bottom) = visibleRange(fieldTop, target(fieldTop, height = noteHeight), noteHeight)
        assertTrue("上缘要留在可见区里（不少于 margin）", top >= margin)
        assertTrue("下缘可以钻进键盘，但不能反过来把上缘顶出去", bottom > top)
    }

    /** 除了「内容起点那个框、已经无处可滚」以外，上缘都不会被推到可见区顶部之上 */
    @Test
    fun topEdgeNeverGoesAboveMargin() {
        listOf(200, 1600, 2400, 3500, 4000).forEach { fieldTop ->
            listOf(fieldHeight, noteHeight).forEach { height ->
                val (top, _) = visibleRange(fieldTop, target(fieldTop, height = height), height)
                assertTrue("fieldTop=$fieldTop height=$height top=$top", top >= margin)
            }
        }
        // 起点框（fieldTop = 0）本来就在可见区顶部，无处可滚：不动即可，别把它推下去
        assertEquals(0, target(0))
    }

    /** 单行框（可见区放得下）：滚完之后下缘一定在键盘上方（要么本来就在，要么刚被让出来） */
    @Test
    fun fittingFieldsAlwaysEndUpAboveKeyboard() {
        listOf(1200, 1600, 2000, 2500).forEach { fieldTop ->
            val scroll = target(fieldTop)
            val (top, bottom) = visibleRange(fieldTop, scroll)
            assertTrue("fieldTop=$fieldTop top=$top bottom=$bottom", bottom <= keyboardTop - gap)
            assertTrue("fieldTop=$fieldTop top=$top", top >= 0)
        }
    }

    /** 目标永远不为负 */
    @Test
    fun targetIsNeverNegative() {
        assertTrue(target(0) >= 0)
        assertTrue(target(10, scroll = 500) >= 0)
    }

    /** 键盘不占高度（IME 未弹起时被调用）：整屏可见，输入框在屏内就不动 */
    @Test
    fun noImeKeepsFieldInsideViewport() {
        assertEquals(
            0,
            targetScrollForIme(
                fieldTopPx = 2200,
                fieldBottomPx = 2380,
                scrollPx = 0,
                viewportPx = viewport,
                imeInsetPx = 0,
                marginPx = margin,
                gapPx = gap,
            ),
        )
    }
}
