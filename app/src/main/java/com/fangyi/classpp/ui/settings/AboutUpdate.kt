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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.fangyi.classpp.data.update.ChangelogResult
import com.fangyi.classpp.data.update.UpdateCheckResult
import com.fangyi.classpp.ui.ai.ChatMarkdown
import com.fangyi.classpp.ui.ai.ChatMarkdownMode
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

/** 关于页两个更新入口：检查最新版本 / 只看更新日志 */
internal enum class AboutUpdateAction { Check, Changelog }

/**
 * 一次更新入口请求。[id] 单调递增：同一入口连续点击也能重新触发加载，
 * 不用把「请求已消费」写回 AboutScreen。
 */
internal data class AboutUpdateRequest(
    val id: Long,
    val action: AboutUpdateAction,
)

/** 下载进度块内部的文字/进度条间距 */
private val UpdateProgressTextGap = 8.dp
private val UpdateProgressSizeGap = 6.dp

/**
 * 浮层状态：「检查更新」走检查 → 下载 → 安装整条链路；
 * 「更新日志」只有加载 → 展示（失败可重试），不出现任何版本比对或更新动作。
 */
private sealed interface UpdateSheetState {
    data object CheckingUpdate : UpdateSheetState

    data object LoadingChangelog : UpdateSheetState

    /** 只读更新日志：当前安装版本及更早版本，按 versionCode 降序 */
    data class Changelog(val releases: List<AppUpdateRelease>) : UpdateSheetState

    data object UpToDate : UpdateSheetState

    data class Available(val update: AvailableUpdate) : UpdateSheetState

    data class Downloading(
        val update: AvailableUpdate,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateSheetState {
        val fraction: Float
            get() = if (totalBytes <= 0L) {
                0f
            } else {
                (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            }
    }

    data class InstallPermission(val update: AvailableUpdate) : UpdateSheetState

    data class Failed(
        val kind: UpdateFailureKind,
        val update: AvailableUpdate?,
    ) : UpdateSheetState
}

private enum class UpdateFailureKind { Check, Changelog, Download, Verify, Install }

/** OverlaySheet 顶栏/底部动作的当前配置；[dismissible] = 遮罩/返回/下拉是否允许关闭 */
private data class UpdateSheetSpec(
    val title: String,
    val confirmLabel: String? = null,
    val confirmIcon: Int = R.drawable.ic_checkmark_circle,
    val onConfirm: () -> Unit = {},
    val rightAction: SheetTopAction,
    val dismissible: Boolean,
    /** true = 确认胶囊在右、动作胶囊在左（有更新时「取消」在左、「更新」在右） */
    val confirmAtEnd: Boolean = false,
)

/**
 * 关于页「检查更新 / 更新日志」浮层（两个入口共用一层 [OverlaySheet]，状态机互不干扰）：
 *
 * - 检查更新：浮层标题恒为「检查更新」；已是最新时顶栏只有右侧关闭，有更新时
 *   左侧「取消」、右侧「更新」（图标沿用更新动作），可应用内下载并校验 APK，
 *   失败回退发布页；`forced`（当前版本低于 `minSupportedVersionCode`）时浮层
 *   不可关闭，只保留更新动作；
 * - 更新日志：只加载并展示**当前安装版本及更早版本**的更新说明，不做版本比对、
 *   不出现「发现新版本」，也不提供任何下载/安装动作；
 * - 检查/加载协程由本组件持有，关闭或取消时一并取消，不把结果写回已关闭的浮层。
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

    fun startDownload(update: AvailableUpdate) {
        // 防同一帧双击：下载已启动后忽略重复触发，避免取消/重启协程互相清临时文件
        if (state is UpdateSheetState.Downloading) return
        if (!update.canDownload) {
            openRelease(update)
            return
        }
        if (!ApkInstaller.canInstall(context)) {
            state = UpdateSheetState.InstallPermission(update)
            return
        }
        currentJob?.cancel()
        state = UpdateSheetState.Downloading(
            update = update,
            downloadedBytes = 0L,
            totalBytes = update.apkSizeBytes,
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
                    )
                }
            }
        }
    }

    fun runUpdateCheck() {
        currentJob?.cancel()
        state = UpdateSheetState.CheckingUpdate
        visible = true
        currentJob = scope.launch {
            when (val result = checker.check()) {
                is UpdateCheckResult.UpToDate -> state = UpdateSheetState.UpToDate
                is UpdateCheckResult.Available -> state = UpdateSheetState.Available(result.update)
                is UpdateCheckResult.Failed -> state = UpdateSheetState.Failed(
                    kind = UpdateFailureKind.Check,
                    update = null,
                )
            }
        }
    }

    fun runChangelogLoad() {
        currentJob?.cancel()
        state = UpdateSheetState.LoadingChangelog
        visible = true
        currentJob = scope.launch {
            when (val result = checker.loadChangelog()) {
                is ChangelogResult.Success -> state = UpdateSheetState.Changelog(result.releases)
                is ChangelogResult.Failed -> state = UpdateSheetState.Failed(
                    kind = UpdateFailureKind.Changelog,
                    update = null,
                )
            }
        }
    }

    LaunchedEffect(request?.id) {
        val currentRequest = request ?: return@LaunchedEffect
        when (currentRequest.action) {
            AboutUpdateAction.Check -> runUpdateCheck()
            AboutUpdateAction.Changelog -> runChangelogLoad()
        }
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
    val changelogErrorTitle = stringResource(R.string.about_changelog_load_error_title)

    val spec: UpdateSheetSpec = when (current) {
        is UpdateSheetState.CheckingUpdate -> UpdateSheetSpec(
            title = checkTitle,
            rightAction = sheetAction(cancelLabel, R.drawable.ic_dismiss_circle) { dismiss() },
            dismissible = false,
        )

        is UpdateSheetState.LoadingChangelog -> UpdateSheetSpec(
            title = changelogTitle,
            rightAction = sheetAction(cancelLabel, R.drawable.ic_dismiss_circle) { dismiss() },
            dismissible = false,
        )

        is UpdateSheetState.Changelog -> UpdateSheetSpec(
            title = changelogTitle,
            rightAction = sheetAction(closeLabel, R.drawable.ic_dismiss_circle) { dismiss() },
            dismissible = true,
        )

        is UpdateSheetState.UpToDate -> UpdateSheetSpec(
            title = checkTitle,
            rightAction = sheetAction(closeLabel, R.drawable.ic_dismiss_circle) { dismiss() },
            dismissible = true,
        )

        is UpdateSheetState.Available -> {
            val actionLabel = if (current.update.canDownload) updateLabel else releaseLabel
            val actionIcon = if (current.update.canDownload) {
                // 与确认按钮同款图标
                R.drawable.ic_checkmark_circle
            } else {
                R.drawable.ic_arrow_export_up
            }
            val action: () -> Unit = {
                if (current.update.canDownload) {
                    startDownload(current.update)
                } else {
                    openRelease(current.update)
                }
            }
            if (current.update.forced) {
                // 强制更新：只保留唯一主操作，禁用遮罩/返回/下拉关闭
                UpdateSheetSpec(
                    title = checkTitle,
                    rightAction = sheetAction(
                        label = actionLabel,
                        icon = actionIcon,
                        primary = true,
                        onClick = action,
                    ),
                    dismissible = false,
                )
            } else {
                // 有更新：左「取消」、右「更新」（图标沿用更新动作的图标）
                UpdateSheetSpec(
                    title = checkTitle,
                    confirmLabel = actionLabel,
                    confirmIcon = actionIcon,
                    onConfirm = action,
                    rightAction = sheetAction(
                        cancelLabel,
                        R.drawable.ic_dismiss_circle,
                    ) { dismiss() },
                    dismissible = true,
                    confirmAtEnd = true,
                )
            }
        }

        is UpdateSheetState.Downloading -> UpdateSheetSpec(
            title = checkTitle,
            rightAction = sheetAction(cancelLabel, R.drawable.ic_dismiss_circle) {
                currentJob?.cancel()
                currentJob = null
                state = UpdateSheetState.Available(current.update)
            },
            dismissible = false,
        )

        is UpdateSheetState.InstallPermission -> UpdateSheetSpec(
            title = checkTitle,
            confirmLabel = settingsLabel,
            confirmIcon = R.drawable.ic_arrow_circle_right_filled,
            onConfirm = {
                if (ApkInstaller.openInstallPermissionSettings(context)) {
                    // 授权页返回后停在更新提示，用户可直接再次点「更新」
                    state = UpdateSheetState.Available(current.update)
                } else {
                    AppToasts.show(context, permissionFailedText)
                }
            },
            // 左「取消」直接关闭浮层，右「去设置」打开系统安装权限页
            rightAction = sheetAction(cancelLabel, R.drawable.ic_dismiss_circle) { dismiss() },
            dismissible = true,
            confirmAtEnd = true,
        )

        is UpdateSheetState.Failed -> {
            val update = current.update
            val forcedUpdate = update?.takeIf { it.forced }
            val primaryAction: () -> Unit = when {
                current.kind == UpdateFailureKind.Check -> ({ runUpdateCheck() })
                current.kind == UpdateFailureKind.Changelog -> ({ runChangelogLoad() })
                update != null -> ({ startDownload(update) })
                else -> ({ runUpdateCheck() })
            }
            val rightAction = if (forcedUpdate != null) {
                // 强制更新失败：先回到不可关闭的更新提示，避免把用户留在失败页
                sheetAction(backLabel, R.drawable.ic_arrow_circle_left) {
                    state = UpdateSheetState.Available(forcedUpdate)
                }
            } else {
                sheetAction(closeLabel, R.drawable.ic_dismiss_circle) { dismiss() }
            }
            UpdateSheetSpec(
                // 检查更新链路标题恒为「检查更新」，具体失败类型由内容卡说明
                title = if (current.kind == UpdateFailureKind.Changelog) {
                    changelogErrorTitle
                } else {
                    checkTitle
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
            current is UpdateSheetState.Failed && current.update?.forced == true -> {
                state = UpdateSheetState.Available(current.update)
            }
            // 检查中/更新日志加载中/下载中/强制更新提示：返回键不打断，只能点顶栏动作
        }
    }

    OverlaySheet(
        title = spec.title,
        confirmLabel = spec.confirmLabel,
        confirmIcon = spec.confirmIcon,
        onConfirm = spec.onConfirm,
        rightAction = spec.rightAction,
        confirmAtEnd = spec.confirmAtEnd,
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
                is UpdateSheetState.CheckingUpdate -> UpdateLoadingContent(
                    text = stringResource(R.string.about_update_checking),
                )

                is UpdateSheetState.LoadingChangelog -> UpdateLoadingContent(
                    text = stringResource(R.string.about_changelog_loading),
                )

                // 更新日志：纯只读，按版本分组展示当前安装版本及更早版本
                is UpdateSheetState.Changelog -> UpdateReleaseHistory(current.releases)

                is UpdateSheetState.UpToDate -> UpdateUpToDateContent(localVersionName)

                is UpdateSheetState.Available -> UpdateAvailableContent(current.update)

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

/** 加载中（检查更新 / 更新日志共用）：与同步/备份加载卡同款「小转圈 + 文案」 */
@Composable
private fun UpdateLoadingContent(text: String) {
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
                    text = text,
                    style = ClassppTextTokens.dialogMessage,
                    color = MaterialTheme.classppColors.secondaryText,
                )
            }
        }
    }
}

/** 已是最新：只给版本状态，不展示历史说明（历史说明走「更新日志」入口） */
@Composable
private fun UpdateUpToDateContent(localVersionName: String) {
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
}

/**
 * 更新日志历史：按版本分组，只渲染有说明的版本；全空时给一行「暂无更新说明」。
 * 数据由 `AppUpdateChecker.loadChangelog()` 提供，只含当前安装版本及更早版本；
 * 更新提示（检查更新入口）只展示目标版本说明，不走这里。
 */
@Composable
private fun UpdateReleaseHistory(releases: List<AppUpdateRelease>) {
    val withNotes = releases.filter { it.changelog.isNotBlank() }
    if (withNotes.isEmpty()) {
        CardSection(title = stringResource(R.string.about_update_section_changelog)) {
            UpdateChangelogCard("")
        }
    } else {
        withNotes.forEach { release ->
            CardSection(title = updateReleaseTitle(release)) {
                UpdateChangelogCard(release.changelog)
            }
        }
    }
}

/** 有新版本：版本头部 + 强制提示 + 目标版本说明；不展示历史列表 */
@Composable
private fun UpdateAvailableContent(update: AvailableUpdate) {
    CardSection {
        SettingsInfoHeaderCard(
            icon = R.drawable.ic_arrow_circle_up_filled,
            title = stringResource(R.string.about_update_available_title),
            description = updateVersionDescription(update),
        )
        if (update.forced) {
            SheetInfoBanner(
                text = stringResource(R.string.about_update_forced_hint),
                isError = true,
            )
        }
    }
    CardSection(title = stringResource(R.string.about_update_section_changelog)) {
        UpdateChangelogCard(update.changelog)
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
            icon = R.drawable.ic_info,
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
    val titleRes = when (kind) {
        UpdateFailureKind.Check -> R.string.about_update_check_error_title
        UpdateFailureKind.Changelog -> R.string.about_changelog_load_error_title
        else -> R.string.about_update_error_title
    }
    val messageRes = when (kind) {
        UpdateFailureKind.Check -> R.string.about_update_check_failed
        UpdateFailureKind.Changelog -> R.string.about_changelog_load_failed
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

/** 更新内容卡：分语言 Markdown 渲染；没有说明时给一行中性占位 */
@Composable
private fun UpdateChangelogCard(markdown: String) {
    SheetCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            // SheetCard 只铺白底；显式对齐 onSurface，Markdown 的正文/表格/代码块颜色从它派生
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurface,
            ) {
                if (markdown.isBlank()) {
                    Text(
                        text = stringResource(R.string.about_update_changelog_empty),
                        style = ClassppTextTokens.settingsDescription,
                        color = MaterialTheme.classppColors.secondaryText,
                    )
                } else {
                    SelectionContainer {
                        ChatMarkdown(
                            content = markdown,
                            modifier = Modifier.fillMaxWidth(),
                            mode = ChatMarkdownMode.Document,
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
