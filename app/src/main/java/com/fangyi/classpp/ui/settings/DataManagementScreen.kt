package com.fangyi.classpp.ui.settings

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.data.TagRepository
import com.fangyi.classpp.data.TodoRepository
import com.fangyi.classpp.data.ai.AiChatRepository
import com.fangyi.classpp.data.backup.BackupError
import com.fangyi.classpp.data.backup.BackupManager
import com.fangyi.classpp.data.backup.BackupManifest
import com.fangyi.classpp.data.backup.BackupResult
import com.fangyi.classpp.data.backup.StorageUsage
import com.fangyi.classpp.data.backup.StorageUsageReader
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.ConfirmDialogCard
import com.fangyi.classpp.ui.components.DialogPillButton
import com.fangyi.classpp.ui.components.FadeOverlayDialog
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SettingsInfoHeaderCard
import com.fangyi.classpp.ui.components.SheetBottomSlack
import com.fangyi.classpp.ui.motion.LocalPageOverlayActive
import com.fangyi.classpp.ui.motion.LocalPageOverlayPrewarm
import com.fangyi.classpp.ui.motion.rememberOffsetOverscrollEffect
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.RowCardPaddingHorizontal
import com.fangyi.classpp.ui.theme.RowCardPaddingVertical
import com.fangyi.classpp.ui.theme.SettingsCardShape
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.isDarkTheme
import com.fangyi.classpp.ui.theme.themeAccent
import com.kyant.backdrop.backdrops.layerBackdrop
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 用量条高度（对齐示意图的胶囊条；三段按字节占比分宽） */
private val StorageBarHeight = 20.dp

/** 图例圆点直径 */
private val LegendDotSize = 8.dp

/**
 * 极小分段的最小可见占比：缓存常常只有几十 KB（占比 < 0.2%），按真实比例会缩成
 * 亚像素不可见。非零段至少占条宽的 0.6%，再整体归一化——只影响观感，不改变数值。
 */
private const val MinSegmentFraction = 0.006f

/** 数据管理页在途操作（阻断式进度卡文案） */
private enum class BackupBusy { Export, Import }

/**
 * 数据管理子页（设置 → 数据管理）：由 [SettingsScreen] 以整页覆盖转场组合在根页之上
 * （与个性化/通知/AI/账号子页同源，ui.motion 的 PageOverlayTransition），返回按钮/返回键
 * 经 [onBack] 回到设置根页。
 *
 * 骨架与其余设置子页同构（Scaffold + [SettingsScrollContent] + [SettingsTopBar]），正文：
 * - 存储空间用量：分段条（应用大小/用户数据/缓存）+ 图例 + 四行数值（合计），
 *   数值由 [StorageUsageReader] 在 IO 线程计算，进入设置页与每次备份/清理后刷新；
 * - 备份与恢复：「导出备份」经系统文件选择器写 ZIP，「导入备份」读取 ZIP 并覆盖还原，
 *   导入前弹出带内容计数的二次确认（覆盖是不可撤销操作）；
 * - 缓存：两击确认清理 cacheDir/codeCacheDir（不弹窗，3s 无后续点击自动解除）。
 *
 * 备份实现见 [BackupManager]：整库信封直读直写，保留原 ID 与回收站/归档条目；不触碰
 * 账号凭据、WebView 数据与 XMU 同步状态。导入成功后依次重载各仓库并回调
 * [onDataRestored]，让日程页背景图等派生状态立即跟上新数据。
 *
 * 子页与其他设置页一样隐藏时仍常驻组合：用量计算与两击确认的复位只在覆盖层活跃期
 * 有意义，隐藏态不做磁盘遍历。
 */
@Composable
fun DataManagementScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scheduleRepository: ScheduleRepository? = null,
    todoRepository: TodoRepository? = null,
    tagRepository: TagRepository? = null,
    aiChatRepository: AiChatRepository? = null,
    // 恢复完成通知：宿主重读日程页快照等页外状态
    onDataRestored: () -> Unit = {},
    // 设置页任一覆盖层是否正在转场：顶栏据此冻结重放就位帧
    settingsTransforming: () -> Boolean = { false },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backupManager = remember(context) { BackupManager(context.applicationContext) }
    val overlayActive = LocalPageOverlayActive.current

    // 中文案在组合期解析，回调里只引用字符串（不在点击回调里查资源）
    val exportDoneText = stringResource(R.string.data_management_export_done)
    val exportFailedText = stringResource(R.string.data_management_export_failed)
    val importDoneText = stringResource(R.string.data_management_import_done)
    val importFailedText = stringResource(R.string.data_management_import_failed)
    val importWriteFailedText = stringResource(R.string.data_management_import_write_failed)
    val importInvalidText = stringResource(R.string.data_management_import_invalid)
    val importVersionText = stringResource(R.string.data_management_import_version)
    val clearCacheDoneText = stringResource(R.string.data_management_clear_cache_done)
    val busyExportText = stringResource(R.string.data_management_busy_export)
    val busyImportText = stringResource(R.string.data_management_busy_import)

    // 存储用量：进入设置页时算一次，备份/导入/清缓存后自增 epoch 重算
    var usage by remember { mutableStateOf<StorageUsage?>(null) }
    var usageEpoch by remember { mutableStateOf(0) }
    LaunchedEffect(overlayActive, usageEpoch) {
        if (!overlayActive) return@LaunchedEffect
        usage = withContext(Dispatchers.IO) {
            runCatching { StorageUsageReader.read(context) }.getOrNull()
        }
    }

    // 在途操作：挂载与可见分离（同其他叠层），进度卡不可手动关闭
    var busyKind by remember { mutableStateOf(BackupBusy.Export) }
    var busyMounted by remember { mutableStateOf(false) }
    var busyVisible by remember { mutableStateOf(false) }

    // 导入确认：pendingUri 在确认后使用；文案在挂载期间留住（淡出期间不清空）
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingManifest by remember { mutableStateOf<BackupManifest?>(null) }
    var confirmMounted by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }

    // 「清理缓存」两击确认：3s 无后续点击自动解除，覆盖层收起复位
    var clearArmed by remember { mutableStateOf(false) }
    LaunchedEffect(overlayActive) {
        if (!overlayActive) clearArmed = false
    }
    LaunchedEffect(clearArmed) {
        if (clearArmed) {
            delay(3000)
            clearArmed = false
        }
    }

    fun beginBusy(kind: BackupBusy) {
        busyKind = kind
        busyMounted = true
        busyVisible = true
    }

    fun endBusy() {
        busyVisible = false
    }

    fun errorText(reason: BackupError): String = when (reason) {
        BackupError.InvalidArchive -> importInvalidText
        BackupError.UnsupportedVersion -> importVersionText
        // 读流失败发生在落盘前，现有数据未动；写盘失败可能已写入一部分整库
        BackupError.ReadFailed -> importFailedText
        BackupError.WriteFailed -> importWriteFailedText
    }

    // 导出：系统文件选择器创建 ZIP；文件名在点击时按当前时间生成
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            beginBusy(BackupBusy.Export)
            try {
                when (backupManager.exportTo(uri)) {
                    is BackupResult.Ok -> {
                        usageEpoch++
                        AppToasts.show(context, exportDoneText)
                    }
                    is BackupResult.Err -> AppToasts.show(context, exportFailedText)
                }
            } finally {
                // 任何异常都收起进度卡，避免整页卡在不可关闭的遮罩里
                endBusy()
            }
        }
    }

    // 导入第一步：选文件后只读 manifest 做预检（不弹进度卡——只读几十字节），
    // 合法才弹覆盖确认；真正的解压/校验/落盘在确认后走 runImport
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val result = backupManager.inspect(uri)) {
                is BackupResult.Ok -> {
                    pendingUri = uri
                    pendingManifest = result.value
                    confirmMounted = true
                    confirmVisible = true
                }
                is BackupResult.Err -> AppToasts.show(context, errorText(result.reason))
            }
        }
    }

    fun runImport(uri: Uri) {
        scope.launch {
            beginBusy(BackupBusy.Import)
            try {
                when (val result = backupManager.importFrom(uri)) {
                    is BackupResult.Ok -> {
                        // 整库直接替换后重载内存态：顺序无关，各域互相独立
                        scheduleRepository?.reloadFromDisk()
                        todoRepository?.reloadFromDisk()
                        tagRepository?.reloadFromDisk()
                        aiChatRepository?.bootstrap()
                        onDataRestored()
                        usageEpoch++
                        AppToasts.show(context, importDoneText)
                    }
                    is BackupResult.Err -> AppToasts.show(context, errorText(result.reason))
                }
            } finally {
                endBusy()
            }
        }
    }

    Scaffold(
        modifier = modifier,
        // 沉浸式：只吃左右 inset，状态栏/手势条区域交给内容与顶栏自己铺满（同设置根页）
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val hazeState = rememberHazeState()
            val glassBackdrop = rememberSettingsGlassBackdrop()
            var topBarHeight by remember { mutableStateOf(0.dp) }
            val density = LocalDensity.current
            val scrollState = rememberScrollState()
            val overscrollEffect = rememberOffsetOverscrollEffect()
            // 预热帧也要挂 Haze；确认/进度叠层在场或本页隐藏时不挂、也不录制
            // （同账号与同步页：Haze 效果层在全屏 alpha 动画期间增删会触发 RenderThread 递归）
            val hazeActive = (overlayActive || LocalPageOverlayPrewarm.current) &&
                !confirmMounted && !busyMounted
            val darkTheme = MaterialTheme.isDarkTheme
            val themeAccent = MaterialTheme.themeAccent
            val freezeEpoch = LocalSettingsFreezeEpoch.current
            val freezeKey: () -> Any = remember(darkTheme, themeAccent, scrollState, usage, freezeEpoch) {
                {
                    SettingsTopBarFreezeKey(
                        page = SettingsPage.DataManagement,
                        darkTheme = darkTheme,
                        topBarHeight = topBarHeight,
                        scroll = scrollState.value,
                        epoch = freezeEpoch,
                        inputs = listOf(themeAccent, usage),
                    )
                }
            }
            val navBarPadding = WindowInsets.navigationBars
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues()
                .calculateBottomPadding()

            SettingsScrollContent(
                scrollState = scrollState,
                overscrollEffect = overscrollEffect,
                hazeState = hazeState,
                glassBackdrop = glassBackdrop,
                topPadding = topBarHeight + TopBarContentGap,
                bottomPadding = navBarPadding + SheetBottomSlack,
            ) {
                CardSection {
                    SettingsInfoHeaderCard(
                        icon = R.drawable.ic_book_database,
                        title = stringResource(R.string.data_management_header_title),
                        description = stringResource(R.string.data_management_header_description),
                    )
                }

                CardSection(title = stringResource(R.string.data_management_section_storage)) {
                    StorageUsageCard(usage = usage)
                }

                CardSection(title = stringResource(R.string.data_management_section_backup)) {
                    SettingsCard(
                        items = listOf(
                            SettingsCardItem.Nav(
                                label = stringResource(R.string.data_management_export),
                                description = stringResource(R.string.data_management_export_desc),
                                onClick = {
                                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
                                        .format(Date())
                                    exportLauncher.launch(
                                        context.getString(
                                            R.string.data_management_backup_file_name,
                                            stamp,
                                        ),
                                    )
                                },
                            ),
                            SettingsCardItem.Nav(
                                label = stringResource(R.string.data_management_import),
                                description = stringResource(R.string.data_management_import_desc),
                                onClick = {
                                    importLauncher.launch(
                                        arrayOf(
                                            "application/zip",
                                            "application/x-zip-compressed",
                                            "application/octet-stream",
                                        ),
                                    )
                                },
                            ),
                        ),
                    )
                }

                CardSection(title = stringResource(R.string.data_management_section_cache)) {
                    SettingsCard(
                        items = listOf(
                            SettingsCardItem.Nav(
                                label = stringResource(
                                    if (clearArmed) {
                                        R.string.data_management_clear_cache_confirm
                                    } else {
                                        R.string.data_management_clear_cache
                                    },
                                ),
                                description = stringResource(R.string.data_management_clear_cache_desc),
                                onClick = {
                                    if (clearArmed) {
                                        clearArmed = false
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                StorageUsageReader.clearCache(context)
                                            }
                                            usageEpoch++
                                            AppToasts.show(context, clearCacheDoneText)
                                        }
                                    } else {
                                        clearArmed = true
                                    }
                                },
                            ),
                        ),
                    )
                }
            }

            SettingsTopBar(
                title = stringResource(R.string.settings_data_management),
                onBack = onBack,
                // 只在活跃期挂 Haze 效果，避免对屏外页面做源捕获/模糊
                hazeState = if (hazeActive) hazeState else null,
                buttonBackdrop = glassBackdrop,
                freezeKey = freezeKey,
                recordWhen = { hazeActive },
                isTransforming = settingsTransforming,
                titleAlpha = rememberSettingsTitleAlpha(scrollState, overscrollEffect),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .onGloballyPositioned { coords ->
                        topBarHeight = with(density) { coords.size.height.toFloat().toDp() }
                    },
            )

            // 覆盖确认：只有用户确认才落盘，取消/返回/点遮罩都只关闭弹窗
            if (confirmMounted) {
                val manifest = pendingManifest
                val message = if (manifest != null) {
                    stringResource(
                        R.string.data_management_import_confirm_message,
                        manifest.counts.schedules,
                        manifest.counts.todos,
                        manifest.counts.tags,
                        manifest.counts.dayImages,
                        manifest.counts.aiMessages,
                    )
                } else {
                    ""
                }
                FadeOverlayDialog(
                    visible = confirmVisible,
                    onDismiss = { confirmVisible = false },
                    onDismissed = {
                        confirmMounted = false
                        pendingManifest = null
                        pendingUri = null
                    },
                ) {
                    ConfirmDialogCard(
                        title = stringResource(R.string.data_management_import_confirm_title),
                        message = message,
                    ) {
                        DialogPillButton(
                            label = stringResource(R.string.settings_cancel),
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            enabled = confirmVisible,
                            onClick = { confirmVisible = false },
                        )
                        DialogPillButton(
                            label = stringResource(R.string.data_management_import_confirm_action),
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.weight(1f),
                            enabled = confirmVisible,
                            onClick = {
                                // 先取走 uri：淡出结束后 onDismissed 会清 pending
                                val uri = pendingUri
                                confirmVisible = false
                                if (uri != null) runImport(uri)
                            },
                        )
                    }
                }
            }

            // 在途进度：不可取消（遮罩点击与返回键都无效），完成/失败后淡出
            if (busyMounted) {
                FadeOverlayDialog(
                    visible = busyVisible,
                    onDismiss = {},
                    onDismissed = { busyMounted = false },
                ) {
                    BackupBusyCard(
                        text = if (busyKind == BackupBusy.Export) busyExportText else busyImportText,
                    )
                }
            }
        }
    }
}

/** 在途进度卡：小转圈 + 操作文案，垂直居中；进度期间浮层不可关闭 */
@Composable
private fun BackupBusyCard(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            strokeWidth = 3.dp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = text,
            style = ClassppTextTokens.dialogMessage,
            color = MaterialTheme.classppColors.secondaryText,
        )
    }
}

/** 用量卡的单行数据 */
private data class UsageRowData(
    @DrawableRes val icon: Int,
    val label: String,
    val value: String,
)

/**
 * 存储空间用量卡：白卡 + [SettingsCardShape]，上半为分段条与图例，下半为四行数值。
 * 行规格对齐 [SettingsCard] 的多行自然高度（左右 16 / 上下 14 内距、fieldLabel +
 * fieldValue），只是不可点击。
 */
@Composable
private fun StorageUsageCard(
    usage: StorageUsage?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.classppColors
    val placeholder = stringResource(R.string.data_management_storage_placeholder)
    fun bytesText(bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    val rows = listOf(
        UsageRowData(
            icon = R.drawable.ic_app_store,
            label = stringResource(R.string.data_management_storage_app),
            value = usage?.let { bytesText(it.appBytes) } ?: placeholder,
        ),
        UsageRowData(
            icon = R.drawable.ic_database_person,
            label = stringResource(R.string.data_management_storage_user),
            value = usage?.let { bytesText(it.userBytes) } ?: placeholder,
        ),
        UsageRowData(
            icon = R.drawable.ic_clock,
            label = stringResource(R.string.data_management_storage_cache),
            value = usage?.let { bytesText(it.cacheBytes) } ?: placeholder,
        ),
        UsageRowData(
            icon = R.drawable.ic_data_pie,
            label = stringResource(R.string.data_management_storage_total),
            value = usage?.let { bytesText(it.totalBytes) } ?: placeholder,
        ),
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(SettingsCardShape)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(
                start = RowCardPaddingHorizontal,
                end = RowCardPaddingHorizontal,
                top = 18.dp,
            ),
        ) {
            StorageUsageBar(
                appBytes = usage?.appBytes ?: 0L,
                userBytes = usage?.userBytes ?: 0L,
                cacheBytes = usage?.cacheBytes ?: 0L,
                appColor = colors.storageApp,
                userColor = colors.storageUser,
                cacheColor = colors.storageCache,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendItem(
                    color = colors.storageApp,
                    label = stringResource(R.string.data_management_storage_app),
                )
                Spacer(Modifier.width(18.dp))
                LegendItem(
                    color = colors.storageUser,
                    label = stringResource(R.string.data_management_storage_user),
                )
                Spacer(Modifier.width(18.dp))
                LegendItem(
                    color = colors.storageCache,
                    label = stringResource(R.string.data_management_storage_cache),
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = RowCardPaddingHorizontal,
                        vertical = RowCardPaddingVertical,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(row.icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = row.label,
                    style = MaterialTheme.classppTextStyles.fieldLabel,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = row.value,
                    style = MaterialTheme.classppTextStyles.fieldValue,
                )
            }
        }
    }
}

/** 分段用量条：三段按字节占比分宽；全 0 时退化为主题 outline 的空轨道 */
@Composable
private fun StorageUsageBar(
    appBytes: Long,
    userBytes: Long,
    cacheBytes: Long,
    appColor: Color,
    userColor: Color,
    cacheColor: Color,
) {
    val weights = segmentWeights(longArrayOf(appBytes, userBytes, cacheBytes))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(StorageBarHeight)
            .clip(PillShape),
    ) {
        if (weights.all { it <= 0f }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.outline),
            )
        } else {
            if (weights[0] > 0f) {
                Box(
                    modifier = Modifier
                        .weight(weights[0])
                        .fillMaxHeight()
                        .background(appColor),
                )
            }
            if (weights[1] > 0f) {
                Box(
                    modifier = Modifier
                        .weight(weights[1])
                        .fillMaxHeight()
                        .background(userColor),
                )
            }
            if (weights[2] > 0f) {
                Box(
                    modifier = Modifier
                        .weight(weights[2])
                        .fillMaxHeight()
                        .background(cacheColor),
                )
            }
        }
    }
}

/**
 * 三段占比 → Row 权重：非零段至少占 [MinSegmentFraction]，再整体归一化。
 * 全 0 返回全 0（调用方据此显示空轨道）。纯函数，便于核对边界。
 */
private fun segmentWeights(bytes: LongArray): FloatArray {
    val total = bytes.sum()
    if (total <= 0L) return FloatArray(bytes.size)
    val adjusted = bytes.map { value ->
        if (value <= 0L) 0.0 else {
            val fraction = value.toDouble() / total
            if (fraction < MinSegmentFraction) MinSegmentFraction.toDouble() else fraction
        }
    }
    val adjustedSum = adjusted.sum()
    return FloatArray(bytes.size) { index -> (adjusted[index] / adjustedSum).toFloat() }
}

/** 图例项：彩色圆点 + 次级文字 */
@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(LegendDotSize)
                .clip(PillShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = ClassppTextTokens.settingsDescription.copy(
                color = MaterialTheme.classppColors.secondaryText,
            ),
        )
    }
}
