package com.fangyi.classpp.ui.settings

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ai.AiChatRepository
import com.fangyi.classpp.data.ai.AiConnection
import com.fangyi.classpp.data.ai.AiProtocol
import com.fangyi.classpp.data.ai.AiProviderPresets
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.DialogPillButton
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SettingsInfoHeaderCard
import com.fangyi.classpp.ui.components.SheetBottomSlack
import com.fangyi.classpp.ui.components.SheetInfoBanner
import com.fangyi.classpp.ui.components.SheetTextFieldGroup
import com.fangyi.classpp.ui.components.SheetTextFieldItem
import com.fangyi.classpp.ui.components.clearFocusOnTap
import com.fangyi.classpp.ui.motion.LocalPageOverlayActive
import com.fangyi.classpp.ui.motion.LocalPageOverlayPrewarm
import com.fangyi.classpp.ui.motion.rememberOffsetOverscrollEffect
import com.fangyi.classpp.ui.motion.rubberBandVerticalScroll
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.SettingsSectionSpacing
import com.fangyi.classpp.ui.theme.isDarkTheme
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
 * 卡片语言承载原先 AI 设置浮层的表单：
 * - 顶部无题分组：信息头卡（ic_ai）+ 服务商选择行（[SettingsCardItem.Select] 弹菜单，
 *   自定义服务商时追加「接口格式」行）；
 * - 「连接」分组：服务地址 / 模型名 / API Key 三行输入卡（[SheetTextFieldGroup]，
 *   与设置卡同规格）。API Key 为密码框——已配置时占位「留空保持不变」，
 *   **不回显已存 Key**，覆盖保存只在输入非空时发生；
 * - 「隐私与安全」分组：固定展示隐私说明（纯文字行卡）；Keystore 不可用时页面顶部
 *   出现警告条（[SheetInfoBanner]），保存失败原因同步为「安全存储不可用」；
 * - 「对话记录」分组：清空对话历史为两击确认的行（不弹窗），3s 无后续点击自动解除；
 * - 页尾「保存」胶囊落连接配置与（按需）Key，成功后 [AiChatRepository.refreshConfigured]
 *   让对话浮层的引导态即时切换；Keystore 读写是响应式状态，横幅随保存结果出现/消失。
 *
 * 子页与其他子页一样隐藏时仍常驻组合：数据装载与两击确认的复位只在覆盖层活跃期
 * 有意义，隐藏态不重读凭据；表单快照在页面生命周期内不随外部变化回写。
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
    val invalidMessage = stringResource(R.string.ai_settings_invalid)
    val savedMessage = stringResource(R.string.ai_settings_saved)
    val saveFailedMessage = stringResource(R.string.ai_save_failed)
    val keyRequiredMessage = stringResource(R.string.ai_settings_key_required)
    val secureUnavailableMessage = stringResource(R.string.ai_settings_secure_unavailable)
    // Keystore 可用性是响应式状态：读取/保存 Key 后仓库会同步，横幅随之出现/消失
    val secureAvailable by aiChatRepository?.secureAvailable?.collectAsState()
        ?: remember { mutableStateOf(true) }

    // 表单快照：进入页面装载一次（页面生命周期内不随外部变化回写）
    var connection by remember { mutableStateOf(AiConnection()) }
    var hadApiKey by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var baseUrlInvalid by remember { mutableStateOf(false) }
    var modelInvalid by remember { mutableStateOf(false) }
    // 「清空对话历史」两击确认：第一次点击置位，3s 无后续点击自动解除，覆盖层收起复位
    var clearArmed by remember { mutableStateOf(false) }
    val overlayActive = LocalPageOverlayActive.current

    // 仓库就绪后装载一次：连接配置 + 是否已存 Key（Key 本身不回显）
    LaunchedEffect(aiChatRepository) {
        val repository = aiChatRepository ?: return@LaunchedEffect
        connection = repository.loadConnection()
        hadApiKey = repository.loadApiKey() != null
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

    fun save() {
        val repository = aiChatRepository ?: return
        val trimmedUrl = connection.baseUrl.trim()
        val trimmedModel = connection.model.trim()
        val valid = trimmedUrl.startsWith("http") && trimmedModel.isNotBlank()
        baseUrlInvalid = !trimmedUrl.startsWith("http")
        modelInvalid = trimmedModel.isBlank()
        if (!valid) {
            AppToasts.show(context, invalidMessage)
            return
        }
        scope.launch {
            val normalized = connection.copy(
                baseUrl = trimmedUrl.trimEnd('/'),
                model = trimmedModel,
            )
            val connectionSaved = repository.saveConnection(normalized)
            // 没输入新 Key 时只有「已存且能解密」才算成功；空输入不再无条件报已保存
            val keySaved = if (keyInput.isNotBlank()) {
                repository.saveApiKey(keyInput.trim())
            } else {
                repository.loadApiKey() != null
            }
            repository.refreshConfigured()
            when {
                connectionSaved && keySaved -> {
                    connection = normalized
                    hadApiKey = true
                    keyInput = ""
                    AppToasts.show(context, savedMessage)
                }
                // Key 失败优先按真实原因提示（Keystore 不可用 ≠ 没填）
                !keySaved && !repository.secureAvailable.value -> {
                    AppToasts.show(context, secureUnavailableMessage)
                }
                !keySaved -> AppToasts.show(context, keyRequiredMessage)
                else -> AppToasts.show(context, saveFailedMessage)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        // 沉浸式：只吃左右 inset，状态栏/手势条区域交给内容与顶栏自己铺满（同设置根页）
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        // 顶栏叠在内容之上：内容整屏铺开（hazeSource），首屏经 topBarHeight 内缩到顶栏之下，
        // 上滑时从顶栏背后滚过，顶栏用 Haze 对其做自上而下的渐变背景模糊
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
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
            val freezeEpoch = LocalSettingsFreezeEpoch.current
            // 冻结键：主题/顶栏高度/滚动位置/表单与 Key 状态任一变化都回退实时绘制
            val freezeKey: () -> Any = remember(
                darkTheme,
                scrollState,
                connection,
                hadApiKey,
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
                        inputs = listOf(connection, hadApiKey, secureAvailable),
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
                    // release 构建隐藏必然失败的明文预设（ollama 的 http://localhost），
                    // DEBUG 构建保留以便本地联调
                    val debuggable = remember(context) {
                        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                    }
                    val presets = remember(debuggable) { AiProviderPresets.available(debuggable) }
                    val providerItems = remember(presets) {
                        presets.mapIndexed { index, preset -> index to preset.label }
                    }
                    // 文案先在组合上下文里取齐：buildList/map 的 lambda 不是 @Composable 上下文
                    val providerLabel = stringResource(R.string.ai_settings_provider)
                    val providerValue = AiProviderPresets.byId(connection.providerId).label
                    val protocolLabel = stringResource(R.string.ai_settings_protocol)
                    val protocolValue = stringResource(connection.protocol.labelRes())
                    val protocolItems = listOf(
                        AiProtocol.OpenAiCompat.ordinal to
                            stringResource(R.string.ai_settings_protocol_openai),
                        AiProtocol.Anthropic.ordinal to
                            stringResource(R.string.ai_settings_protocol_anthropic),
                    )
                    val providerRows = buildList {
                        add(
                            SettingsCardItem.Select(
                                label = providerLabel,
                                // 旧配置可能指向已下架的预设：byId 回落 custom，值始终可解析
                                value = providerValue,
                                items = providerItems,
                                selectedId = presets.indexOfFirst { it.id == connection.providerId },
                                onPick = { index ->
                                    val preset = presets.getOrNull(index)
                                    if (preset != null) {
                                        connection = connection.copy(
                                            providerId = preset.id,
                                            // 选中预设即回填端点与协议；建议模型仅在非手填
                                            // 预设下覆盖，ollama/custom 无公认默认，保留用户已填值
                                            baseUrl = preset.baseUrl.ifBlank { connection.baseUrl },
                                            model = if (!preset.requiresManualModel &&
                                                preset.suggestedModel.isNotBlank()
                                            ) {
                                                preset.suggestedModel
                                            } else {
                                                connection.model
                                            },
                                            protocol = preset.protocol,
                                        )
                                    }
                                },
                            ),
                        )
                        // 接口格式仅自定义服务商可改：预设自带协议，展示会误导改坏
                        if (connection.providerId == AiProviderPresets.CUSTOM_ID) {
                            add(
                                SettingsCardItem.Select(
                                    label = protocolLabel,
                                    value = protocolValue,
                                    items = protocolItems,
                                    selectedId = connection.protocol.ordinal,
                                    onPick = { id ->
                                        AiProtocol.entries.getOrNull(id)?.let { protocol ->
                                            connection = connection.copy(protocol = protocol)
                                        }
                                    },
                                ),
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .hazeSource(hazeState)
                            // 玻璃返回按钮开启时同一节点再挂液态玻璃采样标记
                            .then(
                                if (glassBackdrop != null) {
                                    Modifier.layerBackdrop(glassBackdrop)
                                } else {
                                    Modifier
                                },
                            )
                            // 橡皮筋增强版 verticalScroll：内容不足一屏时也能拉出橡皮筋
                            // （ui.motion 的 rubberBandVerticalScroll）
                            .rubberBandVerticalScroll(
                                scrollState,
                                overscrollEffect = overscrollEffect,
                            )
                            // 点空白（卡片间隙/留白）取消聚焦收起键盘：页面直排表单需自行挂载
                            .clearFocusOnTap()
                            // 键盘弹起时为输入框让出可滚动余量（adjustResize）
                            .imePadding()
                            .padding(horizontal = PageHorizontalSpacing)
                            .padding(
                                // 首项距顶栏留 TopBarContentGap（页自有节奏），尾部余量与浮层同源
                                top = topBarHeight + TopBarContentGap,
                                bottom = navBarPadding + SheetBottomSlack,
                            ),
                        verticalArrangement = Arrangement.spacedBy(SettingsSectionSpacing),
                    ) {
                        // Keystore 不可用：置顶提示（Key 无法保存，保存按钮仍可落连接配置）
                        if (!secureAvailable) {
                            SheetInfoBanner(text = stringResource(R.string.ai_settings_secure_unavailable))
                        }

                        // 顶部无题分组：信息头卡 + 服务商选择，组内卡间距 10dp
                        CardSection {
                            SettingsInfoHeaderCard(
                                icon = R.drawable.ic_ai,
                                title = stringResource(R.string.ai_settings_header_title),
                                description = stringResource(R.string.ai_settings_header_description),
                            )
                            SettingsCard(
                                items = providerRows,
                                // 选择行菜单的背景磨砂源 = 本页滚动内容
                                hazeState = hazeState,
                            )
                        }

                        // 连接：服务地址 / 模型名 / API Key 三行输入（与设置卡同规格）
                        CardSection(title = stringResource(R.string.ai_settings_section_connection)) {
                            SheetTextFieldGroup(
                                fields = listOf(
                                    SheetTextFieldItem(
                                        label = stringResource(R.string.ai_settings_base_url),
                                        value = connection.baseUrl,
                                        onValueChange = {
                                            connection = connection.copy(baseUrl = it)
                                            baseUrlInvalid = false
                                        },
                                        placeholder = stringResource(R.string.ai_settings_base_url_hint),
                                        isError = baseUrlInvalid,
                                        imeAction = ImeAction.Next,
                                    ),
                                    SheetTextFieldItem(
                                        label = stringResource(R.string.ai_settings_model),
                                        value = connection.model,
                                        onValueChange = {
                                            connection = connection.copy(model = it)
                                            modelInvalid = false
                                        },
                                        placeholder = stringResource(R.string.ai_settings_model_hint),
                                        isError = modelInvalid,
                                        imeAction = ImeAction.Next,
                                    ),
                                    // API Key：密码框；已配置时不回显，留空 = 保持不变
                                    SheetTextFieldItem(
                                        label = stringResource(R.string.ai_settings_api_key),
                                        value = keyInput,
                                        onValueChange = { keyInput = it },
                                        placeholder = stringResource(
                                            if (hadApiKey) {
                                                R.string.ai_settings_api_key_saved
                                            } else {
                                                R.string.ai_settings_api_key_hint
                                            },
                                        ),
                                        imeAction = ImeAction.Done,
                                        visualTransformation = if (keyInput.isEmpty()) {
                                            VisualTransformation.None
                                        } else {
                                            PasswordVisualTransformation()
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

                        // 保存：落到连接配置与（按需）Key；成功后不退出页面，继续可改
                        DialogPillButton(
                            label = stringResource(R.string.ai_settings_save),
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = ::save,
                        )
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
        }
    }
}

/** 接口格式的展示文案（数据层不产文案，与 AiError 的 UI 映射同策略） */
private fun AiProtocol.labelRes(): Int = when (this) {
    AiProtocol.OpenAiCompat -> R.string.ai_settings_protocol_openai
    AiProtocol.Anthropic -> R.string.ai_settings_protocol_anthropic
}
