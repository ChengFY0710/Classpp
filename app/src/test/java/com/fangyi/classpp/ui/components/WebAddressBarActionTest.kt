package com.fangyi.classpp.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 网页地址栏右侧按钮的判定规则（[resolveWebAddressBarAction]）：
 * 空输入不占位；未聚焦且内容等于已打开地址时让位给 UA 更多菜单；
 * 其余（输入中 / 聚焦 / 地址不一致）都显示前往箭头。
 */
class WebAddressBarActionTest {

    private fun action(
        value: String,
        openedUrl: String? = null,
        inputFocused: Boolean = false,
    ): WebAddressBarAction = resolveWebAddressBarAction(
        inputFocused = inputFocused,
        value = value,
        openedUrl = openedUrl,
    )

    @Test
    fun `blank input shows no right action`() {
        assertEquals(WebAddressBarAction.None, action(""))
        assertEquals(WebAddressBarAction.None, action("   "))
        // 空输入即使聚焦、即使有已打开地址也不显示按钮
        assertEquals(
            WebAddressBarAction.None,
            action(value = "  ", openedUrl = "https://jw.xmu.edu.cn", inputFocused = true),
        )
    }

    @Test
    fun `typed address without an opened page shows open`() {
        assertEquals(
            WebAddressBarAction.Open,
            action("https://jw.xmu.edu.cn"),
        )
        assertEquals(
            WebAddressBarAction.Open,
            action("https://jw.xmu.edu.cn", openedUrl = ""),
        )
    }

    @Test
    fun `address differing from the opened page shows open`() {
        assertEquals(
            WebAddressBarAction.Open,
            action(
                value = "https://jw.xmu.edu.cn/login",
                openedUrl = "https://jw.xmu.edu.cn",
            ),
        )
    }

    @Test
    fun `opened address untouched and unfocused shows more`() {
        assertEquals(
            WebAddressBarAction.More,
            action(
                value = "https://jw.xmu.edu.cn",
                openedUrl = "https://jw.xmu.edu.cn",
            ),
        )
    }

    @Test
    fun `comparison ignores surrounding whitespace`() {
        assertEquals(
            WebAddressBarAction.More,
            action(
                value = "  https://jw.xmu.edu.cn  ",
                openedUrl = " https://jw.xmu.edu.cn ",
            ),
        )
    }

    @Test
    fun `focusing the opened address shows open again`() {
        assertEquals(
            WebAddressBarAction.Open,
            action(
                value = "https://jw.xmu.edu.cn",
                openedUrl = "https://jw.xmu.edu.cn",
                inputFocused = true,
            ),
        )
    }
}
