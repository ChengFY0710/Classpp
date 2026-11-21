package com.fangyi.classpp.data.ai

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 本地 / 局域网 AI 端点识别。
 *
 * 生产传输层默认只允许 TLS，但 Ollama 等本地服务默认是明文 http。这里只认回环、
 * 私网、链路本地与 `.local` 主机名：公网地址仍强制 TLS，避免 API Key 走明文。
 *
 * 仅做字面量判断，不对主机名做 DNS 解析——域名即使解析到内网地址也不放行，
 * 防止公网域名通过解析结果绕过「只对本地放行」的策略。
 */
object AiLocalNetwork {

    /** 仅当 URL 是 http 且主机属于本机 / 局域网时返回 true（https 无需明文放行） */
    fun isLocalHttpUrl(url: String): Boolean {
        val parsed = url.trim().toHttpUrlOrNull() ?: return false
        return parsed.scheme == "http" && isLocalHost(parsed.host)
    }

    /** 回环地址：不需要 Android 17 的本地网络权限，也不离开本机 */
    fun isLoopbackHost(rawHost: String): Boolean {
        val host = normalizeHost(rawHost)
        if (host.isEmpty()) return false
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (host == "::1") return true
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        return octets.all { it in 0..255 } && octets[0] == 127
    }

    fun isLocalHost(rawHost: String): Boolean {
        val host = normalizeHost(rawHost)
        if (host.isEmpty()) return false
        if (host == "localhost" || host.endsWith(".localhost")) return true
        // mDNS 局域网主机名（如 my-pc.local）
        if (host.endsWith(".local")) return true
        if (isPrivateIpv4(host)) return true
        return isPrivateIpv6(host)
    }

    private fun normalizeHost(rawHost: String): String =
        rawHost.trim().removePrefix("[").removeSuffix("]").lowercase()

    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val (a, b) = octets
        return a == 10 || a == 127 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) ||
            // CGNAT 网段：Tailscale 等组网工具常用
            (a == 100 && b in 64..127)
    }

    private fun isPrivateIpv6(host: String): Boolean {
        // 只有 IPv6 字面量会带冒号，避免把 fc 开头的普通主机名误判为 ULA
        if (!host.contains(':')) return false
        if (host == "::1") return true
        // ULA fc00::/7
        if (host.startsWith("fc") || host.startsWith("fd")) return true
        // 链路本地 fe80::/10
        if (host.startsWith("fe8") || host.startsWith("fe9") ||
            host.startsWith("fea") || host.startsWith("feb")
        ) {
            return true
        }
        // IPv4-mapped IPv6（::ffff:192.168.1.2）
        if (host.startsWith("::ffff:")) return isPrivateIpv4(host.removePrefix("::ffff:"))
        return false
    }
}
