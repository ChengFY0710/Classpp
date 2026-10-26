package com.fangyi.classpp.data.wakeup

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wakeup 适配脚本（wake_up.js）获取：网络优先（云端热更新，Wakeup 改协议时只换脚本、
 * app 免发版）→ 本地缓存（上次成功下载）→ 内置资产兜底（离线 / gitee 不可达）。
 *
 * gitee raw 直链会 302 到 raw.giteeusercontent.com 的签名地址，`HttpURLConnection`
 * 默认跟随同协议跨主机重定向，无需特殊处理；但防盗链页可能以 HTML 形式返回 200，
 * 以内容标记校验兜底（不含脚本入口调用即视为失败）。
 */
object WakeupScriptStore {

    /** 拾光课表云端脚本仓库（NexioSchedule 同源） */
    private const val SCRIPT_URL =
        "https://gitee.com/XingHeYuZhuan-gh/shiguang_warehouse/raw/main/resources/GLOBAL_TOOLS/wake_up.js"

    private const val ASSET_PATH = "wakeup/wake_up.js"
    private const val CACHE_DIR = "wakeup"
    private const val CACHE_FILE = "wake_up.js"

    /** 下载上限（8MB）：脚本实际 ~34KB，超限视为异常响应 */
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** 内容标记：脚本必有入口函数调用，防盗链 HTML 页没有 */
    private const val CONTENT_MARKER = "runImportFlow"

    /** 网络/缓存/资产全部不可用（UI 报「适配脚本获取失败」） */
    class ScriptUnavailableException : Exception("wake_up.js unavailable")

    /** 获取脚本文本；失败按 缓存 → 资产 逐级回退，全空抛 [ScriptUnavailableException] */
    suspend fun ensureScript(context: Context): String = withContext(Dispatchers.IO) {
        download()?.also { writeCache(context, it) }
            ?: readCache(context)
            ?: readAsset(context)
            ?: throw ScriptUnavailableException()
    }

    private fun download(): String? = try {
        val connection = URL(SCRIPT_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36")
        try {
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(chunk)
                    if (read == -1) break
                    total += read
                    if (total > MAX_BYTES) return null
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }
            val text = String(bytes, Charsets.UTF_8)
            if (isValidScript(text)) text else null
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    private fun readCache(context: Context): String? = try {
        File(File(context.filesDir, CACHE_DIR), CACHE_FILE)
            .takeIf { it.isFile && it.length() > 0 }
            ?.readText()
            ?.takeIf { isValidScript(it) }
    } catch (_: Exception) {
        null
    }

    private fun readAsset(context: Context): String? = try {
        context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            .takeIf { isValidScript(it) }
    } catch (_: Exception) {
        null
    }

    private fun writeCache(context: Context, script: String) {
        try {
            val dir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
            val tmp = File(dir, "$CACHE_FILE.tmp")
            tmp.writeText(script)
            tmp.renameTo(File(dir, CACHE_FILE))
        } catch (_: Exception) {
            // 缓存写失败不影响本次导入
        }
    }

    private fun isValidScript(text: String): Boolean =
        text.contains(CONTENT_MARKER) && !text.trimStart().startsWith("<")
}
