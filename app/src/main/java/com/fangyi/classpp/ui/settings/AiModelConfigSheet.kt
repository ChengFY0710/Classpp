package com.fangyi.classpp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ai.AiModelConfig
import com.fangyi.classpp.ui.components.ActionRowCard
import com.fangyi.classpp.ui.components.BackHandlerHidesKeyboard
import com.fangyi.classpp.ui.components.BottomTextFieldCard
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.PopupMenuItem
import com.fangyi.classpp.ui.components.SelectCard
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetTextField
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.components.rememberHazeRetreat
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.SettingsCardShape
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.classppColors
import dev.chrisbanes.haze.rememberHazeState

/**
 * 「配置模型」浮层（AI 配置方案浮层 → 模型行 /「添加模型」打开）：
 *
 * - 新增（[editingModel] = null）：左上「取消」、右上「确认」；
 * - 编辑（[editingModel] 非空）：左上「删除」、右上「确认」，与课程编辑浮层同一套顶栏实现；
 * - 模型 ID 用 [SheetTextField] 编辑，确认时 trim 后做非空与忽略大小写查重（排除自身）；
 * - 推理等级沿用改造前模型列表的结构：[SelectCard] 点选当前等级、长按行呼出删除菜单、
 *   末尾「添加推理等级」走底部输入卡；等级可删到空，空列表 = 该模型不使用推理等级，
 *   列表非空时选中项自动回落首个；上方另有一行常用等级快捷预设（[ReasoningLevelPresets]），
 *   点按即加入并选中，已在列表中的预设不重复出现；
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
        hazeRetreat.forRewrite {
            levels = levels + trimmed
            // 首个等级自动选中（同旧模型列表：当前为空时新加的项成为当前）
            if (selectedLevel.isBlank()) selectedLevel = trimmed
        }
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

    /** 快捷预设点按：经同帧退让把等级加入列表并选中（已在列表中的只选中，防同帧重复点） */
    fun addPreset(preset: String) {
        hazeRetreat.forRewrite {
            val existing = levels.firstOrNull { it.equals(preset, ignoreCase = true) }
            if (existing == null) levels = levels + preset
            selectedLevel = existing ?: preset
        }
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

            // B. 推理等级：点选当前等级，长按行删除，末尾添加走底部输入卡
            CardSection(title = stringResource(R.string.ai_model_reasoning_section)) {
                if (levels.isNotEmpty()) {
                    val selectedIndex = levels.indexOfFirst {
                        it.equals(selectedLevel, ignoreCase = true)
                    }
                    SelectCard(
                        options = levels,
                        selectedIndices = if (selectedIndex >= 0) {
                            setOf(selectedIndex)
                        } else {
                            emptySet()
                        },
                        onSelectionChange = { picked ->
                            picked.firstOrNull()?.let { index ->
                                levels.getOrNull(index)?.let { selectedLevel = it }
                            }
                        },
                        // 等级是可选项：只剩一个也允许删除（空列表 = 不使用推理等级）
                        longPressMenuItems = { index ->
                            val level = levels.getOrNull(index)
                            if (level == null) {
                                emptyList()
                            } else {
                                listOf(
                                    PopupMenuItem(
                                        label = deleteLabel,
                                        icon = R.drawable.ic_delete,
                                        destructive = true,
                                        onClick = { deleteLevel(level) },
                                    ),
                                )
                            }
                        },
                        menuHazeState = cardHazeState,
                    )
                }
                // 快捷预设：只列尚未在等级列表里的常用值（已在列表中的由上方 SelectCard
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
 * 预设不出现（由上方 [SelectCard] 呈现选中态），全部已添加时宿主直接隐藏整行。
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
