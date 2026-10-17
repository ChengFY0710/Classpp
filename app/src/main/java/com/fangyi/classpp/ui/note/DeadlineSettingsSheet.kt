package com.fangyi.classpp.ui.note

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.ui.components.BottomDateSelectCard
import com.fangyi.classpp.ui.components.BottomNumberSelectCard
import com.fangyi.classpp.ui.components.BottomTimeSelectCard
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.motion.Expandable
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import dev.chrisbanes.haze.rememberHazeState
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** 未设截止时刻时的默认时刻：8:00（入口行初值与草稿播种共用） */
internal const val DEFAULT_DEADLINE_MINUTE = 480

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * 截止日期设置浮层：待办编辑浮层「截止日期」入口行的二级浮层（[OverlaySheet] 叠
 * [OverlaySheet]，分层模式同 [RepeatSettingsSheet]——画在表单浮层之后、自建 haze 采样源、
 * 后注册 BackHandler）。
 *
 * 结构（顶层块一律 [CardSection]）：第一部分无题分组——「设置截止日期」开关卡 + 日期/时刻卡
 * （开关关闭，或「下一次重复开始前」打开时整卡收起，[Expandable] 同重复设置页的自定义区；
 * 两行分别弹 [BottomDateSelectCard] / [BottomTimeSelectCard]）；第二部分「重复待办截止日期」
 * 分组——「下一次重复开始前」开关（仅重复待办可开，[hasRepeat] = false 时整行禁用、开关
 * 锁定为关）+ 开启后展开的「开始前」导航行（[BottomNumberSelectCard] 选 0..[repeatCycleDays]
 * 天，上限 = 重复周期天数）。开关打开后各期截止由展示层按「下一期开始前 N 天」自动折算
 * （见 [com.fangyi.classpp.data.expandRepeatOccurrences]），浮层里不再展示固定日期/时刻。
 *
 * 草稿态语义：挂载即从各 initial 参数快照，确认才经 [onConfirm] 回写（取消/遮罩/返回/下拉
 * 的改动不外泄，出场播完由宿主卸载）。开关关闭时确认，宿主把截止置回 null 对——开关即
 * 总闸，日期轮不再提供「清除」（取代旧链式两步流程的清除胶囊）。
 *
 * 三枚滚轮弹卡两段式挂载（visible/mounted，同宿主的滚轮弹窗）：声明在 [OverlaySheet] 之前
 * 使 covered 生效，画在本浮层之后（组合顺序即绘制顺序），其 BackHandler 后注册、返回键
 * 先关弹卡。
 *
 * @param hasRepeat 待办是否带重复日期（自定义日期 + 有效重复规则）；false = 「下一次重复
 *   开始前」开关禁用且锁定为关
 * @param repeatCycleDays 重复周期天数（[com.fangyi.classpp.data.model.cycleDays]），「开始前」
 *   天数轮的上限；无重复时传 1（不消费）
 */
@Composable
internal fun DeadlineSettingsSheet(
    visible: Boolean,
    initialEnabled: Boolean,
    initialDate: IsoDate,
    initialMinute: Int,
    initialBeforeRepeat: Boolean,
    initialBeforeDays: Int,
    hasRepeat: Boolean,
    repeatCycleDays: Int,
    onConfirm: (enabled: Boolean, date: IsoDate, minute: Int, beforeRepeat: Boolean, beforeDays: Int) -> Unit,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
) {
    // 草稿态：宿主两段式挂载，出场期间组合仍在——确认前的改动不外泄、取消即弃。
    // 未设过截止（initialEnabled = false）时日期/时刻行隐藏，但草稿仍持有一对合法初值，
    // 开关一开即有值可选（宿主传入未设时的播种值：今天 + 8:00）
    var enabled by remember { mutableStateOf(initialEnabled) }
    var date by remember { mutableStateOf(initialDate) }
    var minute by remember { mutableIntStateOf(initialMinute) }
    var beforeRepeat by remember { mutableStateOf(initialBeforeRepeat && hasRepeat) }
    var beforeDays by remember { mutableIntStateOf(initialBeforeDays) }

    // 三枚滚轮弹卡：期望态 + 挂载态两段式（同重复设置页的间隔弹卡）
    var dateWheelVisible by remember { mutableStateOf(false) }
    var dateWheelMounted by remember { mutableStateOf(false) }
    var timeWheelVisible by remember { mutableStateOf(false) }
    var timeWheelMounted by remember { mutableStateOf(false) }
    var daysWheelVisible by remember { mutableStateOf(false) }
    var daysWheelMounted by remember { mutableStateOf(false) }

    // haze 采样源自建（同 RepeatSettingsSheet：不共享表单浮层的——下拖时露出的表单内容
    // 不该被模糊进本层顶栏），同时交给本浮层与三枚弹卡——顶栏磨砂与弹卡背景模糊同源
    val sheetHazeState = rememberHazeState()

    BackHandler(enabled = visible, onBack = onDismiss)

    OverlaySheet(
        title = stringResource(R.string.deadline_card_label),
        confirmLabel = stringResource(R.string.edit_confirm),
        // 确认在右、取消在左（同表单浮层与重复设置页的顶栏排布）
        confirmAtEnd = true,
        onConfirm = {
            onConfirm(enabled, date, minute, beforeRepeat && hasRepeat, beforeDays)
            onDismiss()
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
        // 任一滚轮弹卡在场时本浮层缩小后退（用可见请求态，退场时与上层收场同帧反向恢复）
        covered = dateWheelVisible || timeWheelVisible || daysWheelVisible,
        onDismissed = onDismissed,
        // 无输入框（弹卡的键盘让位由弹卡框架自理），键盘不必让位
        imeBehavior = SheetImeBehavior.IgnoreIme,
        hazeState = sheetHazeState,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            // 第一部分（无分组标题）：截止开关卡 + 日期/时刻卡——开关关闭，或「下一次重复
            // 开始前」打开（截止随重复逐期自动折算，自定义日期/时刻不再参与）时整卡收起
            // （高度动画，兄弟内容逐帧让位，同重复设置页自定义区）；草稿里的日期/时刻保留，
            // 作为落库载体与自动截止的时刻来源
            CardSection {
                SettingsCard(
                    items = listOf(
                        SettingsCardItem.Toggle(
                            label = stringResource(R.string.deadline_sheet_enable),
                            description = stringResource(R.string.deadline_sheet_enable_desc),
                            checked = enabled,
                            onCheckedChange = { enabled = it },
                        ),
                    ),
                )
                Expandable(expanded = enabled && !beforeRepeat) {
                    SettingsCard(
                        hazeState = sheetHazeState,
                        items = listOf(
                            SettingsCardItem.Nav(
                                label = stringResource(R.string.date_card_label),
                                value = date.toDeadlineDateText(),
                                onClick = {
                                    dateWheelMounted = true
                                    dateWheelVisible = true
                                },
                            ),
                            SettingsCardItem.Nav(
                                label = stringResource(R.string.deadline_sheet_time),
                                value = TimeText.format(minute),
                                onClick = {
                                    timeWheelMounted = true
                                    timeWheelVisible = true
                                },
                            ),
                        ),
                    )
                }
            }
            // 第二部分：「下一次重复开始前」开关（无重复日期时禁用锁定为关）+ 开始前天数行
            // （开关打开后展开，animateContentSize 使行增减平滑过渡）
            CardSection(title = stringResource(R.string.deadline_sheet_repeat_section)) {
                SettingsCard(
                    modifier = Modifier.animateContentSize(),
                    items = buildList {
                        add(
                            SettingsCardItem.Toggle(
                                label = stringResource(R.string.deadline_sheet_repeat_toggle),
                                description = stringResource(R.string.deadline_sheet_repeat_toggle_desc),
                                checked = beforeRepeat,
                                onCheckedChange = { beforeRepeat = it },
                                enabled = hasRepeat,
                            ),
                        )
                        if (beforeRepeat) {
                            add(
                                SettingsCardItem.Nav(
                                    label = stringResource(R.string.deadline_sheet_repeat_before),
                                    value = stringResource(R.string.deadline_sheet_repeat_days_value, beforeDays),
                                    onClick = {
                                        daysWheelMounted = true
                                        daysWheelVisible = true
                                    },
                                ),
                            )
                        }
                    },
                )
            }
        }
    }

    // 日期滚轮弹卡画在本浮层之后（组合顺序即绘制顺序），确认回写草稿日期；开关即总闸，
    // 不再提供「清除」胶囊（同声明处）；取消/遮罩/返回只请求关闭，播完退场才卸载
    if (dateWheelMounted) {
        BottomDateSelectCard(
            visible = dateWheelVisible,
            onDismiss = { dateWheelVisible = false },
            onDismissed = { dateWheelMounted = false },
            initialDate = date,
            hazeState = sheetHazeState,
            onConfirm = { picked ->
                date = picked
                dateWheelVisible = false
            },
        )
    }

    // 时刻滚轮弹卡：确认把时/分折算成一天内的分钟数回写草稿（两段式，同日期轮）
    if (timeWheelMounted) {
        BottomTimeSelectCard(
            visible = timeWheelVisible,
            onDismiss = { timeWheelVisible = false },
            onDismissed = { timeWheelMounted = false },
            title = stringResource(R.string.deadline_card_time_title),
            initialHour = minute / 60,
            initialMinute = minute % 60,
            hazeState = sheetHazeState,
            onConfirm = { hour, pickedMinute ->
                minute = hour * 60 + pickedMinute
                timeWheelVisible = false
            },
        )
    }

    // 「开始前」天数轮：范围 0..重复周期天数（上限随待办的重复规则，见 repeatCycleDays），
    // 初值夹紧进范围防旧值越界（两段式，同日期轮）
    if (daysWheelMounted) {
        BottomNumberSelectCard(
            visible = daysWheelVisible,
            onDismiss = { daysWheelVisible = false },
            onDismissed = { daysWheelMounted = false },
            title = stringResource(R.string.deadline_sheet_repeat_days_title),
            valueRange = 0..repeatCycleDays,
            initialValue = beforeDays.coerceIn(0, repeatCycleDays),
            unit = stringResource(R.string.repeat_unit_day),
            hazeState = sheetHazeState,
            onConfirm = { value ->
                beforeDays = value
                daysWheelVisible = false
            },
        )
    }
}

/**
 * 截止值展示的日期段 `2026/3/12`（恒显 4 位年，月/日不补零，同设计稿）；换算同
 * [IsoDate.toString] 的 UTC 整数天。入口行与浮层「日期」行共用。
 */
internal fun IsoDate.toDeadlineDateText(): String {
    val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = epochDay * MILLIS_PER_DAY
    }
    return String.format(
        Locale.ROOT,
        "%d/%d/%d",
        calendar.get(GregorianCalendar.YEAR),
        calendar.get(GregorianCalendar.MONTH) + 1,
        calendar.get(GregorianCalendar.DAY_OF_MONTH),
    )
}

/** 截止日期设置浮层快速预览（Android Studio 中直接查看） */
@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390, heightDp = 900)
@Composable
private fun DeadlineSettingsSheetPreview() = ClassppTheme {
    DeadlineSettingsSheet(
        visible = true,
        initialEnabled = true,
        initialDate = IsoDate.of(2026, 3, 12),
        initialMinute = 8 * 60,
        initialBeforeRepeat = true,
        initialBeforeDays = 0,
        hasRepeat = true,
        repeatCycleDays = 7,
        onConfirm = { _, _, _, _, _ -> },
        onDismiss = {},
        onDismissed = {},
    )
}
