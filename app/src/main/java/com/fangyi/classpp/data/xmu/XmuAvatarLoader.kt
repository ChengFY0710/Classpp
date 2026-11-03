package com.fangyi.classpp.data.xmu

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 头像下载：URL → 原始字节。**任何失败返回 null**（网络/超时/非 2xx/不是图片/超限），
 * 调用方把 null 当作「这次没抓到」静默跳过——头像纯装饰，不能影响同步结果。
 */
internal fun interface XmuAvatarLoader {
    suspend fun load(url: String): ByteArray?
}

/**
 * [XmuAvatarLoader] 的 OkHttp 实现。
 *
 * - 独立 client（不复用 [XmuOkHttpTransport] 的 JSON 通道）：头像请求要自己的短超时
 *   （4s 连接 / 6s 读 / 8s 整次）——同步链路不能被一张头像拖住；重定向交给 OkHttp 默认
 *   跟随（跨域跳转时 OkHttp 会自动摘掉 Cookie 头）；
 * - 同样走 [Ipv4FirstXmuDns]（XMU 域 AAAA 记录在校园网外常不可达）；
 * - **cookie 只发给 XMU 域**：头像地址来自服务端响应，若被改成外域地址，绝不能把 lnt 的
 *   会话 cookie 送出去；
 * - 校验：2xx、体积 ≤ [XmuAvatarStore.MAX_BYTES]（流式读到上限即停，不整段读进内存）、
 *   Content-Type 是 image 族（`image/` 前缀）或魔数命中主流图片格式——防把登录页 HTML
 *   当头像落盘。
 */
internal class XmuHttpAvatarLoader(
    /** 每次请求现取 cookie（原生会话头 / WebView CookieManager 由装配处合并） */
    private val cookie: () -> String?,
) : XmuAvatarLoader {

    private val client = OkHttpClient.Builder()
        .dns(Ipv4FirstXmuDns())
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun load(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val httpUrl = url.toHttpUrlOrNull() ?: return@withContext null
            val request = Request.Builder()
                .url(httpUrl)
                .header("User-Agent", XmuWebViewPolicy.DESKTOP_USER_AGENT)
                .header("Accept", "image/*")
                .apply {
                    cookieFor(httpUrl)?.let { header("Cookie", it) }
                }
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    XmuDebugLog.d("avatar http=${response.code}")
                    return@withContext null
                }
                val bytes = response.body.byteStream().use {
                    readAvatarBytes(it, XmuAvatarStore.MAX_BYTES)
                }
                if (bytes == null) {
                    XmuDebugLog.d("avatar too large")
                    return@withContext null
                }
                if (!looksLikeAvatarImage(response.header("Content-Type"), bytes)) {
                    XmuDebugLog.d("avatar not image")
                    return@withContext null
                }
                bytes
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 网络/超时/解析异常一律按「没抓到」处理（不记 URL，红线见 XmuDebugLog）
            XmuDebugLog.d("avatar fetch failed")
            null
        }
    }

    /** XMU 域（含子域）才带会话 cookie；外域头像按匿名请求取，防止 cookie 外泄 */
    private fun cookieFor(url: HttpUrl): String? {
        val host = url.host.lowercase(Locale.US)
        if (host != XMU_DOMAIN && !host.endsWith(".$XMU_DOMAIN")) return null
        return cookie()?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val XMU_DOMAIN = "xmu.edu.cn"
        const val CONNECT_TIMEOUT_SECONDS = 4L
        const val READ_TIMEOUT_SECONDS = 6L
        const val CALL_TIMEOUT_SECONDS = 8L
    }
}

/**
 * 读满 [maxBytes] 还读不完就返回 null（超过上限即放弃）：按块读、达到上限即断，
 * 不依赖 Content-Length（可能缺省或撒谎）。
 */
internal fun readAvatarBytes(input: InputStream, maxBytes: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return out.toByteArray()
        if (out.size() + read > maxBytes) return null
        out.write(buffer, 0, read)
    }
}

/** Content-Type 声明是图片，或字节头是主流图片格式（JPEG/PNG/GIF/WEBP/BMP） */
internal fun looksLikeAvatarImage(contentType: String?, bytes: ByteArray): Boolean {
    if (contentType?.startsWith("image/", ignoreCase = true) == true) return true
    if (bytes.size < 4) return false
    return when {
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> true // JPEG
        bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> true // PNG
        bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() -> true // GIF
        bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> true // BMP
        bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte() -> true // WEBP
        else -> false
    }
}
