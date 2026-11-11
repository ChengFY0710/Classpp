package com.fangyi.classpp.ui.settings

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ai.AiConnection
import com.fangyi.classpp.data.ai.AiProfile
import com.fangyi.classpp.data.ai.AiProtocol
import com.fangyi.classpp.data.ai.AiProviderPresets
import com.fangyi.classpp.ui.components.ActionRowCard
import com.fangyi.classpp.ui.components.BackHandlerHidesKeyboard
import com.fangyi.classpp.ui.components.BottomTextFieldCard
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.ConfirmDialogCard
import com.fangyi.classpp.ui.components.DialogPillButton
import com.fangyi.classpp.ui.components.FadeOverlayDialog
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.PopupMenuItem
import com.fangyi.classpp.ui.components.SelectCard
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SheetTextFieldGroup
import com.fangyi.classpp.ui.components.SheetTextFieldItem
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 一次「保存为新方案」的挂起信息：保存前的方案 id 集合 + 发起时的草稿，回流后据此选中新增方案 */
private data class PendingProfileSave(
    val knownIds: Set<String>,
    val connection: AiConnection,
)

/** 落库回流退让的兜底帧数：回流迟迟未到时按帧挂回，防磨砂被永久停用 */
private const val HazeCommitFallbackFrames = 30

/**
 * 「AI 配置方案」浮层（AI 设置页 →「AI 配置方案」入口打开），结构对照课程时间方案：
 *
 * - 顶部方案选择（[SelectCard]）：已保存方案 + 末尾「自定义」；点选只把该方案填进下方
 *   **本地草稿**，不落盘、不切换生效配置——右上「确认」才保存并激活；
 * - 「自定义」是独立槽位（复用旧版 `connection` / `api_key`）：「自定义」草稿在本次浮层内
 *   单独留存，切去方案再切回不丢本次编辑；
 * - 长按已保存方案行呼出菜单（重命名 / 删除，内置「自定义」行无菜单）：重命名走底部名称
 *   输入卡，确认即落库；删除先弹确认（方案及其 API Key 一并删除），删除当前选中方案时
 *   自动落回活动项（store 已把活动指针修正到「自定义」或其它方案）；
 * - 下方「连接」分组：服务商预设 / 接口格式（仅自定义服务商可改）+ 服务地址 / 模型名 /
 *   API Key 输入 + 「保存为新方案」（只入库不激活，成功后自动选中新方案）；
 * - 末尾「提示」分组：纯展示行，说明对话图片需要多模态（视觉）模型；
 * - 确认时先做本地校验（服务地址 http 开头、模型名非空），失败 toast 并保留浮层；
 *   Key 缺失由宿主回调判定（保存后仍无可用 Key → 提示并保留浮层）。
 *
 * 容器为 [OverlaySheet]，取消在左、确认在右（同课程时间方案浮层）；底部输入卡与删除弹窗
 * 均为两段式挂载，在场时经 covered 让本浮层缩小后退。
 *
 * Haze 退让：切换服务商预设与确认类落库（改名 / 存为新方案 / 删除）会在 Haze 效果节点
 * 在场时同帧或随仓库回流重写采样源结构，重写窗口内整条链退让为静态底材、落定两帧后
 * 挂回（崩溃史与机制见 hazeSuspended 注释）。
 */
@Composable
internal fun AiProfileSheet(
    profiles: List<AiProfile>,
    activeProfileId: String?,
    visible: Boolean,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    // profileId 为 null = 保存「自定义」槽位
    onApply: (profileId: String?, connection: AiConnection, apiKey: String?) -> Unit,
    onSaveAs: (name: String, connection: AiConnection, apiKey: String?) -> Unit,
    onRename: (id: String, name: String) -> Unit,
    onDelete: (id: String) -> Unit,
    loadCustomConfig: suspend () -> Pair<AiConnection, Boolean>,
    loadProfileKeyPresent: suspend (id: String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val customLabel = stringResource(R.string.ai_profile_custom)
    val invalidMessage = stringResource(R.string.ai_settings_invalid)
    val renameLabel = stringResource(R.string.edit_rename)
    val deleteLabel = stringResource(R.string.edit_delete)
    val defaultName = stringResource(R.string.ai_profile_default_name, profiles.size + 1)
    // 服务商预设名可直接当方案名；自定义服务商的预设名是「自定义」，与槽位行同名，改用序号
    val customProviderLabel = AiProviderPresets.byId(AiProviderPresets.CUSTOM_ID).label

    // 选中项：null = 自定义；draft/hadKey 只服务「确认」前的本地编辑
    var selectedProfileId by remember { mutableStateOf<String?>(null) }
    var draftConnection by remember { mutableStateOf(AiConnection()) }
    var keyInput by remember { mutableStateOf("") }
    var hadKey by remember { mutableStateOf(false) }
    var baseUrlInvalid by remember { mutableStateOf(false) }
    var modelInvalid by remember { mutableStateOf(false) }

    // 「自定义」槽位的会话内草稿：切去已保存方案再切回时恢复
    var customConnection by remember { mutableStateOf(AiConnection()) }
    var customKeyInput by remember { mutableStateOf("") }
    var customHadKey by remember { mutableStateOf(false) }

    // 底部名称输入（保存为新方案 / 重命名共用；nameTargetId 非空 = 重命名）
    var nameVisible by remember { mutableStateOf(false) }
    var nameMounted by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    var nameTargetId by remember { mutableStateOf<String?>(null) }

    // 删除确认弹窗
    var deleteTarget by remember { mutableStateOf<AiProfile?>(null) }
    var deleteDialogVisible by remember { mutableStateOf(false) }
    var deleteDialogMounted by remember { mutableStateOf(false) }

    var pendingSave by remember { mutableStateOf<PendingProfileSave?>(null) }

    fun updateDraft(connection: AiConnection) {
        draftConnection = connection
        // 自定义槽位的草稿实时同步，切走再切回才是用户刚编辑的值
        if (selectedProfileId == null) customConnection = connection
    }

    fun updateKeyInput(value: String) {
        keyInput = value
        if (selectedProfileId == null) customKeyInput = value
    }

    fun clearErrors() {
        baseUrlInvalid = false
        modelInvalid = false
    }

    fun normalizeDraft(): AiConnection? {
        val trimmedUrl = draftConnection.baseUrl.trim()
        val trimmedModel = draftConnection.model.trim()
        baseUrlInvalid = !trimmedUrl.startsWith("http")
        modelInvalid = trimmedModel.isBlank()
        if (baseUrlInvalid || modelInvalid) {
            AppToasts.show(context, invalidMessage)
            return null
        }
        return draftConnection.copy(
            baseUrl = trimmedUrl.trimEnd('/'),
            model = trimmedModel,
        )
    }

    // 载入某个条目：null / 不存在的方案 → 自定义槽位
    suspend fun loadEntry(profileId: String?) {
        val profile = profileId?.let { id -> profiles.firstOrNull { it.id == id } }
        if (profile == null) {
            selectedProfileId = null
            draftConnection = customConnection
            keyInput = customKeyInput
            hadKey = customHadKey
        } else {
            selectedProfileId = profile.id
            draftConnection = profile.connection
            keyInput = ""
            // 快速连点时不让旧的 Key 读取结果覆盖最新选择（只影响占位文案）
            val keyPresent = loadProfileKeyPresent(profile.id)
            if (selectedProfileId == profile.id) hadKey = keyPresent
        }
        clearErrors()
    }

    fun openRename(profile: AiProfile) {
        nameTargetId = profile.id
        nameDraft = profile.name
        nameMounted = true
        nameVisible = true
    }

    fun openSaveAs() {
        // 校验通过才进入命名，避免存下一份不可用的方案
        if (normalizeDraft() == null) return
        nameTargetId = null
        // 预填：非自定义服务商用预设名，否则用「方案 N」
        nameDraft = AiProviderPresets.byId(draftConnection.providerId).label.takeIf {
            it.isNotBlank() && it != customProviderLabel
        } ?: defaultName
        nameMounted = true
        nameVisible = true
    }

    fun requestDelete(profile: AiProfile) {
        deleteTarget = profile
        deleteDialogMounted = true
        deleteDialogVisible = true
    }

    // 首次挂载：读自定义槽位，再按当前活动项初始化草稿（活动项不存在则落回自定义）
    LaunchedEffect(Unit) {
        val (custom, customHasKey) = loadCustomConfig()
        customConnection = custom
        customHadKey = customHasKey
        val active = activeProfileId?.let { id -> profiles.firstOrNull { it.id == id } }
        if (active != null) {
            selectedProfileId = active.id
            draftConnection = active.connection
            keyInput = ""
            hadKey = loadProfileKeyPresent(active.id)
        } else {
            selectedProfileId = null
            draftConnection = custom
            keyInput = ""
            hadKey = customHasKey
        }
    }

    // 选中的方案被删除（或外部消失）：落回当前活动项，避免选中一个不存在的方案
    LaunchedEffect(profiles, activeProfileId) {
        val current = selectedProfileId ?: return@LaunchedEffect
        if (profiles.none { it.id == current }) loadEntry(activeProfileId)
    }

    // 保存为新方案成功：仓库回流后自动选中新增项；回流前草稿又被改过则尊重当前操作
    LaunchedEffect(profiles) {
        val pending = pendingSave ?: return@LaunchedEffect
        val saved = profiles.firstOrNull { it.id !in pending.knownIds } ?: return@LaunchedEffect
        pendingSave = null
        if (draftConnection == pending.connection) {
            selectedProfileId = saved.id
            draftConnection = saved.connection
            keyInput = ""
            hadKey = loadProfileKeyPresent(saved.id)
            clearErrors()
        }
    }

    val customIndex = profiles.size
    val selectedIndex = profiles.indexOfFirst { it.id == selectedProfileId }
        .takeIf { it >= 0 } ?: customIndex
    val covered = nameVisible || deleteDialogVisible
    val sheetHazeState = rememberHazeState()

    // —— Haze 退让窗口（RenderThread SIGSEGV 防御，同 AiChatOverlay 的对话切换退让）——
    // 崩溃史（2026-11-07，切换服务商预设必崩，签名同 tombstone_05 / 账号页退出登录）：
    // 本浮层内容列是 OverlaySheet 顶栏磨砂与弹出菜单（独立 Popup 窗口）的共享采样源。
    // 在服务商下拉菜单里点选那一帧，菜单开始退场但仍挂载采样（播完才卸载），onPick 同帧
    // 重写草稿——「接口格式」行随 providerId 增删 + 三个输入框整值替换，采样源子树在效果
    // 节点在场时被结构重写，Haze 2 构建出自引用 RenderNode，RenderThread 在 prepareTreeImpl
    // 无限递归直到栈溢出（SIGSEGV code 2）。对策沿用项目内两处已验证修复（XmuSyncScreen
    // 对话框 gate / AiChatOverlay 整链退让）：重写与 Haze 卸载落同帧重组，该帧所有窗口零
    // 效果节点，源重写无效果可撞；内容落定两帧后挂回。
    // 覆盖范围：① 服务商预设 / 接口格式点选：点选帧的重写已由 SelectMenu 内建的
    // PopupActionGate 延后到菜单退场播完（重写帧弹层已卸载，零效果节点可撞），本退让
    // 窗口随之延后启动——保护对象变为顶栏磨砂（重写帧内容列仍是它的采样源）；② 改名 /
    // 存为新方案 / 删除确认（重写随仓库回流异步到达，退让延续到 profiles 回流帧 + 两帧，
    // 见 suspendHazeForCommit）。方案选择（SelectCard 点选）只动勾选与文本、无弹层在场，
    // 同全应用其余浮层的同类操作，不在退让范围。
    var hazeSuspended by remember { mutableStateOf(false) }
    // 确认类动作已落库、等待 profiles 回流的窗口
    var awaitingProfilesSettle by remember { mutableStateOf(false) }
    // 挂回计时任务：新一轮退让先取消再重排，保证挂回永不与重写同帧
    var hazeResumeJob by remember { mutableStateOf<Job?>(null) }

    /**
     * 同步改写退让：[block] 里的草稿写入与 Haze 卸载落在同一帧重组，两帧后内容稳定再挂回。
     */
    fun suspendHazeForRewrite(block: () -> Unit) {
        hazeResumeJob?.cancel()
        awaitingProfilesSettle = false
        hazeSuspended = true
        block()
        hazeResumeJob = scope.launch {
            withFrameNanos { }
            withFrameNanos { }
            hazeSuspended = false
        }
    }

    /**
     * 落库回流退让：[block] 触发的重写由仓库回流异步带到、时机未知，退让保持到 profiles
     * 回流（见下方 LaunchedEffect）再延两帧；回流迟迟不来（如同名重命名未触发再发射——
     * 内容相等即无重写，本就无需退让）由 [HazeCommitFallbackFrames] 兜底挂回。
     */
    fun suspendHazeForCommit(block: () -> Unit) {
        hazeResumeJob?.cancel()
        hazeSuspended = true
        awaitingProfilesSettle = true
        block()
        hazeResumeJob = scope.launch {
            repeat(HazeCommitFallbackFrames) { withFrameNanos { } }
            if (awaitingProfilesSettle) {
                awaitingProfilesSettle = false
                hazeSuspended = false
            }
        }
    }

    // 回流落定：重写帧必然仍在退让窗口内，此处只推迟挂回；等待期间再来的回流会重启本
    // 效果、重新计两帧
    LaunchedEffect(profiles) {
        if (!awaitingProfilesSettle) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        awaitingProfilesSettle = false
        hazeSuspended = false
    }

    // 退让期间叠层弹卡 / 菜单统一退为实底（OverlaySheet 同规则：hazeEnabled = false 时不给
    // 叠层传采样源）
    val cardHazeState = if (hazeSuspended) null else sheetHazeState

    // 返回键优先收键盘，键盘已收起时才关浮层
    BackHandlerHidesKeyboard(onBack = onDismiss)

    // 服务商预设行（与旧 AI 设置页同一套回填规则）
    val debuggable = remember(context) {
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
    val presets = remember(debuggable) { AiProviderPresets.available(debuggable) }
    val providerItems = remember(presets) {
        presets.mapIndexed { index, preset -> index to preset.label }
    }
    val providerRowLabel = stringResource(R.string.ai_settings_provider)
    val providerValue = AiProviderPresets.byId(draftConnection.providerId).label
    val protocolLabel = stringResource(R.string.ai_settings_protocol)
    val protocolValue = stringResource(draftConnection.protocol.labelRes())
    val protocolItems = listOf(
        AiProtocol.OpenAiCompat.ordinal to
            stringResource(R.string.ai_settings_protocol_openai),
        AiProtocol.Anthropic.ordinal to
            stringResource(R.string.ai_settings_protocol_anthropic),
    )
    val providerRows = buildList {
        add(
            SettingsCardItem.Select(
                label = providerRowLabel,
                value = providerValue,
                items = providerItems,
                selectedId = presets.indexOfFirst { it.id == draftConnection.providerId },
                onPick = { index ->
                    val preset = presets.getOrNull(index)
                    if (preset != null) {
                        // 动作已由 SelectMenu 的 PopupActionGate 延后到菜单退场播完（重写帧
                        // 弹层已卸载）；此处退让窗口保护顶栏磨砂——重写帧内容列仍是它的采样源
                        suspendHazeForRewrite {
                            updateDraft(
                                draftConnection.copy(
                                    providerId = preset.id,
                                    // 选中预设即回填端点与协议；建议模型仅在非手填预设下覆盖
                                    baseUrl = preset.baseUrl.ifBlank { draftConnection.baseUrl },
                                    model = if (!preset.requiresManualModel &&
                                        preset.suggestedModel.isNotBlank()
                                    ) {
                                        preset.suggestedModel
                                    } else {
                                        draftConnection.model
                                    },
                                    protocol = preset.protocol,
                                ),
                            )
                        }
                    }
                },
            ),
        )
        // 接口格式仅自定义服务商可改：预设自带协议，展示会误导改坏
        if (draftConnection.providerId == AiProviderPresets.CUSTOM_ID) {
            add(
                SettingsCardItem.Select(
                    label = protocolLabel,
                    value = protocolValue,
                    items = protocolItems,
                    selectedId = draftConnection.protocol.ordinal,
                    onPick = { id ->
                        AiProtocol.entries.getOrNull(id)?.let { protocol ->
                            // 同服务商预设：动作延后到退场播完（SelectMenu 的 Gate），此处
                            // 退让窗口保护顶栏磨砂（崩溃史见 hazeSuspended）
                            suspendHazeForRewrite {
                                updateDraft(draftConnection.copy(protocol = protocol))
                            }
                        }
                    },
                ),
            )
        }
    }

    OverlaySheet(
        title = stringResource(R.string.ai_profile_title),
        confirmLabel = stringResource(R.string.edit_confirm),
        // 确认在右、取消在左（同课程时间方案浮层）
        confirmAtEnd = true,
        onConfirm = {
            val normalized = normalizeDraft()
            if (normalized != null) {
                onApply(
                    selectedProfileId,
                    normalized,
                    keyInput.trim().takeIf { it.isNotEmpty() },
                )
            }
        },
        rightAction = SheetTopAction(
            label = stringResource(R.string.settings_cancel),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = onDismiss,
        ),
        onDismiss = onDismiss,
        visible = visible,
        covered = covered,
        onDismissed = onDismissed,
        // 退让窗口内整条链卸载 Haze（顶栏走静态底材），内容落定后再挂回（崩溃史见 hazeSuspended）
        hazeEnabled = !hazeSuspended,
        hazeState = sheetHazeState,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            // A. 方案选择：点选只换草稿；确认才保存并切换生效配置
            CardSection {
                SelectCard(
                    options = profiles.map { it.name } + customLabel,
                    selectedIndices = setOf(selectedIndex),
                    onSelectionChange = { picked ->
                        val index = picked.firstOrNull()
                        if (index != null) {
                            val profile = profiles.getOrNull(index)
                            scope.launch { loadEntry(profile?.id) }
                        }
                    },
                    // 长按已保存方案才出菜单；末尾「自定义」行无菜单
                    longPressMenuItems = { index ->
                        val profile = profiles.getOrNull(index)
                        if (profile == null) {
                            emptyList()
                        } else {
                            listOf(
                                PopupMenuItem(
                                    label = renameLabel,
                                    icon = R.drawable.ic_rename,
                                    onClick = { openRename(profile) },
                                ),
                                PopupMenuItem(
                                    label = deleteLabel,
                                    icon = R.drawable.ic_delete,
                                    onClick = { requestDelete(profile) },
                                ),
                            )
                        }
                    },
                    menuHazeState = cardHazeState,
                )
            }

            // B. 连接明细：服务商 / 接口格式 + 三个输入行 + 保存为新方案
            CardSection(title = stringResource(R.string.ai_settings_section_connection)) {
                SettingsCard(items = providerRows, hazeState = cardHazeState)

                SheetTextFieldGroup(
                    fields = listOf(
                        SheetTextFieldItem(
                            label = stringResource(R.string.ai_settings_base_url),
                            value = draftConnection.baseUrl,
                            onValueChange = {
                                updateDraft(draftConnection.copy(baseUrl = it))
                                baseUrlInvalid = false
                            },
                            placeholder = stringResource(R.string.ai_settings_base_url_hint),
                            isError = baseUrlInvalid,
                            imeAction = ImeAction.Next,
                        ),
                        SheetTextFieldItem(
                            label = stringResource(R.string.ai_settings_model),
                            value = draftConnection.model,
                            onValueChange = {
                                updateDraft(draftConnection.copy(model = it))
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
                            onValueChange = ::updateKeyInput,
                            placeholder = stringResource(
                                if (hadKey) {
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

                // 保存为新方案：只入库不激活，成功后自动选中新增方案
                ActionRowCard(
                    label = stringResource(R.string.ai_profile_save_as),
                    icon = R.drawable.ic_add_circle_regular,
                    enabled = true,
                    onClick = ::openSaveAs,
                )
            }

            // C. 提示：图片输入需要多模态模型（纯展示行，无操作）
            CardSection(title = stringResource(R.string.ai_profile_hint_section)) {
                SettingsCard(
                    items = listOf(
                        SettingsCardItem.Custom(
                            label = stringResource(R.string.ai_profile_hint_title),
                            description = stringResource(R.string.ai_profile_hint_message),
                        ),
                    ),
                    hazeState = cardHazeState,
                )
            }
        }
    }

    // 方案名称输入（保存为新方案 / 重命名共用）：空名禁用确认
    if (nameMounted) {
        val renameTargetId = nameTargetId
        BottomTextFieldCard(
            visible = nameVisible,
            onDismiss = { nameVisible = false },
            onDismissed = { nameMounted = false },
            title = stringResource(
                if (renameTargetId != null) {
                    R.string.ai_profile_rename_title
                } else {
                    R.string.ai_profile_name_title
                },
            ),
            value = nameDraft,
            onValueChange = { nameDraft = it },
            placeholder = stringResource(R.string.ai_profile_name_hint),
            confirmEnabled = nameDraft.isNotBlank(),
            hazeState = cardHazeState,
            onConfirm = {
                if (renameTargetId != null) {
                    nameVisible = false
                    // 落库回流会异步重写采样源（方案行文本），退让到回流落定（崩溃史见 hazeSuspended）
                    suspendHazeForCommit { onRename(renameTargetId, nameDraft.trim()) }
                } else {
                    val normalized = normalizeDraft()
                    if (normalized != null) {
                        nameVisible = false
                        pendingSave = PendingProfileSave(
                            knownIds = profiles.map { it.id }.toSet(),
                            connection = normalized,
                        )
                        // 存为新方案：回流新增方案行（结构重写）+ 自动选中再改草稿，同走退让窗口
                        suspendHazeForCommit {
                            onSaveAs(
                                nameDraft.trim(),
                                normalized,
                                keyInput.trim().takeIf { it.isNotEmpty() },
                            )
                        }
                    }
                }
            },
        )
    }

    // 删除确认：方案及其保存的 API Key 一并删除，不可恢复
    if (deleteDialogMounted) {
        val target = deleteTarget
        FadeOverlayDialog(
            visible = deleteDialogVisible,
            onDismiss = { deleteDialogVisible = false },
            onDismissed = {
                deleteDialogMounted = false
                deleteTarget = null
            },
        ) {
            ConfirmDialogCard(
                title = stringResource(R.string.ai_profile_delete_title),
                message = stringResource(
                    R.string.ai_profile_delete_message,
                    target?.name ?: "",
                ),
            ) {
                DialogPillButton(
                    label = stringResource(R.string.settings_cancel),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    enabled = deleteDialogVisible,
                    onClick = { deleteDialogVisible = false },
                )
                DialogPillButton(
                    label = stringResource(R.string.edit_delete),
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.weight(1f),
                    enabled = deleteDialogVisible,
                    onClick = {
                        deleteDialogVisible = false
                        // 删除确认 = 整屏 FadeOverlayDialog 淡出 + 方案行增删同帧组合，是
                        // 账号页退出登录已验证的崩溃配方——落库重写走退让窗口（见 hazeSuspended）
                        suspendHazeForCommit { target?.let { onDelete(it.id) } }
                    },
                )
            }
        }
    }
}

/** 接口格式的展示文案（数据层不产文案，与 AiError 的 UI 映射同策略） */
private fun AiProtocol.labelRes(): Int = when (this) {
    AiProtocol.OpenAiCompat -> R.string.ai_settings_protocol_openai
    AiProtocol.Anthropic -> R.string.ai_settings_protocol_anthropic
}
