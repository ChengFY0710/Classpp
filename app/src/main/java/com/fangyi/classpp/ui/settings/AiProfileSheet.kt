package com.fangyi.classpp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.fangyi.classpp.data.ai.AiError
import com.fangyi.classpp.data.ai.AiException
import com.fangyi.classpp.data.ai.AiFetchedModel
import com.fangyi.classpp.data.ai.AiModelConfig
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
import com.fangyi.classpp.ui.components.LoadingOverlay
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.PopupMenuItem
import com.fangyi.classpp.ui.components.SelectCard
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SheetTextFieldGroup
import com.fangyi.classpp.ui.components.SheetTextFieldItem
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.components.rememberHazeRetreat
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 一次「保存为新方案」的挂起信息：保存前的方案 id 集合 + 发起时的草稿 + 草稿来源 */
private data class PendingProfileSave(
    val knownIds: Set<String>,
    val connection: AiConnection,
    /** 发起时选中的是「自定义」槽位：Key 已随方案迁走，回流后同步清掉会话内草稿 */
    val fromCustom: Boolean,
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
 * - 下方「连接」分组：服务商预设 / 接口格式（仅自定义服务商可改）+ 服务地址 / API Key
 *   + 末尾「保存为新方案」（只入库不激活，成功后自动选中新方案；API Key 随方案一同保存：
 *   输入框留空时沿用来源条目已存的 Key，来源是「自定义」槽位时整份草稿搬到新方案，
 *   迁移后清空槽位里的 Key 与模型名）；
 * - 「模型」分组：同一服务商 + Key 可保存多个模型，SettingsCard 行点按打开「配置模型」
 *   浮层（模型 ID + 推理等级），末尾「添加模型」同一入口；新增 / 编辑后该模型自动成为
 *   当前模型，删除当前模型时回落剩余首个（允许删空，由确认时的非空校验兜底），
 *   对话浮层输入栏上方仍可切换；
 * - 「从服务商拉取模型」：用当前服务地址与 API Key（输入框优先，留空回落当前条目
 *   已保存的 Key）拉取可用模型（端点随服务商预设的拉取方案 AiModelListApi 配置），
 *   拉取期间整屏加载态；结果过滤掉已加入的模型后打开多选浮层，确认合并进本地草稿
 *   并回填服务商附带的推理等级配置（如 DeepSeek 的 effort 元数据），已有模型与其
 *   配置原样保留，父浮层「确认」才随连接落库；
 * - 末尾「提示」分组：纯展示行，说明对话图片需要多模态（视觉）模型；
 * - 确认时先做本地校验（服务地址 http 开头、至少一个模型名），失败 toast 并保留浮层；
 *   Key 缺失由宿主回调判定（保存后仍无可用 Key → 提示并保留浮层）。
 *
 * 容器为 [OverlaySheet]，取消在左、确认在右（同课程时间方案浮层）；底部输入卡为两段式挂载，
 * 在场时经 covered 让本浮层缩小后退；删除确认是整屏压暗的 [FadeOverlayDialog]，层级已由
 * 遮罩表达，本浮层保持原位不缩小（同课表切换浮层的删除确认）。
 *
 * Haze 退让：切换服务商预设与确认类落库（改名 / 存为新方案 / 删除）会在 Haze 效果节点
 * 在场时同帧或随仓库回流重写采样源结构，重写窗口内整条链退让为静态底材、落定两帧后
 * 挂回（崩溃史与机制见下方 [com.fangyi.classpp.ui.components.HazeRetreatState] 注释）。
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
    // sourceProfileId 为 null = 草稿来自「自定义」槽位（已存 Key 随之迁入新方案并置空槽位）
    onSaveAs: (name: String, connection: AiConnection, apiKey: String?, sourceProfileId: String?) -> Unit,
    onRename: (id: String, name: String) -> Unit,
    onDelete: (id: String) -> Unit,
    loadCustomConfig: suspend () -> Pair<AiConnection, Boolean>,
    loadProfileKeyPresent: suspend (id: String) -> Boolean,
    // 读取已存 Key（null = 「自定义」槽位）：拉取模型时输入框留空的回落来源
    loadSavedKey: suspend (profileId: String?) -> String?,
    // 拉取服务商可用模型列表（GET /models）：只读网络，Key 优先用输入框当前值；
    // 结果含服务商附带的模型级配置（如 DeepSeek 的推理等级）
    fetchModels: suspend (connection: AiConnection, apiKey: String) -> List<AiFetchedModel>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val customLabel = stringResource(R.string.ai_profile_custom)
    val invalidMessage = stringResource(R.string.ai_settings_invalid)
    val modelEmptyMessage = stringResource(R.string.ai_settings_model_empty)
    val fetchKeyRequiredMessage = stringResource(R.string.ai_model_fetch_key_required)
    val fetchEmptyMessage = stringResource(R.string.ai_model_fetch_empty)
    val fetchFailedMessage = stringResource(R.string.ai_model_fetch_failed)
    val renameLabel = stringResource(R.string.edit_rename)
    val deleteLabel = stringResource(R.string.edit_delete)
    val defaultName = stringResource(R.string.ai_profile_default_name, profiles.size + 1)
    // 服务商预设名可直接当方案名；自定义服务商的预设名是「自定义」，与槽位行同名，改用序号
    val customProviderLabel = AiProviderPresets.byId(AiProviderPresets.CUSTOM_ID).label
    // 协程回调里不能调用 stringResource：组合期先把分类错误文案取好。
    // NotFound 用拉取场景专用文案：404 只说明该地址不提供模型列表，与模型名无关
    val errorTexts = mapOf(
        AiError.Auth to stringResource(R.string.ai_error_auth),
        AiError.Quota to stringResource(R.string.ai_error_quota),
        AiError.NotFound to stringResource(R.string.ai_model_fetch_not_found),
        AiError.RateLimited to stringResource(R.string.ai_error_rate_limited),
        AiError.Server to stringResource(R.string.ai_error_server),
        AiError.Network to stringResource(R.string.ai_error_network),
        AiError.BadResponse to stringResource(R.string.ai_error_bad_response),
    )
    val badResponseText = errorTexts.getValue(AiError.BadResponse)

    // 选中项：null = 自定义；draft/hadKey 只服务「确认」前的本地编辑
    var selectedProfileId by remember { mutableStateOf<String?>(null) }
    var draftConnection by remember { mutableStateOf(AiConnection()) }
    var keyInput by remember { mutableStateOf("") }
    var hadKey by remember { mutableStateOf(false) }
    var baseUrlInvalid by remember { mutableStateOf(false) }

    // 「自定义」槽位的会话内草稿：切去已保存方案再切回时恢复
    var customConnection by remember { mutableStateOf(AiConnection()) }
    var customKeyInput by remember { mutableStateOf("") }
    var customHadKey by remember { mutableStateOf(false) }

    // 「配置模型」子浮层（新增 / 编辑共用；target 为 null = 新增）
    var modelSheetVisible by remember { mutableStateOf(false) }
    var modelSheetMounted by remember { mutableStateOf(false) }
    var modelSheetTarget by remember { mutableStateOf<String?>(null) }

    // 「从服务商拉取模型」：在途加载态 + 拉取结果多选浮层（结果只进本地草稿，
    // 含服务商附带的推理等级等模型级配置）
    var fetching by remember { mutableStateOf(false) }
    var fetchedModels by remember { mutableStateOf(emptyList<AiFetchedModel>()) }
    var pickerVisible by remember { mutableStateOf(false) }
    var pickerMounted by remember { mutableStateOf(false) }

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

    fun clearBaseUrlError() {
        baseUrlInvalid = false
    }

    fun normalizeDraft(): AiConnection? {
        val trimmedUrl = draftConnection.baseUrl.trim()
        val normalized = draftConnection
            .copy(baseUrl = trimmedUrl.trimEnd('/'))
            .withNormalizedModels()
            .let { connection ->
                // 当前模型为空但列表已有模型（旧草稿/首个模型尚未选中）时，保存取列表首个
                if (connection.model.isBlank() && connection.modelOptions.isNotEmpty()) {
                    connection.copy(model = connection.modelOptions.first())
                } else {
                    connection
                }
            }
        // 规范化结果写回草稿：后续「保存为新方案」的 pendingSave 比较才能与落库值一致
        updateDraft(normalized)
        // 两类必填分开提示：合并成一条文案时，用户看不出到底缺的是哪一项
        baseUrlInvalid = !trimmedUrl.startsWith("http")
        if (baseUrlInvalid) {
            AppToasts.show(context, invalidMessage)
            return null
        }
        // 模型列表为空才会走到这里（列表非空时上面已回填首个模型）：
        // 拦下确认 / 保存为新方案，提示到具体字段
        if (normalized.model.isBlank()) {
            AppToasts.show(context, modelEmptyMessage)
            return null
        }
        return normalized
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
        clearBaseUrlError()
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

    fun openAddModel() {
        modelSheetTarget = null
        modelSheetMounted = true
        modelSheetVisible = true
    }

    fun openModelEditor(model: String) {
        modelSheetTarget = model
        modelSheetMounted = true
        modelSheetVisible = true
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
        if (pending.fromCustom) {
            // 原「自定义」槽位的 Key 与模型名已随方案保存并清空：会话内草稿同步清掉，
            // 切回自定义时不回显已迁走的内容，也不会再次写回旧槽位
            customKeyInput = ""
            customHadKey = false
            customConnection = customConnection.withModels(emptyList(), "")
        }
        if (draftConnection == pending.connection) {
            selectedProfileId = saved.id
            draftConnection = saved.connection
            keyInput = ""
            hadKey = loadProfileKeyPresent(saved.id)
            clearBaseUrlError()
        }
    }

    val customIndex = profiles.size
    val selectedIndex = profiles.indexOfFirst { it.id == selectedProfileId }
        .takeIf { it >= 0 } ?: customIndex
    // 只有底部输入卡与叠层浮层让本浮层缩小后退：删除确认是整屏压暗的 FadeOverlayDialog，
    // 遮罩本身已表达层级，本浮层保持原位（缩小反而是同一动作的重复表达）
    val covered = nameVisible || modelSheetVisible || pickerVisible
    val sheetHazeState = rememberHazeState()

    // —— Haze 退让窗口（RenderThread SIGSEGV 防御，同 AiChatOverlay 的对话切换退让）——
    // 崩溃史（2026-11-07，切换服务商预设必崩，签名同 tombstone_05 / 账号页退出登录）：
    // 本浮层内容列是 OverlaySheet 顶栏磨砂与弹出菜单（独立 Popup 窗口）的共享采样源。
    // 在服务商下拉菜单里点选那一帧，菜单开始退场但仍挂载采样（播完才卸载），onPick 同帧
    // 重写草稿——「接口格式」行随 providerId 增删 + 三个输入框整值替换，采样源子树在效果
    // 节点在场时被结构重写，Haze 2 构建出自引用 RenderNode，RenderThread 在 prepareTreeImpl
    // 无限递归直到栈溢出（SIGSEGV code 2）。对策沿用项目内两处已验证修复（XmuSyncScreen
    // 对话框 gate / AiChatOverlay 整链退让）：重写与 Haze 卸载落同帧重组，该帧所有窗口零
    // 效果节点，源重写无效果可撞；内容落定两帧后挂回（状态机与时序见
    // [com.fangyi.classpp.ui.components.HazeRetreatState]）。
    // 覆盖范围：① 服务商预设 / 接口格式点选：点选帧的重写已由 SelectMenu 内建的
    // PopupActionGate 延后到菜单退场播完（重写帧弹层已卸载，零效果节点可撞），本退让
    // 窗口随之延后启动——保护对象变为顶栏磨砂（重写帧内容列仍是它的采样源）；② 改名 /
    // 存为新方案 / 删除确认（重写随仓库回流异步到达，退让延续到 profiles 回流帧 + 两帧，
    // 见下方 forCommit / onReflow）。方案选择（SelectCard 点选）只动勾选与文本、无弹层
    // 在场，同全应用其余浮层的同类操作，不在退让范围。
    val hazeRetreat = rememberHazeRetreat()

    // 回流落定：重写帧必然仍在退让窗口内，此处只推迟挂回；等待期间再来的回流会重启本
    // 效果、重新计两帧（未处于退让窗口时为空操作）
    LaunchedEffect(profiles) {
        hazeRetreat.onReflow()
    }

    // 退让期间叠层弹卡 / 菜单统一退为实底（OverlaySheet 同规则：hazeEnabled = false 时不给
    // 叠层传采样源）
    val cardHazeState = if (hazeRetreat.suspended) null else sheetHazeState

    /** 配置模型浮层确认：新增 / 编辑后该模型自动成为当前模型；只改本地草稿 */
    fun applyModelConfig(id: String, config: AiModelConfig) {
        hazeRetreat.forRewrite {
            val target = modelSheetTarget
            val options = draftConnection.modelOptions
            val nextModels = if (target == null) {
                options + id
            } else {
                options.map { if (it.equals(target, ignoreCase = true)) id else it }
            }
            updateDraft(
                draftConnection
                    .withModels(nextModels, id)
                    .withModelConfig(id, config),
            )
        }
    }

    /** 删除模型：删当前模型时回落剩余首个；允许删空（确认时由模型非空校验拦截） */
    fun deleteModelConfig(model: String) {
        hazeRetreat.forRewrite {
            val remaining = draftConnection.modelOptions
                .filterNot { it.equals(model, ignoreCase = true) }
            val nextCurrent = if (draftConnection.model.equals(model, ignoreCase = true)) {
                remaining.firstOrNull().orEmpty()
            } else {
                draftConnection.model
            }
            updateDraft(draftConnection.withModels(remaining, nextCurrent))
        }
    }

    /**
     * 拉取服务商模型列表：Key 优先用输入框当前值，留空时回落当前条目已保存的 Key；
     * 两者都没有才提示先填写。先做地址的本地校验，成功结果过滤掉草稿里已加入的模型
     *（忽略大小写）后交多选浮层。
     */
    fun startFetchModels() {
        val baseUrl = draftConnection.baseUrl.trim()
        if (!baseUrl.startsWith("http")) {
            baseUrlInvalid = true
            AppToasts.show(context, invalidMessage)
            return
        }
        val typedKey = keyInput.trim()
        // 发起时的选中条目：协程起跑后即使选择变化，也按当时条目的已存 Key 回落
        val targetProfileId = selectedProfileId
        val target = draftConnection.copy(baseUrl = baseUrl.trimEnd('/'))
        scope.launch {
            // 读取已存 Key 是本地 I/O：放在加载态之外，缺 Key 时直接提示、不闪加载遮罩
            val apiKey = typedKey.ifEmpty {
                loadSavedKey(targetProfileId)?.trim().orEmpty()
            }
            if (apiKey.isEmpty()) {
                AppToasts.show(context, fetchKeyRequiredMessage)
                return@launch
            }
            fetching = true
            try {
                val models = fetchModels(target, apiKey)
                val existing = draftConnection.modelOptions
                val fresh = models
                    .filterNot { fetched -> existing.any { it.equals(fetched.id, ignoreCase = true) } }
                    .sortedBy { it.id.lowercase() }
                if (fresh.isEmpty()) {
                    AppToasts.show(context, fetchEmptyMessage)
                } else {
                    fetchedModels = fresh
                    pickerMounted = true
                    pickerVisible = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                AppToasts.show(
                    context,
                    fetchFailedMessage.format(errorTexts[e.error] ?: badResponseText),
                )
            } catch (_: Exception) {
                AppToasts.show(context, fetchFailedMessage.format(badResponseText))
            } finally {
                fetching = false
            }
        }
    }

    /**
     * 拉取结果合并：追加勾选的新模型，并把服务商附带的推理等级配置一并写入
     * （如 DeepSeek 的 effort 元数据）；已有模型与它们的配置原样保留。
     */
    fun applyFetchedModels(selected: List<String>) {
        if (selected.isEmpty()) return
        val fetched = fetchedModels
        hazeRetreat.forRewrite {
            val nextCurrent = draftConnection.model.takeIf { it.isNotBlank() } ?: selected.first()
            var next = draftConnection.withModels(draftConnection.modelOptions + selected, nextCurrent)
            fetched.forEach { model ->
                if (!model.config.isEmpty && selected.any { it.equals(model.id, ignoreCase = true) }) {
                    next = next.withModelConfig(model.id, model.config)
                }
            }
            updateDraft(next)
        }
    }

    // 返回键优先收键盘，键盘已收起时才关浮层
    BackHandlerHidesKeyboard(onBack = onDismiss)

    // 服务商预设行（与旧 AI 设置页同一套回填规则）
    val presets = AiProviderPresets.all
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
                        hazeRetreat.forRewrite {
                            val providerChanged = preset.id != draftConnection.providerId
                            // 选中预设即回填端点与协议；建议模型仅在非手填预设下覆盖
                            val nextModel = if (!preset.requiresManualModel &&
                                preset.suggestedModel.isNotBlank()
                            ) {
                                preset.suggestedModel
                            } else {
                                draftConnection.model
                            }
                            // 换服务商时列表收敛为单个模型，避免旧服务商的模型名残留
                            val nextModels = if (providerChanged) {
                                nextModel.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
                            } else {
                                draftConnection.models
                            }
                            updateDraft(
                                draftConnection
                                    // 换服务商时列表收敛：被移除模型的推理等级配置一并裁剪
                                    .withModels(nextModels, nextModel)
                                    .copy(
                                        providerId = preset.id,
                                        baseUrl = preset.baseUrl.ifBlank { draftConnection.baseUrl },
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
                            // 退让窗口保护顶栏磨砂（崩溃史见上方退让窗口）
                            hazeRetreat.forRewrite {
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
        // 退让窗口内整条链卸载 Haze（顶栏走静态底材），内容落定后再挂回（崩溃史见上方退让窗口）
        hazeEnabled = !hazeRetreat.suspended,
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
                                    // 危险操作：error 色 + 自动分割线与「重命名」隔离（B6）
                                    destructive = true,
                                    onClick = { requestDelete(profile) },
                                ),
                            )
                        }
                    },
                    menuHazeState = cardHazeState,
                )
            }

            // B. 连接明细：服务商 / 接口格式 + 服务地址 / API Key + 保存为新方案
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

            // C. 模型列表：行点按打开「配置模型」编辑；确认后该模型自动成为当前模型
            CardSection(title = stringResource(R.string.ai_settings_section_models)) {
                val modelOptions = draftConnection.modelOptions
                // 空列表不出占位卡：没有模型时只保留下方「添加模型」入口，
                // 缺模型的校验由保存 / 确认时的 toast 提示
                if (modelOptions.isNotEmpty()) {
                    SettingsCard(
                        items = modelOptions.map { model ->
                            val level = draftConnection.modelConfig(model).reasoningLevel
                            SettingsCardItem.Nav(
                                label = model,
                                description = level.takeIf { it.isNotBlank() }?.let {
                                    stringResource(R.string.ai_model_reasoning_value, it)
                                },
                                onClick = { openModelEditor(model) },
                            )
                        },
                        hazeState = cardHazeState,
                    )
                }
                ActionRowCard(
                    label = stringResource(R.string.ai_settings_model_add),
                    icon = R.drawable.ic_add_circle_regular,
                    enabled = true,
                    onClick = ::openAddModel,
                )
                // 从服务商拉取可用模型：用当前服务地址 + 当前条目 Key，结果多选后合并进草稿
                SettingsCard(
                    items = listOf(
                        SettingsCardItem.Nav(
                            label = stringResource(R.string.ai_model_fetch),
                            onClick = ::startFetchModels,
                        ),
                    ),
                    hazeState = cardHazeState,
                )
            }

            // D. 提示：图片输入需要多模态模型（纯展示行，无操作）
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

    // 「配置模型」子浮层：确认 / 删除只回写本地草稿，父浮层「确认」才随连接落库；
    // 父层退让（模型列表重写帧）时本浮层同步卸载 Haze 效果节点
    if (modelSheetMounted) {
        val editingModel = modelSheetTarget
        AiModelConfigSheet(
            visible = modelSheetVisible,
            onDismiss = { modelSheetVisible = false },
            onDismissed = { modelSheetMounted = false },
            editingModel = editingModel,
            initialConfig = editingModel?.let(draftConnection::modelConfig) ?: AiModelConfig(),
            existingModels = draftConnection.modelOptions,
            hazeEnabled = !hazeRetreat.suspended,
            onConfirm = ::applyModelConfig,
            onDelete = editingModel?.let { target -> { deleteModelConfig(target) } },
            modifier = Modifier.fillMaxSize(),
        )
    }

    // 「选择模型」子浮层：确认只把勾选的新模型合并进本地草稿（同「配置模型」，
    // 父浮层确认才落库）；父层合并结果（模型行结构变化）期间本浮层同步卸载 Haze
    if (pickerMounted) {
        AiModelPickerSheet(
            visible = pickerVisible,
            models = fetchedModels,
            onDismiss = { pickerVisible = false },
            onDismissed = {
                pickerMounted = false
                fetchedModels = emptyList()
            },
            onConfirm = ::applyFetchedModels,
            hazeEnabled = !hazeRetreat.suspended,
            modifier = Modifier.fillMaxSize(),
        )
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
                    // 落库回流会异步重写采样源（方案行文本），退让到回流落定（崩溃史见上方退让窗口）
                    hazeRetreat.forCommit { onRename(renameTargetId, nameDraft.trim()) }
                } else {
                    val normalized = normalizeDraft()
                    if (normalized != null) {
                        nameVisible = false
                        // 来源在发起时记下：回流后草稿已被自动切到新方案，届时无从判断
                        val sourceProfileId = selectedProfileId
                        pendingSave = PendingProfileSave(
                            knownIds = profiles.map { it.id }.toSet(),
                            connection = normalized,
                            fromCustom = sourceProfileId == null,
                        )
                        // 存为新方案：回流新增方案行（结构重写）+ 自动选中再改草稿，同走退让窗口
                        hazeRetreat.forCommit {
                            onSaveAs(
                                nameDraft.trim(),
                                normalized,
                                keyInput.trim().takeIf { it.isNotEmpty() },
                                sourceProfileId,
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
                        // 账号页退出登录已验证的崩溃配方——落库重写走退让窗口（见上方退让窗口）
                        hazeRetreat.forCommit { target?.let { onDelete(it.id) } }
                    },
                )
            }
        }
    }

    // 拉取模型列表的加载态：整屏压暗由 FadeOverlayDialog 自带遮罩表达，不并入 covered
    //（浮层层级动效规范）；网络请求由传输层 20s 整次上限兜底，不会无限挂起
    LoadingOverlay(
        visible = fetching,
        message = stringResource(R.string.ai_model_fetch_loading),
    )
}

/** 接口格式的展示文案（数据层不产文案，与 AiError 的 UI 映射同策略） */
private fun AiProtocol.labelRes(): Int = when (this) {
    AiProtocol.OpenAiCompat -> R.string.ai_settings_protocol_openai
    AiProtocol.Anthropic -> R.string.ai_settings_protocol_anthropic
}
