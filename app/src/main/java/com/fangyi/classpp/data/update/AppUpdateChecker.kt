package com.fangyi.classpp.data.update

import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.sync.SyncDebugLog
import com.fangyi.classpp.data.sync.net.HttpTransport
import com.fangyi.classpp.data.sync.net.OkHttpTransport
import com.fangyi.classpp.data.sync.net.executeFollowingHttpsRedirects
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一次手动检查的结果：已是最新、发现可更新版本，或清单层失败（原因仅进调试日志） */
sealed interface UpdateCheckResult {
    /** 远端 versionCode ≤ 当前：可展示远端清单里的更新说明（更新日志入口） */
    data class UpToDate(
        val changelog: List<String>,
    ) : UpdateCheckResult

    /** 远端 versionCode > 当前：已校验并裁剪过的可展示/可下载数据 */
    data class Available(val update: AvailableUpdate) : UpdateCheckResult

    /** 网络、HTTP、JSON 或字段校验失败；[reason] 只用于 [SyncDebugLog] */
    data class Failed(val reason: String) : UpdateCheckResult
}

/**
 * 更新提示所需的清单快照：只保留通过校验的 https 直链与发布页。
 * [apkUrls] 为空或 [canDownload] = false 时只能走 [releasePageUrl] 手动下载。
 */
data class AvailableUpdate(
    val versionName: String,
    val versionCode: Long,
    val publishedAt: String,
    val changelog: List<String>,
    val apkUrls: List<String>,
    val apkSizeBytes: Long,
    val apkSha256: String,
    val releasePageUrl: String?,
    val forced: Boolean,
) {
    /** 有直链且大小/哈希合法才尝试应用内下载 */
    val canDownload: Boolean
        get() = apkUrls.isNotEmpty() &&
            apkSizeBytes in 1..AppUpdateConfig.MAX_APK_BYTES &&
            AppUpdateConfig.SHA256_PATTERN.matches(apkSha256)

    val canOpenRelease: Boolean get() = releasePageUrl != null
}

/**
 * 手动检查更新：拉取远端 `update.json` → 字段校验 → `versionCode` 比对。
 *
 * - **只由 UI 手动调用**，不做启动/后台/定时检查，也没有本地缓存或自动重试；
 * - [versionCode][AppUpdateManifest.versionCode] 是更新与否的唯一依据（versionName 只用于展示）；
 * - 清单层失败返回 [UpdateCheckResult.Failed]，不抛出异常；
 * - APK 直链只保留 https；大小与 SHA-256 不合法时降级为「只打开发布页」，
 *   两者都没有才判定清单不可用。
 *
 * 纯 JVM 可测：不依赖 Android Context（包名/当前版本/语言由调用方注入）。
 */
class AppUpdateChecker(
    private val transport: HttpTransport = OkHttpTransport(),
    private val manifestUrl: String = AppUpdateConfig.MANIFEST_URL,
    private val packageName: String,
    private val currentVersionCode: Long,
    private val language: String = "zh",
) {

    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (currentVersionCode <= 0) return@withContext failed("local versionCode unavailable")

        // 协程取消必须原样抛出：用户关掉浮层后不能再落一个 Failed 状态回去
        val response = try {
            transport.executeFollowingHttpsRedirects(manifestUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return@withContext failed("manifest fetch failed")
        if (response.status !in 200..299) {
            return@withContext failed("manifest HTTP ${response.status}")
        }
        if (response.body.length > AppUpdateConfig.MAX_MANIFEST_BYTES) {
            return@withContext failed("manifest body too large (${response.body.length} chars)")
        }

        val manifest = runCatching {
            ScheduleJson.decodeStorage<AppUpdateManifest>(response.body)
        }.getOrNull() ?: return@withContext failed("manifest decode failed")

        if (manifest.schemaVersion !in 1..AppUpdateConfig.SUPPORTED_SCHEMA_VERSION) {
            return@withContext failed("unsupported manifest schemaVersion ${manifest.schemaVersion}")
        }
        if (manifest.packageName != packageName) {
            return@withContext failed("manifest packageName '${manifest.packageName}' != '$packageName'")
        }
        if (manifest.versionCode <= 0) {
            return@withContext failed("manifest versionCode must be positive")
        }
        val versionName = manifest.versionName.trim()
        if (versionName.isEmpty()) return@withContext failed("manifest versionName is blank")

        val changelog = manifest.changelog.changelogFor(language)
        if (manifest.versionCode <= currentVersionCode) {
            return@withContext UpdateCheckResult.UpToDate(changelog = changelog)
        }

        val apk = manifest.apk
        val apkUrls = apk?.urls.orEmpty()
            .map { it.trim() }
            .filter { it.isSafeHttpsUrl() }
            .distinct()
        val apkSizeBytes = apk?.sizeBytes ?: 0L
        val apkSha256 = apk?.sha256.orEmpty().trim().lowercase(Locale.ROOT)
        val downloadable = apkUrls.isNotEmpty() &&
            apkSizeBytes in 1..AppUpdateConfig.MAX_APK_BYTES &&
            AppUpdateConfig.SHA256_PATTERN.matches(apkSha256)
        val releasePageUrl = manifest.releasePageUrl
            ?.trim()
            ?.takeIf { it.isSafeHttpsUrl() }

        if (!downloadable && releasePageUrl == null) {
            return@withContext failed("manifest has neither a usable APK url nor a release page")
        }

        UpdateCheckResult.Available(
            AvailableUpdate(
                versionName = versionName,
                versionCode = manifest.versionCode,
                publishedAt = manifest.publishedAt.trim(),
                changelog = changelog,
                // 校验不过的 APK 元数据整体丢弃：宁可只给发布页，也不把坏哈希交给下载器
                apkUrls = if (downloadable) apkUrls else emptyList(),
                apkSizeBytes = if (downloadable) apkSizeBytes else 0L,
                apkSha256 = if (downloadable) apkSha256 else "",
                releasePageUrl = releasePageUrl,
                forced = currentVersionCode < manifest.minSupportedVersionCode,
            ),
        )
    }

    private fun failed(reason: String): UpdateCheckResult.Failed {
        SyncDebugLog.d("app update check failed: $reason")
        return UpdateCheckResult.Failed(reason)
    }
}
