package com.fangyi.classpp.data.shiguang

import java.io.File
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 拾光仓库中一个可用的学校适配（已扁平化、已过滤）：
 * `school_index.pb` 里的 School + Adapter 合并成 UI 可直接展示的一行。
 */
internal data class ShiguangAdapter(
    val schoolId: String,
    val schoolName: String,
    val initial: String,
    val resourceFolder: String,
    val adapterId: String,
    val adapterName: String,
    val category: ShiguangAdapterCategory,
    val assetPath: String,
    val importUrl: String,
    val description: String,
    val maintainer: String,
) {
    /** 缓存/去重键：同一学校下 adapter_id 唯一 */
    val key: String get() = "$schoolId/$adapterId"
}

/**
 * 索引 → 可导入适配列表：
 * - 只保留本科/研究生适配（GENERAL_TOOL 与 UNKNOWN 不进入「教务导入」入口）；
 * - `import_url` 必须是 http/https，脚本路径必须是仓库内安全相对路径；
 * - 同一 `school_id + adapter_id` 去重，保持索引顺序（上游按 initial/名称排好）。
 */
internal fun ShiguangIndex.toAdapters(): List<ShiguangAdapter> = buildList {
    for (school in schools) {
        if (school.id.isBlank() || school.name.isBlank()) continue
        if (!isSafeResourceFolder(school.resourceFolder)) continue
        for (adapter in school.adapters) {
            if (adapter.adapterId.isBlank()) continue
            if (adapter.category != ShiguangAdapterCategory.BACHELOR_AND_ASSOCIATE &&
                adapter.category != ShiguangAdapterCategory.POSTGRADUATE
            ) {
                continue
            }
            if (!isSafeAssetPath(adapter.assetPath)) continue
            val importUrl = adapter.importUrl?.trim().orEmpty()
            if (!isSafeImportUrl(importUrl)) continue
            add(
                ShiguangAdapter(
                    schoolId = school.id,
                    schoolName = school.name,
                    initial = school.initial,
                    resourceFolder = school.resourceFolder,
                    adapterId = adapter.adapterId,
                    adapterName = adapter.adapterName,
                    category = adapter.category,
                    assetPath = adapter.assetPath,
                    importUrl = importUrl,
                    description = adapter.description,
                    maintainer = adapter.maintainer,
                ),
            )
        }
    }
}.distinctBy { it.schoolId to it.adapterId }

/**
 * 拾光索引的拉取与本地缓存：
 * - [loadCached] 只读磁盘，冷启动/离线时先展示上次成功解析的学校列表；
 * - [refresh] 网络优先，成功后原子回写索引；失败返回 null，由调用方回退缓存；
 * - 协议版本不符视为失败（不猜测新字段布局）。
 *
 * 阻塞 I/O 都包在 [Dispatchers.IO]；缓存目录由调用方传入，便于 JVM 单测用临时目录。
 */
internal class ShiguangCatalog(
    private val fetcher: ShiguangWarehouseFetcher,
    private val cacheDir: File,
) {

    suspend fun loadCached(): List<ShiguangAdapter> = withContext(Dispatchers.IO) {
        readCachedIndex()?.toAdapters().orEmpty()
    }

    suspend fun refresh(): List<ShiguangAdapter>? = withContext(Dispatchers.IO) {
        val bytes = try {
            fetcher.fetch(ShiguangWarehouseConfig.INDEX_URL)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        val index = runCatching { ShiguangIndexParser.parse(bytes) }.getOrNull()
            ?: return@withContext null
        if (index.protocolVersion != ShiguangIndexParser.SUPPORTED_PROTOCOL_VERSION) {
            return@withContext null
        }
        writeCache(bytes)
        index.toAdapters()
    }

    private fun readCachedIndex(): ShiguangIndex? {
        val file = File(cacheDir, ShiguangWarehouseConfig.INDEX_CACHE_FILE)
        if (!file.isFile) return null
        val index = runCatching { ShiguangIndexParser.parse(file.readBytes()) }.getOrNull()
            ?: return null
        return index.takeIf {
            it.protocolVersion == ShiguangIndexParser.SUPPORTED_PROTOCOL_VERSION
        }
    }

    private fun writeCache(bytes: ByteArray) {
        runCatching {
            cacheDir.mkdirs()
            val target = File(cacheDir, ShiguangWarehouseConfig.INDEX_CACHE_FILE)
            val temp = File(cacheDir, "${ShiguangWarehouseConfig.INDEX_CACHE_FILE}.tmp")
            temp.writeBytes(bytes)
            // Android/Linux 上 renameTo 覆盖已有文件可行；先删目标兼容 Windows 单测
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                target.writeBytes(bytes)
                temp.delete()
            }
        }
    }
}

// ---------- 路径/URL 安全校验（目录与脚本共用，JVM 单测直接覆盖） ----------

/** resource_folder 是单层目录名：禁止斜杠、点段与空白 */
internal fun isSafeResourceFolder(value: String): Boolean =
    value.isNotBlank() && SAFE_RESOURCE_FOLDER.matches(value)

/** asset_js_path 是仓库内相对路径：禁止绝对路径、`..` 与反斜杠，必须以 .js 结尾 */
internal fun isSafeAssetPath(value: String): Boolean {
    if (value.isBlank()) return false
    if (value.startsWith('/') || value.startsWith('\\')) return false
    if (value.contains("..") || value.contains("//") || value.contains('\\')) return false
    return SAFE_ASSET_PATH.matches(value)
}

/** import_url 只接受带主机的 http/https；不允许 userinfo（防钓鱼式 `https://a@b`） */
internal fun isSafeImportUrl(raw: String): Boolean {
    if (raw.isBlank()) return false
    val uri = runCatching { URI(raw) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase()
    return (scheme == "http" || scheme == "https") &&
        !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null
}

private val SAFE_RESOURCE_FOLDER = Regex("[A-Za-z0-9_-]{1,64}")

/** 路径只允许字母数字/下划线/点/斜杠/连字符；点号用于扩展名与子目录 */
private val SAFE_ASSET_PATH = Regex("[A-Za-z0-9_./-]{1,160}\\.js")
