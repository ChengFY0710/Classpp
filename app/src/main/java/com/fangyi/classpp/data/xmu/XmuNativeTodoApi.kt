package com.fangyi.classpp.data.xmu

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** 原生会话的 Cookie 头：登录成功时写入，原生取数读取；进程内共享 */
internal class XmuSessionCookieHolder {
    @Volatile
    var header: String? = null
}

/**
 * 原生取数实现（[XmuTodoApi]）：带 [XmuSessionCookieHolder] 里的 lnt cookie 直接请求
 * `/api/profile` 与 `/api/todos`，不走 WebView。
 *
 * 与 WebView 路径同一套判定口径（指导 §6.1）：
 * - 200 且正文是 JSON → 有效；
 * - 200 但是 HTML（登录页）/ 3xx 到身份域 / 401、403 → 会话过期；
 * - 5xx → 服务繁忙；IO 异常 → 网络失败；超时 → Timeout；其他 → 接口格式变化。
 *
 * 只有 `/api/profile` 是探活依据；`/api/todos` 空列表也是合法响应（不能据此判过期）。
 */
internal class XmuNativeTodoApi(
    private val transport: XmuHttpTransport,
    private val session: XmuSessionCookieHolder,
    private val baseUrl: String = "https://${XmuWebViewPolicy.LNT_HOST}",
) : XmuTodoApi {

    override suspend fun fetchSession(): XmuFetchResult {
        val result = try {
            withTimeout(TOTAL_TIMEOUT_MS) { fetchBoth() }
        } catch (_: TimeoutCancellationException) {
            XmuFetchResult.Timeout
        } catch (e: CancellationException) {
            throw e
        } catch (_: SocketTimeoutException) {
            XmuFetchResult.Timeout
        } catch (_: IOException) {
            XmuFetchResult.NetworkError
        } catch (_: Exception) {
            XmuFetchResult.FormatChanged
        }
        XmuDebugLog.d("native fetch outcome=${result::class.simpleName}")
        return result
    }

    private suspend fun fetchBoth(): XmuFetchResult {
        val cookie = session.header
        when (val profile = get("/api/profile", cookie)) {
            is XmuNativeResponse.Ok -> {
                // 被 302 到登录页时有些链路返回 200 + HTML，按过期处理
                if (!profile.body.trimStart().startsWith("{")) {
                    return XmuFetchResult.SessionExpired
                }
                return when (val todos = get("/api/todos", cookie)) {
                    is XmuNativeResponse.Ok ->
                        if (todos.body.trimStart().startsWith("{")) {
                            XmuFetchResult.Ok(profile.body, todos.body)
                        } else {
                            XmuFetchResult.SessionExpired
                        }
                    XmuNativeResponse.Expired -> XmuFetchResult.SessionExpired
                    XmuNativeResponse.Busy -> XmuFetchResult.ServiceBusy
                    XmuNativeResponse.Format -> XmuFetchResult.FormatChanged
                }
            }
            XmuNativeResponse.Expired -> return XmuFetchResult.SessionExpired
            XmuNativeResponse.Busy -> return XmuFetchResult.ServiceBusy
            XmuNativeResponse.Format -> return XmuFetchResult.FormatChanged
        }
    }

    private suspend fun get(path: String, cookie: String?): XmuNativeResponse {
        val response = transport.execute(
            XmuHttpRequest(
                method = "GET",
                url = baseUrl + path,
                headers = buildMap {
                    put("Accept", "application/json")
                    if (!cookie.isNullOrBlank()) put("Cookie", cookie)
                },
            ),
        )
        return when {
            response.status in 500..599 -> XmuNativeResponse.Busy
            response.status == 401 || response.status == 403 -> XmuNativeResponse.Expired
            response.status in 300..399 ->
                if (isIdentityLocation(response.location)) {
                    XmuNativeResponse.Expired
                } else {
                    XmuNativeResponse.Format
                }
            response.status in 200..299 -> XmuNativeResponse.Ok(response.body)
            else -> XmuNativeResponse.Format
        }
    }

    /** 过期后业务接口是 302 到身份域（不是 401），Location 也可能只是相对 /login */
    private fun isIdentityLocation(location: String?): Boolean {
        val value = location ?: return false
        return value.contains("c-identity.xmu.edu.cn", ignoreCase = true) ||
            value.contains("ids.xmu.edu.cn", ignoreCase = true) ||
            value.contains("/login", ignoreCase = true)
    }

    private sealed interface XmuNativeResponse {
        data class Ok(val body: String) : XmuNativeResponse
        data object Expired : XmuNativeResponse
        data object Busy : XmuNativeResponse
        data object Format : XmuNativeResponse
    }

    private companion object {
        /** profile + todos 两次请求的总预算 */
        const val TOTAL_TIMEOUT_MS = 30_000L
    }
}
