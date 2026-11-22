package com.fangyi.classpp.data.ai

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * AI 服务地址的本地校验：必须是带 host 的 http/https URL。
 *
 * 旧实现只查 `startsWith("http")`，`httpfoo`、`http://`（无 host）都会通过，保存后
 * 只能在发送时得到一条网络错误。公网 http 仍由传输层拒绝（仅 TLS），这里只拦格式错。
 */
internal fun isValidAiBaseUrl(url: String): Boolean {
    val parsed = url.trim().toHttpUrlOrNull() ?: return false
    if (parsed.host.isBlank()) return false
    return parsed.scheme == "http" || parsed.scheme == "https"
}
