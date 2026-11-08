package com.fangyi.classpp.data.xmu

import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns

/**
 * 原生 OAuth 登录与原生取数共用的一条 HTTP 请求。
 *
 * 只覆盖本项目需要的形态：GET、表单 POST、JSON POST；请求头/cookie 由调用方显式给。
 * 生产传输 = 通用 `data.sync.net.OkHttpTransport`（经 `schools/xmu` 的适配器桥接），
 * 学校注入 IPv4 优先 DNS；JVM 单测注入脚本化假实现。
 */
internal data class XmuHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val formBody: String? = null,
    val jsonBody: String? = null,
)

internal data class XmuHttpResponse(
    val status: Int,
    /** 3xx 的 Location 原值（可能是相对路径）；非 3xx 为 null */
    val location: String? = null,
    /** 全部 Set-Cookie 原值（一次响应可有多条） */
    val setCookies: List<String> = emptyList(),
    val body: String = "",
)

/** 传输层抽象：生产经适配器接通用 OkHttp 传输，JVM 单测注入脚本化假实现 */
internal interface XmuHttpTransport {
    suspend fun execute(request: XmuHttpRequest): XmuHttpResponse
}

/**
 * XMU 域 IPv4 优先解析（做法参考 democard/xmu_assistant 的 Ipv4FirstDns，Apache-2.0）。
 *
 * lnt/c-identity/ids 都是 applg219.xmu.edu.cn 的 CNAME，同时发布 A（219.229.81.240）
 * 与 AAAA（2001:da8:e800:251c::240）记录。校园网外与模拟器往往没有可用 IPv6 路由，
 * 系统地址序先试 v6 会 TCP 黑洞到超时。这里对 XMU 域「存在 A 记录就只返回 v4」，
 * 其余域完全走系统解析不受影响。
 */
internal class Ipv4FirstXmuDns(
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        val host = hostname.trimEnd('.').lowercase(Locale.US)
        if (host != XMU_DOMAIN && !host.endsWith(".$XMU_DOMAIN")) return addresses
        val ipv4 = addresses.filterIsInstance<Inet4Address>()
        return if (ipv4.isEmpty()) addresses else ipv4
    }

    private companion object {
        const val XMU_DOMAIN = "xmu.edu.cn"
    }
}
