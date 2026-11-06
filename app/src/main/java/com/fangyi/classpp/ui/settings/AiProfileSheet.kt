package com.fangyi.classpp.ui.settings

import android.content.pm.ApplicationInfo
import androidx.activity.compose.BackHandler
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
import kotlinx.coroutines.launch

/** 一次「保存为新方案」的挂起信息：保存前的方案 id 集合 + 发起时的草稿，回流后据此选中新增方案 */
private data class PendingProfileSave(
    val knownIds: Set<String>,
    val connection: AiConnection,
)

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
 * - 确认时先做本地校验（服务地址 http 开头、模型名非空），失败 toast 并保留浮层；
 *   Key 缺失由宿主回调判定（保存后仍无可用 Key → 提示并保留浮层）。
 *
 * 容器为 [OverlaySheet]，取消在左、确认在右（同课程时间方案浮层）；底部输入卡与删除弹窗
 * 均为两段式挂载，在场时经 covered 让本浮层缩小后退。
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

    BackHandler(enabled = true) { onDismiss() }

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
                            updateDraft(draftConnection.copy(protocol = protocol))
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
                    menuHazeState = sheetHazeState,
                )
            }

            // B. 连接明细：服务商 / 接口格式 + 三个输入行 + 保存为新方案
            CardSection(title = stringResource(R.string.ai_settings_section_connection)) {
                SettingsCard(items = providerRows, hazeState = sheetHazeState)

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
            hazeState = sheetHazeState,
            onConfirm = {
                if (renameTargetId != null) {
                    nameVisible = false
                    onRename(renameTargetId, nameDraft.trim())
                } else {
                    val normalized = normalizeDraft()
                    if (normalized != null) {
                        nameVisible = false
                        pendingSave = PendingProfileSave(
                            knownIds = profiles.map { it.id }.toSet(),
                            connection = normalized,
                        )
                        onSaveAs(
                            nameDraft.trim(),
                            normalized,
                            keyInput.trim().takeIf { it.isNotEmpty() },
                        )
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
                        target?.let { onDelete(it.id) }
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
