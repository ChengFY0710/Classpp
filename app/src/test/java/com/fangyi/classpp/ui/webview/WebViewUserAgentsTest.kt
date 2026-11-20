package com.fangyi.classpp.ui.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网页页 UA 选择：未切换时沿用 spec 声明的自定义 UA，切换后严格用手机 / 电脑版；
 * 初始选中项只认桌面 UA（含远端声明的 desktop 别名解析值）。
 */
class WebViewUserAgentsTest {

    private val customUserAgent = "Custom-School-UA/1.0"

    @Test
    fun `untouched choice keeps the declared user agent`() {
        assertEquals(
            customUserAgent,
            resolveWebUserAgent(
                userAgentChoice = null,
                specUserAgent = customUserAgent,
                mobileUserAgent = "mobile",
            ),
        )
    }

    @Test
    fun `untouched choice falls back to mobile user agent without a declaration`() {
        assertEquals(
            "mobile",
            resolveWebUserAgent(
                userAgentChoice = null,
                specUserAgent = null,
                mobileUserAgent = "mobile",
            ),
        )
    }

    @Test
    fun `explicit choice overrides the declared user agent`() {
        assertEquals(
            WebDesktopUserAgent,
            resolveWebUserAgent(
                userAgentChoice = true,
                specUserAgent = customUserAgent,
                mobileUserAgent = "mobile",
            ),
        )
        assertEquals(
            "mobile",
            resolveWebUserAgent(
                userAgentChoice = false,
                specUserAgent = customUserAgent,
                mobileUserAgent = "mobile",
            ),
        )
    }

    @Test
    fun `only the desktop user agent selects desktop mode initially`() {
        assertTrue(isDesktopUserAgent(WebDesktopUserAgent))
        assertFalse(isDesktopUserAgent(null))
        assertFalse(isDesktopUserAgent(customUserAgent))
    }
}
