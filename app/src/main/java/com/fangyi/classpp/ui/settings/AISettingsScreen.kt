package com.fangyi.classpp.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ai.AiChatRepository
import com.fangyi.classpp.data.ai.AiConnection
import com.fangyi.classpp.data.ai.AiProfile
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SettingsInfoHeaderCard
import com.fangyi.classpp.ui.components.SheetBottomSlack
import com.fangyi.classpp.ui.components.SheetInfoBanner
import com.fangyi.classpp.ui.motion.LocalPageOverlayActive
import com.fangyi.classpp.ui.motion.LocalPageOverlayPrewarm
import com.fangyi.classpp.ui.motion.rememberOffsetOverscrollEffect
import com.fangyi.classpp.ui.theme.isDarkTheme
import com.fangyi.classpp.ui.theme.themeAccent
import com.kyant.backdrop.backdrops.layerBackdrop
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * AI 设置子页（设置 → AI 设置）：由 [SettingsScreen] 以整页覆盖转场组合在根页之上
 * （与个性化/通知子页同源，ui.motion 的 PageOverlayTransition），返回按钮/返回键经
 * [onBack] 回到设置根页。
 *
 * 骨架与其余设置子页同构（Scaffold + 滚动内容列 + 顶栏叠加），正文用设置页的
 * 卡片语言：
 * - 顶部无题分组：信息头卡（ic_ai）；
 * - 「配置方案」分组：一行入口显示当前方案名与「服务商 · 模型」摘要，点开
 *   [AiProfileSheet]（独立 OverlaySheet）管理方案库——保存多套、切换、重命名、删除
 *   都在浮层内完成，浮层右上「确认」才保存并切换生效配置；
 * - 「隐私与安全」分组：固定展示隐私说明（纯文字行卡）；Keystore 不可用时页面顶部
 *   出现警告条（[SheetInfoBanner]），保存失败原因同步为「安全存储不可用」；
 * - 「对话记录」分组：清空对话历史为两击确认的行（不弹窗），3s 无后续点击自动解除。
 *
 * 页面本身不持有连接表单：活动配置、方案库与生效状态都由 [AiChatRepository] 的
 * StateFlow 驱动，浮层里保存后入口行即时刷新。子页与其他子页一样隐藏时仍常驻组合：
 * 数据装载与两击确认的复位只在覆盖层活跃期有意义，隐藏态不重读凭据。
 */
@Composable
fun AISettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    aiChatRepository: AiChatRepository? = null,
    // 设置页任一覆盖层是否正在转场：顶栏据此冻结重放就位帧
    settingsTransforming: () -> Boolean = { false },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedMessage = stringResource(R.string.ai_settings_saved)
    val saveFailedMessage = stringResource(R.string.ai_save_failed)
    val keyRequiredMessage = stringResource(R.string.ai_settings_key_required)
    val secureUnavailableMessage = stringResource(R.string.ai_settings_secure_unavailable)
    val profileSavedMessage = stringResource(R.string.ai_profile_saved)
    val profileDeletedMessage = stringResource(R.string.ai_profile_deleted)

    // Keystore 可用性是响应式状态：读取/保存 Key 后仓库会同步，横幅随之出现/消失
    val secureAvailable by aiChatRepository?.secureAvailable?.collectAsState()
        ?: remember { mutableStateOf(true) }
    // 方案库 / 活动指针 / 活动连接：设置页入口行的数据源（浮层保存后自动刷新）
    val profiles by aiChatRepository?.profiles?.collectAsState()
        ?: remember { mutableStateOf(emptyList<AiProfile>()) }
    val activeProfileId by aiChatRepository?.activeProfileId?.collectAsState()
        ?: remember { mutableStateOf<String?>(null) }
    val activeConnection by aiChatRepository?.activeConnection?.collectAsState()
        ?: remember { mutableStateOf(AiConnection()) }

    var loaded by remember { mutableStateOf(false) }
    // 「清空对话历史」两击确认：第一次点击置位，3s 无后续点击自动解除，覆盖层收起复位
    var clearArmed by remember { mutableStateOf(false) }
    // 「AI 配置方案」子浮层：挂载与可见性分离（同其他叠层）
    var profileSheetVisible by remember { mutableStateOf(false) }
    var profileSheetMounted by remember { mutableStateOf(false) }
    val overlayActive = LocalPageOverlayActive.current

    // 仓库就绪后装载一次：刷新方案库 / 活动配置 / 配置就绪态
    LaunchedEffect(aiChatRepository) {
        val repository = aiChatRepository ?: return@LaunchedEffect
        repository.refreshConfigured()
        loaded = true
    }

    // 覆盖层收起时复位两击确认：页面常驻组合，避免下次进入仍处于「再次点击确认」态
    LaunchedEffect(overlayActive) {
        if (!overlayActive) clearArmed = false
    }
    LaunchedEffect(clearArmed) {
        if (clearArmed) {
            delay(3000)
            clearArmed = false
        }
    }

    Scaffold(
        modifier = modifier,
        // 统一不避让横向 inset：内容全宽出血（左右顶到屏幕边缘）；纵向由内容与顶栏自理（同设置根页）
        contentWindowInsets = WindowInsets(0),
    ) {
        // 顶栏叠在内容之上：内容整屏铺开（hazeSource），首屏经 topBarHeight 内缩到顶栏之下，
        // 上滑时从顶栏背后滚过，顶栏用 Haze 对其做自上而下的渐变背景模糊
        Box(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            val hazeState = rememberHazeState()
            // 顶栏返回按钮的液态玻璃：采样层挂下方正文列（与 hazeSource 同一节点，
            // Haze 管顶栏磨砂、Backdrop 只喂返回按钮）；null = 玻璃关闭，标记不挂载
            val glassBackdrop = rememberSettingsGlassBackdrop()
            var topBarHeight by remember { mutableStateOf(0.dp) }
            val density = LocalDensity.current
            // 滚动状态上提：冻结键要读滚动位置
            val scrollState = rememberScrollState()
            // 标题渐显要把过滚动算进内容位移（可滚页面读边缘橡皮筋，不足一屏读整页橡皮筋）
            val overscrollEffect = rememberOffsetOverscrollEffect()
            // 预热帧也要挂 Haze；隐藏态不录制，保留上一次活跃期的就位帧
            val hazeActive = LocalPageOverlayActive.current || LocalPageOverlayPrewarm.current
            val darkTheme = MaterialTheme.isDarkTheme
            val themeAccent = MaterialTheme.themeAccent
            val freezeEpoch = LocalSettingsFreezeEpoch.current
            // 冻结键：主题/顶栏高度/滚动位置/方案与配置状态任一变化都回退实时绘制
            val freezeKey: () -> Any = remember(
                darkTheme,
                themeAccent,
                scrollState,
                profiles,
                activeProfileId,
                activeConnection,
                secureAvailable,
                freezeEpoch,
            ) {
                {
                    SettingsTopBarFreezeKey(
                        page = SettingsPage.AiSettings,
                        darkTheme = darkTheme,
                        topBarHeight = topBarHeight,
                        scroll = scrollState.value,
                        epoch = freezeEpoch,
                        inputs = listOf(
                            themeAccent,
                            profiles,
                            activeProfileId,
                            activeConnection,
                            secureAvailable,
                        ),
                    )
                }
            }
            // 手势条（小白条）区域：内容可铺到屏幕底，末尾留出这段避免被遮挡
            val navBarPadding = WindowInsets.navigationBars
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues()
                .calculateBottomPadding()

            when {
                // 采样源始终存在：加载态也挂 hazeSource，避免顶栏背后无源可采
                aiChatRepository == null || !loaded -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(hazeState)
                        .then(
                            if (glassBackdrop != null) {
                                Modifier.layerBackdrop(glassBackdrop)
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                else -> {
                    // 展示用：当前方案名（活动方案名 / 自定义）与模型名摘要
                    val customLabel = stringResource(R.string.ai_profile_custom)
                    val activeProfileName = activeProfileId
                        ?.let { id -> profiles.firstOrNull { it.id == id }?.name }
                        ?: customLabel
                    val profileDescription = if (activeConnection.isConfigured) {
                        activeConnection.model
                    } else {
                        stringResource(R.string.ai_profile_entry_desc)
                    }

                    // 设置子页共用滚动正文：整页拖拽/过滚动、Haze 采样源与玻璃采样标记统一
                    SettingsScrollContent(
                        scrollState = scrollState,
                        overscrollEffect = overscrollEffect,
                        hazeState = hazeState,
                        glassBackdrop = glassBackdrop,
                        topPadding = topBarHeight + TopBarContentGap,
                        bottomPadding = navBarPadding + SheetBottomSlack,
                    ) {
                        // Keystore 不可用：置顶提示（Key 无法保存，连接与方案仍可落盘）
                        if (!secureAvailable) {
                            SheetInfoBanner(text = stringResource(R.string.ai_settings_secure_unavailable))
                        }

                        CardSection {
                            SettingsInfoHeaderCard(
                                icon = R.drawable.ic_ai,
                                title = stringResource(R.string.ai_settings_header_title),
                                description = stringResource(R.string.ai_settings_header_description),
                            )
                        }

                        // 配置方案：整个方案库（保存/切换/重命名/删除）在子浮层内管理，
                        // 这里只保留一行入口；浮层「确认」后才切换生效配置
                        CardSection(title = stringResource(R.string.ai_settings_section_profile)) {
                            SettingsCard(
                                items = listOf(
                                    SettingsCardItem.Nav(
                                        label = stringResource(R.string.ai_profile_title),
                                        description = profileDescription,
                                        value = activeProfileName,
                                        onClick = {
                                            profileSheetMounted = true
                                            profileSheetVisible = true
                                        },
                                    ),
                                ),
                            )
                        }

                        // 隐私与安全：固定展示数据流向与 Key 的存储方式
                        CardSection(title = stringResource(R.string.ai_settings_section_privacy)) {
                            SettingsCard(
                                items = listOf(
                                    SettingsCardItem.Custom(
                                        label = stringResource(R.string.ai_settings_privacy_title),
                                        description = stringResource(R.string.ai_settings_privacy),
                                    ),
                                ),
                            )
                        }

                        // 对话记录：两击确认的行内动作（不弹窗）
                        CardSection(title = stringResource(R.string.ai_settings_section_history)) {
                            SettingsCard(
                                items = listOf(
                                    SettingsCardItem.Nav(
                                        label = stringResource(
                                            if (clearArmed) {
                                                R.string.ai_settings_clear_history_confirm
                                            } else {
                                                R.string.ai_settings_clear_history
                                            },
                                        ),
                                        description = stringResource(
                                            R.string.ai_settings_clear_history_desc,
                                        ),
                                        onClick = {
                                            if (clearArmed) {
                                                clearArmed = false
                                                // 本分支 aiChatRepository 已由外层 when 排除 null
                                                scope.launch {
                                                    aiChatRepository.clearHistory()
                                                    AppToasts.show(
                                                        context,
                                                        context.getString(
                                                            R.string.ai_settings_clear_history_done,
                                                        ),
                                                    )
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
                }
            }

            SettingsTopBar(
                title = stringResource(R.string.ai_settings_title),
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
                        // 顶栏实测高度 = 内容首屏内缩量（px → dp）
                        topBarHeight = with(density) { coords.size.height.toFloat().toDp() }
                    },
            )

            // 配置方案子浮层：叠在设置页之上（后注册因此先收返回键）。
            // 确认 → 保存并激活（成功才关，Key 缺失 toast 并保留草稿）；
            // 保存为新方案 → 独立入库、不激活，成功后经 profiles 流回到浮层顶部列表
            val repository = aiChatRepository
            if (repository != null && loaded && profileSheetMounted) {
                AiProfileSheet(
                    profiles = profiles,
                    activeProfileId = activeProfileId,
                    visible = profileSheetVisible,
                    onDismiss = { profileSheetVisible = false },
                    onDismissed = { profileSheetMounted = false },
                    onApply = { profileId, connection, apiKey ->
                        scope.launch {
                            val outcome = if (profileId != null) {
                                repository.updateProfile(profileId, connection, apiKey)
                            } else {
                                repository.saveCustom(connection, apiKey)
                            }
                            when {
                                !outcome.configSaved -> AppToasts.show(context, saveFailedMessage)
                                // Key 失败优先按真实原因提示（Keystore 不可用 ≠ 没填）
                                !outcome.keySaved && !repository.secureAvailable.value ->
                                    AppToasts.show(context, secureUnavailableMessage)
                                !outcome.keySaved -> AppToasts.show(context, keyRequiredMessage)
                                else -> {
                                    AppToasts.show(context, savedMessage)
                                    profileSheetVisible = false
                                }
                            }
                        }
                    },
                    onSaveAs = { name, connection, apiKey ->
                        scope.launch {
                            if (repository.createProfile(name, connection, apiKey) != null) {
                                AppToasts.show(context, profileSavedMessage)
                            } else {
                                AppToasts.show(context, saveFailedMessage)
                            }
                        }
                    },
                    onRename = { id, name ->
                        scope.launch {
                            if (!repository.renameProfile(id, name)) {
                                AppToasts.show(context, saveFailedMessage)
                            }
                        }
                    },
                    onDelete = { id ->
                        scope.launch {
                            if (repository.deleteProfile(id)) {
                                AppToasts.show(context, profileDeletedMessage)
                            } else {
                                AppToasts.show(context, saveFailedMessage)
                            }
                        }
                    },
                    loadCustomConfig = { repository.loadCustomConfig() },
                    loadProfileKeyPresent = { id -> repository.loadProfileKeyPresent(id) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
