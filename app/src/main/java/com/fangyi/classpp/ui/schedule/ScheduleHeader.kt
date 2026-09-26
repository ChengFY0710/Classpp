package com.fangyi.classpp.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.theme.ClassppTheme
import java.util.Calendar
import java.util.Date
import kotlin.math.roundToInt

private val IconTint = Color(0xFF212121)

/** 顶栏行自然高度，也是最大折叠量 */
internal val TopBarHeight = 56.dp

private val WeekPillHeight = 40.dp
private val HeaderEdgePadding = 33.dp
private val PillEndPadding = 24.dp

/**
 * 可折叠课表头部：顶栏行（编辑 | 周数胶囊 | 设置）+ 日期行 + 星期行。
 *
 * [collapseFraction] ∈ [0,1]：0 完全展开，1 完全折叠——
 * 顶栏行高度收缩为 0，编辑/设置渐隐，日期行与星期行自然上移，
 * 周数胶囊从中央平移到日期行右端（与日期同一行右对齐）。
 * fraction 外置便于 @Preview 直接预览两态。
 */
@Composable
fun ScheduleHeader(
    collapseFraction: Float,
    date: Date,
    selectedWeek: Int,
    onWeekSelected: (Int) -> Unit,
    onEditClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    weekRange: IntRange = 1..20,
    onMenuExpandedChange: (Boolean) -> Unit = {},
) {
    val fraction = collapseFraction.coerceIn(0f, 1f)
    val iconAlpha = 1f - fraction
    val iconsEnabled = fraction < 0.5f

    val calendar = remember(date) { Calendar.getInstance().apply { time = date } }
    val monthDay = stringResource(
        R.string.month_day_format,
        calendar.get(Calendar.MONTH) + 1,
        calendar.get(Calendar.DAY_OF_MONTH),
    )
    // Calendar 以周日为 1，转成周一起始的下标（周一 = 0）
    val weekIndex = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val weekdays = stringArrayResource(R.array.weekdays)
    val dateTitle = stringResource(R.string.date_title_format, monthDay, weekdays[weekIndex])

    Layout(
        content = {
            // 1) 顶栏行：高度随折叠收缩，图标渐隐
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TopBarHeight * (1f - fraction))
                    .clipToBounds(),
            ) {
                IconButton(
                    onClick = onEditClick,
                    enabled = iconsEnabled,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 20.dp)
                        .graphicsLayer { alpha = iconAlpha },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_calendar_edit),
                        contentDescription = stringResource(R.string.cd_edit_schedule),
                        tint = IconTint,
                        modifier = Modifier.size(30.dp)
                    )
                }
                IconButton(
                    onClick = onSettingsClick,
                    enabled = iconsEnabled,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 20.dp)
                        .graphicsLayer { alpha = iconAlpha },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings),
                        contentDescription = stringResource(R.string.cd_settings),
                        tint = IconTint,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            // 2) 日期行
            Text(
                text = dateTitle,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = HeaderEdgePadding, top = 12.dp, bottom = 12.dp),
                fontSize = 23.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                letterSpacing = (-0.3).sp,
            )

            // 3) 星期行：五等分，今天高亮（周起始下标与日期行一致）
            Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 7.dp)) {
                for (i in 0..4) {
                    Text(
                        text = weekdays[i].substring(1),
                        modifier = Modifier.weight(1f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        color = if (i == weekIndex) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }

            // 4) 周数胶囊：顶层 overlay，在两锚点间插值
            WeekPill(
                selectedWeek = selectedWeek,
                weekRange = weekRange,
                onWeekSelected = onWeekSelected,
                enabled = iconsEnabled,
                onMenuExpandedChange = onMenuExpandedChange,
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) { measurables, constraints ->
        val childConstraints = constraints.copy(minHeight = 0)
        val topBarPlaceable = measurables[0].measure(childConstraints)
        val datePlaceable = measurables[1].measure(childConstraints)
        val weekdayPlaceable = measurables[2].measure(childConstraints)
        val pillPlaceable = measurables[3].measure(childConstraints)

        val width = constraints.maxWidth
        val totalHeight =
            topBarPlaceable.height + datePlaceable.height + weekdayPlaceable.height

        // 胶囊位置：x 居中 → 右对齐，y 顶栏中心 → 日期行中心
        val pillWidth = pillPlaceable.width
        val pillHeight = pillPlaceable.height
        val collapsedEndPad = 110.dp.roundToPx()
        val endPad = PillEndPadding.roundToPx()
        val centerX = (width - pillWidth) / 2f
        val endX = if (layoutDirection == LayoutDirection.Rtl) {
            endPad.toFloat()
        } else {
            (width - pillWidth + collapsedEndPad).toFloat()
        }
        val pillX = centerX + (endX - centerX) * fraction
        val topBarCenterY = TopBarHeight.roundToPx() / 2f
        val dateCenterY = topBarPlaceable.height + datePlaceable.height / 2f
        val pillCenterY = topBarCenterY + (dateCenterY - topBarCenterY) * fraction

        layout(width, totalHeight) {
            var y = 0
            topBarPlaceable.placeRelative(0, y)
            y += topBarPlaceable.height
            datePlaceable.placeRelative(0, y)
            y += datePlaceable.height
            weekdayPlaceable.placeRelative(0, y)

            pillPlaceable.place(
                pillX.roundToInt(),
                (pillCenterY - pillHeight / 2f).roundToInt(),
            )
        }
    }
}

@Composable
private fun WeekPill(
    selectedWeek: Int,
    weekRange: IntRange,
    onWeekSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onMenuExpandedChange: (Boolean) -> Unit = {},
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        var menuExpanded by remember { mutableStateOf(false) }

        fun closeMenu() {
            menuExpanded = false
            onMenuExpandedChange(false)
        }

        Row(
            modifier = Modifier
                .height(WeekPillHeight)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.background)
                .clickable(enabled = enabled) {
                    menuExpanded = true
                    onMenuExpandedChange(true)
                }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.week_format, selectedWeek),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                painter = painterResource(R.drawable.ic_chevron_down),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = IconTint,
            )
        }

        val weeks = remember(weekRange) { weekRange.toList() }
        val listState = rememberLazyListState()

        LaunchedEffect(menuExpanded, selectedWeek) {
            if (menuExpanded) {
                listState.scrollToItem(selectedWeek - weekRange.first)
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { closeMenu() },
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            LazyColumn(state = listState) {
                items(weeks, key = { it }) { week ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.week_format, week),
                                fontWeight = if (week == selectedWeek) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                },
                                color = if (week == selectedWeek) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        },
                        onClick = {
                            onWeekSelected(week)
                            closeMenu()
                        },
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "展开态")
@Composable
private fun ScheduleHeaderExpandedPreview() {
    ClassppTheme {
        ScheduleHeader(
            collapseFraction = 0f,
            date = previewDate(),
            selectedWeek = 2,
            onWeekSelected = {},
            onEditClick = {},
            onSettingsClick = {},
        )
    }
}

@Preview(showBackground = true, name = "折叠态")
@Composable
private fun ScheduleHeaderCollapsedPreview() {
    ClassppTheme {
        ScheduleHeader(
            collapseFraction = 1f,
            date = previewDate(),
            selectedWeek = 2,
            onWeekSelected = {},
            onEditClick = {},
            onSettingsClick = {},
        )
    }
}

// 2026-03-10 周二
private fun previewDate(): Date =
    Calendar.getInstance().apply { set(2026, Calendar.MARCH, 10) }.time
