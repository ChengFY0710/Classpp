package com.fangyi.classpp.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.OnPrimaryContainer
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import java.util.Calendar
import java.util.Date
import kotlin.math.roundToInt

private val IconTint = Color(0xFF000000)

internal val TopBarHeight = 56.dp  //顶栏行自然高度，也是最大折叠量

private val WeekPillHeight = 40.dp
private val HeaderEdgePadding = 33.dp
private val PillEndPadding = 24.dp

private val WeekPickerGap = 16.dp  //周数选择器顶部与胶囊底部的固定垂直间距（展开/折叠态一致）

private val CardShadowPadding = 16.dp

/** 投影向下延伸最多，底部单独加大透明留白，防止被 Popup 窗口下缘裁切 */
private val CardShadowBottomPadding = 48.dp

private val WeekPickerCellSpace = 8.dp // 周数选择器里小方块间距

/** 小方块边长（宽高相同，恒为正方形）。弹窗宽度由此固定、不随屏宽变化；
 *  整宽 ≈ 6×边长 + 102dp，360dp 屏上边长建议 ≤43dp */
private val WeekCellSize = 47.dp



/**
 * 可折叠课表头部：顶栏行（编辑 | 周数胶囊 | 设置）+ 日期行 + 星期行。
 *
 * [collapseFraction] ∈ [0,1]：0 完全展开，1 完全折叠——
 * 顶栏行高度收缩为 0，编辑/设置渐隐，日期行与星期行自然上移，
 * 周数胶囊从中央平移到日期行右端（与日期同一行右对齐）。
 * fraction 外置便于 @Preview 直接预览两态。
 *
 * [blurProgress] ∈ [0,1]（与日期带渐隐同步）：>0 时头部背景改为
 * Haze 背景模糊（叠在 surface 兜底色之上），顶部最强、向下渐弱；
 * 同时驱动周数胶囊从扁平灰底过渡到半透明白 + 投影。
 * [hazeState] 为 null（如 @Preview）时保持不透明背景。
 */
@Composable
fun ScheduleHeader(
    collapseFraction: Float,
    date: Date,
    selectedWeek: Int,
    currentWeek: Int? = null,
    onWeekSelected: (Int) -> Unit,
    onEditClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    blurProgress: Float = 0f,
    hazeState: HazeState? = null,
    weekRange: IntRange = 1..20,
    onMenuExpandedChange: (Boolean) -> Unit = {},
) {
    val fraction = collapseFraction.coerceIn(0f, 1f)
    val progress = blurProgress.coerceIn(0f, 1f)
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
            Row(modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 7.dp)) {
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

            // 4) 周数胶囊：顶层 overlay，在两锚点间插值；模糊激活时材质同步变化
            // 始终可点（折叠态也要能打开周数菜单），图标仍随折叠禁用
            WeekPill(
                selectedWeek = selectedWeek,
                currentWeek = currentWeek,
                weekRange = weekRange,
                onWeekSelected = onWeekSelected,
                blurProgress = progress,
                onMenuExpandedChange = onMenuExpandedChange,
            )
        },
        modifier = modifier
            .fillMaxWidth()
            // surface 兜底：progress≈0 时与原不透明背景逐帧一致
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (hazeState != null && progress > 0f) {
                    // 背景模糊画在兜底色之上、内容之下；alpha 随进度渐入实现无缝衔接
                    Modifier.hazeEffect(hazeState) {
                        alpha = progress
                        blurRadius = 32.dp
                        progressive = HazeProgressive.verticalGradient(
                            startIntensity = 1f,
                            endIntensity = 0f,
                        )
                        tints = listOf(HazeTint(Color.White.copy(alpha = 0.30f)))
                        noiseFactor = 0f
                    }
                } else {
                    Modifier
                },
            )
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
    currentWeek: Int?,
    weekRange: IntRange,
    onWeekSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    blurProgress: Float = 0f,
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
                // 投影随模糊进度长出；graphicsLayer 在 draw 阶段读值，不引发重组
                .graphicsLayer {
                    shape = CircleShape
                    clip = true
                    shadowElevation = 45.dp.toPx() * blurProgress
                    spotShadowColor = Color.Black.copy(alpha = 0.2f)
                }
                // 扁平灰底 → 半透明白，与顶栏模糊同步过渡
                .background(
                    lerp(
                        MaterialTheme.colorScheme.background,
                        Color.White,
                        blurProgress,
                    ),
                )
                .clickable {
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

        // 屏幕水平居中 + 垂直固定：卡片顶部落在胶囊底部下方 WeekPickerGap（展开/折叠态一致）。
        // DropdownMenu 的位置策略锚定胶囊左缘无法满足居中，故用自定义定位器。
        // 窗口含 CardShadowPadding 透明留白，定位按窗口计算需将其扣除
        val density = LocalDensity.current
        val gapPx = with(density) { (WeekPickerGap - CardShadowPadding).roundToPx() }
        val positionProvider = remember(gapPx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset = IntOffset(
                    x = (windowSize.width - popupContentSize.width) / 2,
                    y = anchorBounds.bottom + gapPx,
                )
            }
        }

        if (menuExpanded) {
            Popup(
                onDismissRequest = { closeMenu() },
                popupPositionProvider = positionProvider,
                // focusable：返回键收起；dismissOnClickOutside 默认开启
                properties = PopupProperties(focusable = true),
            ) {
                WeekPickerCardSurface(
                    selectedWeek = selectedWeek,
                    currentWeek = currentWeek,
                    weekRange = weekRange,
                    onWeekSelected = {
                        onWeekSelected(it)
                        closeMenu()
                    },
                )
            }
        }
    }
}


@Composable
private fun WeekPickerCard(
    selectedWeek: Int,
    currentWeek: Int?,
    weekRange: IntRange,
    onWeekSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val weeks = remember(weekRange) { weekRange.toList() }

    Column(
        modifier = modifier.padding(horizontal = WeekPickerCellSpace, vertical = WeekPickerCellSpace),
        verticalArrangement = Arrangement.spacedBy(WeekPickerCellSpace),
    ) {
        weeks.chunked(6).forEach { rowWeeks ->
            Row(horizontalArrangement = Arrangement.spacedBy(WeekPickerCellSpace)) {
                rowWeeks.forEach { week ->
                    WeekCell(
                        week = week,
                        selected = week == selectedWeek,
                        isCurrent = week == currentWeek,
                        size = WeekCellSize,
                        onClick = { onWeekSelected(week) },
                    )
                }
            }
        }
    }
}

/** 弹窗卡片外壳：四周 [CardShadowPadding] 透明留白容纳投影，避免被 Popup 窗口边界裁剪 */
@Composable
private fun WeekPickerCardSurface(
    selectedWeek: Int,
    currentWeek: Int?,
    weekRange: IntRange,
    onWeekSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .padding(
                start = CardShadowPadding,
                top = CardShadowPadding,
                end = CardShadowPadding,
                bottom = CardShadowBottomPadding,
            )
            .graphicsLayer {
                shape = RoundedCornerShape(20.dp)
                clip = true
                shadowElevation = 45.dp.toPx()
                spotShadowColor = Color.Black.copy(alpha = 0.2f)
            },
    ) {
        WeekPickerCard(
            selectedWeek = selectedWeek,
            currentWeek = currentWeek,
            weekRange = weekRange,
            onWeekSelected = onWeekSelected,
        )
    }
}

/** 周数网格中的一格：方形圆角；选中态主色底白字，本周（未选中）浅蓝底蓝字 */
@Composable
private fun WeekCell(
    week: Int,
    selected: Boolean,
    isCurrent: Boolean,
    size: Dp,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.week_format, week)
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(11.dp))
            .background(
                when {
                    selected -> colors.primary
                    isCurrent -> colors.primaryContainer
                    else -> OnPrimaryContainer
                },
            )
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = week.toString(),
            fontSize = 22.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = when {
                selected -> Color.White
                isCurrent -> colors.primary
                else -> colors.onSurfaceVariant
            },
        )
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
