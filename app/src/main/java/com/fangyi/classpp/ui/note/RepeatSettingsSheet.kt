package com.fangyi.classpp.ui.note

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.RepeatUnit
import com.fangyi.classpp.data.model.markRange
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
import com.fangyi.classpp.ui.components.unitText
import com.fangyi.classpp.ui.motion.Expandable
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import dev.chrisbanes.haze.rememberHazeState
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * 重复浮层设置页：日期卡「重复」行的二级浮层（[OverlaySheet] 叠 [OverlaySheet]，分层
 * 模式同 [GroupPickSheet]——画在表单浮层之后、自建 haze 采样源、后注册 BackHandler）。
 *
 * 结构：类型选择卡（[SelectCard] 单选：无重复/每天/每周/每月/每年/自定义）→ 选「自定义」
 * 时经 [Expandable] 展开自定义区——「自定义重复」分组（[SettingsCard]：「频率」选择行弹
 * 行内菜单切天/周/月/年；「每」导航行经 [BottomNumberSelectCard] 选 1..99 的间隔）+
 * 方格（[SelectionGrid]，多选）：周 = 一~日、月 = 1~31、年 = 1月~12月，天无方格。
 *
 * 草稿态语义：挂载即从 [initial] 快照，确认才经 [onConfirm] 回写（取消/遮罩/返回/下拉的
 * 改动不外泄，出场播完由宿主卸载）。切频率单位即以 [anchorDate]（自定义日期）为锚播种
 * 方格——周几/几号/几月跟随锚日期，方格选择从不带空入场；此后可自由增删。自定义且方格
 * 为空时「确认」Toast 提示、不关浮层。
 *
 * 间隔数字弹卡两段式挂载（visible/mounted，同宿主的滚轮弹窗）：声明在 [OverlaySheet]
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

    // 间隔数字弹卡：期望态 + 挂载态两段式（同宿主的滚轮弹窗）
    var intervalVisible by remember { mutableStateOf(false) }
    var intervalMounted by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val marksRequiredMessage = stringResource(R.string.repeat_marks_required)

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
            if (draft.type == RepeatType.Custom && draft.unit != RepeatUnit.Day && draft.marks.isEmpty()) {
                AppToasts.show(context, marksRequiredMessage)
            } else {
                onConfirm(draft)
                onDismiss()
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
        // 间隔弹卡在场时本浮层缩小后退（用可见请求态，退场时与上层收场同帧反向恢复）
        covered = intervalVisible,
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

    // 间隔数字弹卡画在本浮层之后（组合顺序即绘制顺序），叠在浮层之上；确认回写草稿的
    // 间隔，取消/遮罩/返回只请求关闭，播完退场才卸载（两段式，同宿主的滚轮弹窗）
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
}

/** 重复间隔的可选范围（与仓库 normalize 的夹紧一致） */
private const val REPEAT_INTERVAL_MIN = 1
private const val REPEAT_INTERVAL_MAX = 99

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

/** 自定义重复快速预览（Android Studio 中直接查看） */
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
        ),
        anchorDate = IsoDate.today(),
        onConfirm = {},
        onDismiss = {},
        onDismissed = {},
    )
}
