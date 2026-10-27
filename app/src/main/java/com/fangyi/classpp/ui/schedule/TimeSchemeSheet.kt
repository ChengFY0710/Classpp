package com.fangyi.classpp.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ScheduleValidator
import com.fangyi.classpp.data.model.DEFAULT_SLOTS
import com.fangyi.classpp.data.model.DEFAULT_SLOT_DURATION_MINUTES
import com.fangyi.classpp.data.model.DEFAULT_TIME_SCHEME_ID
import com.fangyi.classpp.data.model.MAX_SLOT_DURATION
import com.fangyi.classpp.data.model.MIN_SLOT_DURATION
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.TimeScheme
import com.fangyi.classpp.data.model.TimeSlotDef
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.data.model.slotsWithAdjustedStart
import com.fangyi.classpp.data.model.slotsWithCount
import com.fangyi.classpp.ui.components.BottomNumberSelectCard
import com.fangyi.classpp.ui.components.BottomTextFieldCard
import com.fangyi.classpp.ui.components.BottomTimeSelectCard
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SelectCard
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.SettingsCardShape
import com.fangyi.classpp.ui.theme.SheetFieldHeight
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.tabularNumbers
import dev.chrisbanes.haze.rememberHazeState

/**
 * 时间行 chip 容器高度 = 标签行高（bodyLarge lineHeight 24dp）：
 * 行高与两行间距按标签节奏计算，32dp 的 chip 蓝底保持原高、不计入行高。
 */
private val ChipRowHeight = 24.dp

private val ChipShape = RoundedCornerShape(8.dp)

/**
 * 「课程时间方案」浮层（课表设置 → 节数与时间入口打开）——原内联的节次与时间模块整体迁入：
 *
 * - 顶部方案选择（[SelectCard]）：「默认」+ 已保存方案 + 「自定义」；选中预设即把该方案
 *   整套填入草稿；明细区始终可点，任何**实际改动**都会自动切回「自定义」，避免预设方案
 *   被静默改写（只是打开选择器又确认同值不算改动，仍留在原方案）；
 * - 下方同一个 [CardSection]（节次与时间）依次是：总节数 / 每节课时间、逐节起止时间、
 *   保存方案、合并两小节为一大节（占位）；
 * - 所有明细修改只落在**本地草稿**，右上「确认」才经 [onApply] 整套提交；取消/返回/
 *   下拉关闭丢弃草稿。保存方案是独立动作：经 [onSaveScheme] 落库，成功后经仓库
 *   timeSchemes 流回到顶部列表，取消浮层也不撤销（保存即持久化）。
 *
 * 容器为 [OverlaySheet]，与新建课表浮层同一套顶栏排布（取消在左、确认在右）与拖拽关闭；
 * 底部滚轮/名称输入均走 Bottom*Card 一族，两段式挂载（先播退场再卸载），在场时经
 * covered 让本浮层缩小后退。
 */
@Composable
internal fun TimeSchemeSheet(
    schedule: Schedule,
    timeSchemes: List<TimeScheme>,
    visible: Boolean,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    onApply: (slots: List<TimeSlotDef>, durationMinutes: Int) -> Unit,
    onSaveScheme: (name: String, slots: List<TimeSlotDef>, durationMinutes: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // 内置「默认」方案不落库，按默认作息在 UI 侧构造；名称随语言切换
    val defaultScheme = TimeScheme(
        id = DEFAULT_TIME_SCHEME_ID,
        name = stringResource(R.string.time_scheme_default),
        slots = DEFAULT_SLOTS,
        slotDurationMinutes = DEFAULT_SLOT_DURATION_MINUTES,
    )
    val customLabel = stringResource(R.string.time_scheme_custom)
    val allSchemes = listOf(defaultScheme) + timeSchemes

    // 草稿随浮层卸载复位：每次打开都从当前课表配置出发
    var draftSlots by remember { mutableStateOf(schedule.slots) }
    var draftDuration by remember { mutableIntStateOf(schedule.slotDurationMinutes) }
    // 选中方案 id；null = 自定义。打开时按当前配置反查，未命中任何方案即落在「自定义」。
    // 明细区始终可编辑：预设方案被实际改动时自动切回「自定义」（见 markCustom）
    var selectedSchemeId by remember {
        mutableStateOf(
            allSchemes.firstOrNull {
                it.slots == schedule.slots &&
                    it.slotDurationMinutes == schedule.slotDurationMinutes
            }?.id,
        )
    }
    val isCustom = selectedSchemeId == null
    val selectedIndex = allSchemes.indexOfFirst { it.id == selectedSchemeId }
        .takeIf { it >= 0 } ?: allSchemes.size

    // 底部滚轮/输入弹窗：期望态 + 挂载态两段式（同课表设置浮层）
    var slotCountVisible by remember { mutableStateOf(false) }
    var slotCountMounted by remember { mutableStateOf(false) }
    var slotCountInitial by remember { mutableIntStateOf(1) }

    var durationVisible by remember { mutableStateOf(false) }
    var durationMounted by remember { mutableStateOf(false) }
    var durationInitial by remember { mutableIntStateOf(DEFAULT_SLOT_DURATION_MINUTES) }

    var timeVisible by remember { mutableStateOf(false) }
    var timeMounted by remember { mutableStateOf(false) }
    var timeInitial by remember { mutableIntStateOf(0) }
    var timeTitle by remember { mutableStateOf("") }
    var timeCallback by remember { mutableStateOf<((Int) -> Unit)?>(null) }

    var nameVisible by remember { mutableStateOf(false) }
    var nameMounted by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }

    val covered = slotCountVisible || durationVisible || timeVisible || nameVisible
    val sheetHazeState = rememberHazeState()

    BackHandler(enabled = true) { onDismiss() }

    // 时间胶囊点按上报：初值快照 + 标题，确认的分钟数经 onPicked 回到调用处折算
    fun requestTimePicker(initialMinute: Int, title: String, onPicked: (Int) -> Unit) {
        timeInitial = initialMinute
        timeTitle = title
        timeCallback = onPicked
        timeMounted = true
        timeVisible = true
    }

    // 明细发生实际改动 → 自动切到「自定义」：预设方案只当模板，不能就地改写。
    // 仅在值确实变化时调用（确认同值不切换，仍留在原方案）
    fun markCustom() {
        selectedSchemeId = null
    }

    // 保存按钮在「自定义」下始终可点：即便没改任何值，也允许把当前配置存成新方案；
    // 预设方案只当模板（若要保存预设，先改一项即自动切到自定义）
    val saveEnabled = isCustom

    // 同一屏内的校验提示文案：先取好再进点击闭包
    val slotOverflowToast = stringResource(R.string.slot_overflow_desc)
    val dayOverflowToast = stringResource(R.string.error_slot_day_overflow)
    val orderToast = stringResource(R.string.error_slot_order)

    OverlaySheet(
        title = stringResource(R.string.time_scheme_title),
        confirmLabel = stringResource(R.string.edit_confirm),
        // 确认在右、取消在左（同新建课表浮层）
        confirmAtEnd = true,
        onConfirm = { onApply(draftSlots, draftDuration) },
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
        // 内容列没有输入框：键盘只服务底部名称输入卡，让位由 BottomTextFieldCard 自理
        imeBehavior = SheetImeBehavior.IgnoreIme,
        hazeState = sheetHazeState,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            // A. 方案选择：选中即把整套作息填入草稿；明细改动会自动切回「自定义」
            CardSection {
                SelectCard(
                    options = allSchemes.map { it.name } + customLabel,
                    selectedIndices = setOf(selectedIndex),
                    onSelectionChange = { picked ->
                        val index = picked.firstOrNull() ?: return@SelectCard
                        if (index == allSchemes.size) {
                            // 自定义：保留当前草稿，只解锁编辑
                            selectedSchemeId = null
                        } else {
                            val scheme = allSchemes[index]
                            selectedSchemeId = scheme.id
                            draftSlots = scheme.slots
                            draftDuration = scheme.slotDurationMinutes
                        }
                    },
                )
            }

            // BCD. 明细设置：基础项 + 逐节时间 + 两个底部操作，同属一个分组。
            // 明细始终可点：预设方案下改动任一项即自动切回「自定义」
            CardSection(title = stringResource(R.string.section_slots_time)) {
                SettingsCard(
                    items = listOf(
                        SettingsCardItem.Nav(
                            label = stringResource(R.string.slot_count_label),
                            value = draftSlots.size.toString(),
                            onClick = {
                                slotCountInitial = draftSlots.size
                                slotCountMounted = true
                                slotCountVisible = true
                            },
                        ),
                        SettingsCardItem.Nav(
                            label = stringResource(R.string.slot_duration_label),
                            description = stringResource(R.string.slot_duration_desc),
                            value = stringResource(
                                R.string.slot_duration_value,
                                draftDuration,
                            ),
                            onClick = {
                                durationInitial = draftDuration
                                durationMounted = true
                                durationVisible = true
                            },
                        ),
                    ),
                )

                // 逐节起止时间：行距与胶囊规格沿用原设置卡（行高下限 38dp、卡内上下各 8dp）
                SettingsCard(
                    rowMinHeight = 38.dp,
                    contentVerticalPadding = 8.dp,
                    items = draftSlots.mapIndexed { index, slot ->
                        SettingsCardItem.Custom(
                            label = stringResource(R.string.slot_format, index + 1),
                            trailing = {
                                val slotTitle = stringResource(R.string.slot_format, index + 1)
                                OverflowHeightBox(ChipRowHeight) {
                                    // 开始时间：不早于上一节结束；结束 = 开始 + 每节课时长，
                                    // 与下一节冲突则后续节级联顺延
                                    TimeChip(
                                        text = TimeText.formatDisplay(slot.startTime),
                                        onClick = {
                                            requestTimePicker(
                                                TimeText.parseMinutes(slot.startTime) ?: 8 * 60,
                                                slotTitle,
                                            ) { picked ->
                                                val prevEnd = draftSlots.getOrNull(index - 1)
                                                    ?.let { TimeText.parseMinutes(it.endTime) }
                                                if (prevEnd != null && picked < prevEnd) {
                                                    AppToasts.show(context, orderToast)
                                                } else {
                                                    val adjusted = slotsWithAdjustedStart(
                                                        draftSlots,
                                                        index,
                                                        picked,
                                                        draftDuration,
                                                    )
                                                    if (adjusted != null) {
                                                        // 确认同值不算改动，仍留在原方案
                                                        if (adjusted != draftSlots) {
                                                            draftSlots = adjusted
                                                            markCustom()
                                                        }
                                                    } else {
                                                        AppToasts.show(context, dayOverflowToast)
                                                    }
                                                }
                                            }
                                        },
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    // 结束时间：只改本节，但须晚于本节开始、不晚于下一节开始
                                    TimeChip(
                                        text = TimeText.formatDisplay(slot.endTime),
                                        onClick = {
                                            requestTimePicker(
                                                TimeText.parseMinutes(slot.endTime) ?: 8 * 60,
                                                slotTitle,
                                            ) { picked ->
                                                val startMinute = TimeText.parseMinutes(slot.startTime)
                                                val nextStart = draftSlots.getOrNull(index + 1)
                                                    ?.let { TimeText.parseMinutes(it.startTime) }
                                                val invalid = (startMinute != null && picked <= startMinute) ||
                                                    (nextStart != null && picked > nextStart)
                                                if (invalid) {
                                                    AppToasts.show(context, orderToast)
                                                } else {
                                                    val updated = draftSlots.mapIndexed { i, s ->
                                                        if (i == index) {
                                                            s.copy(endTime = TimeText.format(picked))
                                                        } else {
                                                            s
                                                        }
                                                    }
                                                    // 确认同值不算改动，仍留在原方案
                                                    if (updated != draftSlots) {
                                                        draftSlots = updated
                                                        markCustom()
                                                    }
                                                }
                                            }
                                        },
                                    )
                                }
                            },
                        )
                    },
                )

                // 保存方案：自定义状态下始终可点；点击唤起底部名称输入
                SchemeActionRow(
                    label = stringResource(R.string.time_scheme_save),
                    icon = R.drawable.ic_add_circle_regular,
                    enabled = saveEnabled,
                    onClick = {
                        nameDraft = ""
                        nameMounted = true
                        nameVisible = true
                    },
                )

                // 合并两小节为一大节：本期占位，点击无动作（保留按压反馈）
                SchemeActionRow(
                    label = stringResource(R.string.time_scheme_merge),
                    icon = null,
                    enabled = true,
                    onClick = {},
                )
            }
        }
    }

    // 总节数滚轮：小于现有 → 前缀裁剪；大于现有 → 按每节课时长追加（课间 30 分钟）；
    // 当天放不下（slotsWithCount 返回 null）时 toast 并保持原草稿
    if (slotCountMounted) {
        BottomNumberSelectCard(
            visible = slotCountVisible,
            onDismiss = { slotCountVisible = false },
            onDismissed = { slotCountMounted = false },
            title = stringResource(R.string.slot_count_select_title),
            valueRange = ScheduleValidator.MIN_SLOTS..ScheduleValidator.MAX_SLOTS,
            initialValue = slotCountInitial,
            unit = stringResource(R.string.unit_slot),
            hazeState = sheetHazeState,
            onConfirm = { count ->
                slotCountVisible = false
                val adjusted = slotsWithCount(draftSlots, count, draftDuration)
                if (adjusted != null) {
                    // 确认原节数不算改动，仍留在原方案
                    if (adjusted != draftSlots) {
                        draftSlots = adjusted
                        markCustom()
                    }
                } else {
                    AppToasts.show(context, slotOverflowToast)
                }
            },
        )
    }

    // 每节课时间滚轮：只改推算依据，不改写现有各节时间（与课表设置原行为一致）
    if (durationMounted) {
        BottomNumberSelectCard(
            visible = durationVisible,
            onDismiss = { durationVisible = false },
            onDismissed = { durationMounted = false },
            title = stringResource(R.string.slot_duration_select_title),
            valueRange = MIN_SLOT_DURATION..MAX_SLOT_DURATION,
            initialValue = durationInitial,
            unit = stringResource(R.string.unit_min),
            hazeState = sheetHazeState,
            onConfirm = { minutes ->
                durationVisible = false
                // 确认原时长不算改动，仍留在原方案
                if (minutes != draftDuration) {
                    draftDuration = minutes
                    markCustom()
                }
            },
        )
    }

    // 节次起止时刻滚轮：确认的分钟数经 onPicked 交回对应行
    if (timeMounted) {
        BottomTimeSelectCard(
            visible = timeVisible,
            onDismiss = { timeVisible = false },
            onDismissed = {
                timeMounted = false
                timeCallback = null
            },
            title = timeTitle,
            initialHour = timeInitial / 60,
            initialMinute = timeInitial % 60,
            hazeState = sheetHazeState,
            onConfirm = { hour, minute ->
                timeVisible = false
                timeCallback?.invoke(hour * 60 + minute)
                timeCallback = null
            },
        )
    }

    // 方案名称输入：空名禁用确认；确认即保存当前草稿（不动课表），新方案经流回到顶部列表
    if (nameMounted) {
        BottomTextFieldCard(
            visible = nameVisible,
            onDismiss = { nameVisible = false },
            onDismissed = { nameMounted = false },
            title = stringResource(R.string.time_scheme_name_title),
            value = nameDraft,
            onValueChange = { nameDraft = it },
            placeholder = stringResource(R.string.time_scheme_name_hint),
            confirmEnabled = nameDraft.isNotBlank(),
            hazeState = sheetHazeState,
            onConfirm = {
                nameVisible = false
                onSaveScheme(nameDraft.trim(), draftSlots, draftDuration)
            },
        )
    }
}

/**
 * 底部操作行卡（保存方案 / 合并两小节）：整宽白卡、内容居中，复刻待办详情「添加步骤」
 * 的行卡风格——主色图标（可选）+ 主色文字；[enabled] = false 时整体灰字、无按压反馈。
 */
@Composable
private fun SchemeActionRow(
    label: String,
    icon: Int?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.classppColors.secondaryText
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SheetFieldHeight)
            .clip(SettingsCardShape)
            .background(MaterialTheme.colorScheme.surface)
            .then(if (enabled) Modifier.pressClickable(SettingsCardShape, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.classppTextStyles.fieldValue,
            color = contentColor,
        )
    }
}

/**
 * 时间胶囊：背景灰底、primary 字，点击弹时间选择（节次行尾部控件）。
 * 文字启用等宽数字（tnum）：所有时间同为 00:00 五字符，数字位等宽后各胶囊文字宽度天然一致。
 */
@Composable
private fun TimeChip(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        modifier = modifier
            // 按压反馈放在 clip 之前：缩放/提亮作用于整枚 chip，圆角裁剪在其内侧
            .pressClickable(ChipShape, onClick = onClick)
            .clip(ChipShape)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        style = MaterialTheme.typography.bodyMedium.tabularNumbers(),
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * chip 容器：整体只占 [height] 参与父行行高与行间距；子项放开高度约束按实际尺寸
 * 测量、垂直居中向上下溢出——蓝底保持原高不被压扁，其高度不影响两行间距。
 * （固定高度的 Row/Box 会把约束收紧到容器高、子项被压扁，故用自定义测量。）
 */
@Composable
private fun OverflowHeightBox(height: Dp, content: @Composable () -> Unit) {
    Layout(
        content = content,
        measurePolicy = { measurables, constraints ->
            // 放开高度约束测量子项（宽度约束保持原样），容器高度固定为 height
            val placeables = measurables.map {
                it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            }
            val containerHeight = height.roundToPx()
            layout(placeables.sumOf { it.width }, containerHeight) {
                var x = 0
                for (placeable in placeables) {
                    // 垂直居中：32dp 子项在 24dp 容器内上下各溢出 4dp（负值放置不裁剪）
                    placeable.place(x, (containerHeight - placeable.height) / 2)
                    x += placeable.width
                }
            }
        },
    )
}
