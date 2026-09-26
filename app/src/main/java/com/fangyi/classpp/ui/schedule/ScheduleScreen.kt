package com.fangyi.classpp.ui.schedule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.theme.ClassppTheme
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.util.Date

/**
 * 课表页：可折叠头部（顶栏行/日期行/星期行）+ 课表网格。
 * 折叠状态由 [CollapseState] 的 NestedScrollConnection 驱动，跟手折叠/展开。
 *
 * 网格与顶栏为叠层（Box）：网格铺满全屏并经 contentPadding.top 让位于顶栏，
 * 折叠后内容从顶栏背后滚过，顶栏通过 Haze 对其做背景模糊（随日期带渐隐同步渐入）。
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

    val listState = rememberLazyListState()
    val hazeState = rememberHazeState()

    // 顶栏实时总高（含状态栏 inset）→ 网格 contentPadding.top；
    // 初值为估算，onGloballyPositioned 在首帧绘制前即会校正
    var headerHeight by remember { mutableStateOf(168.dp) }
    val bandHeightPx = with(density) { DateBandHeight.toPx() }
    // 与日期带渐隐同步：index==0 时 offset/bandHeight 爬升，带滚过后恒为 1。
    // derivedStateOf 只在输出变化时通知 → 渐隐窗口外零新增重组
    val blurProgress by remember(bandHeightPx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex == 0) {
                (listState.firstVisibleItemScrollOffset / bandHeightPx).coerceIn(0f, 1f)
            } else {
                1f
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // 顶部状态栏 inset 由头部自行吸收；底部不留白，网格直接延伸到导航栏（小白条）之下
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            CourseGrid(
                courses = courses,
                timeSlots = timeSlots,
                weekDates = weekDates,
                today = today,
                state = listState,
                // 滚动到底时最后一行可停在小白条上方，网格背景仍铺满屏幕底缘；
                // top 跟随顶栏高度，折叠期视觉与原先 Column 上推一致
                contentPadding = PaddingValues(
                    top = headerHeight,
                    bottom = WindowInsets.navigationBars
                        .only(WindowInsetsSides.Bottom)
                        .asPaddingValues()
                        .calculateBottomPadding(),
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(collapseState.nestedScrollConnection)
                    .hazeSource(hazeState),
            )
            ScheduleHeader(
                collapseFraction = collapseState.collapseFraction,
                date = today,
                selectedWeek = selectedWeek,
                onWeekSelected = { selectedWeek = it },
                onEditClick = { /* TODO: 编辑/切换课表 */ },
                onSettingsClick = { /* TODO: 设置页 */ },
                onMenuExpandedChange = { collapseState.menuOpen = it },
                blurProgress = blurProgress,
                hazeState = hazeState,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .onGloballyPositioned { coords ->
                        headerHeight = with(density) { coords.size.height.toFloat().toDp() }
                    },
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
