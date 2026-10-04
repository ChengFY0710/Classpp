package com.fangyi.classpp.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.isDarkTheme

private val CardShape = RoundedCornerShape(6.dp)
private val BarWidth = 3.dp

/** [CourseColor] 到卡片竖条/时间文字颜色的映射（编辑弹窗的色块也用它）。
 *  马卡龙系内容色在浅色白卡与深色深卡上对比都成立，深浅共用一套 */
internal val CourseColor.barColor: Color
    get() = when (this) {
        CourseColor.Blue -> Color(0xFFB1C4EE)
        CourseColor.Green -> Color(0xFF81D689)
        CourseColor.Greentwo -> Color(0xFF98D651)
        CourseColor.Yellow -> Color(0xFFE3C160)
        CourseColor.Orange -> Color(0xFFEBB8A7)
        CourseColor.Purple -> Color(0xFFCBBCF0)
        CourseColor.Teal -> Color(0xFF7CD3D0)
        CourseColor.Pink -> Color(0xFFEDB5C9)
    }

/** 置灰规范色（需求 7）：卡片底 #cbcbcb、课名 #737a83（教师/地点同用后者，避免低对比度）。
 *  深色卡底上取更暗的灰，保持同等「刻意弱化」的视觉强度 */
private val InactiveColorLight = Color(0xFFCBCBCB)
private val InactiveColorDark = Color(0xFF5C6269)

/**
 * 课程卡片：白底圆角卡 + 左侧彩色竖条。
 *
 * 自上而下：开始时间（竖条同色）→ 课程名（两行截断）→ 教师 → @楼名 房间，
 * 底部对齐结束时间。
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
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    endTime: String = slot.endTime,
) {
    val inactiveColor = if (MaterialTheme.isDarkTheme) InactiveColorDark else InactiveColorLight
    val barColor = if (course.active) {
        course.color.barColor
    } else {
        inactiveColor
    }
    val secondaryColor = if (course.active) MaterialTheme.classppColors.secondaryText else inactiveColor
    // 长按与点击同源：都在 clip 之内（圆角外的点击/涟漪被裁掉）
    val clickModifier = when {
        onLongClick != null -> Modifier.combinedClickable(
            onClick = onClick ?: {},
            onLongClick = onLongClick,
        )
        onClick != null -> Modifier.clickable(onClick = onClick)
        else -> Modifier
    }

    Row(
        modifier = modifier
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surface)
            // 水波纹在 clip 之内：圆角外的点击/涟漪都被裁掉
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
                        .background(alternate.barColor),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
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
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        letterSpacing = (-0.1).sp,
                    )
                    Spacer(modifier = Modifier.size(1.dp))
                    // 教师留空时不占位，地点直接上移
                    if (course.teacher.isNotBlank()) {
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
                    // 上课地点，统一一行
                    Text(
                        text = course.location,
                        color = secondaryColor,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Start,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.Medium,
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

/** 添加卡片中间加号的尺寸（设计稿里占卡片中部一大块） */
private val AddIconSize = 30.dp

/** 添加卡片左侧内缩 = 课程卡片的色条 3dp + 内缩 4dp，使两者起止时间同一条竖线 */
private val AddCardStartPadding = 7.dp

/**
 * 编辑态空位上的「添加卡片」：与 [CourseCard] 同骨架——上开始时间、下结束时间，
 * 中间是加号；无左侧色条。点整卡打开添加课程弹窗。
 *
 * [onLongClick] 仅编辑态且剪贴板有课时会传（长按出"粘贴课程"菜单，见 ScheduleScreen）；
 * 与 [CourseCard] 同源：长按与点击都在 clip 之内（圆角外的点击/涟漪被裁掉）。
 */
@Composable
fun AddCourseCard(
    slot: TimeSlot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Column(
        modifier = modifier
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
