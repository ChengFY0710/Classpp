package com.fangyi.classpp.ui.note

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.REPEAT_END_COUNT_DEFAULT
import com.fangyi.classpp.data.model.RepeatEndType
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.RepeatUnit
import com.fangyi.classpp.data.model.markRange
import com.fangyi.classpp.data.model.occurrencesBetween
import com.fangyi.classpp.ui.components.BottomDateSelectCard
import com.fangyi.classpp.ui.components.BottomNumberSelectCard
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SelectCard
import com.fangyi.classpp.ui.components.SelectionGrid
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.components.labelText
import com.fangyi.classpp.ui.components.toCardText
import com.fangyi.classpp.ui.components.unitText
import com.fangyi.classpp.ui.motion.Expandable
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import dev.chrisbanes.haze.rememberHazeState
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * 重复浮层设置页：日期卡「重复」行的二级浮层（[OverlaySheet] 叠 [OverlaySheet]，分层
 * 模式同 [GroupPickSheet]——画在表单浮层之后、自建 haze 采样源、后注册 BackHandler）。
 *
 * 结构：类型选择卡（[SelectCard] 单选：无重复/每天/每周/每月/每年/自定义）→ 结束设置卡
 * （[SettingsCard]，与类型卡同分组：随任一重复档位经 [Expandable] 展开——「结束」选择行
 * 弹菜单切 永不/日期/次数，日期档卡内追加「结束日期」导航行唤起 [BottomDateSelectCard]，
 * 次数档追加「重复次数」导航行唤起 [BottomNumberSelectCard]，卡高变化经 animateContentSize
 * 过渡）→ 选「自定义」时经 [Expandable] 展开自定义区——「自定义重复」分组（[SettingsCard]：
 * 「频率」选择行弹行内菜单切天/周/月/年；「每」导航行经 [BottomNumberSelectCard] 选 1..99
 * 的间隔）+ 方格（[SelectionGrid]，多选）：周 = 一~日、月 = 1~31、年 = 1月~12月，天无方格。
 *
 * 草稿态语义：挂载即从 [initial] 快照，确认才经 [onConfirm] 回写（取消/遮罩/返回/下拉的
 * 改动不外泄，出场播完由宿主卸载）。切频率单位即以 [anchorDate]（自定义日期）为锚播种
 * 方格——周几/几号/几月跟随锚日期，方格选择从不带空入场；此后可自由增删。切结束档位即
 * 落默认值（日期 = 锚日 + 1 个月、次数 = 5），行上恒有具体值可看。确认校验两条：自定义
 * 且方格为空、日期档下结束日期早得连首期都展不开（重复不了）——Toast 提示、不关浮层。
 *
 * 数字/日期弹卡两段式挂载（visible/mounted，同宿主的滚轮弹窗）：声明在 [OverlaySheet]
 * 之前使 covered 生效，画在本浮层之后（组合顺序即绘制顺序），其 BackHandler 后注册、
 * 返回键先关弹卡。
 */
@Composable
internal fun RepeatSettingsSheet(
    visible: Boolean,
    initial: RepeatRule,
    anchorDate: IsoDate,
    onConfirm: (RepeatRule) -> Unit,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
) {
    // 草稿态：宿主两段式挂载，出场期间组合仍在——确认前的改动不外泄、取消即弃
    var draft by remember { mutableStateOf(initial) }

    // 间隔/结束日期/重复次数弹卡：期望态 + 挂载态两段式（同宿主的滚轮弹窗）
    var intervalVisible by remember { mutableStateOf(false) }
    var intervalMounted by remember { mutableStateOf(false) }
    var endDateVisible by remember { mutableStateOf(false) }
    var endDateMounted by remember { mutableStateOf(false) }
    var endCountVisible by remember { mutableStateOf(false) }
    var endCountMounted by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val marksRequiredMessage = stringResource(R.string.repeat_marks_required)
    val endInvalidMessage = stringResource(R.string.repeat_end_invalid)

    // haze 采样源自建（同 GroupPickSheet：不共享表单浮层的——下拖时露出的表单内容不该被
    // 模糊进本层顶栏），同时交给本浮层与间隔弹卡——顶栏磨砂与弹卡背景模糊同源
    val sheetHazeState = rememberHazeState()

    BackHandler(enabled = visible, onBack = onDismiss)

    OverlaySheet(
        title = stringResource(R.string.date_card_repeat),
        confirmLabel = stringResource(R.string.edit_confirm),
        // 确认在右、取消在左（同表单浮层的顶栏排布）
        confirmAtEnd = true,
        onConfirm = {
            val marksMissing =
                draft.type == RepeatType.Custom && draft.unit != RepeatUnit.Day && draft.marks.isEmpty()
            // 结束日期过早：日期档下连首期都展不进「锚日 ~ 结束日期」窗口（重复不了）；
            // 次数档恒有首期（方格空回退锚日属性），无需校验
            val endDate = draft.endDate
            val endTooEarly = draft.type != RepeatType.None &&
                draft.endType == RepeatEndType.Date &&
                endDate != null &&
                draft.occurrencesBetween(anchorDate, anchorDate, endDate).isEmpty()
            when {
                marksMissing -> AppToasts.show(context, marksRequiredMessage)
                endTooEarly -> AppToasts.show(context, endInvalidMessage)
                else -> {
                    onConfirm(draft)
                    onDismiss()
                }
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
        // 弹卡在场时本浮层缩小后退（用可见请求态，退场时与上层收场同帧反向恢复）
        covered = intervalVisible || endDateVisible || endCountVisible,
        onDismissed = onDismissed,
        // 无输入框（数字弹卡的键盘让位由弹卡框架自理），键盘不必让位
        imeBehavior = SheetImeBehavior.IgnoreIme,
        hazeState = sheetHazeState,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            // 类型选择卡：单选（SelectCard 单选语义：点击已选项不变，未选项替换旧选）
            CardSection {
                SelectCard(
                    options = RepeatType.entries.map { it.labelText() },
                    selectedIndices = setOf(RepeatType.entries.indexOf(draft.type)),
                    onSelectionChange = { picked ->
                        picked.firstOrNull()?.let { index ->
                            draft = draft.copy(type = RepeatType.entries[index])
                        }
                    },
                )
                // 结束设置卡：与类型选择卡同分组，随任一重复档位展开/收起（结束条件只随
                // 重复存在）。首行「结束」选择行弹菜单切档；日期/次数档在卡内追加导航行，
                // 卡高变化经 animateContentSize 过渡（SettingsCard 是整体自绘布局，
                // 单行无法用 Expandable窗帘式揭示，整卡尺寸动画即可）
                Expandable(expanded = draft.type != RepeatType.None) {
                    SettingsCard(
                        modifier = Modifier.animateContentSize(
                            animationSpec = tween(Motion.ExpandMillis, easing = Motion.Decelerate),
                        ),
                        // 选择行菜单叠在本浮层上，模糊本层内容（采样源同顶栏）
                        hazeState = sheetHazeState,
                        items = buildList {
                            add(
                                SettingsCardItem.Select(
                                    label = stringResource(R.string.repeat_end_title),
                                    value = draft.endType.labelText(),
                                    items = RepeatEndType.entries.map { it.ordinal to it.labelText() },
                                    selectedId = draft.endType.ordinal,
                                    onPick = { id ->
                                        // 首切日期/次数档即落默认值：行上恒有具体值可看（同设计稿）
                                        when (val picked = RepeatEndType.entries[id]) {
                                            RepeatEndType.Date -> draft = draft.copy(
                                                endType = picked,
                                                endDate = draft.endDate ?: anchorDate.plusMonths(1),
                                            )
                                            RepeatEndType.Count -> draft = draft.copy(
                                                endType = picked,
                                                endCount = draft.endCount ?: REPEAT_END_COUNT_DEFAULT,
                                            )
                                            RepeatEndType.Never -> draft = draft.copy(endType = picked)
                                        }
                                    },
                                ),
                            )
                            when (draft.endType) {
                                RepeatEndType.Date -> add(
                                    SettingsCardItem.Nav(
                                        label = stringResource(R.string.repeat_end_date_row),
                                        description = stringResource(R.string.repeat_end_date_desc),
                                        value = draft.endDate?.toCardText().orEmpty(),
                                        onClick = {
                                            endDateMounted = true
                                            endDateVisible = true
                                        },
                                    ),
                                )
                                RepeatEndType.Count -> add(
                                    SettingsCardItem.Nav(
                                        label = stringResource(R.string.repeat_end_count_row),
                                        description = stringResource(R.string.repeat_end_count_desc),
                                        value = stringResource(
                                            R.string.repeat_end_count_value,
                                            draft.endCount ?: REPEAT_END_COUNT_DEFAULT,
                                        ),
                                        onClick = {
                                            endCountMounted = true
                                            endCountVisible = true
                                        },
                                    ),
                                )
                                RepeatEndType.Never -> Unit
                            }
                        },
                    )
                }
            }
            // 自定义区随「自定义」档位展开/收起（高度动画，兄弟内容逐帧让位，同日期卡子行）
            Expandable(expanded = draft.type == RepeatType.Custom) {
                Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
                    CardSection(title = stringResource(R.string.repeat_custom_section)) {
                        SettingsCard(
                            // 选择行菜单叠在本浮层上，模糊本层内容（采样源同顶栏）
                            hazeState = sheetHazeState,
                            items = listOf(
                                SettingsCardItem.Select(
                                    label = stringResource(R.string.repeat_frequency),
                                    value = draft.unit.unitText(),
                                    items = RepeatUnit.entries.map { it.ordinal to it.unitText() },
                                    selectedId = draft.unit.ordinal,
                                    onPick = { id ->
                                        val unit = RepeatUnit.entries[id]
                                        // 切单位即以自定义日期为锚播种方格（周几/几号/几月），
                                        // 换了语义的旧标记不留存，方格选择从不带空入场
                                        draft = draft.copy(unit = unit, marks = unit.seedMarks(anchorDate))
                                    },
                                ),
                                SettingsCardItem.Nav(
                                    label = stringResource(R.string.repeat_every),
                                    description = stringResource(R.string.repeat_every_desc),
                                    value = draft.interval.toString(),
                                    onClick = {
                                        intervalMounted = true
                                        intervalVisible = true
                                    },
                                ),
                            ),
                        )
                    }
                    // 方格只随 周/月/年 出现（天无方格语义）；标记以 RepeatRule.marks 落库，
                    // 值域随单位（周几 1..7 / 几号 1..31 / 几月 1..12）
                    if (draft.unit != RepeatUnit.Day) {
                        CardSection {
                            SelectionGrid(
                                values = draft.unit.markRange.toList(),
                                labels = draft.unit.gridLabels(),
                                selected = draft.marks,
                                onToggle = { mark ->
                                    draft = if (mark in draft.marks) {
                                        draft.copy(marks = draft.marks - mark)
                                    } else {
                                        draft.copy(marks = draft.marks + mark)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // 弹卡画在本浮层之后（组合顺序即绘制顺序），叠在浮层之上；确认回写草稿，取消/遮罩/
    // 返回只请求关闭，播完退场才卸载（两段式，同宿主的滚轮弹窗）
    if (intervalMounted) {
        BottomNumberSelectCard(
            visible = intervalVisible,
            onDismiss = { intervalVisible = false },
            onDismissed = { intervalMounted = false },
            title = stringResource(R.string.repeat_interval_title),
            valueRange = REPEAT_INTERVAL_MIN..REPEAT_INTERVAL_MAX,
            initialValue = draft.interval,
            unit = draft.unit.unitText(),
            hazeState = sheetHazeState,
            onConfirm = { value ->
                draft = draft.copy(interval = value)
                intervalVisible = false
            },
        )
    }

    // 结束日期滚轮弹卡：确认回写草稿的结束日期（当日不含，展开核心按「该日期前停止」口径消费）
    if (endDateMounted) {
        BottomDateSelectCard(
            visible = endDateVisible,
            onDismiss = { endDateVisible = false },
            onDismissed = { endDateMounted = false },
            title = stringResource(R.string.repeat_end_date_row),
            initialDate = draft.endDate ?: anchorDate,
            hazeState = sheetHazeState,
            onConfirm = { picked ->
                draft = draft.copy(endDate = picked)
                endDateVisible = false
            },
        )
    }

    // 重复次数滚轮弹卡：系列总期数（1..99），确认回写草稿的重复次数
    if (endCountMounted) {
        BottomNumberSelectCard(
            visible = endCountVisible,
            onDismiss = { endCountVisible = false },
            onDismissed = { endCountMounted = false },
            title = stringResource(R.string.repeat_end_count_row),
            valueRange = REPEAT_END_COUNT_MIN..REPEAT_END_COUNT_MAX,
            initialValue = draft.endCount ?: REPEAT_END_COUNT_DEFAULT,
            unit = stringResource(R.string.repeat_unit_count),
            hazeState = sheetHazeState,
            onConfirm = { value ->
                draft = draft.copy(endCount = value)
                endCountVisible = false
            },
        )
    }
}

/** 重复间隔的可选范围（与仓库 normalize 的夹紧一致） */
private const val REPEAT_INTERVAL_MIN = 1
private const val REPEAT_INTERVAL_MAX = 99

/** 重复次数的可选范围（与仓库 normalize 的夹紧一致） */
private const val REPEAT_END_COUNT_MIN = 1
private const val REPEAT_END_COUNT_MAX = 99

/** 各单位的方格标签：周 = 一~日（weekdays_short 同课表列头）、月 = 数字、年 = 「N月」 */
@Composable
private fun RepeatUnit.gridLabels(): List<String> = when (this) {
    RepeatUnit.Day -> emptyList()
    RepeatUnit.Week -> stringArrayResource(R.array.weekdays_short).toList()
    RepeatUnit.Month -> markRange.map { it.toString() }
    RepeatUnit.Year -> markRange.map { stringResource(R.string.repeat_month_mark, it) }
}

/** 以锚日期（自定义日期）为某单位播种方格标记：周几/几号/几月；天无方格语义 */
private fun RepeatUnit.seedMarks(anchorDate: IsoDate): Set<Int> = when (this) {
    RepeatUnit.Day -> emptySet()
    RepeatUnit.Week -> setOf(anchorDate.isoWeekday())
    RepeatUnit.Month -> setOf(anchorDate.dayOfMonth())
    RepeatUnit.Year -> setOf(anchorDate.monthOfYear())
}

private const val MILLIS_PER_DAY = 86_400_000L

private fun IsoDate.calendarField(field: Int): Int =
    GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = epochDay * MILLIS_PER_DAY
    }.get(field)

/** ISO 星期几：周一 = 1 … 周日 = 7（方格标记的周语义） */
private fun IsoDate.isoWeekday(): Int = ((calendarField(GregorianCalendar.DAY_OF_WEEK) + 5) % 7) + 1

private fun IsoDate.dayOfMonth(): Int = calendarField(GregorianCalendar.DAY_OF_MONTH)

private fun IsoDate.monthOfYear(): Int = calendarField(GregorianCalendar.MONTH) + 1

/** 月份步进（月末收拢：1/31 + 1 月 = 2/28，与日历直觉一致）——结束日期档的缺省值用 */
private fun IsoDate.plusMonths(months: Int): IsoDate =
    GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = epochDay * MILLIS_PER_DAY
        add(GregorianCalendar.MONTH, months)
    }.let { calendar -> IsoDate(Math.floorDiv(calendar.timeInMillis, MILLIS_PER_DAY)) }

/** 自定义重复 + 结束日期快速预览（Android Studio 中直接查看） */
@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390, heightDp = 900)
@Composable
private fun RepeatSettingsSheetPreview() = ClassppTheme {
    RepeatSettingsSheet(
        visible = true,
        initial = RepeatRule(
            type = RepeatType.Custom,
            unit = RepeatUnit.Month,
            interval = 3,
            marks = setOf(1, 2, 3, 9, 10, 11, 14),
            endType = RepeatEndType.Date,
            endDate = IsoDate.today() + 30,
        ),
        anchorDate = IsoDate.today(),
        onConfirm = {},
        onDismiss = {},
        onDismissed = {},
    )
}
