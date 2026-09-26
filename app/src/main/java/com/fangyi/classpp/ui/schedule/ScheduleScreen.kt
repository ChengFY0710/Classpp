package com.fangyi.classpp.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.theme.ClassppTheme
import java.util.Date

/**
 * 课表页：可折叠头部（顶栏行/日期行/星期行）+ 占位可滚动内容。
 * 折叠状态由 [CollapseState] 的 NestedScrollConnection 驱动，跟手折叠/展开。
 */
@Composable
fun ScheduleScreen(modifier: Modifier = Modifier) {
    var selectedWeek by rememberSaveable { mutableIntStateOf(2) }
    val today = remember { Date() }

    val density = LocalDensity.current
    val maxCollapsePx = with(density) { TopBarHeight.toPx() }
    val collapseState = rememberCollapseState(maxCollapsePx)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // 顶部状态栏 inset 由头部自行吸收，这里只保留横向与底部
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
        ),
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
            PlaceholderCourseList(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(collapseState.nestedScrollConnection),
            )
        }
    }
}

/** 占位内容：足够长以演示折叠，接真实课表时替换 */
@Composable
private fun PlaceholderCourseList(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(50) { index ->
            Card(modifier = Modifier.fillMaxWidth().height(88.dp)) {
                Box(modifier = Modifier.fillMaxSize().padding(start = 16.dp)) {
                    Text(
                        text = "占位课程 ${index + 1}",
                        modifier = Modifier.align(Alignment.CenterStart),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
