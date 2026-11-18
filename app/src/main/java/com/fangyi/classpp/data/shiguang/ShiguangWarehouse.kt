package com.fangyi.classpp.data.shiguang

import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 拾光仓库地址与缓存布局（上游：shiguang_warehouse，MIT） */
internal object ShiguangWarehouseConfig {

    /** 编译索引：上游 CI 生成后强推到独立分支，协议版本见 [ShiguangIndexParser] */
    const val INDEX_URL =
        "https://gitee.com/XingHeYuZhuan-gh/shiguang_warehouse/raw/index-pb-release/school_index.pb"

    /** 各校适配脚本根路径：`resources/<resource_folder>/<asset_js_path>` */
    const val RESOURCES_BASE_URL =
        "https://gitee.com/XingHeYuZhuan-gh/shiguang_warehouse/raw/main/resources/"

    /** 本地缓存目录（`filesDir/shiguang`）：索引与适配脚本分开存放 */
    const val CACHE_DIR_NAME = "shiguang"
    const val INDEX_CACHE_FILE = "school_index.pb"
    const val ADAPTER_CACHE_DIR = "adapters"

    /** 下载上限：索引与脚本实际都远小于 1MB，超限视为异常/防盗链响应 */
    const val MAX_BYTES = 8 * 1024 * 1024

    /** Gitee raw 会按 UA 返回防盗链页；用浏览器 UA 取原始文件 */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
}

/**
 * 字节级下载契约：索引是 protobuf 二进制，不能走 `HttpTransport` 的 String 响应
 * （UTF-8 解码会破坏 wire 数据）；单测注入假实现，生产用 [OkHttpShiguangWarehouseFetcher]。
 */
internal fun interface ShiguangWarehouseFetcher {
    suspend fun fetch(url: String): ByteArray?
}

/**
 * Gitee raw 下载：手动跟随最多 3 次重定向，且每一跳都必须是 https（与
 * `executeFollowingHttpsRedirects` 同一安全口径）；非 2xx、超限、解析失败都返回 null。
 */
internal class OkHttpShiguangWarehouseFetcher(
    private val client: OkHttpClient = defaultClient,
) : ShiguangWarehouseFetcher {

    override suspend fun fetch(url: String): ByteArray? = withContext(Dispatchers.IO) {
        var current = url
        var redirects = 0
        var result: ByteArray? = null
        while (redirects <= MAX_REDIRECTS) {
            if (!current.startsWith("https://", ignoreCase = true)) break
            var redirectTarget: String? = null
            try {
                val request = Request.Builder()
                    .url(current)
                    .header("User-Agent", ShiguangWarehouseConfig.USER_AGENT)
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .build()
                client.newCall(request).execute().use { response ->
                    when {
                        response.code in REDIRECT_STATUSES -> {
                            val location = response.header("Location")
                            if (location != null) {
                                redirectTarget = runCatching {
                                    URI(current).resolve(location).toString()
                                }.getOrNull()
                            }
                        }
                        response.code in 200..299 -> {
                            val length = response.body.contentLength()
                            // -1 = chunked/未知长度：仍要读，由 readBounded 兜住上限
                            if (length < 0 || length <= ShiguangWarehouseConfig.MAX_BYTES.toLong()) {
                                result = response.body.byteStream().use { readBounded(it) }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                return@withContext null
            }
            if (result != null) break
            val next = redirectTarget ?: break
            current = next
            redirects++
        }
        result
    }

    private fun readBounded(input: java.io.InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read == -1) break
            total += read
            if (total > ShiguangWarehouseConfig.MAX_BYTES) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private companion object {
        const val MAX_REDIRECTS = 3
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}
