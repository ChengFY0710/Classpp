package com.fangyi.classpp.ui.settings

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.appVersionCode
import com.fangyi.classpp.appVersionName
import com.fangyi.classpp.data.update.ApkDownloadError
import com.fangyi.classpp.data.update.ApkDownloadResult
import com.fangyi.classpp.data.update.ApkDownloader
import com.fangyi.classpp.data.update.ApkInstaller
import com.fangyi.classpp.data.update.AppUpdateChecker
import com.fangyi.classpp.data.update.AppUpdateRelease
import com.fangyi.classpp.data.update.AvailableUpdate
import com.fangyi.classpp.data.update.UpdateCheckResult
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SettingsInfoHeaderCard
import com.fangyi.classpp.ui.components.SheetCard
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetInfoBanner
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.classppColors
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 关于页两个更新入口：检查最新版本 / 只看更新说明 */
internal enum class AboutUpdateAction { Check, Changelog }

/**
 * 一次更新入口请求。[id] 单调递增：同一 Action 连续点击也能重新触发检查，
 * 不用把「请求已消费」写回 AboutScreen。
 */
internal data class AboutUpdateRequest(
    val id: Long,
    val action: AboutUpdateAction,
)

/** changelog 条目之间的纵向节奏（条目之间，不是卡片内距） */
private val UpdateChangelogLineSpacing = 10.dp

/** 单条 changelog 圆点与文字的间距（图标↔文本四档中的 8dp） */
private val UpdateChangelogBulletGap = 8.dp

/** 下载进度块内部的文字/进度条间距 */
private val UpdateProgressTextGap = 8.dp
private val UpdateProgressSizeGap = 6.dp

/** 浮层状态：检查、结果、下载、安装授权与失败都在同一个 OverlaySheet 内切换 */
private sealed interface UpdateSheetState {
    data class Checking(val action: AboutUpdateAction) : UpdateSheetState

    data class UpToDate(
        val fromChangelog: Boolean,
        val releases: List<AppUpdateRelease>,
    ) : UpdateSheetState

    data class Available(
        val update: AvailableUpdate,
        val fromChangelog: Boolean,
    ) : UpdateSheetState

    data class Downloading(
        val update: AvailableUpdate,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val fromChangelog: Boolean,
    ) : UpdateSheetState {
        val fraction: Float
            get() = if (totalBytes <= 0L) {
                0f
            } else {
                (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            }
    }

    data class InstallPermission(
        val update: AvailableUpdate,
        val fromChangelog: Boolean,
    ) : UpdateSheetState

    data class Failed(
        val kind: UpdateFailureKind,
        val update: AvailableUpdate?,
        val action: AboutUpdateAction,
    ) : UpdateSheetState
}

private enum class UpdateFailureKind { Check, Download, Verify, Install }

/** OverlaySheet 顶栏/底部动作的当前配置；[dismissible] = 遮罩/返回/下拉是否允许关闭 */
private data class UpdateSheetSpec(
    val title: String,
    val confirmLabel: String? = null,
    val confirmIcon: Int = R.drawable.ic_checkmark_circle,
    val onConfirm: () -> Unit = {},
    val rightAction: SheetTopAction,
    val dismissible: Boolean,
)

/**
 * 关于页「检查更新 / 更新日志」浮层（两者共用同一套 [OverlaySheet] 状态机）：
 *
 * - 点击入口立即拉起浮层并显示「检查中」，结果在原浮层内切换；
 * - 有新版本时展示版本、日期与 changelog；可应用内下载并校验 APK，失败回退发布页；
 * - `forced`（当前版本低于 `minSupportedVersionCode`）时浮层不可关闭，只保留更新动作；
 * - 检查/下载协程由本组件持有，关闭或取消时一并取消，不把结果写回已关闭的浮层。
 *
 * 明确的信任边界：清单与 APK 直链只接受 https；APK 下载后必须同时通过大小与 SHA-256；
 * 安装交给系统安装器，本组件不做静默安装。
 */
@Composable
internal fun AboutUpdateSheet(
    request: AboutUpdateRequest?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 配置感知语言：随系统/应用语言切换重组，不用 LocalContext 里可能过期的 configuration
    val configuration = LocalConfiguration.current
    val language = configuration.locales[0].language
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val localVersionName = remember(context) { appVersionName(context) }
    val checker = remember(context, language) {
        AppUpdateChecker(
            packageName = context.packageName,
            currentVersionCode = appVersionCode(context),
            language = language,
        )
    }
    val downloader = remember(context) { ApkDownloader(File(context.cacheDir, "updates")) }
    val permissionFailedText =
        stringResource(R.string.about_update_permission_settings_failed)
    val releaseFailedText =
        stringResource(R.string.about_update_open_release_failed)

    var state by remember { mutableStateOf<UpdateSheetState?>(null) }
    var visible by remember { mutableStateOf(false) }
    var currentJob by remember { mutableStateOf<Job?>(null) }

    fun dismiss() {
        currentJob?.cancel()
        currentJob = null
        visible = false
    }

    fun openRelease(update: AvailableUpdate) {
        val url = update.releasePageUrl ?: return
        if (!runCatching { uriHandler.openUri(url) }.isSuccess) {
            AppToasts.show(context, releaseFailedText)
        }
    }

    fun startDownload(update: AvailableUpdate, fromChangelog: Boolean) {
        // 防同一帧双击：下载已启动后忽略重复触发，避免取消/重启协程互相清临时文件
        if (state is UpdateSheetState.Downloading) return
        if (!update.canDownload) {
            openRelease(update)
            return
        }
        if (!ApkInstaller.canInstall(context)) {
            state = UpdateSheetState.InstallPermission(update, fromChangelog)
            return
        }
        currentJob?.cancel()
        state = UpdateSheetState.Downloading(
            update = update,
            downloadedBytes = 0L,
            totalBytes = update.apkSizeBytes,
            fromChangelog = fromChangelog,
        )
        currentJob = scope.launch {
            val result = downloader.download(
                urls = update.apkUrls,
                expectedSizeBytes = update.apkSizeBytes,
                expectedSha256 = update.apkSha256,
                versionName = update.versionName,
                versionCode = update.versionCode,
                onProgress = { downloaded, total ->
                    withContext(Dispatchers.Main.immediate) {
                        // 取消后可能还有一帧进度回调在途：任务已取消就不再回写，避免进度态复活
                        if (currentCoroutineContext().isActive) {
                            state = UpdateSheetState.Downloading(
                                update = update,
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                fromChangelog = fromChangelog,
                            )
                        }
                    }
                },
            )
            when (result) {
                is ApkDownloadResult.Ready -> {
                    if (ApkInstaller.install(context, result.file)) {
                        // 交给系统安装器后本浮层直接退场；返回 App 时不再停在旧进度态
                        visible = false
                        currentJob = null
                    } else {
                        state = UpdateSheetState.Failed(
                            kind = UpdateFailureKind.Install,
                            update = update,
                            action = actionOf(fromChangelog),
                        )
                    }
                }
                is ApkDownloadResult.Failed -> {
                    state = UpdateSheetState.Failed(
                        kind = if (result.reason == ApkDownloadError.Verify) {
                            UpdateFailureKind.Verify
                        } else {
                            UpdateFailureKind.Download
                        },
                        update = update,
                        action = actionOf(fromChangelog),
                    )
                }
            }
        }
    }

    fun runCheck(action: AboutUpdateAction) {
        currentJob?.cancel()
        state = UpdateSheetState.Checking(action)
        visible = true
        currentJob = scope.launch {
            when (val result = checker.check()) {
                is UpdateCheckResult.UpToDate -> state = UpdateSheetState.UpToDate(
                    fromChangelog = action == AboutUpdateAction.Changelog,
                    releases = result.releases,
                )
                is UpdateCheckResult.Available -> state = UpdateSheetState.Available(
                    update = result.update,
                    fromChangelog = action == AboutUpdateAction.Changelog,
                )
                is UpdateCheckResult.Failed -> state = UpdateSheetState.Failed(
                    kind = UpdateFailureKind.Check,
                    update = null,
                    action = action,
                )
            }
        }
    }

    LaunchedEffect(request?.id) {
        val currentRequest = request ?: return@LaunchedEffect
        runCheck(currentRequest.action)
    }

    val current = state ?: return

    val checkTitle = stringResource(R.string.about_check_update)
    val changelogTitle = stringResource(R.string.about_changelog)
    val closeLabel = stringResource(R.string.switcher_close)
    val cancelLabel = stringResource(R.string.settings_cancel)
    val backLabel = stringResource(R.string.about_update_action_back)
    val updateLabel = stringResource(R.string.about_update_action_update)
    val releaseLabel = stringResource(R.string.about_update_action_open_release)
    val settingsLabel = stringResource(R.string.about_update_action_open_settings)
    val retryLabel = stringResource(R.string.about_update_action_retry)
    val errorTitle = stringResource(R.string.about_update_error_title)
    val checkErrorTitle = stringResource(R.string.about_update_check_error_title)

    val spec: UpdateSheetSpec = when (current) {
        is UpdateSheetState.Checking -> UpdateSheetSpec(
            title = if (current.action == AboutUpdateAction.Changelog) {
                changelogTitle
            } else {
                checkTitle
            },
            rightAction = sheetAction(cancelLabel, R.drawable.ic_dismiss_circle) { dismiss() },
            dismissible = false,
        )

        is UpdateSheetState.UpToDate -> UpdateSheetSpec(
            title = if (current.fromChangelog) changelogTitle else checkTitle,
            rightAction = sheetAction(closeLabel, R.drawable.ic_dismiss_circle) { dismiss() },
            dismissible = true,
        )

        is UpdateSheetState.Available -> {
            val actionLabel = if (current.update.canDownload) updateLabel else releaseLabel
            val actionIcon = if (current.update.canDownload) {
                R.drawable.ic_arrow_download
            } else {
                R.drawable.ic_arrow_export_up
            }
            val action: () -> Unit = {
                if (current.update.canDownload) {
                    startDownload(current.update, current.fromChangelog)
                } else {
                    openRelease(current.update)
                }
            }
            val title = if (current.fromChangelog) {
                changelogTitle
            } else {
                stringResource(R.string.about_update_available_title)
            }
            if (current.update.forced) {
                // 强制更新：只保留唯一主操作，禁用遮罩/返回/下拉关闭
                UpdateSheetSpec(
                    title = title,
                    rightAction = sheetAction(
                        label = actionLabel,
                        icon = actionIcon,
                        primary = true,
                        onClick = action,
                    ),
                    dismissible = false,
                )
            } else {
                UpdateSheetSpec(
                    title = title,
                    confirmLabel = actionLabel,
                    confirmIcon = actionIcon,
                    onConfirm = action,
                    rightAction = sheetAction(
                        closeLabel,
                        R.drawable.ic_dismiss_circle,
                    ) { dismiss() },
                    dismissible = true,
                )
            }
        }

        is UpdateSheetState.Downloading -> UpdateSheetSpec(
            title = stringResource(R.string.about_update_downloading_title),
            rightAction = sheetAction(cancelLabel, R.drawable.ic_dismiss_circle) {
                currentJob?.cancel()
                currentJob = null
                state = UpdateSheetState.Available(
                    update = current.update,
                    fromChangelog = current.fromChangelog,
                )
            },
            dismissible = false,
        )

        is UpdateSheetState.InstallPermission -> UpdateSheetSpec(
            title = stringResource(R.string.about_update_permission_title),
            confirmLabel = settingsLabel,
            confirmIcon = R.drawable.ic_arrow_circle_right,
            onConfirm = {
                if (ApkInstaller.openInstallPermissionSettings(context)) {
                    // 授权页返回后停在更新提示，用户可直接再次点「立即更新」
                    state = UpdateSheetState.Available(
                        update = current.update,
                        fromChangelog = current.fromChangelog,
                    )
                } else {
                    AppToasts.show(context, permissionFailedText)
                }
            },
            rightAction = sheetAction(backLabel, R.drawable.ic_arrow_circle_left) {
                state = UpdateSheetState.Available(
                    update = current.update,
                    fromChangelog = current.fromChangelog,
                )
            },
            dismissible = false,
        )

        is UpdateSheetState.Failed -> {
            val update = current.update
            val forcedUpdate = update?.takeIf { it.forced }
            val primaryAction: () -> Unit = when {
                current.kind == UpdateFailureKind.Check -> ({ runCheck(current.action) })
                update != null -> ({
                    startDownload(
                        update,
                        fromChangelog = current.action == AboutUpdateAction.Changelog,
                    )
                })
                else -> ({ runCheck(current.action) })
            }
            val rightAction = if (forcedUpdate != null) {
                // 强制更新失败：先回到不可关闭的更新提示，避免把用户留在失败页
                sheetAction(backLabel, R.drawable.ic_arrow_circle_left) {
                    state = UpdateSheetState.Available(
                        update = forcedUpdate,
                        fromChangelog = current.action == AboutUpdateAction.Changelog,
                    )
                }
            } else {
                sheetAction(closeLabel, R.drawable.ic_dismiss_circle) { dismiss() }
            }
            UpdateSheetSpec(
                title = if (current.kind == UpdateFailureKind.Check) {
                    checkErrorTitle
                } else {
                    errorTitle
                },
                confirmLabel = retryLabel,
                confirmIcon = R.drawable.ic_arrow_repeat,
                onConfirm = primaryAction,
                rightAction = rightAction,
                dismissible = forcedUpdate == null,
            )
        }
    }

    BackHandler(enabled = visible) {
        when {
            spec.dismissible -> dismiss()
            current is UpdateSheetState.InstallPermission -> {
                state = UpdateSheetState.Available(
                    update = current.update,
                    fromChangelog = current.fromChangelog,
                )
            }
            current is UpdateSheetState.Failed && current.update?.forced == true -> {
                val update = current.update
                state = UpdateSheetState.Available(
                    update = update,
                    fromChangelog = current.action == AboutUpdateAction.Changelog,
                )
            }
            // Checking / Downloading / 强制更新提示：返回键不打断，只能点顶栏动作
        }
    }

    OverlaySheet(
        title = spec.title,
        confirmLabel = spec.confirmLabel,
        confirmIcon = spec.confirmIcon,
        onConfirm = spec.onConfirm,
        rightAction = spec.rightAction,
        onDismiss = { if (spec.dismissible) dismiss() },
        visible = visible,
        onDismissed = {
            if (!visible) {
                state = null
                currentJob = null
            }
        },
        dragDismissEnabled = spec.dismissible,
        imeBehavior = SheetImeBehavior.IgnoreIme,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween),
        ) {
            when (current) {
                is UpdateSheetState.Checking -> UpdateCheckingContent()

                is UpdateSheetState.UpToDate -> UpdateUpToDateContent(
                    localVersionName = localVersionName,
                    fromChangelog = current.fromChangelog,
                    releases = current.releases,
                )

                is UpdateSheetState.Available -> UpdateAvailableContent(
                    update = current.update,
                    fromChangelog = current.fromChangelog,
                )

                is UpdateSheetState.Downloading -> UpdateDownloadingContent(current)

                is UpdateSheetState.InstallPermission -> UpdatePermissionContent(current.update)

                is UpdateSheetState.Failed -> {
                    val update = current.update
                    val releaseAction: (() -> Unit)? =
                        if (update != null && update.canOpenRelease) {
                            { openRelease(update) }
                        } else {
                            null
                        }
                    UpdateFailedContent(
                        kind = current.kind,
                        update = update,
                        localVersionName = localVersionName,
                        onOpenRelease = releaseAction,
                    )
                }
            }
        }
    }
}

/** 检查中：与同步/备份加载卡同款「小转圈 + 文案」 */
@Composable
private fun UpdateCheckingContent() {
    CardSection {
        SheetCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 3.dp,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.about_update_checking),
                    style = ClassppTextTokens.dialogMessage,
                    color = MaterialTheme.classppColors.secondaryText,
                )
            }
        }
    }
}

/** 已是最新：更新日志入口按版本分组展示已发布版本的历史说明 */
@Composable
private fun UpdateUpToDateContent(
    localVersionName: String,
    fromChangelog: Boolean,
    releases: List<AppUpdateRelease>,
) {
    CardSection {
        SettingsInfoHeaderCard(
            icon = R.drawable.ic_checkmark_circle,
            title = stringResource(R.string.about_update_up_to_date_title),
            description = stringResource(
                R.string.about_update_up_to_date_desc,
                localVersionName,
            ),
        )
    }
    if (fromChangelog) {
        UpdateReleaseHistory(releases)
    }
}

/**
 * 更新日志历史：按版本分组，只渲染有说明的版本；全空时给一行「暂无更新说明」。
 * 更新提示（检查更新入口）只展示目标版本说明，不走这里。
 */
@Composable
private fun UpdateReleaseHistory(releases: List<AppUpdateRelease>) {
    val withNotes = releases.filter { it.changelog.isNotEmpty() }
    if (withNotes.isEmpty()) {
        CardSection(title = stringResource(R.string.about_update_section_changelog)) {
            UpdateChangelogCard(emptyList())
        }
    } else {
        withNotes.forEach { release ->
            CardSection(title = updateReleaseTitle(release)) {
                UpdateChangelogCard(release.changelog)
            }
        }
    }
}

/** 有新版本：版本头部 + 强制提示；「更新日志」入口展示完整历史，检查更新入口只展示目标版本 */
@Composable
private fun UpdateAvailableContent(
    update: AvailableUpdate,
    fromChangelog: Boolean,
) {
    CardSection {
        SettingsInfoHeaderCard(
            icon = R.drawable.app_logo,
            title = stringResource(R.string.about_update_available_title),
            description = updateVersionDescription(update),
            tintIcon = false,
        )
        if (update.forced) {
            SheetInfoBanner(
                text = stringResource(R.string.about_update_forced_hint),
                isError = true,
            )
        }
    }
    if (fromChangelog) {
        UpdateReleaseHistory(update.releases)
    } else {
        CardSection(title = stringResource(R.string.about_update_section_changelog)) {
            UpdateChangelogCard(update.changelog)
        }
    }
}

/** 下载中：百分比 + 确定进度条 + 已下载/总大小 */
@Composable
private fun UpdateDownloadingContent(state: UpdateSheetState.Downloading) {
    val percent = (state.fraction * 100).toInt()
    val context = LocalContext.current
    CardSection {
        SettingsInfoHeaderCard(
            icon = R.drawable.ic_arrow_download,
            title = stringResource(R.string.about_update_downloading_title),
            description = stringResource(
                R.string.about_update_version_desc_no_date,
                state.update.versionName,
            ),
        )
        SheetCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.about_update_downloading_percent,
                        percent,
                    ),
                    style = ClassppTextTokens.settingsDescription,
                    color = MaterialTheme.classppColors.secondaryText,
                )
                Spacer(Modifier.height(UpdateProgressTextGap))
                LinearProgressIndicator(
                    progress = { state.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(UpdateProgressSizeGap))
                Text(
                    text = stringResource(
                        R.string.about_update_downloading_size,
                        Formatter.formatFileSize(context, state.downloadedBytes),
                        Formatter.formatFileSize(context, state.totalBytes),
                    ),
                    style = ClassppTextTokens.settingsDescription,
                    color = MaterialTheme.classppColors.secondaryText,
                )
            }
        }
    }
}

/** 缺少「安装未知应用」授权：明确说明去设置后返回重试 */
@Composable
private fun UpdatePermissionContent(update: AvailableUpdate) {
    CardSection {
        SettingsInfoHeaderCard(
            icon = R.drawable.ic_info_regular,
            title = stringResource(R.string.about_update_permission_title),
            description = updateVersionDescription(update),
        )
        SheetInfoBanner(
            text = stringResource(R.string.about_update_permission_message),
        )
    }
}

/** 失败：头部标明失败类型，正文给出对应原因与手动下载兜底 */
@Composable
private fun UpdateFailedContent(
    kind: UpdateFailureKind,
    update: AvailableUpdate?,
    localVersionName: String,
    onOpenRelease: (() -> Unit)?,
) {
    val titleRes = if (kind == UpdateFailureKind.Check) {
        R.string.about_update_check_error_title
    } else {
        R.string.about_update_error_title
    }
    val messageRes = when (kind) {
        UpdateFailureKind.Check -> R.string.about_update_check_failed
        UpdateFailureKind.Download -> R.string.about_update_download_failed
        UpdateFailureKind.Verify -> R.string.about_update_verify_failed
        UpdateFailureKind.Install -> R.string.about_update_install_failed
    }
    CardSection {
        SettingsInfoHeaderCard(
            icon = R.drawable.ic_info_regular,
            title = stringResource(titleRes),
            description = update?.let { updateVersionDescription(it) }
                ?: stringResource(R.string.about_current_version, localVersionName),
        )
        SheetInfoBanner(
            text = stringResource(messageRes),
            isError = true,
        )
    }
    if (onOpenRelease != null) {
        CardSection {
            SettingsCard(
                items = listOf(
                    SettingsCardItem.Nav(
                        label = stringResource(R.string.about_update_action_open_release),
                        onClick = onOpenRelease,
                    ),
                ),
            )
        }
    }
}

/** 更新内容卡：逐条圆点列表；没有说明时给一行中性占位 */
@Composable
private fun UpdateChangelogCard(entries: List<String>) {
    SheetCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.about_update_changelog_empty),
                    style = ClassppTextTokens.settingsDescription,
                    color = MaterialTheme.classppColors.secondaryText,
                )
            } else {
                entries.forEachIndexed { index, entry ->
                    if (index > 0) Spacer(Modifier.height(UpdateChangelogLineSpacing))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "•",
                            style = ClassppTextTokens.settingsDescription,
                            color = MaterialTheme.classppColors.secondaryText,
                        )
                        Spacer(Modifier.width(UpdateChangelogBulletGap))
                        Text(
                            text = entry,
                            style = ClassppTextTokens.settingsDescription,
                            color = MaterialTheme.classppColors.secondaryText,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** 版本展示文案：有发布日期时带上日期，缺省日期只显示版本名 */
@Composable
private fun updateVersionDescription(update: AvailableUpdate): String =
    if (update.publishedAt.isBlank()) {
        stringResource(R.string.about_update_version_desc_no_date, update.versionName)
    } else {
        stringResource(
            R.string.about_update_version_desc,
            update.versionName,
            update.publishedAt,
        )
    }

/** 更新日志分组标题：版本号 + 发布日期（与更新提示的版本描述同一套文案） */
@Composable
private fun updateReleaseTitle(release: AppUpdateRelease): String =
    if (release.publishedAt.isBlank()) {
        stringResource(R.string.about_update_version_desc_no_date, release.versionName)
    } else {
        stringResource(
            R.string.about_update_version_desc,
            release.versionName,
            release.publishedAt,
        )
    }

/** 顶栏右侧动作：主操作用 primary 配色，其余用浮层卡片同款 surface */
@Composable
private fun sheetAction(
    label: String,
    icon: Int,
    primary: Boolean = false,
    onClick: () -> Unit,
): SheetTopAction {
    val containerColor = if (primary) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (primary) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    return SheetTopAction(
        label = label,
        icon = icon,
        containerColor = containerColor,
        contentColor = contentColor,
        contentColorGlass = if (primary) {
            MaterialTheme.classppColors.onPrimaryGlass
        } else {
            contentColor
        },
        onClick = onClick,
    )
}

/**
 * 下载/安装失败后的来源动作：用于「返回」时重建更新提示。
 * 下载完成/失败发生在 Available 之后，统一视为来自「检查更新」入口的提示。
 */
private fun actionOf(fromChangelog: Boolean): AboutUpdateAction =
    if (fromChangelog) AboutUpdateAction.Changelog else AboutUpdateAction.Check
