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
 * - [loadCached] 只读磁盘，冷启动/离线时先展示上次成功解析的学校列表；解析结果在
 *   本实例内复用（[cachedAdaptersOrNull]），不做每次打开的重复解析；
 * - [refresh] 网络优先，成功后原子回写索引并同步内存缓存；失败返回 null，由调用方回退缓存；
 * - 协议版本不符视为失败（不猜测新字段布局）。
 *
 * 阻塞 I/O 都包在 [Dispatchers.IO]；缓存目录由调用方传入，便于 JVM 单测用临时目录。
 */
internal class ShiguangCatalog(
    val fetcher: ShiguangWarehouseFetcher,
    private val cacheDir: File,
) {

    /**
     * 已解析的适配列表（进程内缓存）：索引缓存命中也只在首次解析，之后打开学校选择浮层
     * 直接复用同一份不可变列表。
     *
     * 这直接决定打开手感：列表 300 行上下的整体组合/测量如果是**入场动画中途**插入的，
     * 会顶掉数帧；有这份缓存时初值即全量列表，整段组合落在浮层仍在屏幕外的那一帧，
     * 动画期间不再发生结构性插入。写入只发生在 [loadCachedOrNull] / [refresh] 成功时
     * （IO 线程）：读路径在主线程（浮层初值）靠 @Volatile 直接读，写入经 [memoryLock]
     * 串行，避免「读缓存的旧解析结果」晚于「手动更新的新结果」落库。
     */
    @Volatile
    private var memoryAdapters: List<ShiguangAdapter>? = null

    /** 保护 [memoryAdapters] 的判空写入：读缓存与手动更新可能并发（预热 vs 更新适配列表） */
    private val memoryLock = Any()

    /** 已解析的列表；null = 本进程还没解析过（不代表磁盘没有缓存） */
    fun cachedAdaptersOrNull(): List<ShiguangAdapter>? = memoryAdapters

    suspend fun loadCached(): List<ShiguangAdapter> = loadCachedOrNull().orEmpty()

    /**
     * 读本地索引缓存：无有效缓存返回 null，「缓存有效但 0 个适配」返回空列表——
     * 入口用这个区分「没缓存要报错 / 首次拉取」与「有缓存但确实是空的」。
     * 命中后结果进内存缓存，重复打开浮层不再解析 protobuf。
     */
    suspend fun loadCachedOrNull(): List<ShiguangAdapter>? {
        memoryAdapters?.let { return it }
        return withContext(Dispatchers.IO) {
            val index = readCachedIndex() ?: return@withContext null
            val adapters = index.toAdapters()
            synchronized(memoryLock) {
                // 手动更新可能已在别的线程写入更新的一版：旧解析结果不得覆盖它
                if (memoryAdapters == null) memoryAdapters = adapters
            }
            adapters
        }
    }

    /**
     * 本地是否已有可解析的索引缓存。入口据此决定「首次打开自动拉取 / 之后只用缓存」：
     * 缓存存在（即使解析出 0 个适配）就不再自动联网，更新只由浮层的「更新适配列表」触发。
     */
    suspend fun hasCache(): Boolean = memoryAdapters != null || loadCachedOrNull() != null

    /** 是否已经执行过首次自动拉取（成功或失败都算，失败也不在下次打开时自动重试） */
    suspend fun hasInitialFetchAttempted(): Boolean = withContext(Dispatchers.IO) {
        File(cacheDir, ShiguangWarehouseConfig.INITIAL_FETCH_MARKER).isFile
    }

    /** 在首次自动拉取前落标记：即使本次失败，后续打开也只走手动更新 */
    suspend fun markInitialFetchAttempted() {
        withContext(Dispatchers.IO) {
            runCatching {
                cacheDir.mkdirs()
                File(cacheDir, ShiguangWarehouseConfig.INITIAL_FETCH_MARKER).writeText("1")
            }
        }
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
        // 与磁盘同步更新内存缓存：手动更新成功后，浮层里立刻换成新列表，后续打开也直接复用
        val adapters = index.toAdapters()
        synchronized(memoryLock) { memoryAdapters = adapters }
        adapters
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
