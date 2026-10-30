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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ScheduleValidator
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.DEFAULT_SLOTS
import com.fangyi.classpp.data.model.DEFAULT_SLOT_DURATION_MINUTES
import com.fangyi.classpp.data.model.DEFAULT_TIME_SCHEME_ID
import com.fangyi.classpp.data.model.MAX_SLOT_DURATION
import com.fangyi.classpp.data.model.MIN_SLOT_DURATION
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.TimeScheme
import com.fangyi.classpp.data.model.TimeSlotDef
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.data.model.previewSlotMerge
import com.fangyi.classpp.data.model.slotsWithAdjustedStart
import com.fangyi.classpp.data.model.slotsWithCount
import com.fangyi.classpp.ui.components.BottomNumberSelectCard
import com.fangyi.classpp.ui.components.BottomTextFieldCard
import com.fangyi.classpp.ui.components.BottomTimeSelectCard
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.PopupMenuCard
import com.fangyi.classpp.ui.components.PopupMenuItem
import com.fangyi.classpp.ui.components.SelectCard
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.RowCardPaddingHorizontal
import com.fangyi.classpp.ui.theme.RowCardPaddingVertical
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

/** 一次保存方案的挂起信息：保存前的方案 id 集合 + 发起时的草稿，回流后据此判断并选中新增方案 */
private data class PendingSchemeSave(
    val knownIds: Set<String>,
    val slots: List<TimeSlotDef>,
    val durationMinutes: Int,
)

/**
 * 「课程时间方案」浮层（课表设置 → 节数与时间入口打开）——原内联的节次与时间模块整体迁入：
 *
 * - 顶部方案选择（[SelectCard]）：「默认」+ 已保存方案 + 「自定义」；选中预设即把该方案
 *   整套填入草稿；明细区始终可点，任何**实际改动**都会自动切回「自定义」，避免预设方案
 *   被静默改写（只是打开选择器又确认同值不算改动，仍留在原方案）；「自定义」有独立的
 *   草稿——切去预设方案再切回来，此前调好的参数原样恢复；
 * - 长按**用户保存的方案**行呼出菜单（[PopupMenuCard] 卡体，删除 / 重命名；内置「默认」与
 *   「自定义」无菜单）：重命名走底部名称输入卡，确认即落库；删除即时生效，删的是当前选中
 *   方案时落回「自定义」并保留当前草稿；
 * - 下方同一个 [CardSection]（节次与时间）依次是：总节数 / 每节课时间、逐节起止时间、
 *   保存方案、合并两小节为一大节；
 * - 合并只改本地草稿（节表 + 课程映射），右上「确认」才随整套配置落库。可点条件由
 *   [previewSlotMerge] 判定：所有课程 span ≥ 2、每门课起止都能精确对齐、且至少有一对
 *   可合并；否则整行置灰（negative 色）不可点。合并输入是 [courses]（编辑态取课程
 *   草稿），因此网格与后续保存编辑都能拿到重映射后的课程；
 * - 所有明细修改只落在**本地草稿**，右上「确认」才经 [onApply] 整套提交；取消/返回/
 *   下拉关闭丢弃草稿。保存方案是独立动作：经 [onSaveScheme] 落库，成功后经仓库
 *   timeSchemes 流回到顶部列表并自动选中刚保存的方案——方案库不参与课表编辑事务，
 *   取消浮层、取消课表编辑都不撤销（保存即持久化）。
 *
 * 容器为 [OverlaySheet]，与新建课表浮层同一套顶栏排布（取消在左、确认在右）与拖拽关闭；
 * 底部滚轮/名称输入均走 Bottom*Card 一族，两段式挂载（先播退场再卸载），在场时经
 * covered 让本浮层缩小后退。
 */
@Composable
internal fun TimeSchemeSheet(
    schedule: Schedule,
    // 合并输入与课程草稿初值：课表编辑态传编辑草稿，非编辑态传仓库课程
    courses: List<CourseEntry>,
    timeSchemes: List<TimeScheme>,
    visible: Boolean,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    // mergedCourses 非空 = 本次草稿改写过课程映射（合并），需与节表同批落库
    onApply: (
        slots: List<TimeSlotDef>,
        durationMinutes: Int,
        mergedCourses: List<CourseEntry>?,
    ) -> Unit,
    onSaveScheme: (name: String, slots: List<TimeSlotDef>, durationMinutes: Int) -> Unit,
    onRenameScheme: (id: String, name: String) -> Unit,
    onDeleteScheme: (id: String) -> Unit,
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
    // 长按菜单文案：菜单项在长按那一刻构造，先取好供闭包使用
    val renameSchemeLabel = stringResource(R.string.edit_rename)
    val deleteSchemeLabel = stringResource(R.string.edit_delete)
    val allSchemes = listOf(defaultScheme) + timeSchemes

    // 草稿随浮层卸载复位：每次打开都从当前课表配置出发
    var draftSlots by remember { mutableStateOf(schedule.slots) }
    var draftDuration by remember { mutableIntStateOf(schedule.slotDurationMinutes) }
    // 课程映射草稿：只有「合并」会改写；与入参相同表示本次没有课程变更
    var draftCourses by remember { mutableStateOf(courses) }
    // 「自定义」名下保存的草稿：切去预设方案只是换掉当前显示，切回「自定义」时恢复这组值
    var customSlots by remember { mutableStateOf(schedule.slots) }
    var customDuration by remember { mutableIntStateOf(schedule.slotDurationMinutes) }
    var customCourses by remember { mutableStateOf(courses) }
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
    // 名称输入卡的目标：null = 保存新方案；非空 = 重命名该 id 的方案（标题随之切换）
    var nameTargetId by remember { mutableStateOf<String?>(null) }

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
    // 改动同时写进「自定义」草稿，之后切去别的项再切回来仍是最新参数。
    // 仅在值确实变化时调用（确认同值不切换，仍留在原方案）
    fun markCustom() {
        selectedSchemeId = null
        customSlots = draftSlots
        customDuration = draftDuration
        customCourses = draftCourses
    }

    // 保存按钮在「自定义」下始终可点：即便没改任何值，也允许把当前配置存成新方案；
    // 预设方案只当模板（若要保存预设，先改一项即自动切到自定义）
    val saveEnabled = isCustom

    // 合并预览与门控：固定两两配对 + 30 分钟间隙；所有课程 span≥2、能精确对齐且
    // 至少有一对可合并时按钮才可点。纯计算，草稿每次变化重算一次（课表规模很小）
    val mergePlan = remember(draftSlots, draftCourses, schedule.daysPerWeek) {
        previewSlotMerge(draftSlots, draftCourses, schedule.daysPerWeek)
    }

    // 同一屏内的校验提示文案：先取好再进点击闭包
    val slotOverflowToast = stringResource(R.string.slot_overflow_desc)
    val dayOverflowToast = stringResource(R.string.error_slot_day_overflow)
    val orderToast = stringResource(R.string.error_slot_order)

    // 保存成功 → 顶部自动切到刚保存的方案：保存时先记下既有方案 id 与发起时的草稿，
    // 仓库回流后取新增项；若回流前草稿又被改过/切去了别的项，则尊重用户当前操作不抢选
    var pendingSchemeSave by remember { mutableStateOf<PendingSchemeSave?>(null) }
    LaunchedEffect(timeSchemes) {
        val pending = pendingSchemeSave ?: return@LaunchedEffect
        val saved = timeSchemes.firstOrNull { it.id !in pending.knownIds } ?: return@LaunchedEffect
        pendingSchemeSave = null
        if (draftSlots == pending.slots && draftDuration == pending.durationMinutes) {
            // 与点选方案同语义：选中即整套填入草稿（保存值本就等于当前草稿）
            selectedSchemeId = saved.id
            draftSlots = saved.slots
            draftDuration = saved.slotDurationMinutes
        }
    }

    OverlaySheet(
        title = stringResource(R.string.time_scheme_title),
        confirmLabel = stringResource(R.string.edit_confirm),
        // 确认在右、取消在左（同新建课表浮层）
        confirmAtEnd = true,
        onConfirm = {
            onApply(
                draftSlots,
                draftDuration,
                // 与入参一致 = 本次没合并过课程，确认时不碰课程
                draftCourses.takeIf { it != courses },
            )
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
                            // 自定义：恢复「自定义」草稿并解锁编辑（此前切走的参数不丢）
                            selectedSchemeId = null
                            draftSlots = customSlots
                            draftDuration = customDuration
                            draftCourses = customCourses
                        } else {
                            val scheme = allSchemes[index]
                            selectedSchemeId = scheme.id
                            draftSlots = scheme.slots
                            draftDuration = scheme.slotDurationMinutes
                            // 预设方案只含节表：此前的合并映射对它无效，回落到入参课程
                            draftCourses = courses
                        }
                    },
                    // 长按用户保存的方案才出菜单；内置「默认」（index 0）与「自定义」（末尾）无菜单
                    longPressMenuItems = { index ->
                        val scheme = allSchemes.getOrNull(index)
                            ?.takeIf { it.id != DEFAULT_TIME_SCHEME_ID }
                        if (scheme == null) {
                            emptyList()
                        } else {
                            listOf(
                                PopupMenuItem(
                                    label = renameSchemeLabel,
                                    icon = R.drawable.ic_rename,
                                    onClick = {
                                        nameTargetId = scheme.id
                                        nameDraft = scheme.name
                                        nameMounted = true
                                        nameVisible = true
                                    },
                                ),
                                PopupMenuItem(
                                    label = deleteSchemeLabel,
                                    icon = R.drawable.ic_delete,
                                    onClick = {
                                        onDeleteScheme(scheme.id)
                                        // 删的是当前选中方案 → 落回「自定义」，当前草稿留作
                                        // 自定义草稿（避免选中 id 悬空、保存按钮状态错位）
                                        if (selectedSchemeId == scheme.id) markCustom()
                                    },
                                ),
                            )
                        }
                    },
                    menuHazeState = sheetHazeState,
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
                        nameTargetId = null
                        nameDraft = ""
                        nameMounted = true
                        nameVisible = true
                    },
                )

                // 合并两小节为一大节：只改草稿，确认才随整套配置落库；
                // 课程 span<2 或无法精确对齐时整体置灰（negative 色）不可点
                SchemeActionRow(
                    label = stringResource(R.string.time_scheme_merge),
                    icon = null,
                    enabled = mergePlan.canMerge,
                    onClick = {
                        draftSlots = mergePlan.newSlots
                        draftDuration = mergePlan.newDurationMinutes
                        draftCourses = mergePlan.newCourses
                        markCustom()
                        AppToasts.show(
                            context,
                            context.getString(
                                R.string.time_scheme_merge_done,
                                mergePlan.mergedPairCount,
                            ),
                        )
                    },
                )
            }
        }
    }

    // 总节数滚轮：小于现有 → 前缀裁剪；大于现有 → 先按课间 30 分钟末尾追加，放不下时把
    // 新增节塞进当天剩余时间，仍不够则全天按每节课时长重排（见 slotsWithCount）；
    // 只有全天都排不下（返回 null）才 toast 并保持原草稿
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

    // 方案名称输入（保存 / 重命名共用同一张卡）：空名禁用确认。
    // 保存 = 当前草稿存成新方案（不动课表）；重命名 = 改目标方案的名字，均即时落库
    if (nameMounted) {
        val renameTargetId = nameTargetId
        BottomTextFieldCard(
            visible = nameVisible,
            onDismiss = { nameVisible = false },
            onDismissed = { nameMounted = false },
            title = stringResource(
                if (renameTargetId != null) {
                    R.string.time_scheme_rename_title
                } else {
                    R.string.time_scheme_name_title
                },
            ),
            value = nameDraft,
            onValueChange = { nameDraft = it },
            placeholder = stringResource(R.string.time_scheme_name_hint),
            confirmEnabled = nameDraft.isNotBlank(),
            hazeState = sheetHazeState,
            onConfirm = {
                nameVisible = false
                if (renameTargetId != null) {
                    onRenameScheme(renameTargetId, nameDraft.trim())
                } else {
                    // 记下保存前的方案 id 与草稿，仓库回流新增项后自动选中它
                    pendingSchemeSave = PendingSchemeSave(
                        knownIds = timeSchemes.map { it.id }.toSet(),
                        slots = draftSlots,
                        durationMinutes = draftDuration,
                    )
                    onSaveScheme(nameDraft.trim(), draftSlots, draftDuration)
                }
            },
        )
    }
}

/**
 * 底部操作行卡（保存方案 / 合并两小节）：整宽白卡、内容居中，复刻待办详情「添加步骤」
 * 的行卡风格——主色图标（可选）+ 主色文字；[enabled] = false 时文字与图标统一用
 * theme 的 negative 置灰色、无按压反馈。
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
        MaterialTheme.classppColors.negative
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SheetFieldHeight)
            .clip(SettingsCardShape)
            .background(MaterialTheme.colorScheme.surface)
            .then(if (enabled) Modifier.pressClickable(SettingsCardShape, onClick = onClick) else Modifier)
            .padding(horizontal = RowCardPaddingHorizontal, vertical = RowCardPaddingVertical),
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
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium).tabularNumbers(),
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
