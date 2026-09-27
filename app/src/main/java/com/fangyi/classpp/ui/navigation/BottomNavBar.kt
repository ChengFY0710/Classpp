package com.fangyi.classpp.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.theme.ClassppTheme

enum class AppTab(
    @DrawableRes val iconRes: Int,
    @StringRes val labelRes: Int,
) {
    Agenda(R.drawable.ic_day, R.string.nav_schedule),
    Timetable(R.drawable.ic_calendar, R.string.nav_timetable),
    Todo(R.drawable.ic_notes, R.string.nav_todo),
}

internal val PillHeight = 56.dp
internal val PillBottomOffset = 12.dp
private val PillSpacing = 14.dp
private val PillShadowElevation = 8.dp
private val PillHorizontalPadding = 40.dp
private val GradientOverhang = 24.dp

/** 课表网格等内容需为导航栏预留的底部高度（不含系统导航栏 inset） */
val NavReserve = PillBottomOffset + PillHeight

/** 导航栏整体高度：系统 inset + 胶囊区 + 渐变上沿延伸 */
@Composable
fun navBarTotalHeight(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        NavReserve + GradientOverhang

/**
 * 底部导航：全宽渐变遮罩（底部 Background → 向上透明）+ 三个胶囊按钮。
 * 调用方负责对齐（如 Modifier.align(Alignment.BottomCenter)）。
 */
@Composable
fun BottomNavBar(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(navBarTotalHeight()),
    ) {
        // 渐变遮罩：不拦截点击，内容从其后滚过时渐隐为背景色
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to MaterialTheme.colorScheme.background,
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = PillHorizontalPadding)
                .navigationBarsPadding()
                .padding(bottom = PillBottomOffset)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(PillSpacing),
        ) {
            AppTab.entries.forEach { tab ->
                NavPill(
                    tab = tab,
                    selected = tab == selectedTab,
                    onClick = { onTabSelected(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NavPill(
    tab: AppTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor =
        if (selected) MaterialTheme.colorScheme.primary else Color.Black
    Column(
        modifier = modifier
            .height(PillHeight)
            .graphicsLayer {
                shape = CircleShape
                clip = true
                shadowElevation = 45.dp.toPx()
                spotShadowColor = Color.Black.copy(alpha = 0.2f)
            }
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                CircleShape,
            )
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(5.dp))
        Icon(
            painter = painterResource(tab.iconRes),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = stringResource(tab.labelRes),
            fontSize = 11.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Medium,
            color = contentColor,
            modifier = Modifier.offset(y = - 1.dp),
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 360, name = "日程选中")
@Composable
private fun AgendaSelectedPreview() = BottomNavBarPreview(AppTab.Agenda)

@Preview(showBackground = true, widthDp = 412, heightDp = 360, name = "课表选中")
@Composable
private fun TimetableSelectedPreview() = BottomNavBarPreview(AppTab.Timetable)

@Preview(showBackground = true, widthDp = 412, heightDp = 360, name = "待办选中")
@Composable
private fun TodoSelectedPreview() = BottomNavBarPreview(AppTab.Todo)

/** 模拟页面内容置于导航栏之后，便于观察渐变遮罩的淡出效果 */
@Composable
private fun BottomNavBarPreview(selectedTab: AppTab) {
    ClassppTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            ) {
                repeat(12) {
                    Text(
                        text = "16:40  形式与政策  XX老师  @学武楼 B101",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
            }
            BottomNavBar(
                selectedTab = selectedTab,
                onTabSelected = {},
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
