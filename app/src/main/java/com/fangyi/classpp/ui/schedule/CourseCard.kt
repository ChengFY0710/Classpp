package com.fangyi.classpp.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.isDarkTheme

private val CardShape = RoundedCornerShape(6.dp)
private val BarWidth = 3.dp

/** [CourseColor] 到卡片竖条/时间文字颜色的浅色映射（白卡底，马卡龙原色；深色见 [barColorDark]）。
 *  分支顺序与枚举声明一致（颜色选集 18 色） */
private val CourseColor.barColorLight: Color
    get() = when (this) {
        CourseColor.Pink -> Color(0xFFEDB5C9)
        CourseColor.Lilac -> Color(0xFFE2B5E9)
        CourseColor.Purple -> Color(0xFFCBBCF0)
        CourseColor.Blue -> Color(0xFFB1C4EE)
        CourseColor.SkyBlue -> Color(0xFFA2C8E8)
        CourseColor.SteelBlue -> Color(0xFFA9C8E2)
        CourseColor.IceBlue -> Color(0xFF90CCE7)
        CourseColor.Aqua -> Color(0xFF6ED4D4)
        CourseColor.Teal -> Color(0xFF7CD3D0)
        CourseColor.Green -> Color(0xFF81D689)
        CourseColor.BrightGreen -> Color(0xFF72DB64)
        CourseColor.Mint -> Color(0xFF73DB5C)
        CourseColor.Greentwo -> Color(0xFF98D651)
        CourseColor.Olive -> Color(0xFFB5CE69)
        CourseColor.Lemon -> Color(0xFFD1C815)
        CourseColor.Yellow -> Color(0xFFE3C160)
        CourseColor.Orange -> Color(0xFFEBB8A7)
        CourseColor.Coral -> Color(0xFFEEB6B6)
    }

/**
 * [CourseColor] 的深色映射（DarkSurface 深卡底）：降明度降饱和策略——明度、饱和度
 * 同比降至浅色的 75%（明度保底 0.44，仅本来就偏暗的 Lemon 触底），色相不变，
 * 呈深卡上的低饱和沉稳同色系；卡上时间文字对比 3.6–6.0，与深色 secondaryText 相当。
 * 经 [barColor] 取用。
 */
private val CourseColor.barColorDark: Color
    get() = when (this) {
        CourseColor.Pink -> Color(0xFFCA7090)
        CourseColor.Lilac -> Color(0xFFB973C4)
        CourseColor.Purple -> Color(0xFF8D74CD)
        CourseColor.Blue -> Color(0xFF6C8ACB)
        CourseColor.SkyBlue -> Color(0xFF6398C4)
        CourseColor.SteelBlue -> Color(0xFF6C98BC)
        CourseColor.IceBlue -> Color(0xFF55A2C4)
        CourseColor.Aqua -> Color(0xFF48AAAA)
        CourseColor.Teal -> Color(0xFF4FACA9)
        CourseColor.Green -> Color(0xFF50B159)
        CourseColor.BrightGreen -> Color(0xFF4DB040)
        CourseColor.Mint -> Color(0xFF51AC3D)
        CourseColor.Greentwo -> Color(0xFF74A43C)
        CourseColor.Olive -> Color(0xFF8BA148)
        CourseColor.Lemon -> Color(0xFFB5AE2B)
        CourseColor.Yellow -> Color(0xFFB99839)
        CourseColor.Orange -> Color(0xFFC87E66)
        CourseColor.Coral -> Color(0xFFCB7070)
    }

/** 按当前主题取课程配色（[dark] = MaterialTheme.isDarkTheme，设置页手动覆盖时同源）；编辑弹窗的色块同源 */
internal fun CourseColor.barColor(dark: Boolean): Color =
    if (dark) barColorDark else barColorLight

/** 卡内文本行数分配结果：[name] 课程名、[location] 地点（教师恒 1 行，不参与分配） */
internal data class CardLines(val name: Int, val location: Int)

/**
 * 行数分配：给定 [budgetPx]（卡高扣掉起止时间行、教师行、内边距与安全余量后的剩余预算），
 * 在单一偏好序（好→差）里取第一个放得下的档位，**预算装得下几行就给几行**、无默认值分支：
 * - 增长段（地点先涨，封顶 6/6）：(6,6) → (5,6) → (4,6) → (3,6) → (3,5) → (3,4)
 * - 默认段：(3,3)
 * - 降级段（课程名先降，保底 1/1）：(2,3) → (1,3) → (1,2) → (1,1)
 *
 * 行成本 = 各自 Text 的 lineHeight px（[nameLinePx] 16sp、[locationLinePx] 14sp，
 * 调用处已乘 fontScale）；连保底档都装不下也返回 (1,1)，溢出交给 maxLines + 省略号。
 * 默认行高（5 天 150 / 7 天 160dp，预算约 107/117）自然落在 (3,4)。
 */
internal fun allocateCardLines(
    budgetPx: Float,
    nameLinePx: Float,
    locationLinePx: Float,
): CardLines {
    val tiers = listOf(
        CardLines(6, 6),
        CardLines(5, 6),
        CardLines(4, 6),
        CardLines(3, 6),
        CardLines(3, 5),
        CardLines(3, 4),
        CardLines(3, 3),
        CardLines(2, 3),
        CardLines(1, 3),
        CardLines(1, 2),
        CardLines(1, 1),
    )
    return tiers.firstOrNull { it.name * nameLinePx + it.location * locationLinePx <= budgetPx }
        ?: CardLines(1, 1)
}

/**
 * 课程卡片：白底圆角卡 + 左侧彩色竖条。
 *
 * 自上而下：开始时间（竖条同色）→ 课程名 → 教师 → @楼名 房间，底部对齐结束时间。
 * 课程名/地点的行数上限**纯由行高预算**在单条偏好序上 first-fit 决定
 * （[allocateCardLines]，无默认值特殊分支）：预算充足走地点先涨段（封顶 6/6），
 * 不足依次回落到课程名先降段（保底 1/1）；教师/起止时间恒 1 行——设置页调小行高
 * 文本变密、调大变疏，超行省略号截断。
 *
 * [showTeacher] = 个性化页「显示教师」开关：关闭时教师行不渲染，视为教师留空——
 * 地点行上移、行高预算同步少扣一行（[allocateCardLines] 的固定开销按 2 行时间 + 内边距计）。
 *
 * 竖条分两段表示交替课程：本周上的那门占上 3/4，非本周的交替课占下 1/4
 * （见 [Course.alternateBar]）；组内非本周的课再多也只占这 1/4、只用一个颜色。
 * 整卡置灰（本周不上）时竖条全灰、不分段。
 *
 * [onLongClick] 仅编辑态会传：长按出"新建交替课程"菜单。[onClick] 编辑态与浏览态都会传
 * （编辑态进编辑面板、浏览态开课程详情浮层，由调用方路由），仅当调用方根本不响应点击时为 null。
 * [endTime] 覆盖底部结束时间：跨节卡传末结束节次的时间，单节默认取 [slot] 的结束时间。
 */
@Composable
fun CourseCard(
    course: Course,
    slot: TimeSlot,
    modifier: Modifier = Modifier,
    showTeacher: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    endTime: String = slot.endTime,
) {
    val inactiveColor = MaterialTheme.classppColors.negative
    val dark = MaterialTheme.isDarkTheme
    val barColor = if (course.active) {
        course.color.barColor(dark)
    } else {
        inactiveColor
    }
    val secondaryColor = if (course.active) MaterialTheme.classppColors.secondaryText else inactiveColor
    // 长按与点击同源：都在 clip 之内（圆角外的点击被裁掉），按压反馈与它们共用按压源
    val pressInteraction = remember { MutableInteractionSource() }
    val clickModifier = when {
        onLongClick != null -> Modifier.combinedClickable(
            interactionSource = pressInteraction,
            indication = null,
            onClick = onClick ?: {},
            onLongClick = onLongClick,
        )
        onClick != null -> Modifier.clickable(
            interactionSource = pressInteraction,
            indication = null,
            onClick = onClick,
        )
        else -> Modifier
    }
    // 按压反馈只在真的可点时接，且接在 clip 之前：缩放连圆角裁剪一起放大，提亮范围与 CardShape 对齐
    val pressModifier = if (onLongClick != null || onClick != null) {
        Modifier.pressFeedback(interactionSource = pressInteraction, shape = CardShape)
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .then(pressModifier)
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surface)
            .then(clickModifier),
    ) {
        // 左侧彩色竖条：3:1 分段对单节卡与跨节卡（requiredHeight 撑出的整卡高度）都成立
        Column(
            modifier = Modifier
                .width(BarWidth)
                .fillMaxHeight(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(3f)
                    .background(barColor),
            )
            val alternate = course.alternateBar
            if (course.active && alternate != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(alternate.barColor(dark)),
                )
            }
        }
        // 内容区包一层 BoxWithConstraints：卡高约束首帧组合期即可用（无先溢出后修正的闪跳），
        // 行预算按起止时间行(12sp) + 教师行(12sp，留空则不扣) + 内边距(6dp) + 1dp 安全余量扣除，
        // 行成本取各 Text 的 lineHeight（16sp/14sp）× fontScale × density
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            // 「显示教师」关闭或教师留空都不渲染教师行：地点上移、预算少扣一行
            val hasTeacher = showTeacher && course.teacher.isNotBlank()
            val lines = if (constraints.maxHeight == Constraints.Infinity) {
                CardLines(3, 3)
            } else {
                val localDensity = LocalDensity.current
                val spPx = { sp: Float -> sp * localDensity.fontScale * localDensity.density }
                val fixedPx = with(localDensity) { 6.dp.toPx() + 1.dp.toPx() } +
                    spPx(12f) * (if (hasTeacher) 3f else 2f)
                allocateCardLines(
                    budgetPx = constraints.maxHeight - fixedPx,
                    nameLinePx = spPx(16f),
                    locationLinePx = spPx(14f),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 4.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Text(
                        text = slot.startTime,
                        color = barColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        textAlign = TextAlign.Start,
                        lineHeight = 12.sp,
                    )
                    Column() {
                        Text(
                            text = course.name,
                            color = if (course.active) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                inactiveColor
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 16.sp,
                            maxLines = lines.name,
                            overflow = TextOverflow.Ellipsis,
                            letterSpacing = (-0.1).sp,
                        )
                        Spacer(modifier = Modifier.size(1.dp))
                        // 教师留空时不占位，地点直接上移
                        if (hasTeacher) {
                            Text(
                                text = course.teacher,
                                color = secondaryColor,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Start,
                                lineHeight = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        // 上课地点：默认三行，课程名降到底（1 行）后才轮到它降（见 allocateCardLines）
                        Text(
                            text = course.location,
                            color = secondaryColor,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Start,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = lines.location,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = endTime,
                    color = barColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    textAlign = TextAlign.Start,
                    lineHeight = 12.sp,
                )
            }
        }
    }
}

/** 添加卡片中间加号的尺寸（设计稿里占卡片中部一大块） */
private val AddIconSize = 30.dp

/** 添加卡片左侧内缩 = 课程卡片的色条 3dp + 内缩 4dp，使两者起止时间同一条竖线 */
private val AddCardStartPadding = 7.dp

/**
 * 编辑态空位上的「添加卡片」：与 [CourseCard] 同骨架——上开始时间、下结束时间，
 * 中间是加号；无左侧色条。点整卡打开添加课程弹窗。
 *
 * [onLongClick] 仅编辑态且剪贴板有课时会传（长按出"粘贴课程"菜单，见 ScheduleScreen）；
 * 与 [CourseCard] 同源：长按与点击都在 clip 之内（圆角外的点击被裁掉），按压反馈共用按压源。
 */
@Composable
fun AddCourseCard(
    slot: TimeSlot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    // 按压与点击同源共用按压源（涟漪由按压反馈取代）
    val pressInteraction = remember { MutableInteractionSource() }
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(
            interactionSource = pressInteraction,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    } else {
        Modifier.clickable(
            interactionSource = pressInteraction,
            indication = null,
            onClick = onClick,
        )
    }
    Column(
        modifier = modifier
            // 按压反馈接在 clip 之前：缩放连圆角裁剪一起放大，提亮范围与 CardShape 对齐
            .pressFeedback(
                interactionSource = pressInteraction,
                shape = CardShape,
            )
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surface)
            .then(clickModifier)
            .padding(
                start = AddCardStartPadding,
                end = 5.dp,
                top = 3.dp,
                bottom = 3.dp,
            ),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.Start,
    ) {
        AddCardTime(text = slot.startTime)
        Icon(
            painter = painterResource(R.drawable.ic_add),
            contentDescription = stringResource(R.string.cd_add_course),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(AddIconSize),
        )
        AddCardTime(text = slot.endTime)
    }
}

/** 添加卡片上的起止时间（与课程卡片的时间同字号/同基线） */
@Composable
private fun AddCardTime(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.inversePrimary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        lineHeight = 12.sp,
    )
}

/** 预览用单元格：等宽、行高、内边距与网格一致（常量取自 CourseGrid） */
@Composable
private fun RowScope.GridCell(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(start = CellPadding, end = CellPadding, top = CellPaddingTop, bottom = CellPadding),
    ) {
        content()
    }
}

@Preview(showBackground = true, name = "添加卡片 · 整行实际尺寸", widthDp = 360)
@Composable
private fun AddCourseCardRowPreview() {
    ClassppTheme {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(GridRowHeight)
                .background(MaterialTheme.colorScheme.background),
        ) {
            repeat(5) {
                GridCell {
                    AddCourseCard(
                        slot = DefaultTimeSlots[1],
                        onClick = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "添加卡片 · 与课程卡片对照", widthDp = 360)
@Composable
private fun AddCourseCardComparePreview() {
    ClassppTheme {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(GridRowHeight)
                .background(MaterialTheme.colorScheme.background),
        ) {
            // 正常课程卡片：起止时间应与右侧添加卡片落在同一条竖线上
            GridCell {
                CourseCard(
                    course = MockCourses[4],
                    slot = DefaultTimeSlots[1],
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 本周不上的置灰卡片：编辑态里它是可点的（点它去添加）
            GridCell {
                CourseCard(
                    course = MockCourses[4].copy(active = false),
                    slot = DefaultTimeSlots[1],
                    modifier = Modifier.fillMaxSize(),
                )
            }
            GridCell {
                AddCourseCard(
                    slot = DefaultTimeSlots[1],
                    onClick = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "常规卡片")
@Composable
private fun CourseCardPreview() {
    ClassppTheme {
        CourseCard(
            course = MockCourses[4],
            slot = DefaultTimeSlots[1],
            modifier = Modifier.fillMaxWidth().height(150.dp),
        )
    }
}

@Preview(showBackground = true, name = "两行长名")
@Composable
private fun CourseCardLongNamePreview() {
    ClassppTheme {
        CourseCard(
            course = MockCourses[0],
            slot = DefaultTimeSlots[0],
            modifier = Modifier.fillMaxWidth().height(150.dp),
        )
    }
}

@Preview(showBackground = true, name = "交替课程卡片", widthDp = 360)
@Composable
private fun CourseCardAlternatePreview() {
    ClassppTheme {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(GridRowHeight)
                .background(MaterialTheme.colorScheme.background),
        ) {
            // 普通单课：竖条整条同色
            GridCell {
                CourseCard(
                    course = MockCourses[4],
                    slot = DefaultTimeSlots[1],
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 交替课程：本周课占上 3/4，非本周的交替课占下 1/4
            GridCell {
                CourseCard(
                    course = MockCourses[6].copy(alternateBar = CourseColor.Green),
                    slot = DefaultTimeSlots[1],
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 本周不上：整卡置灰，色条不分段
            GridCell {
                CourseCard(
                    course = MockCourses[6].copy(active = false, alternateBar = CourseColor.Green),
                    slot = DefaultTimeSlots[1],
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * 7 天视图的整行实际尺寸：固定放 7 门各具代表性的课（正常 / 跨节 / 交替 / 置灰 / 周末课），
 * 用来看 7 列窄卡下课程名与教师、地点的排版。行高取 [gridRowHeight]，与网格同源。
 */
@Preview(showBackground = true, name = "7 天视图 · 整行实际尺寸", widthDp = 411)
@Composable
private fun SevenDayRowPreview() {
    val weekend = MockCoursesWeekend.takeLast(2)
    val row = listOf(
        MockCourses[4],                                            // 常规课
        MockCourses[1],                                            // 跨节（span = 2）
        MockCourses[6].copy(alternateBar = CourseColor.Green),      // 交替课程
        MockCourses[7],                                            // 长课名
        MockCourses[12].copy(active = false),                      // 本周不上（置灰）
        weekend[0],                                                // 周六
        weekend[1],                                                // 周日
    )
    ClassppTheme {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(gridRowHeight(daysPerWeek = 7))
                .background(MaterialTheme.colorScheme.background),
        ) {
            row.forEach { course ->
                GridCell {
                    CourseCard(
                        course = course,
                        slot = DefaultTimeSlots[1],
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
