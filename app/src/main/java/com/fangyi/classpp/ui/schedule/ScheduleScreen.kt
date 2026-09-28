package com.fangyi.classpp.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.data.TermPosition
import com.fangyi.classpp.ui.navigation.NavReserve
import com.fangyi.classpp.ui.theme.ClassppTheme
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.util.Date
import kotlin.math.abs

/**
 * 课表页：可折叠头部（顶栏行/日期行/星期行）+ 课表网格。
 * 折叠状态由 [CollapseState] 的 NestedScrollConnection 驱动，跟手折叠/展开。
 *
 * 网格与顶栏为叠层（Box）：网格铺满全屏并经 contentPadding.top 让位于顶栏，
 * 折叠后内容从顶栏背后滚过，顶栏通过 Haze 对其做背景模糊（随日期带渐隐同步渐入）。
 *
 * 网格内容区是横向 Pager（每周一页，见 [CourseGrid]）：左右滑动跟手翻上一周/下一周，
 * 顶栏是叠层里的独立一层，翻页期间位置与内容均不动，只在拖动过半时随周次更新文案。
 *
 * 数据来自 [repository]（null = 尚未加载完成，显示指示器）；
 * 无激活课表时显示空状态，经 [onOpenSettings] 引导至设置页新建。
 */
@Composable
fun ScheduleScreen(
    modifier: Modifier = Modifier,
    repository: ScheduleRepository? = null,
    onOpenSettings: () -> Unit = {},
) {
    // get() 返回即 bootstrap 完成，此分支仅首帧毫秒级；早返回后下方 smart cast 为非空
    if (repository == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    var selectedWeek by rememberSaveable { mutableIntStateOf(0) }
    val today = remember { Date() }

    // State 对象本身稳定（remember），供 Pager 的 pageCount 闭包长期读取；schedule 为当前值
    val scheduleState = repository.activeSchedule.collectAsState()
    val schedule = scheduleState.value

    // 选周：0=未初始化 → 学期中取当前周、学期后取末周、学期前取第 1 周；
    // 学期起止被设置页修改后自动钳制到新范围
    val week = when {
        schedule == null -> 1
        selectedWeek == 0 -> when (val position = repository.termPosition(schedule.id)) {
            is TermPosition.InTerm -> position.week
            TermPosition.AfterTerm -> schedule.totalWeeks
            else -> 1
        }
        else -> selectedWeek.coerceIn(1, schedule.totalWeeks)
    }
    LaunchedEffect(week) {
        if (selectedWeek != week) selectedWeek = week
    }

    // 本周（仅学期中有）：供周数弹窗「本周」方块浅蓝高亮；学期起止变更随 schedule 换新而重算
    val currentWeek: Int? = if (schedule != null) {
        remember(schedule) {
            (repository.termPosition(schedule.id) as? TermPosition.InTerm)?.week
        }
    } else {
        null
    }

    // 顶栏日期：查看本周显示今天，查看其它周（学期外一律算「其它周」）显示该周周一；
    // 头部日期标题与星期高亮均由 date 驱动，随之联动（格式仍为 #月#日 周X）
    val headerDate = if (schedule != null && week != currentWeek) {
        remember(schedule, week) {
            repository.datesForWeek(schedule.id, week).first().toUiDate()
        }
    } else {
        today
    }

    // 节次：结构性数据，随 schedule 换新而变；实例只建一次，避免翻页期三页无谓重组
    val timeSlots = if (schedule != null) {
        remember(schedule) { schedule.slots.toUiSlots() }
    } else {
        emptyList()
    }

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
        if (schedule == null) {
            // 空状态：不组合头部与网格，经按钮引导至设置页新建课表
            EmptyScheduleContent(
                onOpenSettings = onOpenSettings,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            // ——— 内容区横向翻周 ———
            // 初始页即目标周：Pager 只在有课表时创建，避免首帧从第 1 周跳变到当前周。
            // pageCount 刻意读 State 而非局部值：该闭包在创建时被 Pager 捕获，
            // 读局部值会让学期起止修改后的周数变化（如 20 → 25 周）不再生效
            val pagerState = rememberPagerState(initialPage = week - 1) {
                scheduleState.value?.totalWeeks ?: 1
            }

            // 每周页内容（纯函数、无 I/O）：key=schedule 覆盖置灰开关/时段/学期等全部变更源——
            // 任意 mutator 成功都会发布新的 Schedule 实例，记忆随之失效、同帧刷新
            val contentForWeek: (Int) -> WeekPageContent = remember(schedule, currentWeek, today) {
                { pageWeek ->
                    val dates = repository.datesForWeek(schedule.id, pageWeek)
                    WeekPageContent(
                        week = pageWeek,
                        courses = repository.visibleCoursesForWeek(schedule.id, pageWeek)
                            .map { it.toUiCourse() },
                        // 7 天模式只渲染前 5 天（网格仍为 5 列硬编码，视图批次再扩展）
                        dates = dates.take(5).map { it.toUiDate() },
                        // 与顶栏日期同规则：查看本周高亮今天，其它周高亮该周周一
                        highlightDate = if (pageWeek == currentWeek) {
                            today
                        } else {
                            dates.first().toUiDate()
                        },
                    )
                }
            }

            // 手势 → 周次：currentPage 在拖动过半时即翻转，顶栏周数胶囊与日期随之切换；
            // 写回同值时 State 自身忽略，不产生额外重组
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.currentPage }.collect { selectedWeek = it + 1 }
            }

            // 周次 → 翻页：周数弹窗、返回本周、学期范围钳制触发的换周。
            // 相邻周走滑动动画（与手势翻页连贯），跨多周直接落位（与原先的瞬时换周一致）
            LaunchedEffect(week) {
                val target = week - 1
                val current = pagerState.currentPage
                if (current != target) {
                    if (abs(current - target) == 1) {
                        pagerState.animateScrollToPage(target)
                    } else {
                        pagerState.scrollToPage(target)
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                CourseGrid(
                    pagerState = pagerState,
                    timeSlots = timeSlots,
                    contentForWeek = contentForWeek,
                    state = listState,
                    // 滚动到底时最后一行可停在导航栏胶囊上方，网格背景仍铺满屏幕底缘；
                    // top 跟随顶栏高度，折叠期视觉与原先 Column 上推一致
                    contentPadding = PaddingValues(
                        top = headerHeight,
                        bottom = WindowInsets.navigationBars
                            .only(WindowInsetsSides.Bottom)
                            .asPaddingValues()
                            .calculateBottomPadding() + NavReserve,
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(collapseState.nestedScrollConnection)
                        .hazeSource(hazeState),
                )
                ScheduleHeader(
                    collapseFraction = collapseState.collapseFraction,
                    date = headerDate,
                    selectedWeek = week,
                    currentWeek = currentWeek,
                    onWeekSelected = { selectedWeek = it },
                    onEditClick = { /* TODO: 编辑/切换课表 */ },
                    onSettingsClick = onOpenSettings,
                    onMenuExpandedChange = { collapseState.menuOpen = it },
                    weekRange = 1..schedule.totalWeeks,
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
}

/** 无激活课表时的空状态：标题 + 说明 + 新建按钮（打开设置页） */
@Composable
private fun EmptyScheduleContent(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.empty_title),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_desc),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpenSettings) {
            Text(stringResource(R.string.create_schedule))
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
