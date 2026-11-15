package com.fangyi.classpp.data.sync.net

import android.webkit.CookieManager
import com.fangyi.classpp.data.RemoteAttachmentFetcher
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 课程中心附件的流式下载实现（[RemoteAttachmentFetcher]）。
 *
 * - 只接受 https；首个请求按 URL 域名从 WebView [CookieManager] 取会话 Cookie
 *   （原生登录的 Cookie 由 `AndroidCookieMirror` 镜像进 CookieManager）；
 * - 课程中心 blob 接口会 302 到对象存储签名地址：手动跟随最多 [MAX_REDIRECTS] 次；
 *   每跳都按目标域名重新取 Cookie（CookieManager 按域隔离，课程中心会话不会被带到
 *   外部对象存储，跨域只带目标域自己的 Cookie）；
 * - 失败（非 2xx、跳转异常、断网、超时）返回 null，由 UI Toast，不影响同步主流程；
 * - 返回的流关闭时同时关闭底层响应，调用方（仓库）负责在复制完成后关闭。
 */
class HttpRemoteAttachmentFetcher(
    private val userAgent: String? = null,
    private val cookieProvider: (String) -> String? = { url ->
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
    },
    private val client: OkHttpClient = defaultClient(),
) : RemoteAttachmentFetcher {

    override suspend fun open(url: String, expectedMimeType: String): InputStream? =
        withContext(Dispatchers.IO) {
            val parsed = url.toHttpUrlOrNull() ?: return@withContext null
            if (parsed.scheme != "https") return@withContext null
            try {
                follow(
                    url = parsed,
                    cookie = cookieProvider(parsed.toString()),
                    expectedMimeType = expectedMimeType,
                    redirectsLeft = MAX_REDIRECTS,
                )
            } catch (_: Exception) {
                null
            }
        }

    private fun follow(
        url: HttpUrl,
        cookie: String?,
        expectedMimeType: String,
        redirectsLeft: Int,
    ): InputStream? {
        val request = Request.Builder()
            .url(url)
            .get()
            .apply {
                if (!cookie.isNullOrBlank()) header("Cookie", cookie)
                userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
            }
            .build()
        val response = client.newCall(request).execute()
        if (response.isRedirect) {
            val location = response.header("Location")
            response.close()
            if (redirectsLeft <= 0 || location.isNullOrBlank()) return null
            val next = url.resolve(location) ?: return null
            // 跳到明确的登录路径 = 会话过期；身份域/html 登录页由最终 Content-Type 兜底
            if (next.scheme != "https" || isLoginPath(next.encodedPath)) return null
            // 按目标域名重新取 Cookie：同域继续带会话，跨域（对象存储签名 URL）通常为空，
            // 也不会把课程中心会话泄漏给外部域
            return follow(
                url = next,
                cookie = cookieProvider(next.toString()),
                expectedMimeType = expectedMimeType,
                redirectsLeft = redirectsLeft - 1,
            )
        }
        if (!response.isSuccessful) {
            response.close()
            return null
        }
        // 200 登录页（未重定向的过期形态）：按 HTML 响应拒绝，避免存成打不开的“附件”
        val contentType = response.header("Content-Type").orEmpty()
        if (contentType.startsWith("text/html", ignoreCase = true) &&
            !expectedMimeType.startsWith("text/html", ignoreCase = true)
        ) {
            response.close()
            return null
        }
        return try {
            object : FilterInputStream(response.body.byteStream()) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        response.close()
                    }
                }
            }
        } catch (_: Exception) {
            response.close()
            null
        }
    }

    /** 明确的登录路径视为会话过期；通用层不内置学校域名 */
    private fun isLoginPath(path: String): Boolean =
        path.equals("/login", ignoreCase = true) ||
            path.startsWith("/login/", ignoreCase = true)

    private companion object {
        const val MAX_REDIRECTS = 5

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
