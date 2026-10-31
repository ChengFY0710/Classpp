package com.fangyi.classpp.data.xmu

import java.net.URI

/**
 * 登录链路的进程内 cookie 罐：按域名保存 name=value。
 *
 * 简化口径（对校方 CAS/Keycloak 链路足够）：
 * - 不区分 path——会话 cookie 都在 `/`，无同域同名不同 path 的场景；
 * - 支持精确域与父域（`Domain=.xmu.edu.cn`），不做 Port/HttpOnly 校验；
 * - `Max-Age<=0` 视为删除；`Expires` 不解析（会话 cookie 由探活判定有效性）。
 *
 * 只用于原生登录与原生取数，不读写 WebView 的 CookieManager。
 */
internal class XmuCookieJar {

    /** domain（小写、无前导点）→ name → value */
    private val byDomain = mutableMapOf<String, MutableMap<String, String>>()

    /** 记录一次响应的全部 Set-Cookie（按请求 URL 确定默认域） */
    fun store(requestUrl: String, setCookies: List<String>) {
        val host = hostOf(requestUrl) ?: return
        for (raw in setCookies) {
            val parts = raw.split(';')
            val pair = parts.firstOrNull()?.trim().orEmpty()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            if (name.isEmpty()) continue

            val attributes = parts.drop(1).associate { attr ->
                val i = attr.indexOf('=')
                if (i < 0) {
                    attr.trim().lowercase() to ""
                } else {
                    attr.substring(0, i).trim().lowercase() to attr.substring(i + 1).trim()
                }
            }
            val domain = attributes["domain"]
                ?.trimStart('.')
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
                ?: host
            val expired = attributes["max-age"]?.toLongOrNull()?.let { it <= 0L } == true
            if (expired) {
                byDomain[domain]?.remove(name)
            } else {
                byDomain.getOrPut(domain) { mutableMapOf() }[name] = value
            }
        }
    }

    /** 拼出该 URL 应携带的 Cookie 头；无匹配返回 null */
    fun headerFor(url: String): String? {
        val host = hostOf(url) ?: return null
        val matched = mutableMapOf<String, String>()
        for ((domain, cookies) in byDomain) {
            if (host == domain || host.endsWith(".$domain")) matched.putAll(cookies)
        }
        if (matched.isEmpty()) return null
        return matched.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** 当前 lnt 域（含其父域）的全部 cookie：登录成功的凭据就是它们 */
    fun lntCookies(): Map<String, String> {
        val host = XmuWebViewPolicy.LNT_HOST
        val matched = mutableMapOf<String, String>()
        for ((domain, cookies) in byDomain) {
            if (host == domain || host.endsWith(".$domain")) matched.putAll(cookies)
        }
        return matched
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()
    } catch (_: Exception) {
        null
    }
}
