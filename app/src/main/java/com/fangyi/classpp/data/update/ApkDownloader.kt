package com.fangyi.classpp.data.update

import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 下载结果：成功时返回已通过大小与 SHA-256 校验的本地 APK */
sealed interface ApkDownloadResult {
    data class Ready(val file: File) : ApkDownloadResult

    /** [detail] 只用于调试日志；UI 按 [reason] 选择文案 */
    data class Failed(val reason: ApkDownloadError, val detail: String) : ApkDownloadResult
}

enum class ApkDownloadError {
    /** 网络/HTTP/重定向失败，或清单里的直链全部不可用 */
    Network,

    /** 落盘后大小或 SHA-256 与清单不符 */
    Verify,

    /** 缓存目录不可写或重命名失败 */
    Storage,
}

/**
 * 应用内更新 APK 下载器（手动触发、单文件、无后台任务）：
 *
 * - 按清单顺序尝试 [urls]；每个请求手动跟随最多 3 跳重定向，且**每一跳只接受 https**；
 * - 先写 `*.part`，按清单 [expectedSizeBytes] 与 `sha256` 校验通过后再原子重命名为正式文件；
 * - 下载/校验失败会删除临时文件；用户取消（协程取消）会取消底层 OkHttp Call 并清理残留；
 * - 下载期间按约 1% 的粒度回调进度，避免每 64KB 触发一次重组。
 */
class ApkDownloader(
    private val cacheDir: File,
    private val client: OkHttpClient = defaultClient(),
) {

    suspend fun download(
        urls: List<String>,
        expectedSizeBytes: Long,
        expectedSha256: String,
        versionName: String,
        versionCode: Long,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): ApkDownloadResult = withContext(Dispatchers.IO) {
        if (expectedSizeBytes !in 1..AppUpdateConfig.MAX_APK_BYTES) {
            return@withContext ApkDownloadResult.Failed(
                ApkDownloadError.Verify,
                "invalid expected size $expectedSizeBytes",
            )
        }
        val sha256 = expectedSha256.trim().lowercase(Locale.ROOT)
        if (!AppUpdateConfig.SHA256_PATTERN.matches(sha256)) {
            return@withContext ApkDownloadResult.Failed(
                ApkDownloadError.Verify,
                "invalid expected sha256 '$expectedSha256'",
            )
        }
        val candidates = urls.filter { it.isSafeHttpsUrl() }
        if (candidates.isEmpty()) {
            return@withContext ApkDownloadResult.Failed(
                ApkDownloadError.Network,
                "no https apk url",
            )
        }
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            return@withContext ApkDownloadResult.Failed(
                ApkDownloadError.Storage,
                "cannot create update cache dir",
            )
        }

        clearStaleFiles()
        val fileName = apkFileName(versionName, versionCode)
        val part = File(cacheDir, "$fileName.part")
        val target = File(cacheDir, fileName)
        var lastDetail = "download failed"

        try {
            for (url in candidates) {
                part.delete()
                val fetch = runCatching {
                    downloadFrom(
                        url = url,
                        destination = part,
                        expectedSizeBytes = expectedSizeBytes,
                        onProgress = onProgress,
                    )
                }
                val failure = fetch.exceptionOrNull()
                if (failure != null) {
                    if (failure is CancellationException) throw failure
                    currentCoroutineContext().ensureActive()
                    lastDetail = failure.message ?: failure.javaClass.simpleName
                    part.delete()
                    continue
                }
                if (!ApkVerifier.verify(part, expectedSizeBytes, sha256)) {
                    part.delete()
                    return@withContext ApkDownloadResult.Failed(
                        ApkDownloadError.Verify,
                        "size or sha256 mismatch",
                    )
                }
                if (part.isFile && !part.renameTo(target)) {
                    // 同名旧文件可能还在：删掉后重试一次
                    target.delete()
                    if (!part.renameTo(target)) {
                        part.delete()
                        return@withContext ApkDownloadResult.Failed(
                            ApkDownloadError.Storage,
                            "cannot move apk into place",
                        )
                    }
                }
                return@withContext ApkDownloadResult.Ready(target)
            }
            ApkDownloadResult.Failed(ApkDownloadError.Network, lastDetail)
        } finally {
            // 取消/失败时清掉半截文件；成功时 part 已不在
            if (part.exists()) part.delete()
        }
    }

    /**
     * 下载单个 URL 到 [destination]。重定向在每一跳校验 https；
     * 声明的总大小作为硬上限，超出一律中止。
     */
    private suspend fun downloadFrom(
        url: String,
        destination: File,
        expectedSizeBytes: Long,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        var current = url
        var hops = 0
        while (true) {
            if (!current.isSafeHttpsUrl()) throw IOException("insecure redirect target")
            val request = Request.Builder()
                .url(current)
                .header("Accept", "application/octet-stream")
                .build()
            val call = client.newCall(request)
            // 协程取消时同时取消底层 Call，避免 UI 已取消但下载仍占着连接
            val job = currentCoroutineContext()[Job]
            val cancelHandle = job?.invokeOnCompletion { call.cancel() }
            val response: Response = try {
                call.execute()
            } catch (t: Throwable) {
                cancelHandle?.dispose()
                if (t is IOException) currentCoroutineContext().ensureActive()
                throw t
            }
            try {
                if (response.code in REDIRECT_STATUSES) {
                    if (hops >= MAX_REDIRECTS) throw IOException("too many redirects")
                    val location = response.header("Location")
                        ?: throw IOException("redirect without Location")
                    current = runCatching { URI(current).resolve(location).toString() }
                        .getOrNull()
                        ?: throw IOException("invalid redirect Location")
                    hops++
                    continue
                }
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body
                destination.outputStream().buffered().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var downloaded = 0L
                        var lastPercent = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            downloaded += read
                            if (downloaded > expectedSizeBytes) {
                                throw IOException("response exceeds declared size")
                            }
                            output.write(buffer, 0, read)
                            val percent = ((downloaded * 100) / expectedSizeBytes).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(downloaded, expectedSizeBytes)
                            }
                        }
                        output.flush()
                    }
                }
                return
            } finally {
                response.close()
                cancelHandle?.dispose()
            }
        }
    }

    /** 清理一天前的旧安装包与所有残留半截文件；刚下载的文件不碰 */
    private fun clearStaleFiles() {
        val cutoff = System.currentTimeMillis() - STALE_FILE_MILLIS
        cacheDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            when {
                file.name.endsWith(".part") -> file.delete()
                file.name.endsWith(".apk") && file.lastModified() < cutoff -> file.delete()
            }
        }
    }

    private fun apkFileName(versionName: String, versionCode: Long): String {
        val safeVersion = versionName
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(MAX_VERSION_NAME_CHARS)
            .ifBlank { "update" }
        return "Classpp-v$safeVersion-$versionCode.apk"
    }

    private companion object {
        const val MAX_REDIRECTS = 3
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        const val BUFFER_BYTES = 64 * 1024
        const val MAX_VERSION_NAME_CHARS = 40
        const val STALE_FILE_MILLIS = 24L * 60 * 60 * 1000

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
    }
}

/**
 * APK 完整性校验：大小与 SHA-256 都必须与清单一致。
 * 独立成对象便于 JVM 单测直接覆盖校验逻辑（下载链路本身不依赖 Android）。
 */
internal object ApkVerifier {
    fun verify(file: File, expectedSizeBytes: Long, expectedSha256: String): Boolean {
        if (!file.isFile || file.length() != expectedSizeBytes) return false
        val expected = expectedSha256.trim().lowercase(Locale.ROOT)
        if (!AppUpdateConfig.SHA256_PATTERN.matches(expected)) return false
        return runCatching { file.sha256Hex() == expected }.getOrDefault(false)
    }

    private fun File.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val bytes = digest.digest()
        val hex = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xff
            hex[index * 2] = HEX_DIGITS[value ushr 4]
            hex[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
        }
        return String(hex)
    }

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()
}
