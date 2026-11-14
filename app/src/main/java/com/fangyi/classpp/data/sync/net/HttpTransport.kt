package com.fangyi.classpp.data.sync.net

import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 中性 HTTP 传输契约（指导 §2.3/§四 Phase 4）：原生登录与原生取数共用一条请求。
 *
 * 只覆盖本项目需要的形态：GET、表单 POST、JSON POST；请求头/cookie 由调用方显式给。
 * 学校适配层可以实现本接口（脚本化测试用 fake，生产用 [OkHttpTransport] 或自己的
 * 客户端），通用层不关心具体传输实现。
 */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val formBody: String? = null,
    val jsonBody: String? = null,
)

data class HttpResponse(
    val status: Int,
    /** 3xx 的 Location 原值（可能是相对路径）；非 3xx 为 null */
    val location: String? = null,
    /** 全部 Set-Cookie 原值（一次响应可有多条） */
    val setCookies: List<String> = emptyList(),
    val body: String = "",
)

/** 传输层抽象：生产默认 [OkHttpTransport]，JVM 单测注入脚本化假实现 */
fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

/**
 * 中性 OkHttp 实现（不含任何学校特化逻辑：DNS、UA、白名单都由调用方决定）。
 *
 * - **不自动跟随重定向**：登录链路要在每一跳收集 Set-Cookie，并在回调处截取 code；
 * - `callTimeout` 兜底整次调用：请求被黑洞/滴流挂住也在 30 秒内收口；
 * - 超时/IO 异常原样抛出，由登录客户端按 `SocketTimeoutException` / `IOException`
 *   分类为 Timeout / NetworkError；
 * - 不开自定义 SSL：证书错误按网络异常处理，绝不绕过校验。
 */
class OkHttpTransport(
    /** null = 交由 OkHttp 默认 UA；需要桌面版页面的学校显式传入 */
    private val userAgent: String? = null,
    private val acceptLanguage: String = "zh-CN,zh;q=0.9",
    /**
     * null = 系统解析。学校主机若同时发布 A + AAAA 记录而 IPv6 路由不可达
     * （先试 v6 会 TCP 黑洞到超时），由学校适配层注入自己的 DNS 策略。
     */
    dns: Dns? = null,
) : HttpTransport {

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(dns ?: Dns.SYSTEM)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(request: HttpRequest): HttpResponse =
        withContext(Dispatchers.IO) {
            val method = request.method.uppercase()
            val requestBody = when {
                method in BODY_METHODS && request.formBody != null ->
                    request.formBody.toRequestBody(FORM_MEDIA_TYPE)
                method in BODY_METHODS && request.jsonBody != null ->
                    request.jsonBody.toRequestBody(JSON_MEDIA_TYPE)
                else -> null
            }
            val builder = Request.Builder()
                .url(request.url)
                .method(method, requestBody)
            userAgent?.let { builder.header("User-Agent", it) }
            if (acceptLanguage.isNotBlank()) builder.header("Accept-Language", acceptLanguage)
            for ((name, value) in request.headers) builder.header(name, value)

            client.newCall(builder.build()).execute().use { response ->
                HttpResponse(
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
 * 原生会话的进程内 cookie 持有者：登录成功后写入，会话过期时清空。
 * 只服务原生取数路径（WebView 的 cookie 由系统 CookieManager 管理），不落盘——
 * 持久化由 [com.fangyi.classpp.data.sync.credential.SecureStore] 的 cookie 密文负责。
 */
class SessionCookieHolder {
    @Volatile
    var header: String? = null
}

/**
 * 手动跟随重定向（[OkHttpTransport] 为登录链路设计，默认不跟随）：
 * 远端 raw / release 资产常经 CDN 302，最多 [maxRedirects] 跳且**每一跳只接受 https**；
 * 超限、缺 Location、非 https 或 URI 解析失败返回 null，由调用方按失败处理。
 *
 * 学校声明清单与应用更新清单共用这条路径；返回值是最终响应（非重定向），
 * 网络异常原样抛出，交由调用方 `runCatching` 收口。
 */
suspend fun HttpTransport.executeFollowingHttpsRedirects(
    url: String,
    maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
): HttpResponse? {
    var current = url
    repeat(maxRedirects + 1) {
        val response = execute(HttpRequest(method = "GET", url = current))
        if (response.status !in REDIRECT_STATUSES) return response
        val location = response.location ?: return null
        current = runCatching { URI(current).resolve(location).toString() }.getOrNull()
            ?: return null
        if (!current.startsWith("https://")) return null
    }
    return null
}

private const val DEFAULT_MAX_REDIRECTS = 3
private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
