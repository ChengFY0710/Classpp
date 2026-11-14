package com.fangyi.classpp.widget

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import com.fangyi.classpp.MainActivity
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.ui.theme.WidgetColors
import com.fangyi.classpp.ui.theme.WidgetTextStyles
import com.fangyi.classpp.ui.theme.widgetTextStyles

/**
 * 「今日课程」组件（4×2）的 Glance 布局。
 *
 * 视觉与 `ui/components/CourseInfoCard.kt` 同一套：左侧课程色竖条 4dp + 12dp 间距 + 文字列
 * （课程名 / 时间 · 地点）。差异只有两处，都是「当前节」语义：
 * - 当前节竖条改 `colorScheme.primary`、整行加 `classppColors.primaryContainerSolid` 选中底；
 * - 文字仍按规范取色：标题 `onSurface`、时间/地点 `secondaryText`。
 *
 * 卡面 / 描边 / 字号全部来自 [WidgetColors] / [WidgetTextStyles]（ui/theme 从既有 token 派生），
 * 本文件只做布局与尺寸。
 */
@Composable
internal fun TodayCoursesWidgetContent(state: TodayCoursesWidgetState) {
    val context = LocalContext.current
    val colors = state.palette.toColors()
    val styles = widgetTextStyles(colors)
    // 4×2 目标显示 3 行；launcher 给的单元格更矮时按实际高度降为 2 / 1 行，不裁切文字
    val maxRows = maxRowsForHeight(LocalSize.current.height)
    val rows = selectWindow(state.entries, state.nowMinute, maxRows)
    val openApp = actionStartActivity(
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    // 外层是 1dp outline 描边层，内层是卡面：Glance 没有 border 修饰符，用嵌套 Box 画
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(colors.outline)
            .cornerRadius(CardCornerRadius)
            .padding(CardBorderWidth)
            .clickable(openApp),
    ) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(colors.surface)
                .cornerRadius(CardCornerRadius - CardBorderWidth)
                .padding(horizontal = CardPaddingHorizontal, vertical = CardPaddingVertical),
        ) {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth().height(HeaderHeight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = headerTitle(state),
                        modifier = GlanceModifier.defaultWeight(),
                        style = styles.headerTitle,
                        maxLines = 1,
                    )
                    if (state.entries.isNotEmpty()) {
                        Spacer(modifier = GlanceModifier.width(HeaderCountGap))
                        Text(
                            text = context.getString(
                                R.string.widget_today_count,
                                state.entries.size,
                            ),
                            style = styles.headerMeta,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(modifier = GlanceModifier.height(HeaderGap))
                if (state.entries.isEmpty()) {
                    Box(
                        modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = context.getString(
                                if (state.hasSchedule) {
                                    R.string.widget_no_course_today
                                } else {
                                    R.string.empty_title
                                },
                            ),
                            style = styles.empty,
                            maxLines = 1,
                        )
                    }
                } else {
                    rows.forEachIndexed { index, entry ->
                        if (index > 0) Spacer(modifier = GlanceModifier.height(RowGap))
                        CourseRow(
                            entry = entry,
                            colors = colors,
                            styles = styles,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun headerTitle(state: TodayCoursesWidgetState): String {
    val context = LocalContext.current
    if (!state.hasSchedule) return context.getString(R.string.widget_today_label)
    val weekday = context.resources.getStringArray(R.array.weekdays)
        .getOrElse(state.weekday - 1) { "" }
    return context.getString(
        R.string.widget_week_weekday,
        context.getString(R.string.week_format, state.weekNumber),
        weekday,
    )
}

@Composable
private fun CourseRow(
    entry: WidgetCourseRow,
    colors: WidgetColors,
    styles: WidgetTextStyles,
) {
    val context = LocalContext.current
    val timeRange = context.getString(
        R.string.widget_course_time,
        TimeText.formatDisplay(entry.startMinute),
        TimeText.formatDisplay(entry.endMinute),
    )
    val location = entry.location.trim()
    val detail = if (location.isEmpty()) {
        timeRange
    } else {
        context.getString(R.string.widget_course_detail, timeRange, location)
    }
    // 底色 + 圆角必须先于 padding：背景要盖住整行（含行内缩进）
    val rowModifier = GlanceModifier
        .fillMaxWidth()
        .height(RowHeight)
        .then(
            if (entry.current) {
                GlanceModifier
                    .background(colors.currentRowBackground)
                    .cornerRadius(RowCornerRadius)
            } else {
                GlanceModifier
            },
        )
        .padding(horizontal = RowPaddingHorizontal)

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier
                .width(BarWidth)
                .height(BarHeight)
                .background(Color(entry.barColor))
                .cornerRadius(BarCornerRadius),
        ) {}
        Spacer(modifier = GlanceModifier.width(BarTextGap))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(text = entry.name, style = styles.courseTitle, maxLines = 1)
            Spacer(modifier = GlanceModifier.height(TitleMetaGap))
            Text(text = detail, style = styles.courseMeta, maxLines = 1)
        }
    }
}

/** 按组件实际高度算能放几行：卡内边距 + 标题行 + 标题下间距之后，按「行高 + 行距」步进。 */
private fun maxRowsForHeight(height: Dp): Int {
    val usable = height - CardPaddingVertical * 2 - HeaderHeight - HeaderGap
    if (usable <= 0.dp) return 0
    val rows = ((usable + RowGap) / (RowHeight + RowGap)).toInt()
    return rows.coerceIn(0, MaxRows)
}

// ---- 几何（4×2 目标 ≈ 320×170dp：卡内 3 行需要约 169dp，矮屏由 maxRowsForHeight 降行） ----
// 注：Glance 的 cornerRadius 只在 Android 12+ 生效，更低版本回退直角；这是平台限制，
// 不影响卡面颜色与行布局。
private val CardCornerRadius = 20.dp
private val CardBorderWidth = 1.dp
private val CardPaddingHorizontal = 16.dp
private val CardPaddingVertical = 5.dp
private val HeaderHeight = 22.dp
private val HeaderGap = 3.dp
private val HeaderCountGap = 8.dp
private val RowHeight = 42.dp
private val RowGap = 4.dp
private val RowCornerRadius = 12.dp
private val RowPaddingHorizontal = 6.dp
private val BarWidth = 4.dp
private val BarHeight = 32.dp
private val BarCornerRadius = 2.dp
private val BarTextGap = 12.dp
private val TitleMetaGap = 1.dp

/** 拉高后最多显示的行数：留出余量给未来 4×4，不无限铺满。 */
private const val MaxRows = 6
