package com.fangyi.classpp.ui.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 拾光学校浮层的外置搜索条位移：只验证「占位项根坐标 − 浮层卡片根坐标」这一层纯逻辑。
 *
 * 固定一组像素值：卡片顶端在根坐标系的 y = 100，顶栏高 234px，钉住抬升 15px（5dp @3x），
 * 列表在顶部时占位项（紧跟说明卡）在 y = 900 —— 搜索条就该画在相对卡片的 800。
 * 上机形态由 ShiguangImportSheet 组合，这里只锁规则：位置只认占位项的实测坐标；
 * 占位项滚到钉住位置以上后钳成 `顶栏高 − 抬升` = 219（不是 0，否则会滑进顶栏后面；
 * 也不是 234，钉住位置要比顶栏下缘高 5dp）。
 */
class ShiguangSearchPinOffsetTest {

    private fun pin(anchorRootY: Int, containerTopRootY: Int = 100): Int =
        shiguangSearchPinOffsetPx(
            anchorRootY = anchorRootY,
            containerTopRootY = containerTopRootY,
            topBarPx = 234,
            pinLiftPx = 15,
        )

    @Test
    fun `bar sits exactly where the placeholder item is`() {
        // 列表在顶部：条落在说明卡下缘（相对卡片 800），不是钉在顶栏下缘
        assertEquals(800, pin(anchorRootY = 900))
    }

    @Test
    fun `bar follows the placeholder while it scrolls up`() {
        // 内容上滚 500：占位项升到 400，条跟着到 300
        assertEquals(300, pin(anchorRootY = 400))
    }

    @Test
    fun `bar pins 5dp above the bottom edge of the top bar`() {
        // 占位项滚到与卡片顶端齐平：条停在顶栏下缘上方 5dp（234 − 15 = 219）
        assertEquals(219, pin(anchorRootY = 100))
        // 继续上滚：仍是钉住位置，不出更小的位移
        assertEquals(219, pin(anchorRootY = 0))
        assertEquals(219, pin(anchorRootY = -400))
    }

    @Test
    fun `bar stays inside the card while the sheet moves on screen`() {
        // 浮层整卡平移（进出场动画）时两个坐标一起变，位移不受影响
        assertEquals(800, pin(anchorRootY = 1400, containerTopRootY = 600))
        assertEquals(300, pin(anchorRootY = 900, containerTopRootY = 600))
    }
}
