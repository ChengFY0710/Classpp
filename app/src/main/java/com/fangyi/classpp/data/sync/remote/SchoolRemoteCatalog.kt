package com.fangyi.classpp.data.sync.remote

import android.content.Context
import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.sync.SyncDebugLog
import com.fangyi.classpp.data.sync.net.HttpRequest
import com.fangyi.classpp.data.sync.net.HttpResponse
import com.fangyi.classpp.data.sync.net.HttpTransport
import com.fangyi.classpp.data.sync.net.OkHttpTransport
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 远端适配分发仓库的地址与缓存路径常量（仓库内容由 tools/schoolsync/ 撰写并 push） */
object SchoolRemoteConfig {
    /** 缓存目录内清单文件名；声明文件按 `school-<id>.json` 落盘（id 经校验，防路径注入） */
    const val INDEX_FILE = "index.json"
    const val SCHOOLS_CACHE_PREFIX = "school-"

    /** Gitee 仓库 raw 根地址（main 分支；声明清单 = [INDEX_URL]） */
    const val BASE_URL = "https://gitee.com/ChengFY0710/classpp_schoolsync/raw/main/"
    const val INDEX_URL = "$BASE_URL$INDEX_FILE"

    /** 缓存目录名（`filesDir/sync_remote`）：手动更新成功后落盘；冷启动只读本地缓存，不联网 */
    const val CACHE_DIR_NAME = "sync_remote"

    fun cacheDir(context: Context): File =
        File(context.applicationContext.filesDir, CACHE_DIR_NAME)
}

/**
 * 一次手动拉取的结果：清单层失败（缓存保持原样），或成功（有效声明 + 被跳过的清单条目）。
 * 被跳过条目的原因只进调试日志，不渲染给用户。
 */
sealed interface RemoteCatalogResult {
    data class Success(
        val declarations: List<SchoolDeclaration>,
        val skipped: List<RemoteSkippedSchool>,
    ) : RemoteCatalogResult

    /** 清单拉取/解码/版本不兼容；[reason] 仅用于调试日志 */
    data class Failed(val reason: String) : RemoteCatalogResult
}

/** 拉取成功但未产出声明的清单条目（诊断用：坏 id/路径、拉取失败、解码或校验不过） */
data class RemoteSkippedSchool(val id: String, val reason: String)

/**
 * 远端学校声明的**手动**拉取与缓存：
 *
 * - [refresh]：**仅由用户手动触发**（学校选择浮层「更新远端学校列表」）拉取清单 → 逐校
 *   声明 → 解码 + 校验（**单校失败跳过，不拖垮整批**，跳过原因进 [SyncDebugLog]）→
 *   回写缓存 → 返回有效声明与跳过条目；清单层失败返回 [RemoteCatalogResult.Failed]，
 *   缓存原样保留（不自动重试、不后台拉取、不启动拉取）；
 * - [loadCached]：同步读本地缓存（冷启动装配路径：手动更新过的声明据此恢复远端校，
 *   只读磁盘、不联网；从未手动更新则返回空 = 默认关闭）；
 * - 已注册学校的声明变更（host 调整等）由注册表按内容令牌识别并提示重启后生效
 *   （运行中的会话不受影响）。
 */
class SchoolRemoteCatalog(
    private val transport: HttpTransport = OkHttpTransport(),
    private val cacheDir: File,
    private val indexUrl: String = SchoolRemoteConfig.INDEX_URL,
    private val baseUrl: String = SchoolRemoteConfig.BASE_URL,
) {

    /** 同步读缓存；阻塞 I/O，调用方包 Dispatchers.IO */
    fun loadCached(): List<SchoolDeclaration> {
        val index = runCatching {
            ScheduleJson.decodeStorage<SchoolRemoteIndex>(cacheFile(SchoolRemoteConfig.INDEX_FILE).readText())
        }.getOrNull() ?: return emptyList()
        if (index.schemaVersion !in 1..SCHOOL_DECLARATION_SCHEMA_VERSION) return emptyList()
        return index.schools
            .filter { SchoolRemoteIndex.isSafeRelativeFile(it.file) }
            .mapNotNull { entry -> decodeCached(entry.id) }
    }

    /**
     * 手动拉取：返回有效声明与被跳过条目；[RemoteCatalogResult.Failed] = 清单拉取/解码失败
     * （缓存保持原样，由 UI 提示）。空声明 = 拉取成功但没有可装配的学校。阻塞 I/O，内部包 IO。
     */
    suspend fun refresh(): RemoteCatalogResult = withContext(Dispatchers.IO) {
        val index = runCatching {
            val response = fetch(indexUrl) ?: return@runCatching null
            if (!response.isSuccess) return@runCatching null
            ScheduleJson.decodeStorage<SchoolRemoteIndex>(response.body)
        }.getOrNull() ?: return@withContext failed("index fetch or decode failed")
        if (index.schemaVersion !in 1..SCHOOL_DECLARATION_SCHEMA_VERSION) {
            return@withContext failed("unsupported index schemaVersion ${index.schemaVersion}")
        }

        val skipped = mutableListOf<RemoteSkippedSchool>()
        val seenIds = mutableSetOf<String>()
        val declarations = index.schools.mapNotNull { entry ->
            // 清单条目本身不合法（坏 id / 危险路径 / 重复登记）直接跳过，绝不发起请求
            if (!SchoolDeclaration.SCHOOL_ID_PATTERN.matches(entry.id)) {
                return@mapNotNull skip(skipped, entry.id, "invalid school id")
            }
            if (!SchoolRemoteIndex.isSafeRelativeFile(entry.file)) {
                return@mapNotNull skip(skipped, entry.id, "invalid file path '${entry.file}'")
            }
            if (!seenIds.add(entry.id)) {
                return@mapNotNull skip(skipped, entry.id, "duplicate school id")
            }
            val response = runCatching { fetch(resolveFileUrl(entry.file)) }.getOrNull()
                ?: return@mapNotNull skip(skipped, entry.id, "declaration fetch failed")
            if (!response.isSuccess) {
                return@mapNotNull skip(skipped, entry.id, "HTTP ${response.status}")
            }
            val declaration = runCatching { SchoolDeclaration.decode(response.body) }.getOrNull()
                ?: return@mapNotNull skip(skipped, entry.id, "declaration decode failed")
            if (declaration.id != entry.id) {
                return@mapNotNull skip(skipped, entry.id, "declaration id does not match index entry")
            }
            val issues = declaration.validate()
            if (issues.isNotEmpty()) {
                return@mapNotNull skip(
                    skipped,
                    entry.id,
                    "invalid declaration: ${issues.joinToString("; ")}",
                )
            }
            declaration
        }
        persistCache(index, declarations)
        RemoteCatalogResult.Success(declarations, skipped)
    }

    /** 写缓存：清单 + 逐校声明（按 id 落文件；未通过校验的声明不进缓存） */
    private fun persistCache(index: SchoolRemoteIndex, declarations: List<SchoolDeclaration>) {
        runCatching {
            cacheDir.mkdirs()
            cacheFile(SchoolRemoteConfig.INDEX_FILE).writeText(ScheduleJson.encodeStorage(index))
            declarations.forEach { declaration ->
                cacheFile(cachedSchoolFileName(declaration.id))
                    .writeText(ScheduleJson.encodeStorage(declaration))
            }
        }
    }

    private fun decodeCached(id: String): SchoolDeclaration? = runCatching {
        val declaration = SchoolDeclaration.decode(cacheFile(cachedSchoolFileName(id)).readText())
        if (declaration.validate().isEmpty() && declaration.id == id) declaration else null
    }.getOrNull()

    /**
     * 手动跟随重定向（[OkHttpTransport] 为登录链路设计，默认不跟随）：
     * Gitee raw 可能 302 到 CDN；最多 [MAX_REDIRECTS] 跳，只接受 https，失败返回 null。
     */
    private suspend fun fetch(url: String): HttpResponse? {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val response = transport.execute(HttpRequest(method = "GET", url = current))
            if (response.status !in REDIRECT_STATUSES) return response
            val location = response.location ?: return null
            current = runCatching { URI(current).resolve(location).toString() }.getOrNull()
                ?: return null
            if (!current.startsWith("https://")) return null
        }
        return null
    }

    /** 清单路径已由 [SchoolRemoteIndex.isSafeRelativeFile] 限定为仓库内相对路径 */
    private fun resolveFileUrl(file: String): String = baseUrl + file

    private fun cacheFile(name: String): File = File(cacheDir, name)

    private companion object {
        const val MAX_REDIRECTS = 3
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

        /** 缓存文件名只允许安全字符（id 由声明校验约束为 `[a-z0-9_]`，双保险防路径注入） */
        fun cachedSchoolFileName(id: String): String =
            SchoolRemoteConfig.SCHOOLS_CACHE_PREFIX +
                id.replace(Regex("[^a-z0-9_]"), "_") + ".json"
    }
}

/** HTTP 2xx 才算成功：Gitee 的 404/错误页不能当 JSON 解析 */
private val HttpResponse.isSuccess: Boolean get() = status in 200..299

/** 记录单条清单条目的跳过原因（仅调试日志），并返回 null 供 `mapNotNull` 直接返回 */
private fun skip(
    target: MutableList<RemoteSkippedSchool>,
    id: String,
    reason: String,
): SchoolDeclaration? {
    target += RemoteSkippedSchool(id, reason)
    SyncDebugLog.d("remote school '$id' skipped: $reason")
    return null
}

/** 清单层失败：记录原因（仅调试日志）并构造结果；缓存由调用方保持原样 */
private fun failed(reason: String): RemoteCatalogResult.Failed {
    SyncDebugLog.d("remote catalog refresh failed: $reason")
    return RemoteCatalogResult.Failed(reason)
}
