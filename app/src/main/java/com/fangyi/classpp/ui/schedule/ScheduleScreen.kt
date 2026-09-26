package com.fangyi.classpp.ui.schedule

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import com.fangyi.classpp.ui.theme.ClassppTheme
import java.util.Date

/**
 * 课表页：可折叠头部（顶栏行/日期行/星期行）+ 课表网格。
 * 折叠状态由 [CollapseState] 的 NestedScrollConnection 驱动，跟手折叠/展开。
 */
@Composable
fun ScheduleScreen(modifier: Modifier = Modifier) {
    var selectedWeek by rememberSaveable { mutableIntStateOf(2) }
    val today = remember { Date() }

    // 本阶段为单周 mock：选周仅影响日期带，课表数据不变
    val courses = remember { MockCourses }
    val timeSlots = remember { DefaultTimeSlots }
    val weekDates = remember(selectedWeek) { datesForWeek(selectedWeek) }

    val density = LocalDensity.current
    val maxCollapsePx = with(density) { TopBarHeight.toPx() }
    val collapseState = rememberCollapseState(maxCollapsePx)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // 顶部状态栏 inset 由头部自行吸收；底部不留白，网格直接延伸到导航栏（小白条）之下
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            ScheduleHeader(
                collapseFraction = collapseState.collapseFraction,
                date = today,
                selectedWeek = selectedWeek,
                onWeekSelected = { selectedWeek = it },
                onEditClick = { /* TODO: 编辑/切换课表 */ },
                onSettingsClick = { /* TODO: 设置页 */ },
                onMenuExpandedChange = { collapseState.menuOpen = it },
            )
            CourseGrid(
                courses = courses,
                timeSlots = timeSlots,
                weekDates = weekDates,
                today = today,
                // 滚动到底时最后一行可停在小白条上方，网格背景仍铺满屏幕底缘
                contentPadding = WindowInsets.navigationBars
                    .only(WindowInsetsSides.Bottom)
                    .asPaddingValues(),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(collapseState.nestedScrollConnection),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ScheduleScreenPreview() {
    ClassppTheme {
        ScheduleScreen()
    }
}
