package com.fangyi.classpp.data.xmu

import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 原生 OAuth 登录与原生取数共用的一条 HTTP 请求。
 *
 * 只覆盖本项目需要的形态：GET、表单 POST、JSON POST；请求头/cookie 由调用方显式给。
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

/** 传输层抽象：生产用 [XmuOkHttpTransport]，JVM 单测注入脚本化假实现 */
internal interface XmuHttpTransport {
    suspend fun execute(request: XmuHttpRequest): XmuHttpResponse
}

/**
 * OkHttp 传输实现。
 *
 * - **不自动跟随重定向**：登录链路要在每一跳收集 Set-Cookie，并在 c-mobile 回调处
 *   截取 code（不能真的请求 App 回调页）；
 * - **IPv4 优先 DNS**（[Ipv4FirstXmuDns]）：XMU 主机同时发布 A + AAAA 记录，校园网外
 *   与模拟器的 IPv6 路由常不可达，按地址序先试 v6 会长时间黑洞——这是「点登录没反应」
 *   的直接原因（参考 democard/xmu_assistant 的 Ipv4FirstDns，Apache-2.0）；
 * - `callTimeout` 兜底整次调用：即有请求被黑洞/滴流挂住，也在 30 秒内收口；
 * - 超时/IO 异常原样抛出，由登录客户端按 [java.net.SocketTimeoutException] /
 *   [java.io.IOException] 分类为 Timeout / NetworkError；
 * - 不开自定义 SSL：证书错误按网络异常处理，绝不绕过校验。
 */
internal class XmuOkHttpTransport : XmuHttpTransport {

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(Ipv4FirstXmuDns())
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(request: XmuHttpRequest): XmuHttpResponse =
        withContext(Dispatchers.IO) {
            val method = request.method.uppercase()
            // POST/PUT/PATCH 必须带 body（OkHttp method(method, null) 抛异常），
            // GET/HEAD 禁止 body；Content-Type 随媒体类型自动写入
            val requestBody = when {
                method in BODY_METHODS && request.formBody != null ->
                    request.formBody.toRequestBody(FORM_MEDIA_TYPE)
                method in BODY_METHODS && request.jsonBody != null ->
                    request.jsonBody.toRequestBody(JSON_MEDIA_TYPE)
                else -> null
            }
            val builder = Request.Builder()
                .url(request.url)
                .header("User-Agent", XmuWebViewPolicy.DESKTOP_USER_AGENT)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .method(method, requestBody)
            for ((name, value) in request.headers) builder.header(name, value)

            client.newCall(builder.build()).execute().use { response ->
                XmuHttpResponse(
                    status = response.code,
                    location = response.header("Location"),
                    setCookies = response.headers.values("Set-Cookie"),
                    body = response.body.string(),
                )
            }
        }

    private companion object {
        val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
        val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
        const val CONNECT_TIMEOUT_SECONDS = 8L
        const val READ_TIMEOUT_SECONDS = 15L
        const val CALL_TIMEOUT_SECONDS = 30L
    }
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
