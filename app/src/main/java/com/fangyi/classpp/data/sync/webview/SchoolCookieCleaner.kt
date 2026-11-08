package com.fangyi.classpp.data.sync.webview

import android.webkit.CookieManager

/**
 * 按学校声明的域集合清理登录态（指导 §2.4 第 10 条 / §2.6）：
 * 逐域把该域可见的 cookie 置过期（`setCookie` + `Max-Age=0`），**不调用**
 * `CookieManager.removeAllCookies`——全局清空会把其他学校的网页登录一起退出。
 *
 * WebStorage 无按域清理 API，这里**不清**：残留 localStorage 无害，清了会登出其他学校
 * （若产品最终要求全局清理，必须先在确认文案中明确「会退出所有学校的网页登录」）。
 *
 * 尽力而为：按域只能看到 URL 可见的 cookie（Path 受限的 cookie 可能残留）；登录失败与
 * 残留会话都由下次登录/探活自愈。
 */
object SchoolCookieCleaner {

    /** 清 [hosts] 各自域下的 cookie（仅 https），结束后 flush 持久化 */
    fun clearForHosts(
        hosts: Set<String>,
        cookieManager: CookieManager = CookieManager.getInstance(),
    ) {
        for (host in hosts) {
            val url = "https://$host/"
            val visible = runCatching { cookieManager.getCookie(url) }.getOrNull()
                ?: continue
            val names = visible.split(';')
                .mapNotNull { part ->
                    part.substringBefore('=', "").trim().takeIf { it.isNotEmpty() }
                }
                .distinct()
            for (name in names) {
                runCatching {
                    cookieManager.setCookie(url, "$name=; Max-Age=0; Path=/")
                }
            }
        }
        runCatching { cookieManager.flush() }
    }
}
