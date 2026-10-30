package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.TimeText as TimeFormatter
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.schedule.CourseColor
import com.fangyi.classpp.ui.schedule.barColor
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.DayCardShape
import com.fangyi.classpp.ui.theme.DayCourseAlertTagShape
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.isDarkTheme
import com.kyant.shapes.Capsule

/**
 * 上课提醒卡（DayCourseAlert）：设计稿的「XX分钟后上课」提醒卡片，自带白底圆角卡壳——
 *
 * 一、**左侧竖条**：课程色圆头竖条，撑满左侧文字高度（上下各缩进 8dp）。
 * 二、**中列**：标题行（图标 + 「XX分钟后」/「上课中」+ 课程名）→ 教师 → 上课地点 →
 *     备注（带「备注：」前缀）。教师/地点/备注为 null 或空白时整行不显示；教师/地点为
 *     secondaryText 灰，备注为 Primary 蓝。**卡片高度只由中列决定**，中列文字距卡
 *     上下缘所有情况恒定 [CardPaddingVertical]（14dp）。
 * 三、**右侧**按中列行数（标题以外）分两种形态，均**不参与定高**：
 *     - **单行**（无教师/地点/备注）：时间组与标题同行水平排「8:00 上课 9:00」，标签横排
 *       且自然高（不高于标题行）；
 *     - **≥2 行**：时间组改竖列覆盖层（matchParentSize——卡片由中列定型后才测量，高度
 *       恒等于卡片高度），上下时间距卡上下缘恒定 [TimeEdgeInset]（2dp），「上课」标签
 *       weight(1f) 撑满两时间之间，宽高随卡片变化、宽度与上下时间对齐（双行横排
 *       「上课」，三行起竖排
 *       「上/课」，备注折行属「大于四行」，布局同四行）。
 *     - **无课/课已上完/非今天概览**（[DayCourseAlertState.NoCourse] /
 *       [DayCourseAlertState.DayDone] / [DayCourseAlertState.DaySummary]）：仅标题行
 *       「今日无课 好好休息吧」/「今日课已上完 好好休息吧」/「这天 N 节课 做好准备」（0 节
 *       「这天没有课 好好休息吧」），无时间列与课程信息（教师/地点/备注即使传入也不显示），
 *       竖条与图标取 Primary。
 *
 * **标题语义色**：倒计时 ≤ 10 分钟红色（colorScheme.error）、≤ 15 分钟橙色
 * （classppColors.urgentOrange）、更早为 Primary 蓝；「上课中」「今日无课」「今日课已
 * 上完」同为 Primary 蓝。图标随状态切换：倒计时 ic_alert_urgent（铃铛+闪电），其余
 * ic_alert_snooze（铃铛+Zz）。倒计时标题不足 1 小时为「X分钟后」，达到 1 小时为
 * 「X小时Y分钟后」（整小时「X小时后」，见 [countdownTitle]）。
 *
 * **课程色同源**（[CourseColor.barColor]，深浅主题各自映射）：竖条、上下时间文字、标签
 * 全部取标准课程卡色（无课卡竖条取 Primary）。两个小巧思：「上课」文字取课程色降明度
 * （RGB × [TagTextDimFactorLight]/[TagTextDimFactorDark]，深浅各一档），标签底色取
 * 课程色 25% 透明。
 *
 * **点击**：[onClick] 非 null 时整卡可点（按压反馈为 ui.motion 的 pressFeedback，反馈形状
 * 同卡壳圆角），宿主用它唤起课程详情浮层；null（默认，无课/课已上完没有详情可看）时
 * 卡片不可点、无按压反馈。
 *
 * 展示组件（同 DayCalendar 的「不接数据库」约定）：提醒状态与课程信息全由调用方传入，
 * 倒计时/上课中的判定与分钟数刷新由宿主负责；无课/课已上完卡只需传 [state]，其余参数
 * 走默认值并被忽略。
 */
sealed interface DayCourseAlertState {
    /** 距上课还有 [minutes] 分钟（标题「XX分钟后」） */
    data class Countdown(val minutes: Int) : DayCourseAlertState

    /** 已开课（标题「上课中」） */
    data object Ongoing : DayCourseAlertState

    /** 今日无课（标题「今日无课」+ 固定副文案「好好休息吧」；无时间列与课程信息，
     *  竖条与图标取 Primary） */
    data object NoCourse : DayCourseAlertState

    /** 今日课已上完（标题「今日课已上完」+ 固定副文案「好好休息吧」；样式同 [NoCourse]，
     *  仅标题区分「真没课」与「课上完了」） */
    data object DayDone : DayCourseAlertState

    /** 非今天的整天概览，文案按节数分档：[count] > 0 标题「这天 [count] 节课」+ 固定副文案
     *  「做好准备」；0 节（含开学前/学期外）标题「这天没有课」+ 固定副文案「好好休息吧」。
     *  样式同 [NoCourse]：仅标题行，竖条与图标取 Primary，无时间列与课程信息 */
    data class DaySummary(val count: Int) : DayCourseAlertState
}

@Composable
fun DayCourseAlert(
    modifier: Modifier = Modifier,
    state: DayCourseAlertState,
    courseName: String = "",
    startTime: String = "",
    endTime: String = "",
    color: CourseColor = CourseColor.Blue,
    teacher: String? = null,
    location: String? = null,
    note: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    // 无课/课已上完/整天概览卡没有课程信息：竖条与标题同用 Primary
    val titleOnlyCard = state is DayCourseAlertState.NoCourse || state is DayCourseAlertState.DayDone ||
        state is DayCourseAlertState.DaySummary
    val barColor = if (titleOnlyCard) {
        colors.primary
    } else {
        color.barColor(MaterialTheme.isDarkTheme)
    }

    // —— 标题语义：倒计时按剩余分钟分档（≤10 红 / ≤15 橙 / 更早蓝），上课中蓝
    val titleText: String
    val titleColor: Color
    val iconRes: Int
    when (state) {
        is DayCourseAlertState.Countdown -> {
            titleText = countdownTitle(state.minutes)
            titleColor = when {
                state.minutes <= UrgentThresholdMinutes -> colors.error
                state.minutes <= WarnThresholdMinutes -> MaterialTheme.classppColors.urgentOrange
                else -> colors.primary
            }
            iconRes = R.drawable.ic_alert_urgent
        }
        DayCourseAlertState.Ongoing -> {
            titleText = "上课中"
            titleColor = colors.primary
            iconRes = R.drawable.ic_alert_snooze
        }
        DayCourseAlertState.NoCourse -> {
            titleText = "今日无课"
            titleColor = colors.primary
            iconRes = R.drawable.ic_alert_snooze
        }
        DayCourseAlertState.DayDone -> {
            titleText = "今日课已上完"
            titleColor = colors.primary
            iconRes = R.drawable.ic_alert_snooze
        }
        is DayCourseAlertState.DaySummary -> {
            titleText = if (state.count > 0) "这天 ${state.count} 节课" else "这天没有课"
            titleColor = colors.primary
            iconRes = R.drawable.ic_alert_snooze
        }
    }

    // 右侧形态由中列行数（标题以外）决定：0 = 单行同行水平，1 = 竖列 + 横排标签，
    // ≥2 = 竖列 + 竖排标签。备注折行不改变行数档位（布局同四行）。
    // 无课/课已上完/整天概览卡只有标题行：无时间组与课程信息行，第二段文字为固定副文案。
    val infoRowCount = if (titleOnlyCard) 0 else listOf(teacher, location, note).count { !it.isNullOrBlank() }
    val showInlineTimes = !titleOnlyCard && infoRowCount == 0
    val stackedTimes = infoRowCount >= 1
    val verticalTag = infoRowCount >= 2
    val subtitleText = when {
        state is DayCourseAlertState.DaySummary && state.count > 0 -> "做好准备"
        titleOnlyCard -> "好好休息吧"
        else -> courseName
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .pressClickable(shape = DayCardShape, enabled = onClick != null) { onClick?.invoke() }
            .background(colors.surface, DayCardShape),
    ) {
        // 左侧文字列：唯一的高度来源，垂直内边距恒定 → 文字距卡上下缘恒定 14dp。
        // ≥2 行时中列右端让出右列宽度，标题/备注不会钻到时间下面。
        Row(
            modifier = Modifier
                .padding(
                    start = CardPaddingHorizontal,
                    top = CardPaddingVertical,
                    end = if (stackedTimes) CardPaddingHorizontal + RightColumnReserve else CardPaddingHorizontal,
                    bottom = CardPaddingVertical,
                )
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(ContentBarGap),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(BarWidth)
                    .padding(top = 3.dp, bottom = 3.dp)
                    .background(barColor, Capsule()),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(LineSpacing),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        tint = titleColor,
                        modifier = Modifier.size(TitleIconSize),
                    )
                    Spacer(Modifier.width(TitleIconGap))
                    Text(
                        titleText,
                        color = titleColor,
                        style = ClassppTextTokens.alertCardTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(TitleNameGap))
                    Text(
                        subtitleText,
                        // 单行卡用它把右侧时间组推到行尾；≥2 行卡右端已让位，无需 weight
                        modifier = if (showInlineTimes) Modifier.weight(1f) else Modifier,
                        color = colors.onSurface,
                        style = ClassppTextTokens.alertCardTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (showInlineTimes) {
                        Spacer(Modifier.width(InlineRightGap))
                        TimeText(startTime, barColor)
                        Spacer(Modifier.width(TimeTagGap))
                        TagBox(
                            stretch = false,
                            vertical = false,
                            barColor = barColor,
                            modifier = Modifier,
                        )
                        Spacer(Modifier.width(TimeTagGap))
                        TimeText(endTime, barColor)
                    }
                }
                if (!titleOnlyCard) {
                    if (!teacher.isNullOrBlank()) {
                        InfoText(teacher, MaterialTheme.classppColors.secondaryText)
                    }
                    if (!location.isNullOrBlank()) {
                        InfoText(location, MaterialTheme.classppColors.secondaryText)
                    }
                    if (!note.isNullOrBlank()) {
                        InfoText("备注：$note", colors.primary)
                    }
                }
            }
        }
        if (stackedTimes) {
            // ≥2 行右侧竖列：matchParentSize 在卡片定型后才测量——右侧永不把卡片撑高，
            // 时间距卡上下缘恒定 2dp，标签撑满时间之间、高度随卡片高度变化。
            StackedTimeColumn(
                startTime = startTime,
                endTime = endTime,
                barColor = barColor,
                verticalTag = verticalTag,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** ≥2 行时的右侧竖列（[modifier] 传 matchParentSize）：时间顶到上下缘 [TimeEdgeInset] 处，
 *  「上课」标签 weight(1f) 撑满两时间之间，自身高度完全由卡片高度决定；标签定宽
 *  [StackedTagWidth]，与上下时间共用同一条竖直中轴（列宽经 width(IntrinsicSize.Max)
 *  取上下时间中较宽者）。 */
@Composable
private fun StackedTimeColumn(
    startTime: String,
    endTime: String,
    barColor: Color,
    verticalTag: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(
            top = TimeEdgeInset,
            bottom = TimeEdgeInset,
            end = CardPaddingHorizontal,
        ),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(IntrinsicSize.Max),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TagGap),
        ) {
            TimeText(startTime, barColor)
            TagBox(
                stretch = true,
                vertical = verticalTag,
                barColor = barColor,
                modifier = Modifier.weight(1f).width(StackedTagWidth),
            )
            TimeText(endTime, barColor)
        }
    }
}

/** 「上课」标签：[stretch] = 撑满给定约束的高度（竖列中间档：高 weight 撑满、定宽
 *  [StackedTagWidth] 与上下时间共用中轴；水平内边距归零，宽度完全交给外部定宽），
 *  否则自然宽高（单行卡内嵌）；[vertical] = 文字竖排「上/课」两行，否则横排「上课」。
 *  底色 = 课程色 25% 透明，文字 = 课程色降明度（RGB × 深/浅各一套的降明度系数）。 */
@Composable
private fun TagBox(
    stretch: Boolean,
    vertical: Boolean,
    barColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(barColor.copy(alpha = TagBackgroundAlpha), DayCourseAlertTagShape)
            .padding(
                horizontal = if (stretch) StretchedTagPaddingHorizontal else TagPaddingHorizontal,
                vertical = TagPaddingVertical,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 深浅各自降明度：深色课程色本就比浅色深一档，沿用浅色系数会闷进标签底色
        val dimFactor = if (MaterialTheme.isDarkTheme) TagTextDimFactorDark else TagTextDimFactorLight
        val tagColor = barColor.darkened(dimFactor)
        if (vertical) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TagText("上", tagColor)
                TagText("课", tagColor)
            }
        } else {
            TagText("上课", tagColor)
        }
    }
}

/** 教师/地点/备注行：14sp Normal，颜色由调用方给（教师地点 = secondaryText，备注 = primary） */
@Composable
private fun InfoText(text: String, color: Color) {
    Text(
        text,
        style = ClassppTextTokens.metaText,
        color = color,
    )
}

/** 右侧上下课时间：12sp Medium，标准课程卡色；显示时去掉小时前导零（`08:00` → `8:00`） */
@Composable
private fun TimeText(text: String, color: Color) {
    Text(
        text = TimeFormatter.normalize(text) ?: text,
        style = ClassppTextTokens.metaTextSmall,
        color = color,
    )
}

/** 「上课」标签文字：12sp Medium，课程色降明度 */
@Composable
private fun TagText(text: String, color: Color) {
    Text(
        text,
        style = ClassppTextTokens.tagText,
        color = color,
    )
}

/** 课程色降明度：RGB 同比乘 [factor]（色相与透明度不变），比时间文字深一档（时间轴任务卡标签同用） */
internal fun Color.darkened(factor: Float): Color =
    Color(red = red * factor, green = green * factor, blue = blue * factor, alpha = alpha)

/** 倒计时标题：不足 1 小时「X分钟后」，达到 1 小时「X小时Y分钟后」，整小时「X小时后」 */
private fun countdownTitle(minutes: Int): String {
    if (minutes < 60) return "${minutes}分钟后"
    val hours = minutes / 60
    val rest = minutes % 60
    return if (rest == 0) "${hours}小时后" else "${hours}小时${rest}分钟后"
}

// ---------- 视觉规格（设计稿量取；颜色一律取主题/课程色，见各用点） ----------

/** 倒计时语义分档：≤ 此值红色（colorScheme.error） */
private const val UrgentThresholdMinutes = 10

/** 倒计时语义分档：≤ 此值橙色（urgentOrange），更早 Primary 蓝 */
private const val WarnThresholdMinutes = 15

private val CardPaddingHorizontal = 12.dp

/** 左侧文字距卡上下缘的距离（所有行数档位恒定，卡片高度 = 中列高度 + 它的两倍） */
private val CardPaddingVertical = 14.dp
private val BarWidth = 4.dp
private val ContentBarGap = 12.dp
private val LineSpacing = 3.dp
private val TitleIconSize = 22.dp
private val TitleIconGap = 8.dp
private val TitleNameGap = 12.dp

/** ≥2 行时中列右端让位宽度：右列宽（横向标签 42dp 为最宽）+ 与中列的间距 12dp */
private val RightColumnReserve = 54.dp

/** 单行卡课程名与右侧时间组的最小间距（courseName weight(1f) 撑开后的下限） */
private val InlineRightGap = 12.dp

/** 单行卡时间与「上课」标签的间距 */
private val TimeTagGap = 6.dp

/** ≥2 行时上下时间距卡片上下缘的距离（恒定，卡片高度变化全部由标签吸收） */
private val TimeEdgeInset = 2.dp

/** 竖列内时间与「上课」标签的间距 */
private val TagGap = 1.dp

/** 竖列「上课」标签定宽：不再随列宽（= 时间文字宽）撑满，与上下时间共用中轴居中 */
private val StackedTagWidth = 32.dp

/** 「上课」标签底色的透明度：课程色 25%（设计巧思） */
private const val TagBackgroundAlpha = 0.25f

/** 「上课」文字相对课程色的降明度系数（RGB 同比，设计巧思）·浅色主题 */
private const val TagTextDimFactorLight = 0.5f

/** 同上 ·深色主题：课程色本身已比浅色深一档，系数放缓避免文字闷进 25% 透明的标签底色 */
private const val TagTextDimFactorDark = 0.95f
private val TagPaddingHorizontal = 5.dp

/** 拉伸态标签（竖列中间档）的水平内边距：宽度已由列宽（= 上下时间中较宽者）决定，
 *  内边距归零避免标签把自己撑得比时间宽、破坏对齐（双行横排标签尤其需要） */
private val StretchedTagPaddingHorizontal = 0.dp

/** 标签垂直内边距：拉伸态高度由列分配（行高收紧后余量很小，内边距会把最小内容高
 *  撑出可分配空间造成溢出），文字盒自带的行距已提供呼吸感，归零 */
private val TagPaddingVertical = 0.dp

// ---------- 预览 ----------

/** 设计稿同款课程色（Blue 的马卡龙竖条浅蓝） */
private val PreviewCourseColor = CourseColor.Blue

/** 设计稿同款课程信息 */
private const val PreviewCourseName = "毛泽东思想和中国特色社会主义理论体系概论"
private const val PreviewTeacher = "陈锦秀"
private const val PreviewLocation = "学武楼 A304"

@Composable
private fun DayCourseAlertPreviewContent() {
    Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 单行：右侧与标题同行水平排
        DayCourseAlert(
            state = DayCourseAlertState.Countdown(minutes = 30),
            courseName = PreviewCourseName,
            startTime = "8:00",
            endTime = "9:00",
            color = PreviewCourseColor,
        )
        // 双行：竖列 + 横排标签，时间距卡上下缘 2dp
        DayCourseAlert(
            state = DayCourseAlertState.Countdown(minutes = 30),
            courseName = PreviewCourseName,
            startTime = "8:00",
            endTime = "9:00",
            color = PreviewCourseColor,
            teacher = PreviewTeacher,
        )
        // 三行：竖列 + 竖排标签
        DayCourseAlert(
            state = DayCourseAlertState.Countdown(minutes = 10),
            courseName = PreviewCourseName,
            startTime = "8:00",
            endTime = "9:00",
            color = PreviewCourseColor,
            teacher = PreviewTeacher,
            location = PreviewLocation,
        )
        // 四行：备注一行
        DayCourseAlert(
            state = DayCourseAlertState.Ongoing,
            courseName = PreviewCourseName,
            startTime = "8:00",
            endTime = "9:00",
            color = PreviewCourseColor,
            teacher = PreviewTeacher,
            location = PreviewLocation,
            note = "这是备注内容，这是备注内容",
        )
        // 大于四行：备注折多行，标签随之更高
        DayCourseAlert(
            state = DayCourseAlertState.Countdown(minutes = 15),
            courseName = PreviewCourseName,
            startTime = "10:10",
            endTime = "11:50",
            color = CourseColor.Coral,
            teacher = PreviewTeacher,
            location = PreviewLocation,
            note = "这是备注内容，".repeat(6).trimEnd('，'),
        )
        // 今日无课：仅标题行，竖条/图标取 Primary，无时间列
        DayCourseAlert(
            state = DayCourseAlertState.NoCourse,
        )
        // 今日课已上完：样式同今日无课，仅标题区分
        DayCourseAlert(
            state = DayCourseAlertState.DayDone,
        )
        // 非今天整天概览：有课「这天 4 节课 做好准备」，没课「这天没有课 好好休息吧」
        DayCourseAlert(
            state = DayCourseAlertState.DaySummary(count = 4),
        )
        DayCourseAlert(
            state = DayCourseAlertState.DaySummary(count = 0),
        )
    }
}

/** 全部行数档位·浅色 */
@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390)
@Composable
private fun DayCourseAlertLightPreview() = ClassppTheme {
    DayCourseAlertPreviewContent()
}

/** 全部行数档位·深色 */
@Preview(showBackground = true, backgroundColor = 0xFF0F1114, widthDp = 390)
@Composable
private fun DayCourseAlertDarkPreview() = ClassppTheme(darkTheme = true) {
    DayCourseAlertPreviewContent()
}
