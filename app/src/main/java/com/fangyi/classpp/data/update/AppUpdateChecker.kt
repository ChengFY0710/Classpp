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
    /** 远端 versionCode ≤ 当前：带回可展示的版本历史（更新日志入口） */
    data class UpToDate(
        val releases: List<AppUpdateRelease>,
    ) : UpdateCheckResult

    /** 远端 versionCode > 当前：已校验并裁剪过的可展示/可下载数据 */
    data class Available(val update: AvailableUpdate) : UpdateCheckResult

    /** 网络、HTTP、JSON 或字段校验失败；[reason] 只用于 [SyncDebugLog] */
    data class Failed(val reason: String) : UpdateCheckResult
}

/**
 * 「更新日志」浮层的加载结果：只含**当前安装版本及更早版本**的说明，
 * 不代表任何「是否有新版本」的判断。
 */
sealed interface ChangelogResult {
    data class Success(val releases: List<AppUpdateRelease>) : ChangelogResult

    /** 网络、HTTP、JSON 或字段校验失败；[reason] 只用于 [SyncDebugLog] */
    data class Failed(val reason: String) : ChangelogResult
}

/**
 * 更新日志中的单个版本记录：已按语言取好 Markdown、按 versionCode 降序排列。
 * [changelog] 为空串表示该版本没有可展示的说明，UI 可跳过。
 */
data class AppUpdateRelease(
    val versionCode: Long,
    val versionName: String,
    val publishedAt: String,
    val changelog: String,
)

/**
 * 更新提示所需的清单快照：只保留通过校验的 https 直链与发布页。
 * [apkUrls] 为空或 [canDownload] = false 时只能走 [releasePageUrl] 手动下载。
 */
data class AvailableUpdate(
    val versionName: String,
    val versionCode: Long,
    val publishedAt: String,
    val apkUrls: List<String>,
    val apkSizeBytes: Long,
    val apkSha256: String,
    val releasePageUrl: String?,
    val forced: Boolean,
    /** 版本历史（降序）；更新提示只展示与 [versionCode] 匹配的那一版说明 */
    val releases: List<AppUpdateRelease>,
) {
    /** 当前待更新版本的 Markdown 说明（来自版本历史，找不到时为空串） */
    val changelog: String
        get() = releases.firstOrNull { it.versionCode == versionCode }?.changelog.orEmpty()

    /** 有直链且大小/哈希合法才尝试应用内下载 */
    val canDownload: Boolean
        get() = apkUrls.isNotEmpty() &&
            apkSizeBytes in 1..AppUpdateConfig.MAX_APK_BYTES &&
            AppUpdateConfig.SHA256_PATTERN.matches(apkSha256)

    val canOpenRelease: Boolean get() = releasePageUrl != null
}

/**
 * 手动更新入口的数据层，拉取远端 `update.json` → 字段校验：
 *
 * - [check]：`versionCode` 比对，返回是否有新版本及下载所需元数据；
 * - [loadChangelog]：只返回当前安装版本及更早版本的更新说明，不做版本比对；
 * - **只由 UI 手动调用**，不做启动/后台/定时检查，也没有本地缓存或自动重试；
 * - [versionCode][AppUpdateManifest.versionCode] 是更新与否的唯一依据（versionName 只用于展示）；
 * - 清单层失败返回 `Failed`，不抛出异常；
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
        val loaded = when (val result = loadManifest()) {
            is ManifestLoad.Failed -> return@withContext failed(result.reason)
            is ManifestLoad.Success -> result
        }
        val manifest = loaded.manifest
        val releaseHistory = buildReleaseHistory(
            manifest = manifest,
            latestVersionName = loaded.versionName,
            latestChangelog = loaded.changelog,
        )
        if (manifest.versionCode <= currentVersionCode) {
            return@withContext UpdateCheckResult.UpToDate(releases = releaseHistory)
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
                versionName = loaded.versionName,
                versionCode = manifest.versionCode,
                publishedAt = manifest.publishedAt.trim(),
                // 校验不过的 APK 元数据整体丢弃：宁可只给发布页，也不把坏哈希交给下载器
                apkUrls = if (downloadable) apkUrls else emptyList(),
                apkSizeBytes = if (downloadable) apkSizeBytes else 0L,
                apkSha256 = if (downloadable) apkSha256 else "",
                releasePageUrl = releasePageUrl,
                forced = currentVersionCode < manifest.minSupportedVersionCode,
                releases = releaseHistory,
            ),
        )
    }

    /**
     * 加载「更新日志」浮层的数据：只返回**当前安装版本及更早版本**的说明。
     *
     * 与 [check] 完全独立：不做 versionCode 比对，也不要求清单带有可用的 APK/发布页，
     * 因此「有新版本但下载信息缺失」不会影响历史说明的展示。
     */
    suspend fun loadChangelog(): ChangelogResult = withContext(Dispatchers.IO) {
        val loaded = when (val result = loadManifest()) {
            is ManifestLoad.Failed -> return@withContext changelogFailed(result.reason)
            is ManifestLoad.Success -> result
        }
        ChangelogResult.Success(
            releases = buildReleaseHistory(
                manifest = loaded.manifest,
                latestVersionName = loaded.versionName,
                latestChangelog = loaded.changelog,
                maxVersionCode = currentVersionCode,
            ),
        )
    }

    /**
     * 拉取并校验清单的公共部分：更新检查与更新日志共用同一套信任边界
     * （https 重定向、响应体上限、schema/包名/版本号校验）。
     * 失败原因只交给 [SyncDebugLog]，不向 UI 抛出异常。
     */
    private suspend fun loadManifest(): ManifestLoad {
        if (currentVersionCode <= 0) return ManifestLoad.Failed("local versionCode unavailable")

        // 协程取消必须原样抛出：用户关掉浮层后不能再落一个 Failed 状态回去
        val response = try {
            transport.executeFollowingHttpsRedirects(manifestUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return ManifestLoad.Failed("manifest fetch failed")
        if (response.status !in 200..299) {
            return ManifestLoad.Failed("manifest HTTP ${response.status}")
        }
        if (response.body.length > AppUpdateConfig.MAX_MANIFEST_BYTES) {
            return ManifestLoad.Failed("manifest body too large (${response.body.length} chars)")
        }

        val manifest = runCatching {
            ScheduleJson.decodeStorage<AppUpdateManifest>(response.body)
        }.getOrNull() ?: return ManifestLoad.Failed("manifest decode failed")

        if (manifest.schemaVersion !in 1..AppUpdateConfig.SUPPORTED_SCHEMA_VERSION) {
            return ManifestLoad.Failed("unsupported manifest schemaVersion ${manifest.schemaVersion}")
        }
        if (manifest.packageName != packageName) {
            return ManifestLoad.Failed("manifest packageName '${manifest.packageName}' != '$packageName'")
        }
        if (manifest.versionCode <= 0) {
            return ManifestLoad.Failed("manifest versionCode must be positive")
        }
        val versionName = manifest.versionName.trim()
        if (versionName.isEmpty()) return ManifestLoad.Failed("manifest versionName is blank")

        return ManifestLoad.Success(
            manifest = manifest,
            versionName = versionName,
            changelog = manifest.changelog.markdownFor(language),
        )
    }

    /** 清单公共校验的中间结果：成功时带上解析好的版本名与当前语言说明 */
    private sealed interface ManifestLoad {
        data class Success(
            val manifest: AppUpdateManifest,
            val versionName: String,
            val changelog: String,
        ) : ManifestLoad

        data class Failed(val reason: String) : ManifestLoad
    }

    /**
     * 把顶层最新版本与可选 `releases` 合并成展示用历史：
     *
     * - 只接受 `versionCode ∈ [1, min(顶层 versionCode, [maxVersionCode])]` 且 versionName
     *   非空的条目，既防止把尚未发布的版本写进历史，也支持「更新日志」按当前安装版本截断；
     * - 按 versionCode 降序、同版本去重，最多 [AppUpdateConfig.MAX_RELEASE_HISTORY] 条；
     * - `releases` 缺省时回退为「顶层最新版本」单条（兼容旧清单）；
     * - `releases` 里缺顶层最新版本时补一条顶层记录；最新版本条目的说明为空时
     *   用顶层 `changelog` 兜底。
     */
    private fun buildReleaseHistory(
        manifest: AppUpdateManifest,
        latestVersionName: String,
        latestChangelog: String,
        maxVersionCode: Long = manifest.versionCode,
    ): List<AppUpdateRelease> {
        val latestVersionCode = manifest.versionCode
        // 历史版本上界：清单最新版本与调用方上界取小（更新日志 = 当前安装版本）
        val upperBound = minOf(latestVersionCode, maxVersionCode)
        val declared = manifest.releases
            .asSequence()
            .filter { it.versionCode in 1..upperBound }
            .mapNotNull { release ->
                val name = release.versionName.trim()
                if (name.isEmpty()) null else release to name
            }
            .sortedByDescending { (release, _) -> release.versionCode }
            .distinctBy { (release, _) -> release.versionCode }
            .map { (release, name) ->
                val notes = release.changelog.markdownFor(language)
                AppUpdateRelease(
                    versionCode = release.versionCode,
                    versionName = name,
                    publishedAt = release.publishedAt.trim(),
                    // 最新版本的 release 条目没写说明时，用顶层 changelog 兜底
                    changelog = if (release.versionCode == latestVersionCode && notes.isEmpty()) {
                        latestChangelog
                    } else {
                        notes
                    },
                )
            }
            .toList()

        val withLatest = when {
            declared.any { it.versionCode == latestVersionCode } -> declared
            // 顶层最新版本高于上界（查看更新日志时本地版本更旧）：不补，避免混入更新提示
            latestVersionCode > upperBound -> declared
            else -> listOf(
                AppUpdateRelease(
                    versionCode = latestVersionCode,
                    versionName = latestVersionName,
                    publishedAt = manifest.publishedAt.trim(),
                    changelog = latestChangelog,
                ),
            ) + declared
        }

        return withLatest
            .sortedByDescending { it.versionCode }
            .take(AppUpdateConfig.MAX_RELEASE_HISTORY)
    }

    private fun failed(reason: String): UpdateCheckResult.Failed {
        SyncDebugLog.d("app update check failed: $reason")
        return UpdateCheckResult.Failed(reason)
    }

    private fun changelogFailed(reason: String): ChangelogResult.Failed {
        SyncDebugLog.d("app update changelog load failed: $reason")
        return ChangelogResult.Failed(reason)
    }
}
