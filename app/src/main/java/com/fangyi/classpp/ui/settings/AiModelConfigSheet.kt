package com.fangyi.classpp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ai.AiModelConfig
import com.fangyi.classpp.data.ai.AiProviderPresets
import com.fangyi.classpp.ui.components.ActionRowCard
import com.fangyi.classpp.ui.components.BackHandlerHidesKeyboard
import com.fangyi.classpp.ui.components.BottomTextFieldCard
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SheetCard
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetTextField
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.components.rememberHazeRetreat
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.SettingsCardShape
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import dev.chrisbanes.haze.rememberHazeState

/**
 * 「配置模型」浮层（AI 配置方案浮层 → 模型行 /「添加模型」打开）：
 *
 * - 新增（[editingModel] = null）：左上「取消」、右上「确认」；
 * - 编辑（[editingModel] 非空）：左上「删除」、右上「确认」，与课程编辑浮层同一套顶栏实现；
 * - 模型 ID 用 [SheetTextField] 编辑，确认时 trim 后做非空与忽略大小写查重（排除自身）；
 * - 推理等级每个等级一张行卡（与「切换课表」浮层的课表卡同款 [SheetCard]）：点卡选中
 *   （当前等级名称主色 + 尾部对勾），行尾删除键即删（无二次确认）；末尾「添加推理等级」
 *   走底部输入卡，上方另有一行常用等级快捷预设（[ReasoningLevelPresets]），点按即加入
 *   并选中，已在列表中的预设不重复出现；等级可删到空，空列表 = 该模型不使用推理等级，
 *   列表非空时选中项自动回落首个；
 * - 新增等级直接追加卡片，**不走 Haze 源重写退让**（本浮层其余重写动作仍沿用退让接线）；
 * - 说明卡按服务商 + 模型族区分：始终思考的模型族（Kimi K3、GLM-5.3 等）首档只是
 *   最低强度，不显示「第一个为关」的旧说明；
 * - 本浮层只改父浮层的**本地草稿**（父浮层「确认」才连同连接一起落库）。
 *
 * 层级：叠在 AI 配置方案浮层之上，打开/关闭时父浮层经 covered 缩小后退。等级增删是
 * 本浮层内容列的结构重写，走 [rememberHazeRetreat] 的同帧退让；上层父浮层的重写
 * （确认 / 删除回写模型列表）由 [hazeEnabled] 下发退让开关，两层 Haze 同帧卸载。
 */
@Composable
internal fun AiModelConfigSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    // null = 新增；非空 = 正在编辑的模型 id（查重时排除自身）
    editingModel: String?,
    initialConfig: AiModelConfig,
    // 同一方案内已有的模型 id（不含正被编辑的写法差异由本浮层处理）
    existingModels: List<String>,
    // 当前草稿的服务商预设 id：决定该模型族是否支持「关」档，说明卡文案随之切换
    providerId: String,
    // 父浮层的 Haze 退让开关：父层源子树重写帧本浮层同步卸载效果节点
    hazeEnabled: Boolean,
    onConfirm: (modelId: String, config: AiModelConfig) -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val emptyMessage = stringResource(R.string.ai_settings_model_empty)
    val duplicateModelMessage = stringResource(R.string.ai_settings_model_duplicate)
    val duplicateLevelMessage = stringResource(R.string.ai_model_reasoning_duplicate)
    val deleteLabel = stringResource(R.string.edit_delete)

    var modelId by remember { mutableStateOf(editingModel.orEmpty()) }
    var levels by remember { mutableStateOf(initialConfig.reasoningLevels) }
    var selectedLevel by remember { mutableStateOf(initialConfig.reasoningLevel) }
    var idError by remember { mutableStateOf(false) }
    // 始终思考的模型族首档不是「关」，说明卡文案需要区分
    val reasoningOffSupported = AiProviderPresets
        .thinkingControlFor(providerId, modelId)
        .supportsOff

    // 添加推理等级的底部输入卡（与旧模型列表的底部输入同一套两段式挂载）
    var levelInputVisible by remember { mutableStateOf(false) }
    var levelInputMounted by remember { mutableStateOf(false) }
    var levelDraft by remember { mutableStateOf("") }

    // 每次打开（含退场未播完就重开）按当前编辑目标重置草稿
    LaunchedEffect(visible, editingModel) {
        if (!visible) return@LaunchedEffect
        val normalized = initialConfig.normalized()
        modelId = editingModel.orEmpty()
        levels = normalized.reasoningLevels
        selectedLevel = normalized.reasoningLevel
        idError = false
        levelInputVisible = false
        levelInputMounted = false
        levelDraft = ""
    }

    val sheetHazeState = rememberHazeState()
    val hazeRetreat = rememberHazeRetreat()
    // 父层重写帧（确认 / 删除回写模型列表）与本地等级增删帧都让本条 Haze 链退让
    val hazeActive = hazeEnabled && !hazeRetreat.suspended
    val cardHazeState = if (hazeActive) sheetHazeState else null

    fun confirm() {
        val trimmed = modelId.trim()
        if (trimmed.isEmpty()) {
            idError = true
            AppToasts.show(context, emptyMessage)
            return
        }
        val duplicate = existingModels.any { model ->
            model.equals(trimmed, ignoreCase = true) &&
                !model.equals(editingModel.orEmpty(), ignoreCase = true)
        }
        if (duplicate) {
            AppToasts.show(context, duplicateModelMessage)
            return
        }
        idError = false
        onConfirm(trimmed, AiModelConfig(levels, selectedLevel))
        onDismiss()
    }

    fun openAddLevel() {
        levelDraft = ""
        levelInputMounted = true
        levelInputVisible = true
    }

    fun addLevel() {
        val trimmed = levelDraft.trim()
        if (trimmed.isEmpty()) return
        if (levels.any { it.equals(trimmed, ignoreCase = true) }) {
            AppToasts.show(context, duplicateLevelMessage)
            return
        }
        levelInputVisible = false
        // 新增即追加一张等级卡：按需求不走 Haze 源重写退让（见浮层 KDoc）
        levels = levels + trimmed
        // 首个等级自动选中（同旧模型列表：当前为空时新加的项成为当前）
        if (selectedLevel.isBlank()) selectedLevel = trimmed
    }

    fun deleteLevel(level: String) {
        hazeRetreat.forRewrite {
            val remaining = levels.filterNot { it.equals(level, ignoreCase = true) }
            levels = remaining
            if (selectedLevel.equals(level, ignoreCase = true)) {
                selectedLevel = remaining.firstOrNull().orEmpty()
            }
        }
    }

    /** 快捷预设点按：把等级加入列表并选中（已在列表中的只选中，防同帧重复点；不走 Haze 退让） */
    fun addPreset(preset: String) {
        val existing = levels.firstOrNull { it.equals(preset, ignoreCase = true) }
        if (existing == null) levels = levels + preset
        selectedLevel = existing ?: preset
    }

    fun deleteModel() {
        onDelete?.invoke()
        onDismiss()
    }

    // 返回键优先收键盘，键盘已收起时才关浮层；等级输入卡后注册、先收到返回键。
    // 退场挂载期间不再接管返回键，交回父浮层
    BackHandlerHidesKeyboard(enabled = visible, onBack = onDismiss)

    OverlaySheet(
        title = stringResource(R.string.ai_model_sheet_title),
        confirmLabel = stringResource(R.string.edit_confirm),
        // 确认在右、取消/删除在左（同课程编辑浮层）
        confirmAtEnd = true,
        onConfirm = ::confirm,
        rightAction = if (editingModel != null) {
            SheetTopAction(
                label = deleteLabel,
                icon = R.drawable.ic_delete_filled,
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                contentColorGlass = MaterialTheme.classppColors.onPrimaryGlass,
                onClick = ::deleteModel,
            )
        } else {
            SheetTopAction(
                label = stringResource(R.string.settings_cancel),
                icon = R.drawable.ic_dismiss_circle,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                onClick = onDismiss,
            )
        },
        onDismiss = onDismiss,
        visible = visible,
        // 只有底部输入卡让本浮层缩小后退
        covered = levelInputVisible,
        onDismissed = onDismissed,
        imeBehavior = SheetImeBehavior.ContentScroll,
        hazeEnabled = hazeActive,
        hazeState = sheetHazeState,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            // A. 模型 ID：确认时 trim + 查重
            CardSection {
                SheetTextField(
                    label = stringResource(R.string.ai_model_id_label),
                    value = modelId,
                    onValueChange = {
                        modelId = it
                        idError = false
                    },
                    placeholder = stringResource(R.string.ai_settings_model_hint),
                    isError = idError,
                    imeAction = ImeAction.Done,
                )
            }

            // B. 推理等级：每个等级一张行卡（点卡选中、尾部删除键即删），末尾添加走底部输入卡
            CardSection(title = stringResource(R.string.ai_model_reasoning_section)) {
                levels.forEach { level ->
                    key(level) {
                        LevelCard(
                            level = level,
                            selected = level.equals(selectedLevel, ignoreCase = true),
                            onClick = { selectedLevel = level },
                            onDelete = { deleteLevel(level) },
                        )
                    }
                }
                // 快捷预设：只列尚未在等级列表里的常用值（已在列表中的由上方等级卡
                // 呈现选中态），点按即加入并选中；全部已添加时整行隐藏
                val pendingPresets = ReasoningLevelPresets.filterNot { preset ->
                    levels.any { it.equals(preset, ignoreCase = true) }
                }
                if (pendingPresets.isNotEmpty()) {
                    LevelPresetRow(presets = pendingPresets, onPick = ::addPreset)
                }
                ActionRowCard(
                    label = stringResource(R.string.ai_model_reasoning_add),
                    icon = R.drawable.ic_add_circle_regular,
                    enabled = true,
                    onClick = ::openAddLevel,
                )
                // 说明卡（纯描述行：无控件与点击）：支持关的模型族首档为「关」；
                // 始终思考的模型族（Kimi K3、GLM-5.3）首档是最低强度，文案随之区分
                SettingsCard(
                    items = listOf(
                        SettingsCardItem.Custom(
                            label = stringResource(R.string.ai_model_reasoning_map_title),
                            description = stringResource(
                                if (reasoningOffSupported) {
                                    R.string.ai_model_reasoning_map_desc
                                } else {
                                    R.string.ai_model_reasoning_map_desc_always_on
                                },
                            ),
                        ),
                    ),
                )
            }
        }
    }

    // 推理等级输入：空值禁用确认；重复项留在输入卡上提示
    if (levelInputMounted) {
        BottomTextFieldCard(
            visible = levelInputVisible,
            onDismiss = { levelInputVisible = false },
            onDismissed = { levelInputMounted = false },
            title = stringResource(R.string.ai_model_reasoning_add),
            value = levelDraft,
            onValueChange = { levelDraft = it },
            placeholder = stringResource(R.string.ai_model_reasoning_hint),
            confirmEnabled = levelDraft.isNotBlank(),
            hazeState = cardHazeState,
            onConfirm = ::addLevel,
        )
    }
}

/**
 * 一张推理等级卡（与「切换课表」浮层的课表卡同款 [SheetCard]）：白卡整行可点选中，
 * 当前等级名称主色 + 尾部对勾，行尾删除键即删（无二次确认，同改造前的长按菜单删除）。
 */
@Composable
private fun LevelCard(
    level: String,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    SheetCard(onClick = onClick) {
        Text(
            text = level,
            style = MaterialTheme.classppTextStyles.fieldLabel.copy(
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            ),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_checkmark),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        // 删除钮用独立按压源（同课表卡）：宿主 SheetCard 的反馈是它自己的源，子钮不会跟着双亮；
        // 按压反馈追加在 size 之后、最靠近按钮本体
        val press = remember { MutableInteractionSource() }
        IconButton(
            onClick = onDelete,
            interactionSource = press,
            modifier = Modifier
                .size(40.dp)
                .pressFeedback(press, CircleShape),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = stringResource(R.string.edit_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 常用推理等级预设（OpenAI reasoning_effort 的通用档位；Anthropic 侧同名映射为思考预算） */
private val ReasoningLevelPresets = listOf("minimal", "low", "medium", "high", "xhigh")

/** 预设胶囊间距：横向相邻与换行后的行距同值 */
private val ReasoningPresetSpacing = 8.dp

/** 预设行白卡的内距（同 SelectionGrid 的卡缘留白量级） */
private val ReasoningPresetRowPadding = 10.dp

/** 单枚预设胶囊内距：水平 12、垂直 8（同 SheetInfoPillRow 的信息胶囊几何） */
private val ReasoningPresetPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

/** 预设胶囊底色透明度：primary 10% 底 + primary 字（标签胶囊未选中态同源配色） */
private const val ReasoningPresetBackgroundAlpha = 0.1f

/**
 * 推理等级快捷预设行：白卡壳内一行 [PillShape] 胶囊（[FlowRow]，大字号/窄屏自动换行），
 * primary 10% 底 + primary 字。点按经 [onPick] 把预设加入等级列表并选中；已在列表中的
 * 预设不出现（由上方 [LevelCard] 呈现选中态），全部已添加时宿主直接隐藏整行。
 */
@Composable
private fun LevelPresetRow(presets: List<String>, onPick: (String) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SettingsCardShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(ReasoningPresetRowPadding),
        horizontalArrangement = Arrangement.spacedBy(ReasoningPresetSpacing),
        verticalArrangement = Arrangement.spacedBy(ReasoningPresetSpacing),
    ) {
        presets.forEach { preset ->
            LevelPresetChip(label = preset, onClick = { onPick(preset) })
        }
    }
}

/** 单枚预设胶囊：primary 10% 底 + primary 字，按压反馈经 pressClickable 铺满胶囊形状 */
@Composable
private fun LevelPresetChip(label: String, onClick: () -> Unit) {
    val contentColor = MaterialTheme.colorScheme.primary
    Text(
        text = label,
        modifier = Modifier
            .pressClickable(PillShape, onClick = onClick)
            .clip(PillShape)
            .background(contentColor.copy(alpha = ReasoningPresetBackgroundAlpha))
            .padding(ReasoningPresetPadding),
        style = ClassppTextTokens.secondaryTextSmall.copy(color = contentColor),
    )
}
