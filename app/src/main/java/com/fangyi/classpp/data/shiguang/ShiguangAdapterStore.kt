package com.fangyi.classpp.data.shiguang

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 拾光学校适配脚本的获取与缓存：网络优先（仓库热更新）→ 本地缓存（上次成功下载）。
 *
 * 脚本 URL 完全由索引里的 `resource_folder + asset_js_path` 决定，路径已过
 * [isSafeResourceFolder] / [isSafeAssetPath] 校验；下载内容再做一次「非 HTML + 含导入入口」
 * 校验，避免 Gitee 防盗链页被当成脚本注入。
 */
internal class ShiguangAdapterStore(
    private val fetcher: ShiguangWarehouseFetcher,
    private val cacheDir: File,
) {

    suspend fun ensureScript(adapter: ShiguangAdapter): String? = withContext(Dispatchers.IO) {
        val url = scriptUrl(adapter) ?: return@withContext null
        val downloaded = try {
            fetcher.fetch(url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }?.toString(Charsets.UTF_8)
            ?.takeIf(::isValidShiguangScript)
        if (downloaded != null) {
            writeCache(adapter, downloaded)
            return@withContext downloaded
        }
        readCache(adapter)
    }

    private fun readCache(adapter: ShiguangAdapter): String? = runCatching {
        val file = File(cacheDir, adapterCacheFileName(adapter))
        if (!file.isFile || file.length() == 0L) return@runCatching null
        file.readText().takeIf(::isValidShiguangScript)
    }.getOrNull()

    private fun writeCache(adapter: ShiguangAdapter, script: String) {
        runCatching {
            cacheDir.mkdirs()
            val target = File(cacheDir, adapterCacheFileName(adapter))
            val temp = File(cacheDir, "${adapterCacheFileName(adapter)}.tmp")
            temp.writeText(script)
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                target.writeText(script)
                temp.delete()
            }
        }
    }
}

/** 脚本直链：两个路径段都来自索引，已在 [ShiguangIndex.toAdapters] 过滤过 */
internal fun scriptUrl(adapter: ShiguangAdapter): String? {
    if (!isSafeResourceFolder(adapter.resourceFolder)) return null
    if (!isSafeAssetPath(adapter.assetPath)) return null
    return ShiguangWarehouseConfig.RESOURCES_BASE_URL +
        adapter.resourceFolder + "/" + adapter.assetPath
}

/** 缓存文件名：学校 + 适配 id 只保留安全字符，限长防文件系统异常 */
internal fun adapterCacheFileName(adapter: ShiguangAdapter): String =
    (adapter.schoolId + "_" + adapter.adapterId)
        .replace(UNSAFE_FILE_CHARS, "_")
        .take(MAX_CACHE_NAME_CHARS) + ".js"

/**
 * 脚本内容校验：非空、非 HTML、含桥接导入入口。脚本可能压缩成单行，不能依赖换行；
 * `saveImportedCourses` 是所有适配脚本写入课程的必经调用。
 */
internal fun isValidShiguangScript(text: String): Boolean {
    if (text.isBlank() || text.length > MAX_SCRIPT_CHARS) return false
    if (text.trimStart().startsWith("<")) return false
    return text.contains("saveImportedCourses")
}

private val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9_.-]")
private const val MAX_CACHE_NAME_CHARS = 120

/** 脚本字符上限（2MB）：适配脚本通常几十 KB，超限视为异常响应 */
private const val MAX_SCRIPT_CHARS = 2 * 1024 * 1024
