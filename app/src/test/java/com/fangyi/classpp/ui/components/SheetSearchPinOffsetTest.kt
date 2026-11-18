package com.fangyi.classpp.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 浮层外置搜索条（[PinnedSheetSearchField]）的位移：只验证「占位项根坐标 − 浮层卡片根坐标」
 * 这一层纯逻辑，拾光导入与学校选择浮层共用同一规则。
 *
 * 固定一组像素值：卡片顶端在根坐标系的 y = 100，顶栏高 234px，钉住抬升 15px（5dp @3x），
 * 列表在顶部时占位项（紧跟说明卡）在 y = 900 —— 搜索条就该画在相对卡片的 800。
 * 这里只锁规则：位置只认占位项的实测坐标；
 * 占位项滚到钉住位置以上后钳成 `顶栏高 − 抬升` = 219（不是 0，否则会滑进顶栏后面；
 * 也不是 234，钉住位置要比顶栏下缘高 5dp）。位移按 Float 计算，小数像素不被取整。
 */
class SheetSearchPinOffsetTest {

    private fun pin(anchorRootY: Float, containerTopRootY: Float = 100f): Float =
        sheetSearchPinOffsetPx(
            anchorRootY = anchorRootY,
            containerTopRootY = containerTopRootY,
            topBarPx = 234,
            pinLiftPx = 15,
        )

    /** 浮点断言容差：纯加减运算，只需吸收 Float 精度误差 */
    private val PinDelta = 0.001f

    @Test
    fun `bar sits exactly where the placeholder item is`() {
        // 列表在顶部：条落在说明卡下缘（相对卡片 800），不是钉在顶栏下缘
        assertEquals(800f, pin(anchorRootY = 900f), PinDelta)
    }

    @Test
    fun `bar follows the placeholder while it scrolls up`() {
        // 内容上滚 500：占位项升到 400，条跟着到 300
        assertEquals(300f, pin(anchorRootY = 400f), PinDelta)
    }

    @Test
    fun `bar pins 5dp above the bottom edge of the top bar`() {
        // 占位项滚到与卡片顶端齐平：条停在顶栏下缘上方 5dp（234 − 15 = 219）
        assertEquals(219f, pin(anchorRootY = 100f), PinDelta)
        // 继续上滚：仍是钉住位置，不出更小的位移
        assertEquals(219f, pin(anchorRootY = 0f), PinDelta)
        assertEquals(219f, pin(anchorRootY = -400f), PinDelta)
    }

    @Test
    fun `bar keeps subpixel anchor positions`() {
        // 占位项按小数像素移动（滚动/图层次像素位移）：位移同样不取整
        assertEquals(300.5f, pin(anchorRootY = 400.5f), PinDelta)
    }

    @Test
    fun `bar stays inside the card while the sheet moves on screen`() {
        // 浮层整卡平移（进出场动画）时两个坐标一起变，位移不受影响
        assertEquals(800f, pin(anchorRootY = 1400f, containerTopRootY = 600f), PinDelta)
        assertEquals(300f, pin(anchorRootY = 900f, containerTopRootY = 600f), PinDelta)
    }
}
