package com.fangyi.classpp.ui.settings

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 设置顶栏冻结键（纯 JVM）：任一视觉输入变化都必须产生不同的键，转场期才会回退实时
 * 绘制而不是重放过期帧。
 */
class SettingsTopBarFreezeKeyTest {

    private fun key(
        page: SettingsPage = SettingsPage.Root,
        darkTheme: Boolean = false,
        topBarHeight: androidx.compose.ui.unit.Dp = 116.dp,
        scroll: Int = 0,
        epoch: Int = 0,
        inputs: Any? = listOf("uid", "name"),
    ) = SettingsTopBarFreezeKey(page, darkTheme, topBarHeight, scroll, epoch, inputs)

    @Test
    fun identicalInputsProduceEqualKeys() {
        assertEquals(key(), key())
    }

    @Test
    fun pageIdentitySeparatesFrames() {
        assertNotEquals(key(page = SettingsPage.Root), key(page = SettingsPage.XmuSync))
    }

    @Test
    fun themeChangeInvalidatesFrame() {
        assertNotEquals(key(darkTheme = false), key(darkTheme = true))
    }

    @Test
    fun topBarHeightChangeInvalidatesFrame() {
        assertNotEquals(key(topBarHeight = 116.dp), key(topBarHeight = 120.dp))
    }

    @Test
    fun scrollChangeInvalidatesFrame() {
        assertNotEquals(key(scroll = 0), key(scroll = 48))
    }

    @Test
    fun sessionEpochChangeInvalidatesFrameAfterBackgrounding() {
        // 回前台后代际递增：被系统清空的显示列表不会再被重放
        assertNotEquals(key(epoch = 0), key(epoch = 1))
    }

    @Test
    fun pageSpecificInputsInvalidateFrame() {
        assertNotEquals(key(inputs = listOf("uid", "name")), key(inputs = listOf("uid", "other")))
        assertNotEquals(key(inputs = null), key(inputs = listOf("uid", "name")))
    }
}
