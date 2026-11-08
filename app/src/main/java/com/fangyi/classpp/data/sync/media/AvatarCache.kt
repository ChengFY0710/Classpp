package com.fangyi.classpp.data.sync.media

import com.fangyi.classpp.data.sync.AvatarImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 头像下载契约：URL → 原始字节。**任何失败返回 null**（网络/超时/非 2xx/不是图片/超限），
 * 调用方把 null 当作「这次没抓到」静默跳过——头像纯装饰，不能影响同步结果。
 */
fun interface AvatarFetcher {
    suspend fun fetch(url: String): ByteArray?
}

/**
 * 头像缓存（可选能力，指导 §2.3）：落盘到 `filesDir/sync/<id>/avatar.img`，与同步状态
 * 文件同目录；备份规则按目录整体排除（`file/sync`），头像不会进入云备份。
 *
 * 不实现/不提供 [fetcher] 的学校，引擎的 `avatarImage` 恒为 null，UI 走姓名首字占位。
 * 落盘先写 `avatar.img.tmp` 再 rename：进程半途被杀不会留下半张图。
 */
class AvatarCache(
    /** 学校专属目录（`filesDir/sync/<id>`），目录不存在时首次写入自动创建 */
    private val dir: File,
    /** null = 只读缓存、不下载（测试或纯装饰场景） */
    private val fetcher: AvatarFetcher? = null,
) {

    private val main = File(dir, FILE_NAME)
    private val tmp = File(dir, TMP_NAME)

    /** 已缓存的头像（null = 还没有）；阻塞 I/O，调用方包到 Dispatchers.IO */
    fun cachedImage(): AvatarImage? =
        main.takeIf { it.length() > 0 }?.let { AvatarImage(it, it.lastModified()) }

    /**
     * 下载并落盘一张头像；任何失败返回 null（调用方保留上一张/首字占位）。
     * 体积/格式校验在 [HttpAvatarFetcher] 完成，这里只兜底磁盘与空数据。
     */
    suspend fun store(url: String): AvatarImage? {
        val loader = fetcher ?: return null
        val bytes = loader.fetch(url) ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        return withContext(Dispatchers.IO) {
            try {
                dir.mkdirs()
                tmp.outputStream().use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
                // rename 不能覆盖已存在的文件（Windows 直接失败）：先删旧图再换名
                if (!tmp.renameTo(main)) {
                    main.delete()
                    if (!tmp.renameTo(main)) {
                        tmp.delete()
                        return@withContext null
                    }
                }
                cachedImage()
            } catch (_: Exception) {
                tmp.delete()
                null
            }
        }
    }

    /** 退出登录/换号时随同步状态一并删除，避免下一个账号看到上一张脸 */
    fun delete() {
        main.delete()
        tmp.delete()
    }

    companion object {
        private const val FILE_NAME = "avatar.img"
        private const val TMP_NAME = "avatar.img.tmp"

        /** 头像体积上限 2MB：正常头像远小于它，超限按异常响应丢弃（不落盘、不重试） */
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}

/**
 * [AvatarFetcher] 的 OkHttp 实现。
 *
 * - 独立 client（短超时：4s 连接 / 6s 读 / 8s 整次）——同步链路不能被一张头像拖住；
 * - **cookie 只发给 [cookieHosts] 及其子域**：头像地址来自服务端响应，若被改成外域地址，
 *   绝不能把会话 cookie 送出去；
 * - 校验：2xx、体积 ≤ [AvatarCache.MAX_BYTES]（流式读到上限即停，不整段读进内存）、
 *   Content-Type 是 image 族或魔数命中主流图片格式——防把登录页 HTML 当头像落盘。
 */
class HttpAvatarFetcher(
    /** 每次请求现取 cookie（原生会话头 / WebView CookieManager 由装配处合并） */
    private val cookie: () -> String?,
    /** 允许携带 cookie 的域（含子域）；空集 = 头像请求一律匿名 */
    private val cookieHosts: Set<String> = emptySet(),
    private val userAgent: String? = null,
    /** null = 系统解析；学校主机 IPv6 路由不可达时由适配层注入自己的 DNS 策略 */
    dns: Dns? = null,
) : AvatarFetcher {

    private val client = OkHttpClient.Builder()
        .dns(dns ?: Dns.SYSTEM)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun fetch(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val httpUrl = url.toHttpUrlOrNull() ?: return@withContext null
            val request = Request.Builder()
                .url(httpUrl)
                .header("Accept", "image/*")
                .apply {
                    userAgent?.let { header("User-Agent", it) }
                    cookieFor(httpUrl.host)?.let { header("Cookie", it) }
                }
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bytes = response.body.byteStream().use {
                    readAvatarBytes(it, AvatarCache.MAX_BYTES)
                } ?: return@withContext null
                if (!looksLikeAvatarImage(response.header("Content-Type"), bytes)) {
                    return@withContext null
                }
                bytes
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** 只有声明的域（含子域）才带会话 cookie；外域头像按匿名请求取 */
    private fun cookieFor(host: String): String? {
        val normalized = host.lowercase(Locale.US)
        val allowed = cookieHosts.any { domain ->
            normalized == domain || normalized.endsWith(".$domain")
        }
        if (!allowed) return null
        return cookie()?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 4L
        const val READ_TIMEOUT_SECONDS = 6L
        const val CALL_TIMEOUT_SECONDS = 8L
    }
}

/**
 * 读满 [maxBytes] 还读不完就返回 null（超过上限即放弃）：按块读、达到上限即断，
 * 不依赖 Content-Length（可能缺省或撒谎）。
 */
fun readAvatarBytes(input: InputStream, maxBytes: Int): ByteArray? {
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
fun looksLikeAvatarImage(contentType: String?, bytes: ByteArray): Boolean {
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
