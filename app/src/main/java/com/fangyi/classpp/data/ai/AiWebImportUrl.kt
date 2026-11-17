package com.fangyi.classpp.data.ai

import java.net.URI

/**
 * 「AI 识别教务网页」的网址归一化与校验。
 *
 * 用户可能只填 `jw.example.edu.cn`，也可能带完整的 `http(s)://`。规则：
 * - 已有 `://` 的必须显式是 http/https，其它协议（file/content/intent/javascript 等）
 *   一律拒绝；
 * - 没有 `://` 时补 `https://`（安全默认），也允许用户显式写 http 以兼容老教务系统；
 * - 必须有 host，拒绝带 userinfo 的地址（避免把账号口令拼进 URL）；
 * - 含空白的地址直接拒绝，避免 WebView 解析出与用户预期不同的目标。
 *
 * 纯 JVM 实现（不依赖 android.net.Uri），供 UI 校验与单测直接复用。
 */
internal object AiWebImportUrl {

    /** 规范化为可交给 WebView 的地址；非法返回 null */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it.isWhitespace() }) return null

        val hasSchemeSeparator = trimmed.contains("://")
        if (hasSchemeSeparator) {
            val lower = trimmed.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        }
        val withScheme = if (hasSchemeSeparator) trimmed else "https://$trimmed"

        val uri = try {
            URI(withScheme)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        if (uri.rawUserInfo != null) return null
        return withScheme
    }
}
