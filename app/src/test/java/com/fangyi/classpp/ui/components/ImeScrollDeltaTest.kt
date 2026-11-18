package com.fangyi.classpp.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * lazy 内容模式（LazyColumn）的键盘让位位移：规则与列模式的 [targetScrollForIme] 对齐，
 * 但输入是视口坐标、输出是增量。固定一组参数：视口 1000、键盘 300、顶栏 78、
 * 上缘余量 12、键盘上方间距 24 → 可见区下缘 700，落点 676。
 */
class ImeScrollDeltaTest {

    private fun delta(
        top: Int,
        bottom: Int,
        imeInsetPx: Int = 300,
        topInsetPx: Int = 78,
    ): Int = imeScrollDelta(
        fieldTopPx = top,
        fieldBottomPx = bottom,
        viewportPx = 1000,
        imeInsetPx = imeInsetPx,
        marginPx = 12,
        gapPx = 24,
        topInsetPx = topInsetPx,
    )

    @Test
    fun `field fully above the keyboard does not scroll`() {
        assertEquals(0, delta(top = 200, bottom = 260))
    }

    @Test
    fun `field bottom already exactly at the gap line does not scroll`() {
        // 676 = 700 - 24：下缘刚好停在「键盘上方留 24dp」的线上，不再动
        assertEquals(0, delta(top = 300, bottom = 676))
    }

    @Test
    fun `field under the keyboard scrolls its bottom above the keyboard`() {
        // 下缘 720 被键盘压住：滚 44，把下缘带到 676
        assertEquals(44, delta(top = 500, bottom = 720))
    }

    @Test
    fun `tall field is stopped by the top inset margin`() {
        // 下缘对齐需要 224，但那样上缘会顶进顶栏：取上界 10（上缘停在顶栏下缘 + 12）
        assertEquals(10, delta(top = 100, bottom = 900))
    }

    @Test
    fun `pinned search header exactly at the top inset does not scroll`() {
        // sticky 搜索条钉住时上缘恰好等于 topInset、下缘远在键盘上方：
        // 不能因为 12dp 余量把列表往回拽
        assertEquals(0, delta(top = 78, bottom = 124))
    }

    @Test
    fun `field under the top bar with bottom under the keyboard scrolls back up`() {
        // 上缘已被顶栏盖住、下缘又被键盘压住：与列模式一致，向回滚（由上界决定）
        assertEquals(-40, delta(top = 50, bottom = 900))
    }

    @Test
    fun `no top bar means the margin is measured from the viewport top`() {
        // 无顶栏遮挡时上界 = 上缘 - 12
        assertEquals(-2, delta(top = 10, bottom = 900, topInsetPx = 0))
    }
}
