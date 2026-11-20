package com.fangyi.classpp.ui.webview

import android.webkit.WebSettings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.fangyi.classpp.data.sync.remote.DECLARATION_DESKTOP_USER_AGENT

/**
 * 桌面 UA：教务系统常按 UA 分流桌面 / 移动门户，网页页的「电脑版」统一用这一串。
 * 与远端学校声明的 `userAgent = "desktop"` 同值，切换时不会把声明学校带到另一个门户。
 */
internal const val WebDesktopUserAgent =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

/** 系统默认 UA 取不到时的手机兜底（极少见；明确带 Mobile 标识） */
private const val FallbackMobileUserAgent =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

/** 手机 UA 取系统 WebView 默认值：与设备 / 内核最匹配 */
@Composable
internal fun rememberWebMobileUserAgent(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching { WebSettings.getDefaultUserAgent(context) }
            .getOrDefault(FallbackMobileUserAgent)
    }
}

/**
 * 初始选中项判定：声明里明确写了桌面 UA（或与内置桌面 UA 同值）才默认「电脑版」，
 * 空 / 自定义 UA 默认「手机版」；自定义 UA 在用户主动切换前仍原样用于首次加载。
 */
internal fun isDesktopUserAgent(userAgent: String?): Boolean =
    userAgent == WebDesktopUserAgent || userAgent == DECLARATION_DESKTOP_USER_AGENT

/**
 * 当前应写入 WebView 的 UA：
 * - [userAgentChoice] 非空 = 用户已在地址栏菜单里明确选过手机 / 电脑版；
 * - 为空 = 还没切过，沿用 spec 声明的 UA（自定义 UA 不被静默覆盖），无声明则用系统 UA。
 */
internal fun resolveWebUserAgent(
    userAgentChoice: Boolean?,
    specUserAgent: String?,
    mobileUserAgent: String,
): String = when (userAgentChoice) {
    true -> WebDesktopUserAgent
    false -> mobileUserAgent
    null -> specUserAgent ?: mobileUserAgent
}
