package com.fangyi.classpp.data.update

import com.fangyi.classpp.data.sync.remote.SchoolRemoteConfig
import java.net.URI
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * App 更新清单的地址与硬上限（清单由远端分发仓库 classpp_schoolsync 维护，
 * 与远端学校声明同一个仓库根地址，见 README §7）。
 *
 * 检查是**纯手动**的：只有用户在关于页点「检查更新 / 更新日志」时才请求清单，
 * 启动、后台与定时任务都不访问这里。
 */
object AppUpdateConfig {
    /** `update.json`（main 分支）：版本、APK 直链与校验值、发布页、分语言更新说明 */
    const val MANIFEST_URL = SchoolRemoteConfig.BASE_URL + "update.json"

    /** 当前 App 支持的清单 schemaVersion 上限；更高版本整体忽略 */
    const val SUPPORTED_SCHEMA_VERSION = 1

    /** 清单响应体上限：正常清单只有几 KB，超过视为异常响应，避免坏页面撑爆解码 */
    const val MAX_MANIFEST_BYTES = 256 * 1024

    /** 单次更新 APK 上限：防止异常清单诱导下载超大文件 */
    const val MAX_APK_BYTES = 200L * 1024 * 1024

    /** 单语言 Markdown 更新说明的长度上限：远端文案再长也做一次截断保护 */
    const val MAX_CHANGELOG_MARKDOWN_CHARS = 20_000

    /** 更新日志最多展示的已发布版本数：远端历史再长也只保留最近这些版本 */
    const val MAX_RELEASE_HISTORY = 20

    /** APK SHA-256：64 位十六进制（大小写容错，比较前统一小写） */
    val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
}

/**
 * 远端 `update.json` 的线上结构。
 *
 * 所有字段都有默认值：新增字段对旧客户端可忽略（`ignoreUnknownKeys`），
 * 缺失的必填字段由 [AppUpdateChecker] 在解码后显式校验，不依赖反序列化异常。
 */
@Serializable
data class AppUpdateManifest(
    val schemaVersion: Int = 1,
    val packageName: String = "",
    val versionCode: Long = 0,
    val versionName: String = "",
    val minSupportedVersionCode: Long = 0,
    val publishedAt: String = "",
    val apk: AppUpdateApk? = null,
    val releasePageUrl: String? = null,
    /** 最新版本的分语言 Markdown 更新说明（`zh` / `en`） */
    val changelog: Map<String, String> = emptyMap(),
    /**
     * 已发布版本的历史更新说明（可选）：App 按 versionCode 降序展示；
     * 缺省或为空时回退到顶层 [changelog]（只显示最新一版）。
     */
    val releases: List<AppUpdateReleaseManifest> = emptyList(),
)

/**
 * 单个已发布版本的说明（`releases` 数组项）。
 * 版本号必须 ≤ 顶层 [AppUpdateManifest.versionCode]，避免把尚未发布的版本写进历史。
 */
@Serializable
data class AppUpdateReleaseManifest(
    val versionCode: Long = 0,
    val versionName: String = "",
    val publishedAt: String = "",
    /** 该版本的分语言 Markdown 更新说明（`zh` / `en`） */
    val changelog: Map<String, String> = emptyMap(),
)

/** APK 元数据：直链按顺序尝试，下载后必须同时通过大小与 SHA-256 校验 */
@Serializable
data class AppUpdateApk(
    val urls: List<String> = emptyList(),
    val sizeBytes: Long = 0,
    val sha256: String = "",
)

/** 只接受 https、有主机且不带 userinfo 的绝对 URL（与学校声明的信任边界一致） */
internal fun String.isSafeHttpsUrl(): Boolean = runCatching {
    val uri = URI(this)
    uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null
}.getOrDefault(false)

/**
 * 取分语言 Markdown 更新说明：先按应用语言（zh/en）取，缺失或空白回退中文，再回退英文；
 * 结果做 trim 与长度截断。返回空串表示没有可展示说明。
 */
internal fun Map<String, String>.markdownFor(language: String): String {
    val normalized = language.lowercase(Locale.ROOT)
    val preferred = when {
        normalized.startsWith("zh") -> "zh"
        normalized.startsWith("en") -> "en"
        // App 只提供 zh/en，其他系统语言按项目既有语义回落中文
        else -> "zh"
    }
    val markdown = this[preferred]?.takeIf { it.isNotBlank() }
        ?: this["zh"]?.takeIf { it.isNotBlank() }
        ?: this["en"].orEmpty()
    return markdown.trim().take(AppUpdateConfig.MAX_CHANGELOG_MARKDOWN_CHARS)
}
