package com.fangyi.classpp.ui.schedule

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.FieldReason
import com.fangyi.classpp.data.OpResult
import com.fangyi.classpp.data.ReadResult
import com.fangyi.classpp.data.ScheduleError
import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.data.ScheduleValidator
import com.fangyi.classpp.data.TodoFilter
import com.fangyi.classpp.data.TodoReadResult
import com.fangyi.classpp.data.TodoRepository
import com.fangyi.classpp.data.TodoSort
import com.fangyi.classpp.data.ai.AiChatRepository
import com.fangyi.classpp.data.ai.AiError
import com.fangyi.classpp.data.ai.AiScheduleRecognition
import com.fangyi.classpp.data.expandRepeatOccurrences
import com.fangyi.classpp.data.export.ShareCodec
import com.fangyi.classpp.data.filterTodos
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.ScheduleFile
import com.fangyi.classpp.data.model.overlappingCourses
import com.fangyi.classpp.data.sync.AvatarImage
import com.fangyi.classpp.data.sync.ImportBlockReason
import com.fangyi.classpp.data.sync.ImportError
import com.fangyi.classpp.data.sync.ImportOutcome
import com.fangyi.classpp.data.sync.ImportSemester
import com.fangyi.classpp.data.sync.ScheduleImportService
import com.fangyi.classpp.data.sync.SchoolRegistry
import com.fangyi.classpp.data.sync.SemestersOutcome
import com.fangyi.classpp.data.sync.SessionSlot
import com.fangyi.classpp.data.withMatchedCourseTag
import com.fangyi.classpp.data.wakeup.WakeupImportExecutor
import com.fangyi.classpp.data.wakeup.WakeupScheduleMapper
import com.fangyi.classpp.data.wakeup.WakeupScriptStore
import com.fangyi.classpp.data.wakeup.WakeupShareText
import com.fangyi.classpp.data.sortedFor
import com.fangyi.classpp.ui.LocalToday
import com.fangyi.classpp.ui.LocalTodayState
import com.fangyi.classpp.ui.components.AccountStatusUi
import com.fangyi.classpp.ui.components.BottomFadeScrim
import com.fangyi.classpp.ui.components.EmptyState
import com.fangyi.classpp.ui.components.EmptyStateActionButton
import com.fangyi.classpp.ui.components.rememberHazeRetreat
import com.fangyi.classpp.ui.components.rememberPopupActionGate
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.TabTransitionState
import com.fangyi.classpp.ui.navigation.NavReserve
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.isGlassStyleEnabled
import com.fangyi.classpp.ui.theme.rememberGlassBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [selectedWeek] 的「未初始化」哨兵：0 与负数都是合法周号（开学前的日历周），故取最小值 */
private const val WEEK_UNSET = Int.MIN_VALUE

/**
 * 页索引 ↔ 周号换算：最左页（页 0）= [firstPageWeek]——开学前是今周所在的日历周
 * （原始周号 ≤ 0，天然不可左越），学期中/后是第 1 周——向右每周一页直到学期最后一周。
 */
private fun pageOfWeek(week: Int, firstPageWeek: Int): Int = week - firstPageWeek

private fun weekOfPage(page: Int, firstPageWeek: Int): Int = page + firstPageWeek


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
 * [editing] 为编辑态：顶栏换成固定编辑栏（保存 / 切换课表 / 取消），日期带隐藏、翻周停用、
 * 底部导航栏由调用方隐藏；空位渲染添加卡片，点它开弹窗加课。改动只落进
 * [ScheduleEditSession] 的草稿，点保存才写回仓库，取消（或返回键）整份丢弃。
 *
 * [onOverlayOverNavBarChange]：需要盖住底部导航栏的全屏浮层（课程详情/新建课表/
 * 教务课表网页）在场与否。导航栏在组合顺序上位于本页之上、会画在浮层头顶，故由调用方以
 * 「藏」实现「盖」——浮层入场即上报、退场动画播完（卸载）才解除。
 *
 * [tabTransition]：tab 转场状态只读视图（ui.navigation 的 TabPager 状态机）。非空时顶栏与
 * 编辑栏启用「转场冻结层」——转场进行中重放就位帧、绕开 haze 对中间态几何的重采样，
 * 杜绝快速切 tab 时顶栏毛玻璃闪白/闪黑（详见 ui.motion 的 transitionFreeze）。
 *
 * 数据来自 [repository]（null = 尚未加载完成，显示指示器）；
 * 无激活课表时显示空状态，「创建课表」按钮打开 [NewScheduleSheet] 新建首份课表。
 */
@Composable
fun ScheduleScreen(
    modifier: Modifier = Modifier,
    repository: ScheduleRepository? = null,
    todoRepository: TodoRepository? = null,
    editing: Boolean = false,
    onEditingChange: (Boolean) -> Unit = {},
    onOverlayOverNavBarChange: (Boolean) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    // 教务导入浮层「账号与同步」：收起浮层串、编辑态取消更改后由调用方转到设置页-账号与同步
    onOpenAccountSync: () -> Unit = {},
    // AI 识别导入浮层的数据源与设置出口：未配置引导态读 configured，按钮直达设置页 AI 子页
    aiChatRepository: AiChatRepository? = null,
    onOpenAiSettings: () -> Unit = {},
    // 学校注册表：教务导入的账号状态、拦截原因与取数编排全部来自活跃会话的
    // `scheduleImport` 槽位（可选能力）。null = 容器未装配，按未连接展示
    schools: SchoolRegistry? = null,
    tabTransition: TabTransitionState? = null,
    // 课程卡片高度倍率状态（MainActivity 持有、个性化页写入；网格行高随每节时长伸缩，
    // 倍率整体缩放）：State 实例透传给 CourseGrid，本屏不读 .value——滑条拖动时
    // ScheduleScreen 整体 skip，只有 CourseGrid 重组重排
    cardHeights: State<CardHeightScales> = mutableStateOf(CardHeightScales()),
    // 课程卡片「显示教师」开关（MainActivity 持有、个性化页写入）：State 实例透传纪律同上
    showTeacher: State<Boolean> = mutableStateOf(true),
) {
    // get() 返回即 bootstrap 完成，此分支仅首帧毫秒级；早返回后下方 smart cast 为非空
    if (repository == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    var selectedWeek by rememberSaveable { mutableIntStateOf(WEEK_UNSET) }

    // 「今天」动态跟随：跨天自动前进（前台挂过夜、后台存活数日）——共享源见
    // [rememberTodayState]（ClassppTheme 注入，待办页同源）。读 State 而非裸值：
    // Date 语义取本地正午——日期比较与日号显示不受时刻影响
    val todayIsoState = LocalTodayState.current
    val todayIso = todayIsoState.value
    val today = todayIso.toUiDate()

    // State 对象本身稳定（remember），供 Pager 的 pageCount 闭包长期读取；schedule 为当前值
    val scheduleState = repository.activeSchedule.collectAsState()
    val schedule = scheduleState.value
    // 全部课表（切换浮层列表 + 落库退让的回流信号）：无条件收集——退让窗口不随浮层
    // 挂载态断开，列表重写后的回流总能被 onReflow 看见
    val schedulesState = repository.schedules.collectAsState()
    // 切换课表浮层的 Haze 退让：列表行增删（删除 / 新建的落库回流）就是该浮层顶栏磨砂的
    // 采样源重写，窗口内整条链卸载、回流落定两帧后挂回（机制见 HazeRetreatState）。
    // 回流信号走 snapshotFlow 而非组合期读值：它只喂状态机，不该驱动本屏重组
    val switcherHazeRetreat = rememberHazeRetreat()
    LaunchedEffect(schedulesState) {
        snapshotFlow { schedulesState.value }.collect { switcherHazeRetreat.onReflow() }
    }
    // 待办仓库仅供课程详情浮层展示该课程标签下的待办；未注入（预览/首帧）视为空列表
    val todosState = todoRepository?.todos?.collectAsState()
    // 展示用待办：课程中心同步的待办匹配到激活课表课程时直接并入对应课程标签——与待办页/
    // 日视图同一派生口径（见 withMatchedCourseTag），课程详情浮层按课程名关联待办直接命中
    val rawTodos = todosState?.value ?: emptyList()
    val courseTagNameSet = remember(schedule) { schedule?.courses?.map { it.name }?.toSet() ?: emptySet() }
    val todos = remember(rawTodos, courseTagNameSet) {
        rawTodos.map { it.withMatchedCourseTag(courseTagNameSet) }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 新建课表浮层（无激活课表的空态专用）：开关在空态分支（按钮打开）与 Scaffold 末尾
    // （承载块关闭）两处读写，挂在页面顶层；挂载与可见性分离的两段式关闭同其他浮层
    var newScheduleVisible by rememberSaveable { mutableStateOf(false) }
    var newScheduleMounted by remember { mutableStateOf(false) }

    // ——— 编辑态事务：保存提交 / 取消整库回滚 ———
    // 进入编辑时整库快照一次（全部课表 + 激活 id；JSON 存 saveable，进程重建后取消仍可回滚）。
    // 编辑期间的切换/设置/新建/删除/导入照旧即时落库，取消时以快照一次性恢复，保存则自然保留；
    // 方案库（timeSchemes）不在回滚范围：保存方案在浮层内确认即持久化，不受编辑保存/取消控制
    var editBaselineJson by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(editing) {
        if (editing) {
            // 进程死亡重建：基线已随 saveable 回来，不重拍（重拍会把切换/设置的中间态固化为基线）
            if (editBaselineJson.isEmpty()) {
                editBaselineJson = ScheduleJson.encodeStorage(repository.snapshot())
            }
        } else {
            editBaselineJson = ""
        }
    }

    val persistFailedToast = stringResource(R.string.error_persist_failed)

    // 取消编辑：恢复进入编辑前的整库快照（切换/设置/新建/删除/导入一并回滚），再退出编辑态。
    // 方案库除外：保存方案在浮层内确认即落库，不随编辑取消回滚（见 restoreEditBaseline）。
    // 基线先置空：连点取消/返回不会触发两次恢复；恢复失败仅 Toast（磁盘写失败极罕见），仍退出
    fun cancelEdit() {
        val baseline = editBaselineJson
        editBaselineJson = ""
        if (baseline.isEmpty()) {
            onEditingChange(false)
            return
        }
        scope.launch {
            val restored = repository.restoreEditBaseline(
                ScheduleJson.decodeStorage<ScheduleFile>(baseline),
            )
            if (restored is OpResult.Err) {
                Toast.makeText(
                    context,
                    persistFailedToast,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            onEditingChange(false)
        }
    }

    // 无激活课表（如从切换浮层删空全部课表）时编辑态已无意义：网格与编辑栏都随之消失，
    // 若停留在编辑态，底部导航栏会一直隐藏（它的显隐跟着编辑态走）——走取消路径自动退出。
    // 删除已同步移出编辑基线（不可撤销），回滚不会使被删课表复活，退出后直接落空态
    LaunchedEffect(schedule) {
        if (schedule == null && editing) cancelEdit()
    }

    // 今周原始周号（按日历周对齐，第 1 周 = 开学所在日历周）：开学前 ≤ 0——开学前的每个
    // 日历周都是一页「第 0 周」，最左页始终是当今周；学期后 > 总周数 → 无今周（默认停末周）
    val todayWeek: Int? = schedule?.let { s ->
        (((todayIso.mondayOfWeek() - s.termStart.mondayOfWeek()) / 7).toInt() + 1)
            .takeIf { it <= s.totalWeeks }
    }
    // 最左页的周号：开学前 = 今周（第 0 周区段的第一页），学期中/后 = 第 1 周
    val firstPageWeek: Int = if (todayWeek != null && todayWeek < 1) todayWeek else 1

    // 选周：WEEK_UNSET=未初始化 → 落到今周（学期后取末周）；学期起止被设置页修改后
    // 自动钳制到新范围（下界 = 最左页的周号）
    val week = when {
        schedule == null -> 1
        selectedWeek == WEEK_UNSET -> todayWeek ?: schedule.totalWeeks
        else -> selectedWeek.coerceIn(firstPageWeek, schedule.totalWeeks)
    }
    LaunchedEffect(week) {
        if (selectedWeek != week) selectedWeek = week
    }

    // 顶栏日期：查看今周（含开学前的第 0 周）显示今天，其它周显示该周周一；
    // 头部日期标题与星期高亮均由 date 驱动，随之联动（格式仍为 #月#日 周X）
    val headerDate = if (schedule != null && week != todayWeek) {
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
    // 顶栏周数胶囊的液态玻璃：设置页「更高级的玻璃材质」开关 + API 31+（RenderEffect）
    // 双门控，关闭即原实心底（blurProgress 渐变的扁平灰底→surface）。采样层 = 页面
    // 底色 + 课程网格——网格从顶栏下滚过，玻璃才有东西可折射（底色必须画进采样层，
    // 否则漏透明像素）；胶囊在采样节点之外，玻璃不会照见自己。采样与顶栏的 Haze
    // 磨砂同源（hazeSource / layerBackdrop 挂同一节点），两套采样互不干扰
    val glassPillEnabled = isGlassStyleEnabled()
    val weekPillBackdrop = rememberGlassBackdrop(MaterialTheme.colorScheme.background)

    // 顶栏实测高度（含状态栏 inset）→ 网格顶部留白。两种模式各存一份：
    // 编辑转场期间 CourseGrid 按 editProgress 在两个实测值之间插值，网格随顶栏
    // 平滑上滑收拢；首次进入该模式用估算值，onGloballyPositioned 在首帧绘制前即会校正
    var headerHeight by remember { mutableStateOf(HeaderHeightGuess) }
    var editBarHeight by remember { mutableStateOf(EditBarHeightGuess) }
    val bandHeightPx = with(density) { DateBandHeight.toPx() }
    // 与日期带渐隐同步：index==0 时 offset/bandHeight 爬升，带滚过后恒为 1。
    // 保持为 State 原样下传（不在组合期 by 委托取值）：顶栏 / 编辑栏在绘制期与
    // Haze Style 块内读它，渐隐窗口内滚动不触发重组，也不重建 HazeBlurStyle
    val blurProgressState = remember(bandHeightPx) {
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
        // 统一不避让横向 inset：内容全宽出血；纵向由头部与网格自理
        contentWindowInsets = WindowInsets(0),
    ) { contentPadding ->
        // contentWindowInsets = WindowInsets(0)：padding 恒为 0；显式消费以满足 Scaffold
        // 的内容内距约定，纵向 inset 仍由头部与网格自理
        if (schedule == null) {
            // 空状态：不组合头部与网格，「创建课表」按钮打开新建课表浮层
            EmptyScheduleContent(
                onCreateSchedule = { newScheduleVisible = true },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            )
        } else {
            // ——— 内容区横向翻周 ———
            // 初始页即目标周：Pager 只在有课表时创建，避免首帧从第 1 周跳变到当前周。
            // pageCount 刻意读 State 而非局部值：该闭包在创建时被 Pager 捕获，
            // 读局部值会让学期起止修改后的周数变化（如 20 → 25 周）不再生效
            val pagerState = rememberPagerState(initialPage = pageOfWeek(week, firstPageWeek)) {
                // 闭包创建时捕获：读 State + 现算最左页，页数才跟得上学期起止修改、开学翻转
                // 与跨天（今周前进）。todayIso 必须经 State 现读——捕获普通值会冻结在创建那一刻
                scheduleState.value?.let { s ->
                    val cw = repository.calendarWeek(s.id, todayIsoState.value)
                    val first = if (cw != null && cw < 1) cw else 1
                    s.totalWeeks - first + 1
                } ?: 1
            }

            // 每周页内容（纯函数、无 I/O）：key=schedule 覆盖置灰开关/时段/天数/学期等全部变更源——
            // 任意 mutator 成功都会发布新的 Schedule 实例，记忆随之失效、同帧刷新。
            // 入参是**页索引**：页 → 周的换算在此处做（CourseGrid 不感知周号）
            val contentForPage: (Int) -> WeekPageContent = remember(schedule, todayWeek, today) {
                { page ->
                    val pageWeek = weekOfPage(page, firstPageWeek)
                    val dates = repository.datesForWeek(schedule.id, pageWeek)
                    WeekPageContent(
                        week = pageWeek,
                        // 先解析再按开关过滤：同格只留当周那张卡，非本周的交替课只剩色条，
                        // 关掉「显示本周不上的课」也照旧看得到这格还有别的课
                        // （第 0 周 = 开学前的当今周，整页视为不上课 → 全灰/按开关隐藏）
                        courses = repository.coursesForWeek(schedule.id, pageWeek)
                            .toWeekCards(schedule.showInactiveCourses),
                        // 天数即列数：仓库按 daysPerWeek 返回 5 或 7 个日期，网格与星期行同步列数
                        dates = dates.map { it.toUiDate() },
                        // 与顶栏日期同规则：查看今周（含第 0 周）高亮今天，其它周高亮该周周一
                        // （越界周 dates 为空时退回今天，页内无日期带可高亮）
                        highlightDate = if (pageWeek == todayWeek) {
                            today
                        } else {
                            (dates.firstOrNull() ?: IsoDate.today()).toUiDate()
                        },
                        // 高亮列是今天 → 日期带给它垫胶囊底；浏览其它周只变色不加底
                        highlightIsToday = pageWeek == todayWeek,
                        // 学期起止日标记：日期带里出现时绿/红字（恰为今天时带内自动让位给蓝字胶囊底）
                        termStartDate = schedule.termStart.toUiDate(),
                        termEndDate = schedule.termEnd.toUiDate(),
                    )
                }
            }

            // ——— 编辑态：草稿只活在 UI 层，点保存才写回仓库 ———
            // 会话随 editing 重开（进来时取当前课表），退出即换成占位会话、草稿丢弃；
            // Saver 连 active 一起存，故旋转/进程重建不会把恢复出来的草稿清掉。
            // inputs 含 schedule.id：切换激活课表时 inputs 变化 → 旧会话连同草稿一起
            // 被丢弃、以新课表的课程快照重建——切换即隐式"丢弃旧草稿、开始编辑新课表"。
            // 会话处于编辑态事务内（见顶部 editBaselineJson）：其余课表/设置的改动虽即时
            // 落库，取消时都会随整库快照一并回滚，保存则随课程草稿一起保留
            val session: ScheduleEditSession = rememberSaveable(
                editing,
                schedule.id,
                saver = ScheduleEditSession.Saver,
            ) {
                // 已落库的单次调课同样进草稿：否则编辑态网格看不到调课卡、设置调课列表
                // 显示为空，点保存还会用空列表整份写回，把已有调课全部清掉
                if (editing) {
                    ScheduleEditSession(
                        draft = schedule.courses,
                        draftAdjustments = schedule.adjustments,
                    )
                } else {
                    ScheduleEditSession.Inactive
                }
            }
            val editSession: ScheduleEditSession? = session.takeIf { it.active }
            // 编辑态内容过渡进度：0 = 浏览、1 = 编辑，驱动网格整体上滑收拢、日期带折叠
            // 与空位加号卡的渐次浮现。与顶栏换场共用 [Motion.EditMillis]（本进度配
            // [Motion.Overlay]，顶栏换场配更缓的 [Motion.Standard]），同一个 editing
            // 翻转同帧启动；Animatable 互斥且从当前值续播——转场中途
            // 反向操作（如快速取消再进入）会就地反向，不会跳帧
            val editContentProgress = remember {
                Animatable(if (editSession != null) 1f else 0f)
            }
            // 传给网格的是只读 State：Animatable 本身是 State<Float, AnimationVector1D>，
            // 与 State<Float> 不同型；remember 一次避免每帧新建包装对象
            val editContentState = remember(editContentProgress) {
                editContentProgress.asState()
            }
            // 两段式挂载（同 OverlaySheet 的 mounted 手法）：退场期间加号卡保持在场、
            // 随进度淡出，播完才卸载；否则退场第一帧卡片就会凭空消失
            var editContentMounted by remember { mutableStateOf(editSession != null) }
            LaunchedEffect(editSession != null) {
                val entering = editSession != null
                if (entering) editContentMounted = true
                editContentProgress.animateTo(
                    targetValue = if (entering) 1f else 0f,
                    animationSpec = tween(Motion.EditMillis, easing = Motion.Overlay),
                )
                if (!entering) editContentMounted = false
            }
            // 待添加的格子：[dayOfWeek, slotId]；空 = 添加面板未打开
            var addTarget by rememberSaveable { mutableStateOf(listOf<Int>()) }
            // 正在编辑的课程 id（点已有课卡进入）；空 = 编辑面板未打开。
            // 编辑条目从草稿反查，删除后反查落空 → 面板自动关闭
            var editTargetId by rememberSaveable { mutableStateOf("") }
            // 课程详情浮层：被点课程 id（浏览态点课卡进入）；空 = 未打开。
            // 编辑态点课卡仍开编辑面板，路由见 onEditClick；条目从当前课表反查。
            // 点的是调课卡时另外记住调课 id：详情展示目标时间并补一行原课次
            var detailTargetId by rememberSaveable { mutableStateOf("") }
            var detailAdjustmentId by rememberSaveable { mutableStateOf("") }
            // 长按菜单：被长按卡的 id + 窗口坐标；空 = 菜单未打开。
            // 刻意不 rememberSaveable——菜单是瞬时 UI，旋转重建就收起（Rect 也不可存）
            var menuAnchor by remember { mutableStateOf<CardMenuAnchor?>(null) }
            // 菜单可见性，与锚点拆两态：收起只翻它，锚点保留到退场播完（onExitFinished 清）——
            // 退场 150ms 里菜单内容稳定、行点击动作仍可命中（动作用得上锚点里的 id/坐标）
            var menuVisible by remember { mutableStateOf(false) }
            // 选择要编辑的交替课程：被点卡片的 id；空 = 弹窗未打开
            var chooserSourceId by rememberSaveable { mutableStateOf("") }
            // 新建交替课程：源课 id（长按菜单进来）；空 = 面板未打开
            var alternateSourceId by rememberSaveable { mutableStateOf("") }
            // 调课编辑面板：课程 id + 原课次周 + 已有调课 id（空 = 新建）。
            // 与 AddCoursePanel 同款「id 状态 → 请求 → 两段式挂载」，旋转重建后仍能恢复
            var adjustCourseId by rememberSaveable { mutableStateOf("") }
            var adjustFromWeek by rememberSaveable { mutableStateOf(0) }
            var adjustExistingId by rememberSaveable { mutableStateOf("") }
            // 调课列表浮层（设置 → 设置调课）：可见性 + 挂载态两段式
            var adjustListVisible by rememberSaveable { mutableStateOf(false) }
            var adjustListMounted by remember { mutableStateOf(false) }
            // 整天调休浮层（设置 → 设置调休）：可见性 + 挂载态两段式
            var dayAdjustVisible by rememberSaveable { mutableStateOf(false) }
            var dayAdjustMounted by remember { mutableStateOf(false) }
            // 课程剪贴板：被复制课程的完整快照。不随编辑会话/课表清除——退出编辑再进、
            // 甚至切换课表后仍可粘贴；源课被删不受影响（快照语义），再复制即覆盖
            var copiedEntry by rememberSaveable(stateSaver = CopiedEntrySaver) {
                mutableStateOf<CourseEntry?>(null)
            }
            // 空位长按菜单：被长按格 [dayOfWeek, slotId] + 格子窗口坐标；空 = 菜单未打开。
            // 刻意不 rememberSaveable——菜单是瞬时 UI，旋转重建就收起（Rect 也不可存），同 menuAnchor
            var pasteAnchor by remember { mutableStateOf<SlotMenuAnchor?>(null) }
            // 可见性同 menuVisible：收起只翻它，锚点保留到退场播完
            var pasteVisible by remember { mutableStateOf(false) }
            // 粘贴 = 同帧往课表网格（被采样源）加课的结构重写，菜单退场活采样期间不可撞：
            // 动作延后到退场播完（PopupActionGate，机制见 PopupMenuPopup KDoc）
            val pasteGate = rememberPopupActionGate()
            // 切换课表浮层：开关、待切换目标（非空 = 脏草稿确认打开）、新建表单浮层开关
            //（叠在切换课表上，两段式挂载见浮层处；新建失败走系统 Toast，无表单内联错误）
            var switcherVisible by rememberSaveable { mutableStateOf(false) }
            var pendingSwitchId by rememberSaveable { mutableStateOf("") }
            var switcherCreateVisible by rememberSaveable { mutableStateOf(false) }
            // 导入课表浮层：编辑栏「导入」打开（不依赖切换浮层是否曾打开过）
            var importSheetVisible by rememberSaveable { mutableStateOf(false) }
            // Wakeup 口令导入浮层：导入浮层第三入口打开，叠在导入浮层之上（同分层动效）
            var wakeupSheetVisible by rememberSaveable { mutableStateOf(false) }
            // 教务导入浮层：导入浮层第一入口打开，叠在导入浮层之上（同分层动效）
            var schoolImportVisible by rememberSaveable { mutableStateOf(false) }
            // AI 识别导入浮层：导入浮层第一张入口卡打开，叠在导入浮层之上（同分层动效）
            var aiImportVisible by rememberSaveable { mutableStateOf(false) }
            // 教务课表页（WebView 登录模式）：教务导入浮层第二张卡打开，全屏盖住课表页
            var schoolTimetableVisible by rememberSaveable { mutableStateOf(false) }
            // 活跃学校会话（fallback flow 保证 collectAsState 无条件调用，同设置页）：
            // 教务导入是可选能力——`scheduleImport == null` = 该校不实现，入口隐藏
            val fallbackSchoolSlot = remember { MutableStateFlow<SessionSlot>(SessionSlot.Loading) }
            val activeSchoolSlot by
                (schools?.activeSession ?: fallbackSchoolSlot).collectAsState()
            val schoolSession = (activeSchoolSlot as? SessionSlot.Ready)?.session
            // 教务账号连接状态文案（与设置页同口径：uid 非空即已连接，姓名缺省只显示「已连接」）
            val schoolSyncState = schoolSession?.sync?.state?.collectAsState()?.value
            // 已连接账户状态卡（设置页同款）：头像随同步下载。fallback flow 保证
            // collectAsState 无条件调用（同上面的 slot）
            val fallbackAvatarImage = remember { MutableStateFlow<AvatarImage?>(null) }
            val schoolAvatarImage by
                (schoolSession?.sync?.avatarImage ?: fallbackAvatarImage).collectAsState()
            // 未连接（uid 为空）→ null：浮层不渲染状态卡，只留「账号与同步」行
            val schoolAccountCard: AccountStatusUi? = schoolSyncState
                ?.takeIf { it.uid.isNotEmpty() }
                ?.let { state ->
                    AccountStatusUi(
                        accountName = state.accountName,
                        lastSyncAtMillis = state.lastSyncAtMillis,
                        avatarImage = schoolAvatarImage,
                    )
                }
            val schoolAccountStatus = when {
                schoolSyncState == null || schoolSyncState.uid.isEmpty() ->
                    stringResource(R.string.sync_not_connected_label)
                schoolSyncState.accountName.isBlank() ->
                    stringResource(R.string.sync_connected_label)
                else -> stringResource(R.string.sync_connected_name, schoolSyncState.accountName)
            }
            // 教务导入服务（可选槽位）与阻断原因：不再由 UI 自己拼 hasCredentials
            val schoolScheduleImport: ScheduleImportService? = schoolSession?.scheduleImport
            val schoolImportBlockedReason: ImportBlockReason? =
                schoolScheduleImport?.blockedReason?.collectAsState()?.value
            // 网页方式导入：学校为教务导入声明了教务课表页，且当前是 WebView 登录模式
            //（已连接但没有原生凭据）。原生凭据可用（blockedReason == null）时不显示。
            val schoolWebViewTimetable = schoolScheduleImport?.webViewTimetable
            val schoolWebViewImportMode = schoolWebViewTimetable != null &&
                schoolImportBlockedReason == ImportBlockReason.NoCredentials
            // 未连接不额外加横幅：浮层账号行已是「未连接」，与改造前视觉一致；
            // 网页方式导入可用时不显示「不支持」横幅（改用课表页入口）；其余两类给出
            // 说明并拦截确认
            val schoolImportBlockedText: String? = when {
                schoolWebViewImportMode -> null
                schoolImportBlockedReason == ImportBlockReason.NoCredentials ||
                    schoolImportBlockedReason == ImportBlockReason.Unsupported ->
                    stringResource(R.string.sync_import_unsupported)
                else -> null
            }
            // 教务导入浮层「账号与同步」：收起整串浮层（教务导入 → 导入 → 切换），编辑态取消
            // 更改（整库回滚，同编辑栏取消），再转到设置页-账号与同步（调用方直达子页）
            val onSchoolAccountClick: () -> Unit = {
                schoolImportVisible = false
                wakeupSheetVisible = false
                importSheetVisible = false
                switcherVisible = false
                if (editing) cancelEdit()
                onOpenAccountSync()
            }
            // 教务导入浮层「从教务网页导入」：收起整串浮层（教务导入 → 导入 → 切换），
            // 不取消编辑态，直接打开教务课表页；关页面后回到编辑中的课表
            val onSchoolWebViewImportClick: () -> Unit = {
                schoolImportVisible = false
                wakeupSheetVisible = false
                importSheetVisible = false
                switcherVisible = false
                schoolTimetableVisible = true
            }

            // 课表设置浮层：编辑栏按钮打开（内容自原设置页迁入，见 ScheduleSettingsSheet）
            var settingsSheetVisible by rememberSaveable { mutableStateOf(false) }
            // 编辑态的网格数据源：草稿 → 渲染模型（复用仓库路径同一套映射与置灰规则）。
            // 同样收**页索引**，页 → 周换算在 lambda 内做
            val displayedContentForPage: (Int) -> WeekPageContent =
                if (editSession != null) {
                    remember(editSession, schedule, todayWeek, today) {
                        { page ->
                            val pageWeek = weekOfPage(page, firstPageWeek)
                            val dates = repository.datesForWeek(schedule.id, pageWeek)
                            WeekPageContent(
                                week = pageWeek,
                                // 编辑态**一律显示全部课程**（非本周的照旧置灰），不受
                                // 「显示本周不上的课」开关影响：开关关掉时若把它们藏起来，
                                // 用户看不见"占着这一格但本周不上"的课，加课撞上冲突却找不到原因
                                // （第 0 周 = 开学前的当今周，整页课程全部置灰）
                                courses = editSession.courses.toWeekCards(
                                    pageWeek,
                                    editSession.adjustments,
                                ),
                                dates = dates.map { it.toUiDate() },
                                highlightDate = if (pageWeek == todayWeek) {
                                    today
                                } else {
                                    (dates.firstOrNull() ?: IsoDate.today()).toUiDate()
                                },
                                // 高亮列是今天 → 日期带给它垫胶囊底；浏览其它周只变色不加底
                                highlightIsToday = pageWeek == todayWeek,
                                // 编辑态日期带隐藏（showDates=false），标记随数据一起带上保持同构
                                termStartDate = schedule.termStart.toUiDate(),
                                termEndDate = schedule.termEnd.toUiDate(),
                            )
                        }
                    }
                } else {
                    contentForPage
                }

            // 星期行学期标记：查看第 1 周时开学日的星期列绿（Correct）、最后一周时结束日的
            // 星期列红（Error），与下方日期带的同日标记上下对齐（星期行在日期带正上方）。
            // 开始日恒在第 1 周、结束日恒在第 totalWeeks 周（totalWeeks 按所在日历周的周一
            // 相减计）；起止日恰为今天时传 null——星期列让位给「今天」的蓝字高亮。
            // 下标与顶栏 weekIndex 同为周一起始（isoDayOfWeek 1 = 周一）
            val termStartWeekdayIndex: Int? =
                if (week == 1 && schedule.termStart != todayIso) {
                    schedule.termStart.isoDayOfWeek() - 1
                } else {
                    null
                }
            val termEndWeekdayIndex: Int? =
                if (week == schedule.totalWeeks && schedule.termEnd != todayIso) {
                    schedule.termEnd.isoDayOfWeek() - 1
                } else {
                    null
                }

            val onAddClick: (Int, TimeSlot) -> Unit = remember {
                { day, slot -> addTarget = listOf(day, slot.id) }
            }
            // 点已有课卡：浏览态 → 课程详情浮层；编辑态：与之相交的只有一门 → 直接开编辑面板，
            // 多门（span 相交的交替课程，含跨节覆盖的续格课）→ 先让用户点名要编辑哪门，
            // 否则被跨节卡盖住的那几门永远进不去（它们不在本周的网格上）
            val onEditClick: (String, String?) -> Unit = remember(editSession) {
                { courseId, adjustmentId ->
                    val session = editSession
                    if (session == null) {
                        detailTargetId = courseId
                        detailAdjustmentId = adjustmentId ?: ""
                    } else if (adjustmentId != null) {
                        // 点调课卡：直接编辑这条调课（而不是课程本身）
                        val adjustment = session.adjustments.firstOrNull { it.id == adjustmentId }
                        if (adjustment != null) {
                            adjustCourseId = adjustment.courseId
                            adjustFromWeek = adjustment.fromWeek
                            adjustExistingId = adjustment.id
                        }
                    } else {
                        val entry = session.courses.firstOrNull { it.id == courseId }
                        if (entry != null && session.courses.overlappingCourses(
                                entry.dayOfWeek,
                                entry.startSlot,
                                entry.endSlot,
                            ).size > 1
                        ) {
                            chooserSourceId = courseId
                        } else {
                            editTargetId = courseId
                        }
                    }
                }
            }
            // 长按已有课卡 → 弹上下文菜单，锚点用卡片自己的窗口坐标
            val onCourseLongClick: (String, String?, Rect) -> Unit = remember {
                { courseId, adjustmentId, anchor ->
                    menuAnchor = CardMenuAnchor(courseId, adjustmentId, anchor)
                    menuVisible = true
                }
            }
            // 长按空位 → 弹「粘贴课程」菜单；剪贴板为空时无响应（没东西可粘贴）。
            // lambda 里读的是 copiedEntry 的 state 本体，弹出与否始终跟随当前剪贴板
            val onSlotLongClick: (Int, TimeSlot, Rect) -> Unit = remember {
                { day, slot, anchor ->
                    if (copiedEntry != null) {
                        pasteAnchor = SlotMenuAnchor(day, slot.id, anchor)
                        pasteVisible = true
                    }
                }
            }

            // 跨多周跳转的单页滑入进度：1 = 整块网格从右侧一页宽处入场（跳向更后的周）、
            // -1 = 从左侧（跳向更前的周）、0 = 落定。存"页宽倍数"而非像素，图层里乘自身宽度即
            // 得位移，跳多远都恒定一页宽——不途经中间页（途经页的组合成本随距离线性涨，
            // 超远跳转峰值帧会吃紧）
            val jumpSlideFraction = remember { Animatable(0f) }

            // 手势 → 周次：currentPage 在拖动过半时即翻转，顶栏周数胶囊与日期随之切换；
            // 写回同值时 State 自身忽略，不产生额外重组。
            // 页 → 周换算依赖最左页（随开学状态/学期日期变化），把它挂进 key 防闭包捕获过期
            LaunchedEffect(pagerState, firstPageWeek) {
                snapshotFlow { pagerState.currentPage }.collect { page ->
                    selectedWeek = weekOfPage(page, firstPageWeek)
                }
            }

            // 周次 → 翻页：周数弹窗、返回本周、学期范围钳制触发的换周。
            // 相邻周走 Pager 原生滑动（与手势翻页连贯）；跨多周先瞬移落位、再让整块网格从
            // 手势方向滑入一页宽。滑入动画挂在 scope 上而非本效果里：week 途中再变时本效果
            // 会重启，滑入不能被连带取消、停在半程偏移上；snapTo 同时会打断上一次未完成的
            // 滑入，连续跳转天然收敛到最后一次
            LaunchedEffect(week, firstPageWeek) {
                val target = pageOfWeek(week, firstPageWeek)
                val current = pagerState.currentPage
                if (current != target) {
                    if (abs(current - target) == 1) {
                        pagerState.animateScrollToPage(target)
                    } else {
                        val forward = target > current
                        pagerState.scrollToPage(target)
                        scope.launch {
                            jumpSlideFraction.snapTo(if (forward) 1f else -1f)
                            jumpSlideFraction.animateTo(
                                0f,
                                tween(durationMillis = 240, easing = Motion.Decelerate),
                            )
                        }
                    }
                }
            }

            // 点顶栏星期行 = 5 天 / 7 天视图互切（等价于设置页的「每周上课天数」）。
            // 只改天数：周六/周日的课保留在数据里、5 天视图只是不画它们，切回 7 天原样出现，
            // 故切换永远不会失败，也不必弹任何提示。
            val onToggleDaysPerWeek: () -> Unit = {
                val targetDays = if (schedule.daysPerWeek == 7) 5 else 7
                scope.launch { repository.setDaysPerWeek(schedule.id, targetDays) }
            }

            // 保存：先拿"草稿 + 快照"整份预检（给用户可读的原因），再整份写回仓库。
            //
            // 必须整份替换，不能按 diff 逐条 upsert：逐条写在中间态短暂非法时就整轮失败，
            // 而合法的草稿常常必然经过非法中间态——典型的是"把原课周数缩成单周 + 同格再加一门
            // 双周的交替课"：先写新课那一步，仓库里的原课还占着全部周数，于是弹出一场
            // 最终并不存在的"与「原课」时间冲突"。
            val onSaveEdit: () -> Unit = {
                val session = editSession
                if (session != null) {
                    val blocking = ScheduleValidator
                        .validateCourses(
                            schedule.copy(
                                courses = session.courses,
                                adjustments = session.adjustments,
                            ),
                        )
                        .firstOrNull()
                    if (blocking != null) {
                        Toast.makeText(
                            context,
                            blocking.toEditMessage(context),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        scope.launch {
                            val result = repository.replaceCoursesAndAdjustments(
                                schedule.id,
                                session.courses,
                                session.adjustments,
                            )
                            if (result is OpResult.Err) {
                                Toast.makeText(
                                    context,
                                    result.error.toEditMessage(context),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                onEditingChange(false)
                            }
                        }
                    }
                }
            }

            // 切换激活课表：关浮层、清脏确认，并把依赖旧草稿的瞬时状态全部复位——
            // 旧草稿的格子/编辑目标 id 在新课表里没有意义；selectedWeek 复位为未初始化，
            // 让选周逻辑（week 计算）重新按新课表的学期位置落周
            val performSwitch: (String) -> Unit = { id ->
                pendingSwitchId = ""
                switcherVisible = false
                switcherCreateVisible = false
                importSheetVisible = false
                wakeupSheetVisible = false
                aiImportVisible = false
                addTarget = emptyList()
                editTargetId = ""
                chooserSourceId = ""
                alternateSourceId = ""
                menuAnchor = null
                menuVisible = false
                selectedWeek = WEEK_UNSET
                scope.launch {
                    // 先关浮层再落盘：id 取自列表故 NotFound 不可达，落盘失败提示后重开浮层即可重试
                    val r = repository.setActiveSchedule(id)
                    if (r is OpResult.Err) {
                        Toast.makeText(
                            context,
                            r.error.toEditMessage(context),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            // 统一切换入口（行点击 / 导入 / 新建成功）：草稿有改动先弹丢弃确认
            val requestSwitch: (String) -> Unit = { id ->
                if (id != schedule.id) {
                    if (editSession != null && editSession.courses != schedule.courses) {
                        pendingSwitchId = id
                    } else {
                        performSwitch(id)
                    }
                }
            }
            // 导出：当前激活课表 → pretty JSON → 系统分享面板。
            // 读的是仓库已保存状态，草稿里未保存的改动不包含在内（已接受的限制）
            val exportFailedToast = stringResource(R.string.error_unexpected)
            val onExport: () -> Unit = {
                scope.launch {
                    when (val r = repository.exportSchedule(schedule.id)) {
                        is ReadResult.Ok -> {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_TEXT, r.value)
                            }
                            try {
                                context.startActivity(Intent.createChooser(send, null))
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(
                                    context,
                                    exportFailedToast,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                        is ReadResult.Err -> Toast.makeText(
                            context,
                            r.error.toEditMessage(context),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            // 新建：成功先关表单浮层回切换列表再走切换（脏则弹确认；取消确认也不会停在
            // 表单里重复点创建）；失败走系统 Toast（新建失败多为重名/名称非法，表单内容无需改动引导）。
            // 新课表随仓库回流追加到切换浮层列表末尾（采样源结构重写），落库窗口内退让
            val onCreateConfirm: (String, IsoDate, IsoDate) -> Unit = { name, start, end ->
                switcherHazeRetreat.forCommit {
                    scope.launch {
                        when (val r = repository.createSchedule(name, start, end)) {
                            is ReadResult.Ok -> {
                                switcherCreateVisible = false
                                requestSwitch(r.value)
                            }
                            is ReadResult.Err -> AppToasts.show(context, r.error.toEditMessage(context))
                        }
                    }
                }
            }
            // 删除课表：仓库删除（删激活项自动回落到剩余第一张，全部数据层校验已就绪）；
            // 删的是当前激活课表时周选择复位防越界（同 performSwitch 的复位思路）；
            // 删空则关浮层回无课表空态（新建课表页）。
            // 删除为最终态（弹窗已告知不可撤销）：同步把被删课表从编辑基线里移除——
            // 取消编辑的整库回滚因此不会复活它，其余改动照旧可回滚。
            // 被删行随仓库回流从切换浮层列表消失（删空为 N→0），落库窗口内整条 Haze 链退让
            val onDeleteSchedule: (String) -> Unit = { id ->
                switcherHazeRetreat.forCommit {
                    scope.launch {
                        when (val r = repository.deleteSchedule(id)) {
                            is OpResult.Ok -> {
                                if (id == schedule.id) selectedWeek = WEEK_UNSET
                                if (repository.schedules.value.isEmpty()) {
                                    switcherVisible = false
                                    switcherCreateVisible = false
                                }
                                if (editing && editBaselineJson.isNotEmpty()) {
                                    val baseline =
                                        ScheduleJson.decodeStorage<ScheduleFile>(editBaselineJson)
                                    editBaselineJson = ScheduleJson.encodeStorage(
                                        baseline.copy(
                                            schedules = baseline.schedules.filterNot { it.id == id },
                                            // 基线激活 id 指向被删课表时回落到剩余第一张（同仓库语义）
                                            activeScheduleId = baseline.activeScheduleId?.takeIf { it != id }
                                                ?: baseline.schedules.firstOrNull { it.id != id }?.id,
                                        ),
                                    )
                                }
                            }
                            is OpResult.Err -> AppToasts.show(context, r.error.toEditMessage(context))
                        }
                    }
                }
            }
            // 导入：系统文件选择器读 JSON → 仓库导入（新 id、名称去重、不自动激活）→
            // 走统一切换路径（含脏确认）；取消确认则新课表留在列表里但不激活
            val importReadFailedToast = stringResource(R.string.import_read_failed)
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    scope.launch {
                        val json = try {
                            context.contentResolver.openInputStream(uri)
                                ?.bufferedReader()?.use { it.readText() }
                        } catch (_: Exception) {
                            null
                        }
                        if (json == null) {
                            Toast.makeText(
                                context,
                                importReadFailedToast,
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            when (val r = repository.importSchedule(json)) {
                                // 导入成功：先收掉导入浮层，脏草稿确认框随之叠在切换列表上
                                is ReadResult.Ok -> {
                                    importSheetVisible = false
                                    requestSwitch(r.value)
                                }
                                is ReadResult.Err -> Toast.makeText(
                                    context,
                                    r.error.toEditMessage(context),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                }
            }
            // 剪贴板导入：读系统剪贴板里的 JSON 文本走同一套仓库导入（校验/名称去重/不自动激活）；
            // 空剪贴板只提示、不关浮层，便于用户复制后重试
            val clipboardEmptyToast = stringResource(R.string.import_clipboard_empty)
            val onImportClipboard: () -> Unit = {
                val text = context.getSystemService(ClipboardManager::class.java)
                    ?.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(context)
                    ?.toString()
                    ?.trim()
                if (text.isNullOrEmpty()) {
                    AppToasts.show(context, clipboardEmptyToast)
                } else {
                    scope.launch {
                        when (val r = repository.importSchedule(text)) {
                            is ReadResult.Ok -> {
                                importSheetVisible = false
                                requestSwitch(r.value)
                            }
                            is ReadResult.Err ->
                                AppToasts.show(context, r.error.toEditMessage(context))
                        }
                    }
                }
            }

            // Wakeup 口令导入：解析口令 → 无头 WebView 跑云端适配脚本 → 纯函数映射成
            // Classpp 课表 → 确认弹窗（课程数/课表名）→ 走仓库导入（复用 JSON 导入的
            // 校验/名称去重/新 id 路径）。运行态（importing/进度/解析结果）刻意不
            // rememberSaveable：旋转/进程重建时 rememberCoroutineScope 一并取消，导入就地
            // 作废——比起跨重建续跑一个无头 WebView，作废重导更简单也更稳
            val wakeupKeyInvalidToast = stringResource(R.string.error_wakeup_key_invalid)
            val wakeupScriptToast = stringResource(R.string.error_wakeup_script)
            val wakeupTimeoutToast = stringResource(R.string.error_wakeup_timeout)
            val wakeupNoCoursesToast = stringResource(R.string.error_wakeup_no_courses)
            val unexpectedToast = stringResource(R.string.error_unexpected)
            val wakeupDefaultName = stringResource(R.string.import_wakeup_default_name)
            var wakeupImporting by remember { mutableStateOf(false) }
            var wakeupProgressText by remember { mutableStateOf("") }
            var wakeupParsed by remember { mutableStateOf<Schedule?>(null) }
            var wakeupImportJob by remember { mutableStateOf<Job?>(null) }
            val onWakeupImport: (String) -> Unit = { shareText ->
                if (!wakeupImporting) {
                    val key = WakeupShareText.extractShareKey(shareText)
                    // 口令非法就地拦截，垃圾输入不进无头 WebView
                    if (!WakeupShareText.isValidKey(key)) {
                        AppToasts.show(context, wakeupKeyInvalidToast)
                    } else {
                        wakeupImporting = true
                        wakeupProgressText = ""
                        wakeupImportJob = scope.launch {
                            try {
                                val script = WakeupScriptStore.ensureScript(context)
                                val payload = WakeupImportExecutor.import(context, key, script) { progress ->
                                    // 进度文案来自脚本 toast（桥已收敛到主线程），直接进状态
                                    wakeupProgressText = progress
                                }
                                val schedule = WakeupScheduleMapper.toScheduleOrNull(
                                    payload,
                                    shareText,
                                    wakeupDefaultName,
                                )
                                if (schedule == null) {
                                    AppToasts.show(context, wakeupNoCoursesToast)
                                } else {
                                    wakeupParsed = schedule
                                }
                            } catch (e: WakeupScriptStore.ScriptUnavailableException) {
                                AppToasts.show(context, wakeupScriptToast)
                            } catch (e: WakeupImportExecutor.WakeupImportException) {
                                val message = when (e.reason) {
                                    WakeupImportExecutor.Reason.Timeout,
                                    WakeupImportExecutor.Reason.PageLoad,
                                    -> wakeupTimeoutToast
                                    WakeupImportExecutor.Reason.NoCourses -> wakeupNoCoursesToast
                                    // 脚本给出的可读文案原样透传（如「无效的分享口令」）
                                    WakeupImportExecutor.Reason.Failed ->
                                        e.message?.takeIf { it.isNotBlank() } ?: unexpectedToast
                                }
                                AppToasts.show(context, message)
                            } finally {
                                wakeupImporting = false
                            }
                        }
                    }
                }
            }

            // 教务导入：学期列表随浮层打开拉取（卸载即弃，重开重拉——连接状态可能变化），
            // 选中学期确认后拉课表 → 映射 → 确认弹窗 → 复用 JSON 导入路径落库（同 Wakeup）
            var schoolSemesters by remember { mutableStateOf<List<ImportSemester>?>(null) }
            var schoolSemestersLoading by remember { mutableStateOf(false) }
            var schoolSemestersError by remember { mutableStateOf<ImportError?>(null) }
            var schoolImporting by remember { mutableStateOf(false) }
            var schoolImportJob by remember { mutableStateOf<Job?>(null) }
            var schoolParsed by remember { mutableStateOf<Schedule?>(null) }
            // 教务确认框所在的浮层块挂载标志：网页方式导入时浮层串已收起，靠它把块
            // 留到确认框退场播完（onDismissed 清），否则取消/确认瞬间会硬切掉动画
            var schoolConfirmBlockMounted by remember { mutableStateOf(false) }
            if (schoolParsed != null) schoolConfirmBlockMounted = true
            // 失败分类 → 文案（组合期解析 stringResource，协程里只查表）。这些键不含校名，
            // 通用 UI 直接复用旧键（资源名不影响显示）
            val schoolImportErrorTexts: Map<ImportError, String> = mapOf(
                ImportError.NeedLogin to stringResource(R.string.sync_import_error_need_login),
                ImportError.NeedCaptcha to stringResource(R.string.sync_import_error_captcha),
                ImportError.InvalidCredentials to stringResource(R.string.sync_import_error_credentials),
                ImportError.SessionExpired to stringResource(R.string.sync_import_error_session),
                ImportError.ServiceBusy to stringResource(R.string.sync_import_error_busy),
                ImportError.NetworkError to stringResource(R.string.sync_import_error_network),
                ImportError.Timeout to stringResource(R.string.sync_import_error_timeout),
                ImportError.LoginFormatChanged to stringResource(R.string.sync_import_error_login_format),
                ImportError.FormatChanged to stringResource(R.string.sync_import_error_format),
            )
            val schoolNoCoursesToast = stringResource(R.string.sync_import_error_no_courses)
            // 学期列表只在服务未阻断时拉取（阻断原因来自服务端 blockedReason，不再由 UI 拼
            // hasCredentials）；其余状态由浮层的账号行与拦截横幅引导，不出请求
            val schoolCanFetch = schoolScheduleImport != null &&
                schoolImportBlockedReason == null
            val loadSchoolSemesters: () -> Unit = {
                val service = schoolScheduleImport
                if (service != null && schoolCanFetch && !schoolSemestersLoading && !schoolImporting) {
                    schoolSemestersLoading = true
                    schoolSemestersError = null
                    schoolImportJob = scope.launch {
                        schoolSemesters = when (val r = service.fetchSemesters()) {
                            is SemestersOutcome.Ok -> r.semesters
                            is SemestersOutcome.Failed -> {
                                schoolSemestersError = r.error
                                schoolSemesters
                            }
                        }
                        schoolSemestersLoading = false
                    }
                }
            }
            // 浮层确认 → 按选中学期拉课表（只取数 + 映射，落库仍走本页的 JSON 导入路径）；
            // 未连接给引导 toast（浮层本身不拦截未连接），网页登录的拦截横幅已在浮层内说明
            val onSchoolImportConfirm: (Int) -> Unit = { semesterIndex ->
                val service = schoolScheduleImport
                val semester = schoolSemesters?.getOrNull(semesterIndex)
                if (service != null && semester != null && !schoolImporting) {
                    when {
                        !schoolCanFetch ->
                            AppToasts.show(
                                context,
                                schoolImportErrorTexts.getValue(ImportError.NeedLogin),
                            )
                        else -> {
                            schoolImporting = true
                            schoolImportJob = scope.launch {
                                when (val r = service.fetchSchedule(semester)) {
                                    is ImportOutcome.Ok -> schoolParsed = r.schedule
                                    ImportOutcome.NoCourses ->
                                        AppToasts.show(context, schoolNoCoursesToast)
                                    is ImportOutcome.Failed -> AppToasts.show(
                                        context,
                                        schoolImportErrorTexts[r.error] ?: schoolNoCoursesToast,
                                    )
                                }
                                schoolImporting = false
                            }
                        }
                    }
                }
            }

            // 编辑态的返回键 = 取消：整库恢复进入编辑前的快照（同取消按钮），再退出编辑态
            BackHandler(enabled = editSession != null) { cancelEdit() }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                CourseGrid(
                    pagerState = pagerState,
                    timeSlots = timeSlots,
                    contentForPage = displayedContentForPage,
                    state = listState,
                    // 滚动到底时最后一行可停在导航栏胶囊上方，网格背景仍铺满屏幕底缘。
                    // 顶部留白不在这里给：由 CourseGrid 按 editProgress 在下方两个高度间
                    // 插值（State 在网格内部读取，逐帧重组不外溢到本屏）
                    contentPadding = PaddingValues(
                        bottom = WindowInsets.navigationBars
                            .only(WindowInsetsSides.Bottom)
                            .asPaddingValues()
                            .calculateBottomPadding() + NavReserve,
                    ),
                    editMode = editSession != null,
                    // 编辑进度：网格收拢、日期带折叠、加号卡浮现共用这一条进度
                    editProgress = editContentState,
                    addCardsMounted = editContentMounted,
                    headerHeight = headerHeight,
                    editBarHeight = editBarHeight,
                    // 列数：5 天课表 5 列、7 天课表 7 列（含周六/周日），与顶栏星期行/日期带同源
                    daysPerWeek = schedule.daysPerWeek,
                    // State 实例透传，值在 CourseGrid 内读取（拖动时本屏 skip 的关键）
                    cardHeights = cardHeights,
                    showTeacher = showTeacher,
                    onAddClick = onAddClick,
                    onEditClick = onEditClick,
                    onCourseLongClick = onCourseLongClick,
                    onSlotLongClick = onSlotLongClick,
                    modifier = Modifier
                        .fillMaxSize()
                        // 跨多周跳转的单页滑入：位移 = 滑入进度 × 自身宽度（即页宽），只动图层不触发布局
                        .graphicsLayer { translationX = jumpSlideFraction.value * size.width }
                        // 编辑态不折叠：顶栏换成了固定编辑栏，滚动连接不参与
                        .then(
                            if (editSession == null) {
                                Modifier.nestedScroll(collapseState.nestedScrollConnection)
                            } else {
                                Modifier
                            },
                        )
                        .hazeSource(hazeState)
                        // 玻璃周数胶囊开启时同一节点再挂液态玻璃采样标记（Haze 管顶栏
                        // 磨砂、Backdrop 只喂周数胶囊）；关闭时不挂载，采样层零开销
                        .then(
                            if (glassPillEnabled) {
                                Modifier.layerBackdrop(weekPillBackdrop)
                            } else {
                                Modifier
                            },
                        ),
                )
                // 底部渐变遮罩：页面自绘（原导航栏内置层下沉至此），盖住滚进导航栏区域的网格。
                // 编辑态随导航栏一同下移出屏——与 MainActivity 的导航栏 AnimatedVisibility
                // 共用 Motion.EditMillis + Motion.Overlay 规格，同一个 editing 翻转同帧启动
                BottomFadeScrim(
                    visible = editSession == null,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
                // 顶栏 ↔ 编辑栏进出场：新内容自顶部滑入淡入、旧内容向上滑出淡出，进退对称。
                // 与网格收拢、底部遮罩、导航栏共用 [Motion.EditMillis]，同一个 editing 翻转
                // 同帧启动；但本换场用 [Motion.Standard] 而非 [Motion.Overlay]——Overlay
                // 前重后轻，顶栏会在前 ~100ms 窜完大半行程，观感太急；Standard 起步更缓、
                // 全程更均匀，顶栏这块的位移量最大，配合它更稳。
                // 内容读 lambda 参数而非外层 editSession：会话翻转时若读外层值，
                // 退场中的旧槽位也会跟着渲染成新内容
                AnimatedContent(
                    targetState = editSession == null,
                    contentAlignment = Alignment.TopStart,
                    transitionSpec = {
                        (slideInVertically(
                            animationSpec = tween(Motion.EditMillis, easing = Motion.Standard),
                        ) { -it } + fadeIn(tween(Motion.EditMillis, easing = Motion.Standard))) togetherWith
                            (slideOutVertically(
                                animationSpec = tween(Motion.EditMillis, easing = Motion.Standard),
                            ) { -it } + fadeOut(tween(Motion.EditMillis, easing = Motion.Standard)))
                    },
                    modifier = Modifier.align(Alignment.TopStart),
                    label = "topBarSwap",
                ) { viewing ->
                    if (viewing) {
                        ScheduleHeader(
                            collapseFraction = collapseState.collapseFraction,
                            date = headerDate,
                            selectedWeek = week,
                            todayWeek = todayWeek,
                            onWeekSelected = { selectedWeek = it },
                            onEditClick = { onEditingChange(true) },
                            onSettingsClick = onOpenSettings,
                            onMenuExpandedChange = { collapseState.menuOpen = it },
                            weekRange = 1..schedule.totalWeeks,
                            daysPerWeek = schedule.daysPerWeek,
                            onDaysPerWeekToggle = onToggleDaysPerWeek,
                            blurProgress = blurProgressState,
                            hazeState = hazeState,
                            pillBackdrop = if (glassPillEnabled) weekPillBackdrop else null,
                            tabTransition = tabTransition,
                            termStartWeekdayIndex = termStartWeekdayIndex,
                            termEndWeekdayIndex = termEndWeekdayIndex,
                            modifier = Modifier.onGloballyPositioned { coords ->
                                headerHeight = with(density) { coords.size.height.toFloat().toDp() }
                            },
                        )
                    } else {
                        ScheduleEditBar(
                            onSave = onSaveEdit,
                            // 导入：唤起导入课表浮层（编辑态唯一的导入入口）
                            onImport = { importSheetVisible = true },
                            onSwitchSchedule = { switcherVisible = true },
                            // 课表设置：唤起课表设置浮层（原设置页内容迁入）
                            onScheduleSettings = { settingsSheetVisible = true },
                            // 取消：整库恢复进入编辑前的快照（切换/设置一并回滚）
                            onCancel = { cancelEdit() },
                            // 与折叠后的原顶栏同一套背景模糊：内容滚到栏下时渐入
                            blurProgress = blurProgressState,
                            hazeState = hazeState,
                            tabTransition = tabTransition,
                            // 编辑态的星期行同样按课表天数分列，与网格列对齐
                            daysPerWeek = schedule.daysPerWeek,
                            modifier = Modifier.onGloballyPositioned { coords ->
                                editBarHeight = with(density) { coords.size.height.toFloat().toDp() }
                            },
                        )
                    }
                }
            }

            // 面板目标反查：添加=目标格子（进度 / 旋转重建后 slotId 仍有效）；编辑=草稿反查条目；
            // 交替课=源课与它的起始节（新面板沿用同一格的位置）
            val targetDay = addTarget.firstOrNull()
            val targetSlot = addTarget.getOrNull(1)?.let { id -> timeSlots.firstOrNull { it.id == id } }
            val editingEntry = editSession?.courses?.firstOrNull { it.id == editTargetId }
            val editingSlot = editingEntry?.let { e -> timeSlots.firstOrNull { it.id == e.startSlot } }
            val alternateSource = editSession?.courses?.firstOrNull { it.id == alternateSourceId }
            val alternateSlot = alternateSource?.let { e -> timeSlots.firstOrNull { it.id == e.startSlot } }
            // 选择弹窗的候选：被点课程 span 相交的同天课程（交替组，含跨节卡覆盖的续格课）。
            // 从草稿反查，条目没了弹窗自然关闭
            val chooserCourses = editSession?.let { draft ->
                draft.courses.firstOrNull { it.id == chooserSourceId }?.let { entry ->
                    draft.courses.overlappingCourses(entry.dayOfWeek, entry.startSlot, entry.endSlot)
                }
            }.orEmpty()

            // 添加/编辑课程面板：页内覆盖层而非 Dialog 窗口——输入法要接得进来（见 AddCoursePanel 注释）。
            // 三种入口互斥：点已有课卡编辑 / 点空格新建 / 长按已有课新建交替课，归一成一个面板请求。
            // 编辑态从草稿反查条目，命中才给请求（删除后条目消失 → 请求清空 → 面板自动关闭）。
            // 收场动画期间请求已清空但面板还在组合里——mountedRequest 记住最后一次请求，
            // 参数保持稳定让面板带着原内容滑出；动画播完（onDismissed）才真正卸载。
            // key() 保证换目标时面板整体重建（表单初值复位，与旧的 if 分支行为一致）。
            val panelRequest = when {
                editingEntry != null && editingSlot != null -> AddCourseRequest(
                    day = editingEntry.dayOfWeek,
                    slot = editingSlot,
                    existing = editingEntry,
                )
                targetDay != null && targetSlot != null -> AddCourseRequest(
                    day = targetDay,
                    slot = targetSlot,
                )
                alternateSource != null && alternateSlot != null -> AddCourseRequest(
                    day = alternateSource.dayOfWeek,
                    slot = alternateSlot,
                    alternateFrom = alternateSource,
                )
                else -> null
            }
            var mountedRequest by remember { mutableStateOf<AddCourseRequest?>(null) }
            if (panelRequest != null) mountedRequest = panelRequest
            mountedRequest?.let { request ->
                key(request) {
                    AddCoursePanel(
                        visible = panelRequest != null,
                        day = request.day,
                        slot = request.slot,
                        schedule = schedule,
                        draftCourses = editSession?.courses.orEmpty(),
                        existing = request.existing,
                        alternateFrom = request.alternateFrom,
                        onDismiss = {
                            editTargetId = ""
                            addTarget = emptyList()
                            alternateSourceId = ""
                        },
                        onDismissed = { mountedRequest = null },
                        onConfirm = { entry ->
                            val existingId = request.existing?.id
                            if (existingId != null) editSession?.update(existingId, entry) else editSession?.add(entry)
                            editTargetId = ""
                            addTarget = emptyList()
                            alternateSourceId = ""
                        },
                        onDelete = request.existing?.let { existing ->
                            {
                                editSession?.remove(existing.id)
                                editTargetId = ""
                            }
                        },
                    )
                }
            }

            // 课程详情浮层：浏览态点课卡进入。条目从当前课表反查（浏览态无草稿），
            // 课程被删/切课表后反查落空 → 请求清空 → 浮层自动关闭。
            // 挂载/可见两段式与面板相同：收场动画期间 mountedDetail 保持内容稳定不闪空
            val detailAdjustment = schedule.adjustments
                .firstOrNull { it.id == detailAdjustmentId }
            // 条目保持原课程（备注保存写回的就是它）；详情里的位置胶囊在 CourseDetailSheet
            // 内按 adjustment 的目标值显示，原课次另起一行
            val detailEntry = schedule.courses.firstOrNull { it.id == detailTargetId }
            var detailMounted by remember { mutableStateOf<CourseEntry?>(null) }
            if (detailEntry != null) detailMounted = detailEntry
            detailMounted?.let { detail ->
                // 该课程标签（=课程名）命中的全部待办：先做重复待办的分期展开（同待办列表页
                // 口径——分期副本的日期与"随重复"折算截止才是当期真实值，直接用存储层数据
                // 会显示锚日期与原始截止），再按标签过滤（标签语义走 filterTodos 的"任一重合"），
                // 排序固定创建时间降序（不跟随待办页顶栏的排序选择）；todos 回流时列表实时刷新。
                // "只显未完成"与"本次勾选的保留显示"的区分在浮层内做（见 CourseDetailSheet）
                val today = LocalToday
                val courseTodos = remember(todos, today, detail.name) {
                    todos
                        .expandRepeatOccurrences(today)
                        .filterTodos(TodoFilter(tags = setOf(detail.name)))
                        .sortedFor(TodoSort.Created, descending = true)
                }
                // 新建课程待办浮层：详情浮层左上「新建」请求挂载。期望态 + 挂载态两段式
                //（同其它浮层），期望态同时驱动详情浮层 covered（上层在场时缩小后退）
                var courseTodoVisible by remember { mutableStateOf(false) }
                var courseTodoMounted by remember { mutableStateOf(false) }
                val keyboard = LocalSoftwareKeyboardController.current
                val saveFailedMessage = stringResource(R.string.todo_error_save_failed)
                key(detail.id) {
                    CourseDetailSheet(
                        visible = detailEntry != null,
                        entry = detail,
                        schedule = schedule,
                        todos = courseTodos,
                        adjustment = detailAdjustment,
                        covered = courseTodoVisible,
                        onTodoCheckedChange = { todo, checked ->
                            // 同 TodoScreen：异步提交，Err（如已被删）静默忽略。分期副本
                            // dates[0] 即所勾分期日期，走逐期结算；普通待办整条完成
                            scope.launch {
                                if (todo.repeat.type != RepeatType.None) {
                                    todo.dates.firstOrNull()?.let { date ->
                                        todoRepository?.setOccurrenceCompleted(todo.id, date, checked)
                                    }
                                } else {
                                    todoRepository?.setCompleted(todo.id, checked)
                                }
                            }
                        },
                        onNoteSave = { note ->
                            val cleaned = note.trim()
                            if (cleaned != detail.note) {
                                scope.launch {
                                    val result = repository.upsertCourse(schedule.id, detail.copy(note = cleaned))
                                    if (result is OpResult.Err) {
                                        Toast.makeText(
                                            context,
                                            result.error.toEditMessage(context),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            }
                        },
                        // 左上「新建」：收键盘后挂载新建课程待办浮层（入口进弹层的通用约定）
                        onCreateTodoClick = {
                            keyboard?.hide()
                            courseTodoMounted = true
                            courseTodoVisible = true
                        },
                        onDismiss = {
                            detailTargetId = ""
                            detailAdjustmentId = ""
                        },
                        onDismissed = { detailMounted = null },
                    )
                }
                // 新建课程待办浮层：画在详情浮层之后（组合顺序即绘制顺序）叠层，返回键
                // 后注册先响应（先于详情浮层的关闭拦截）。确认落库：Ok 关浮层回详情（新
                // 待办带课程名标签，回流后即出现在上方待办卡列表）；Err Toast 不关，可改后重试
                if (courseTodoMounted) {
                    CourseTodoSheet(
                        visible = courseTodoVisible,
                        entry = detail,
                        schedule = schedule,
                        onConfirm = { todo ->
                            scope.launch {
                                when (todoRepository?.addTodo(todo)) {
                                    is TodoReadResult.Ok -> courseTodoVisible = false
                                    is TodoReadResult.Err -> AppToasts.show(context, saveFailedMessage)
                                    null -> Unit
                                }
                            }
                        },
                        onDismiss = { courseTodoVisible = false },
                        onDismissed = { courseTodoMounted = false },
                    )
                }
            }
            // 浮层在场（含收场动画期间）就请调用方藏导航栏；onDismissed 卸载后才放回来，
            // 导航栏不会在浮层还挂着出场时提前滑回
            LaunchedEffect(detailMounted != null) {
                onOverlayOverNavBarChange(detailMounted != null)
            }

            // 交替课程选择弹窗：同格多门时才出现（见 onEditClick），选完开那一门的编辑面板。
            // 挂载与可见性分离（同浮层两段式关闭）：选完/关闭先播淡出，播完（onDismissed）才卸载
            var chooserMounted by remember { mutableStateOf(false) }
            val chooserVisible = editSession != null && chooserCourses.size > 1
            if (chooserVisible) chooserMounted = true
            if (chooserMounted) {
                AlternatePickerDialog(
                    courses = chooserCourses,
                    visible = chooserVisible,
                    onDismissed = { chooserMounted = false },
                    onDismiss = { chooserSourceId = "" },
                    onPick = { entry ->
                        chooserSourceId = ""
                        editTargetId = entry.id
                    },
                )
            }

            // 切换课表浮层与导入课表浮层共用这一块挂载：导入浮层从编辑栏「导入」打开，
            // 不依赖切换浮层是否曾打开过，故挂载条件是两者任一在场（含收场动画期）。
            // 组合顺序保证返回键优先级：脏草稿确认 → 新建表单 → 教务/Wakeup 确认框 →
            // 教务/Wakeup 输入浮层 → AI 识别导入浮层 → 导入浮层 → 切换浮层 → 编辑取消。
            // 挂载与可见性分离（同课程面板的两段式关闭）：关闭先播浮层出场动画，
            // 播完（onDismissed）才真正卸载
            var switcherMounted by remember { mutableStateOf(false) }
            if (switcherVisible) switcherMounted = true
            // 导入浮层同样两段式；挂载后独立于切换浮层存活（切换先关时不会被提前卸载）
            var importSheetMounted by remember { mutableStateOf(false) }
            if (importSheetVisible) importSheetMounted = true
            // AI 识别导入浮层同样两段式：叠在导入浮层之上，其存活期可长于导入浮层
            var aiImportMounted by remember { mutableStateOf(false) }
            if (aiImportVisible) aiImportMounted = true
            // 教务网页导入完成后浮层串已收起，但确认框仍要能挂载并播完退场：
            // schoolParsed 非空时置挂载标志，onDismissed 才清。确认后的切课可能因
            // 脏草稿打开确认框——pendingSwitchId 非空同样保块存活
            if (switcherMounted || importSheetMounted || aiImportMounted ||
                schoolConfirmBlockMounted || pendingSwitchId.isNotEmpty()
            ) {
                // 切换浮层本身只在它自己挂载时组合：从编辑栏直接开导入时下方不应有它
                if (switcherMounted) {
                    ScheduleSwitcherSheet(
                        visible = switcherVisible,
                        onDismissed = { switcherMounted = false },
                        // 全部课表流无条件收集于页面顶部（退让回流信号同源）
                        schedules = schedulesState.value,
                        activeScheduleId = schedule.id,
                        // 「新建」= 打开表单浮层（复用 NewScheduleSheet）；盖上来时本浮层经
                        // covered 缩小后退，清开关（取消/确认）的瞬间同步回位
                        onCreate = { switcherCreateVisible = true },
                        // 新建表单盖上来时本浮层缩小后退（分层动效与待办详情→编辑一致）
                        covered = switcherCreateVisible,
                        onDismiss = { switcherVisible = false },
                        onSwitch = requestSwitch,
                        onDelete = onDeleteSchedule,
                        onExport = onExport,
                        // 删除 / 新建落库回流窗口内整条 Haze 链退让（列表行增删即采样源重写）
                        hazeEnabled = !switcherHazeRetreat.suspended,
                    )
                }
                // 导入课表浮层：编辑栏「导入」打开；与切换浮层共用挂载块，后注册因此先收到返回键。
                // 挂载与可见性分离（同浮层的两段式关闭）：关闭先播出场动画，播完才卸载
                if (importSheetMounted) {
                    ImportScheduleSheet(
                        visible = importSheetVisible,
                        onDismissed = { importSheetMounted = false },
                        onDismiss = { importSheetVisible = false },
                        // AI 识别导入浮层盖上来时本浮层同样缩小后退（分层动效与新建表单一致）
                        onImportAi = { aiImportVisible = true },
                        // 教务导入浮层盖上来时本浮层缩小后退（分层动效与新建表单一致）。
                        // 活跃学校未实现教务导入（无 scheduleImport 槽位）→ 入口整行隐藏
                        onImportSchedule = if (schoolScheduleImport != null) {
                            { schoolImportVisible = true }
                        } else {
                            null
                        },
                        onImportJsonFile = { importLauncher.launch(ScheduleJsonMimeTypes) },
                        onImportClipboard = onImportClipboard,
                        // Wakeup 输入浮层盖上来时本浮层缩小后退（分层动效与新建表单一致）
                        onImportWakeup = { wakeupSheetVisible = true },
                        covered = wakeupSheetVisible || schoolImportVisible || aiImportVisible,
                    )
                }
                // AI 识别导入浮层：叠在导入浮层之上，后注册因此先收到返回键。
                // 未配置服务商时浮层内走「先配置 AI 服务商」引导态（与 AI 对话浮层一致）；
                // 设置入口先收起 AI 浮层与导入浮层，再由宿主直达设置页 AI 子页（保留编辑态）
                if (aiImportMounted) {
                    val aiConfigured by aiChatRepository?.configured?.collectAsState()
                        ?: remember { mutableStateOf(false) }
                    // 识别运行态：浮层纯展示，请求 / 临时图 / 结果都由本屏持有（同 Wakeup 导入）。
                    // 刻意不 rememberSaveable：旋转/进程重建时 scope 一并取消，识别就地作废
                    var aiAnalyzing by remember { mutableStateOf(false) }
                    var aiError by remember { mutableStateOf<String?>(null) }
                    var aiParsed by remember { mutableStateOf<Schedule?>(null) }
                    var aiJob by remember { mutableStateOf<Job?>(null) }
                    var aiImageName by remember { mutableStateOf<String?>(null) }
                    // 请求代号：取消后立刻重开时，旧协程的 finally / 迟到结果不得改写新请求的状态
                    var aiRequestId by remember { mutableIntStateOf(0) }
                    val aiRepo = aiChatRepository

                    // 错误文案在组合期解析，协程里只查表（同教务导入的映射方式）
                    val aiErrorTexts: Map<AiError, String> = mapOf(
                        AiError.Auth to stringResource(R.string.ai_error_auth),
                        AiError.Quota to stringResource(R.string.ai_error_quota),
                        AiError.NotFound to stringResource(R.string.ai_error_not_found),
                        AiError.RateLimited to stringResource(R.string.ai_error_rate_limited),
                        AiError.Server to stringResource(R.string.ai_error_server),
                        AiError.Network to stringResource(R.string.ai_error_network),
                        AiError.BadResponse to stringResource(R.string.ai_error_bad_response),
                    )
                    val aiVisionHint = stringResource(R.string.import_ai_error_vision_hint)
                    val aiNoJsonText = stringResource(R.string.import_ai_error_no_json)
                    val aiNoCoursesText = stringResource(R.string.import_ai_error_no_courses)
                    val aiFormatText = stringResource(R.string.import_ai_error_invalid)
                    val aiUnreadableText = stringResource(R.string.import_ai_error_unreadable)
                    val aiNotConfiguredText = stringResource(R.string.ai_not_configured_hint)
                    val aiConfirmDetail = stringResource(R.string.import_ai_confirm_detail)

                    // 临时图片只保留当前这一份：换图 / 重试 / 收场都清掉旧文件
                    val clearAiImage: () -> Unit = {
                        val name = aiImageName
                        aiImageName = null
                        if (name != null) aiRepo?.imageStore?.delete(name)
                    }
                    // 课表格式类错误（模型没按契约输出）给 AI 专属文案；其余校验错误沿用现有映射
                    val aiInvalidText: (ScheduleError) -> String = { error ->
                        when (error) {
                            is ScheduleError.ImportFormatInvalid,
                            is ScheduleError.ImportVersionUnsupported,
                            is ScheduleError.ImportKindMismatch,
                            -> aiFormatText
                            else -> error.toEditMessage(context)
                        }
                    }
                    val onAiAnalyze: (Uri) -> Unit = { uri ->
                        if (aiRepo != null && !aiAnalyzing) {
                            aiAnalyzing = true
                            aiError = null
                            aiJob?.cancel()
                            val requestId = aiRequestId + 1
                            aiRequestId = requestId
                            aiJob = scope.launch {
                                try {
                                    val name = withContext(Dispatchers.IO) {
                                        aiRepo.imageStore.import(context, uri)
                                    }
                                    if (aiRequestId != requestId) return@launch
                                    if (name == null) {
                                        aiError = aiUnreadableText
                                    } else {
                                        clearAiImage()
                                        aiImageName = name
                                        val recognition = aiRepo.recognizeSchedule(name)
                                        // 取消后立即重开：旧请求的结果直接丢弃
                                        if (aiRequestId != requestId) return@launch
                                        aiError = when (recognition) {
                                            is AiScheduleRecognition.Ok -> {
                                                aiParsed = recognition.schedule
                                                null
                                            }
                                            is AiScheduleRecognition.AiFailed -> {
                                                val base = aiErrorTexts.getValue(recognition.error)
                                                // 纯文本模型收到图片通常落到 BadResponse：补一句换视觉模型
                                                if (recognition.error == AiError.BadResponse) {
                                                    base + "\n" + aiVisionHint
                                                } else {
                                                    base
                                                }
                                            }
                                            is AiScheduleRecognition.Invalid ->
                                                aiInvalidText(recognition.error)
                                            AiScheduleRecognition.NoJson -> aiNoJsonText
                                            AiScheduleRecognition.NoCourses -> aiNoCoursesText
                                            AiScheduleRecognition.ImageUnavailable -> aiUnreadableText
                                            AiScheduleRecognition.NotConfigured -> aiNotConfiguredText
                                        }
                                    }
                                } finally {
                                    if (aiRequestId == requestId) aiAnalyzing = false
                                }
                            }
                        }
                    }

                    AiImportSheet(
                        visible = aiImportVisible,
                        onDismissed = { aiImportMounted = false },
                        onDismiss = {
                            aiImportVisible = false
                            aiRequestId++
                            aiJob?.cancel()
                            aiJob = null
                            aiAnalyzing = false
                            aiError = null
                            aiParsed = null
                            clearAiImage()
                        },
                        configured = aiConfigured,
                        onOpenSettings = {
                            aiImportVisible = false
                            importSheetVisible = false
                            onOpenAiSettings()
                        },
                        analyzing = aiAnalyzing,
                        errorText = aiError,
                        onAnalyze = onAiAnalyze,
                        onStopAnalyze = {
                            aiRequestId++
                            aiJob?.cancel()
                            aiJob = null
                            aiAnalyzing = false
                            clearAiImage()
                        },
                        onImageChanged = { aiError = null },
                    )

                    // 识别结果确认：复用 Wakeup / 教务同一个导入确认弹窗（课程数 + 课表名）。
                    // 取消留在本浮层（图片仍在）可重试；确认 → 统一导入管线新建课表并切换
                    var aiConfirmMounted by remember { mutableStateOf(false) }
                    val aiConfirmVisible = aiParsed != null
                    if (aiConfirmVisible) aiConfirmMounted = true
                    if (aiConfirmMounted) {
                        ScheduleImportConfirmDialog(
                            title = stringResource(R.string.import_ai_confirm_title),
                            courseCount = aiParsed?.courses?.size ?: 0,
                            scheduleName = aiParsed?.name.orEmpty(),
                            detailLine = aiConfirmDetail,
                            visible = aiConfirmVisible,
                            onDismissed = { aiConfirmMounted = false },
                            onCancel = {
                                aiParsed = null
                                clearAiImage()
                            },
                            onConfirm = {
                                val parsed = aiParsed
                                aiParsed = null
                                if (parsed != null) {
                                    scope.launch {
                                        when (
                                            val r = repository.importSchedule(
                                                ShareCodec.encode(parsed, System.currentTimeMillis()),
                                            )
                                        ) {
                                            is ReadResult.Ok -> {
                                                aiImportVisible = false
                                                importSheetVisible = false
                                                clearAiImage()
                                                requestSwitch(r.value)
                                            }
                                            is ReadResult.Err -> AppToasts.show(
                                                context,
                                                r.error.toEditMessage(context),
                                            )
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
                // Wakeup 口令导入浮层：叠在导入浮层之上（粘贴分享文案 → 跑云端适配脚本）。
                // 关浮层 = 取消导入（onDismissed 取消协程，执行器 finally 销毁 WebView）
                var wakeupSheetMounted by remember { mutableStateOf(false) }
                if (wakeupSheetVisible) wakeupSheetMounted = true
                if (wakeupSheetMounted) {
                    WakeupImportSheet(
                        visible = wakeupSheetVisible,
                        onDismissed = {
                            wakeupSheetMounted = false
                            // 浮层收场即取消在途导入：解析态一并复位（确认弹窗未开时无副作用）
                            wakeupImporting = false
                            wakeupImportJob?.cancel()
                        },
                        onDismiss = { wakeupSheetVisible = false },
                        onConfirm = onWakeupImport,
                        importing = wakeupImporting,
                        progressText = wakeupProgressText,
                    )
                }
                // Wakeup 导入确认框：叠在输入浮层之上（两段式同删除确认框）；
                // 确认 → 仓库导入（复用 JSON 导入路径）→ 关浮层链 → 走统一切换（脏则弹确认）
                var wakeupConfirmMounted by remember { mutableStateOf(false) }
                val wakeupConfirmVisible = wakeupParsed != null
                if (wakeupConfirmVisible) wakeupConfirmMounted = true
                // 教务导入浮层：叠在导入浮层之上（账号与同步行 + 可导入学期单选）。学期列表
                // 随浮层打开拉取、卸载即弃；确认 → 拉课表映射 → 确认弹窗
                var schoolImportMounted by remember { mutableStateOf(false) }
                if (schoolImportVisible) schoolImportMounted = true
                LaunchedEffect(schoolImportVisible) {
                    if (schoolImportVisible) {
                        schoolSemesters = null
                        schoolSemestersError = null
                        loadSchoolSemesters()
                    }
                }
                if (schoolImportMounted) {
                    SchoolImportSheet(
                        visible = schoolImportVisible,
                        onDismissed = {
                            schoolImportMounted = false
                            // 浮层收场即取消在途请求与解析态（同 Wakeup 输入浮层）
                            schoolImportJob?.cancel()
                            schoolImporting = false
                            schoolParsed = null
                            schoolSemesters = null
                            schoolSemestersError = null
                        },
                        onDismiss = { schoolImportVisible = false },
                        onOpenAccountSync = onSchoolAccountClick,
                        // 非空 = WebView 登录模式：账号卡下方追加教务课表页入口，
                        // 同时浮层隐藏「不支持」横幅与确认胶囊
                        onOpenWebViewTimetable = if (schoolWebViewImportMode) {
                            onSchoolWebViewImportClick
                        } else {
                            null
                        },
                        accountStatus = schoolAccountStatus,
                        // 已连接账户的带头像状态卡（与设置页「账号与同步」同款）
                        account = schoolAccountCard,
                        // 非空 = 当前登录方式不支持教务导入：浮层内横幅说明并拦截确认
                        importBlockedReason = schoolImportBlockedText,
                        semesters = schoolSemesters?.map { it.name }.orEmpty(),
                        semestersLoading = schoolSemestersLoading,
                        semestersError = schoolSemestersError?.let { schoolImportErrorTexts[it] },
                        onRetrySemesters = loadSchoolSemesters,
                        importing = schoolImporting,
                        onConfirm = onSchoolImportConfirm,
                    )
                }
                if (wakeupConfirmMounted) {
                    ScheduleImportConfirmDialog(
                        title = stringResource(R.string.import_wakeup_confirm_title),
                        courseCount = wakeupParsed?.courses?.size ?: 0,
                        scheduleName = wakeupParsed?.name.orEmpty(),
                        visible = wakeupConfirmVisible,
                        onDismissed = { wakeupConfirmMounted = false },
                        onCancel = { wakeupParsed = null },
                        onConfirm = {
                            val parsed = wakeupParsed
                            wakeupParsed = null
                            if (parsed != null) {
                                scope.launch {
                                    when (val r = repository.importSchedule(
                                        WakeupScheduleMapper.toShareJson(parsed),
                                    )) {
                                        is ReadResult.Ok -> {
                                            wakeupSheetVisible = false
                                            importSheetVisible = false
                                            requestSwitch(r.value)
                                        }
                                        is ReadResult.Err -> AppToasts.show(
                                            context,
                                            r.error.toEditMessage(context),
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
                // 教务导入确认框：叠在教务导入浮层之上（同 Wakeup 确认框的两段式）；
                // 确认 → 仓库导入（ShareCodec 编码走既有 JSON 导入路径）→ 关浮层链 → 统一切换
                var schoolConfirmMounted by remember { mutableStateOf(false) }
                val schoolConfirmVisible = schoolParsed != null
                if (schoolConfirmVisible) schoolConfirmMounted = true
                if (schoolConfirmMounted) {
                    ScheduleImportConfirmDialog(
                        title = stringResource(R.string.sync_import_confirm_title),
                        courseCount = schoolParsed?.courses?.size ?: 0,
                        scheduleName = schoolParsed?.name.orEmpty(),
                        // 开学日为推算默认值：确认时展示，导入后仍可在课表设置里调整
                        detailLine = schoolParsed?.let {
                            stringResource(R.string.import_confirm_detail, it.termStart.toString())
                        },
                        visible = schoolConfirmVisible,
                        onDismissed = {
                            schoolConfirmMounted = false
                            schoolConfirmBlockMounted = false
                        },
                        onCancel = { schoolParsed = null },
                        onConfirm = {
                            val parsed = schoolParsed
                            schoolParsed = null
                            if (parsed != null) {
                                scope.launch {
                                    when (val r = repository.importSchedule(
                                        ShareCodec.encode(parsed, System.currentTimeMillis()),
                                    )) {
                                        is ReadResult.Ok -> {
                                            schoolImportVisible = false
                                            importSheetVisible = false
                                            requestSwitch(r.value)
                                        }
                                        is ReadResult.Err -> AppToasts.show(
                                            context,
                                            r.error.toEditMessage(context),
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
                // 新建表单浮层：叠在切换课表之上（同待办详情→编辑的接线与动效）。
                // 确认创建 → 关表单回列表再走切换（脏则弹确认）；取消/返回只收表单
                var switcherCreateMounted by remember { mutableStateOf(false) }
                if (switcherCreateVisible) switcherCreateMounted = true
                if (switcherCreateMounted) {
                    NewScheduleSheet(
                        visible = switcherCreateVisible,
                        onDismissed = { switcherCreateMounted = false },
                        onDismiss = { switcherCreateVisible = false },
                        onConfirm = onCreateConfirm,
                    )
                }
                // 脏草稿确认叠在最上，同样两段式：取消/确认先播淡出，播完才卸载
                var discardMounted by remember { mutableStateOf(false) }
                val discardVisible = pendingSwitchId.isNotEmpty()
                if (discardVisible) discardMounted = true
                if (discardMounted) {
                    DiscardSwitchConfirmDialog(
                        visible = discardVisible,
                        onDismissed = { discardMounted = false },
                        onCancel = { pendingSwitchId = "" },
                        onConfirm = { performSwitch(pendingSwitchId) },
                    )
                }
            }

            // 课表设置浮层：内容自设置页迁入（改即时提交仓库，失败 Toast）。
            // 挂载与可见性分离（同切换课表浮层的两段式关闭）：关闭先播浮层出场动画，
            // 播完（onDismissed）才真正卸载
            var settingsSheetMounted by remember { mutableStateOf(false) }
            if (settingsSheetVisible) settingsSheetMounted = true
            if (settingsSheetMounted) {
                ScheduleSettingsSheet(
                    schedule = schedule,
                    // 设置浮层只在编辑态打开：课程/调课草稿才是网格真源，合并输入取草稿；
                    // 合并落库成功后同步回草稿，避免保存编辑用旧 span/旧节次覆盖结果
                    mergeCourses = editSession?.courses ?: schedule.courses,
                    adjustments = editSession?.adjustments ?: schedule.adjustments,
                    repository = repository,
                    visible = settingsSheetVisible,
                    onDismissed = { settingsSheetMounted = false },
                    onDismiss = { settingsSheetVisible = false },
                    onCoursesMerged = { merged -> editSession?.replaceAll(merged) },
                    onAdjustmentsMerged = { merged -> editSession?.replaceAdjustments(merged) },
                    onOpenAdjustments = {
                        adjustListMounted = true
                        adjustListVisible = true
                    },
                    onOpenDayAdjust = {
                        dayAdjustMounted = true
                        dayAdjustVisible = true
                    },
                    extraCovered = adjustListVisible || adjustCourseId.isNotEmpty() ||
                        dayAdjustVisible,
                )
            }

            // 有效课表（编辑态 = 课程/调课草稿）：调课列表与编辑面板的校验口径。
            // 仓库里的调课是上次保存的结果，草稿里的才是用户眼前的数据
            val effectiveSchedule = schedule.copy(
                courses = editSession?.courses ?: schedule.courses,
                adjustments = editSession?.adjustments ?: schedule.adjustments,
            )

            // 设置 → 设置调课：列表叠在设置浮层之上（画在设置之后）
            // visible 是 saveable：旋转恢复出 visible=true 时 mounted 必须跟上，
            // 否则列表不渲染、设置浮层还因 extraCovered 停在「被覆盖」的缩小态
            if (adjustListVisible) adjustListMounted = true
            if (adjustListMounted) {
                AdjustmentListSheet(
                    visible = adjustListVisible,
                    schedule = effectiveSchedule,
                    covered = adjustCourseId.isNotEmpty(),
                    onDismiss = { adjustListVisible = false },
                    onDismissed = { adjustListMounted = false },
                    onEdit = { adjustment ->
                        adjustCourseId = adjustment.courseId
                        adjustFromWeek = adjustment.fromWeek
                        adjustExistingId = adjustment.id
                    },
                    onDelete = { adjustment -> editSession?.removeAdjustment(adjustment.id) },
                )
            }

            // 调课编辑浮层：长按菜单 / 列表点击 / 点调课卡都汇到这里。
            // 请求由 saveable 的 id/周状态派生；收场动画期间用 mounted 快照保持内容稳定
            val adjustRequest = if (adjustCourseId.isNotEmpty()) {
                AdjustRequest(adjustCourseId, adjustFromWeek, adjustExistingId)
            } else {
                null
            }
            var mountedAdjustRequest by remember { mutableStateOf<AdjustRequest?>(null) }
            if (adjustRequest != null) mountedAdjustRequest = adjustRequest
            mountedAdjustRequest?.let { request ->
                key(request) {
                    AdjustCourseSheet(
                        visible = adjustRequest != null,
                        schedule = effectiveSchedule,
                        courseId = request.courseId,
                        initialFromWeek = request.fromWeek,
                        existingId = request.existingId.takeIf { it.isNotEmpty() },
                        onDismiss = {
                            adjustCourseId = ""
                            adjustFromWeek = 0
                            adjustExistingId = ""
                        },
                        onDismissed = { mountedAdjustRequest = null },
                        onConfirm = { adjustment ->
                            if (editSession?.adjustments?.any { it.id == adjustment.id } == true) {
                                editSession.updateAdjustment(adjustment.id, adjustment)
                            } else {
                                editSession?.addAdjustment(adjustment)
                            }
                            adjustCourseId = ""
                            adjustFromWeek = 0
                            adjustExistingId = ""
                        },
                        onDelete = request.existingId.takeIf { it.isNotEmpty() }?.let { id ->
                            {
                                editSession?.removeAdjustment(id)
                                adjustCourseId = ""
                                adjustFromWeek = 0
                                adjustExistingId = ""
                            }
                        },
                    )
                }
            }

            // 整天调休浮层：设置 → 调休。确认只写编辑草稿（课程/调课同批保存），
            // 与单次调课面板一致；收场动画期间用 mounted 快照保持内容稳定。
            // 初值：原周 = 当前浏览周；原星期 = 浏览今周时取今天，否则周一
            if (dayAdjustVisible) dayAdjustMounted = true
            if (dayAdjustMounted) {
                DayAdjustSheet(
                    visible = dayAdjustVisible,
                    schedule = effectiveSchedule,
                    initialWeek = week.coerceIn(1, schedule.totalWeeks.coerceAtLeast(1)),
                    initialDay = if (week == todayWeek) todayIso.isoDayOfWeek() else 1,
                    onDismiss = { dayAdjustVisible = false },
                    onDismissed = { dayAdjustMounted = false },
                    onConfirm = { upserts, removals ->
                        val session = editSession
                        if (session != null) {
                            removals.forEach(session::removeAdjustment)
                            upserts.forEach { adjustment ->
                                if (session.adjustments.any { it.id == adjustment.id }) {
                                    session.updateAdjustment(adjustment.id, adjustment)
                                } else {
                                    session.addAdjustment(adjustment)
                                }
                            }
                        }
                        dayAdjustVisible = false
                    },
                )
            }

            // 长按卡片的上下文菜单（Popup 独立窗口，位置由卡片坐标决定）。
            // 单节与跨节课程都可复制：粘贴端保留源课跨度
            menuAnchor?.let { anchor ->
                val copiedToast = stringResource(R.string.toast_course_copied)
                val session = editSession
                val source = session?.courses?.firstOrNull { it.id == anchor.courseId }
                // 调课卡的菜单只保留「编辑调课」：复制/新建交替针对周期课程，不适用于单次例外
                val adjusted = anchor.adjustmentId?.let { id ->
                    session?.adjustments?.firstOrNull { it.id == id }
                }
                if (adjusted != null) {
                    CourseContextMenu(
                        anchor = anchor.rect,
                        expanded = menuVisible,
                        onDismiss = { menuVisible = false },
                        onCopy = null,
                        onAdjust = {
                            adjustCourseId = adjusted.courseId
                            adjustFromWeek = adjusted.fromWeek
                            adjustExistingId = adjusted.id
                            menuVisible = false
                        },
                        adjustIsEditing = true,
                        hazeState = hazeState,
                        onExitFinished = { menuAnchor = null },
                    )
                } else {
                    // 当前浏览周不是该课的上课周时没有可调课次，隐藏调课项
                    val existingAdjustment = source?.let { session.adjustmentFor(it.id, week) }
                    val canAdjust = source != null && source.weeks.contains(week)
                    CourseContextMenu(
                        anchor = anchor.rect,
                        expanded = menuVisible,
                        onDismiss = { menuVisible = false },
                        onCopy = source?.let { entry ->
                            {
                                copiedEntry = entry
                                AppToasts.show(context, copiedToast)
                                menuVisible = false
                            }
                        },
                        onNewAlternate = {
                            alternateSourceId = anchor.courseId
                            menuVisible = false
                        },
                        onAdjust = if (canAdjust) {
                            {
                                adjustCourseId = anchor.courseId
                                adjustFromWeek = week
                                adjustExistingId = existingAdjustment?.id ?: ""
                                menuVisible = false
                            }
                        } else {
                            null
                        },
                        adjustIsEditing = existingAdjustment != null,
                        hazeState = hazeState,
                        // 锚点保留到退场播完（收起只翻 menuVisible）：退场期间内容稳定、动作仍可命中
                        onExitFinished = { menuAnchor = null },
                    )
                }
            }

            // 空位长按的粘贴菜单：只有剪贴板有课时才会被 onSlotLongClick 打开。
            // 粘贴 = 以快照为模板在长按格新建一门课（星期/起始节换成目标格、跨度随源课保留），
            // 其余参数原样保留；剪贴板不清空，可连续粘贴多格
            pasteAnchor?.let { anchor ->
                SlotContextMenu(
                    anchor = anchor.rect,
                    expanded = pasteVisible,
                    onDismiss = { pasteVisible = false },
                    hazeState = hazeState,
                    // 锚点保留到退场播完，同上方 menuAnchor；顺带冲刷延后的粘贴动作
                    onExitFinished = {
                        pasteAnchor = null
                        pasteGate.flush()
                    },
                    onPaste = {
                        pasteVisible = false
                        // 会话与剪贴板快照在点选帧读取（与原即时语义一致），落格延后
                        val session = editSession
                        val source = copiedEntry
                        pasteGate.defer {
                            if (session != null && source != null) {
                                val entry = source.pastedAt(anchor.day, anchor.slotId)
                                // 跨节粘贴会覆盖下方格子：整份预检兜住同格冲突 / 末节越界 /
                                // 跨课表粘贴的周次越界；只拦与这门新课相关的错误，草稿里无关的旧错误不误伤
                                val blocking = ScheduleValidator
                                    .validateCourses(schedule.copy(courses = session.courses + entry))
                                    .firstOrNull { it.involvesCourse(entry.id) }
                                if (blocking != null) {
                                    AppToasts.show(context, blocking.toPasteMessage(context, entry.span))
                                } else {
                                    session.add(entry)
                                }
                            }
                        }
                    },
                )
            }

            // 教务课表页：WebView 登录模式下从教务导入浮层进入（全屏页，组合序最后 =
            // 画在所有浮层之上）；页面内脚本回传原文 → 学校适配层映射成课表
            if (schoolTimetableVisible && schoolWebViewTimetable != null) {
                SchoolTimetableWebViewSheet(
                    spec = schoolWebViewTimetable,
                    onDismiss = { schoolTimetableVisible = false },
                    // 网页方式导入：页面脚本回传原文 → 学校适配层映射成课表 →
                    // 关页面并复用既有教务导入确认框（确认后走 JSON 导入路径落库）
                    onExtract = if (schoolWebViewTimetable.importScript != null) {
                        { raw ->
                            when (val r = schoolScheduleImport.importFromWebTimetable(raw)) {
                                is ImportOutcome.Ok -> {
                                    schoolTimetableVisible = false
                                    schoolParsed = r.schedule
                                }
                                ImportOutcome.NoCourses ->
                                    AppToasts.show(context, schoolNoCoursesToast)
                                is ImportOutcome.Failed -> AppToasts.show(
                                    context,
                                    schoolImportErrorTexts[r.error] ?: schoolNoCoursesToast,
                                )
                            }
                        }
                    } else {
                        null
                    },
                )
            }
            // 全屏页在场（含开关翻转帧）就藏底部导航栏；页面关闭后放回（同课程详情浮层）
            LaunchedEffect(schoolTimetableVisible, schoolWebViewTimetable) {
                onOverlayOverNavBarChange(
                    schoolTimetableVisible && schoolWebViewTimetable != null,
                )
            }
        }
        // 新建课表浮层：无激活课表的空态经「创建课表」按钮打开。挂在空态/网格 if-else 之外——
        // 创建成功瞬间新课表就位、空态翻转为网格，浮层照常播完出场动画（onDismissed）才卸载
        if (newScheduleVisible) newScheduleMounted = true
        if (newScheduleMounted) {
            NewScheduleSheet(
                visible = newScheduleVisible,
                onDismissed = { newScheduleMounted = false },
                onDismiss = { newScheduleVisible = false },
                onConfirm = { name, start, end ->
                    scope.launch {
                        when (val r = repository.createSchedule(name, start, end)) {
                            // 无激活课表时 createSchedule 自动激活新课表，空态自行翻转为网格
                            is ReadResult.Ok -> newScheduleVisible = false
                            // 失败（名称非法等）走系统 Toast，表单保持原样便于修正
                            is ReadResult.Err -> AppToasts.show(context, r.error.toEditMessage(context))
                        }
                    }
                },
            )
        }
        // 浮层在场（含收场动画期间）就藏底部导航栏，onDismissed 卸载后才放回（同课程详情浮层）
        LaunchedEffect(newScheduleMounted) {
            onOverlayOverNavBarChange(newScheduleMounted)
        }
    }
}

/** 普通态顶栏总高估算（状态栏 + 顶栏行 + 日期行 + 星期行），首帧后由实测值覆盖 */
private val HeaderHeightGuess = 168.dp

/**
 * 导入课表文件选择器的 MIME 过滤：Classpp 分享 JSON 是主形态，但部分文件管理器把 .json
 * 报成 text/plain 或 application/octet-stream，一并放开（内容真伪仍由 ShareCodec 校验）。
 */
private val ScheduleJsonMimeTypes = arrayOf(
    "application/json",
    "text/plain",
    "application/octet-stream",
)

/** 编辑栏总高估算（状态栏 + 按钮行 + 星期行），首帧后由实测值覆盖 */
private val EditBarHeightGuess = 112.dp

/**
 * 长按菜单的锚点：被长按卡的 id（菜单动作用它回查草稿）与卡片的窗口坐标（菜单贴卡片定位）。
 * 非 saveable 的瞬时状态，见 [ScheduleScreen] 里的 menuAnchor。
 */
private data class CardMenuAnchor(
    val courseId: String,
    /** 非空 = 被长按的是调课卡，菜单只给「编辑调课」 */
    val adjustmentId: String?,
    val rect: Rect,
)

/**
 * 空位长按菜单的锚点：被长按格的星期与节次（粘贴动作以这格为目标建课）+ 格子的窗口坐标
 * （菜单贴格子定位）。非 saveable 的瞬时状态，同 [CardMenuAnchor]。
 */
private data class SlotMenuAnchor(val day: Int, val slotId: Int, val rect: Rect)

/**
 * 课程剪贴板的 Saver：CourseEntry 走存储 JSON 序列化（同 [ScheduleEditSession.Saver] 存草稿），
 * 旋转 / 进程重建后剪贴板不丢；空串表示「没有复制」。
 */
private val CopiedEntrySaver: Saver<CourseEntry?, String> = Saver(
    save = { entry -> entry?.let { ScheduleJson.encodeStorage<CourseEntry>(it) } ?: "" },
    restore = { text -> if (text.isEmpty()) null else ScheduleJson.decodeStorage<CourseEntry>(text) },
)

/**
 * 课程面板请求：三种入口（点课卡编辑 / 空格新建 / 长按新建交替课）归一后的参数快照。
 * 三个入口互斥；数据类相等性配合 key() 使用——同一目标重组合不重建面板，换目标才重建。
 */
private data class AddCourseRequest(
    val day: Int,
    val slot: TimeSlot,
    val existing: CourseEntry? = null,
    val alternateFrom: CourseEntry? = null,
)

/**
 * 调课编辑面板请求：课程 id + 原课次周 + 已有调课 id（空 = 新建）。
 * 三个入口（长按菜单 / 列表点击 / 点调课卡）归一成同一份参数；数据类相等性配合 key()。
 */
private data class AdjustRequest(
    val courseId: String,
    val fromWeek: Int,
    val existingId: String,
)

/**
 * 数据层错误 → 用户可读文案（编辑流程的 Toast 用）。课程类错误一律点名到课程或给中文结论，
 * 绝不把内部 id / 英文 message 直接抛给用户；真正没覆盖到的形态才落到通用文案。
 */
private fun ScheduleError.toEditMessage(context: Context): String = when (this) {
    is ScheduleError.CourseFieldInvalid -> when (reason) {
        FieldReason.BlankName -> context.getString(R.string.error_course_name_blank)
        else -> context.getString(R.string.error_unexpected)
    }
    is ScheduleError.WeeksBeyondTerm -> context.getString(R.string.error_weeks_beyond_term)
    // 末周被收掉后放不下的课：给中文结论（课程名请去网格里看）
    is ScheduleError.TermEndInvalid -> context.getString(R.string.error_term_end_short)
    // 学期被改短/改窄后放不下的课：说清是哪几门
    is ScheduleError.CoursesOutOfRange -> context.getString(
        R.string.error_courses_out_of_range,
        affected.size,
        affected.take(3).joinToString { it.name },
    )
    // 冲突对里 a 是列表靠前的那门（新增的课总在末尾），故指它
    is ScheduleError.GridConflict -> context.getString(R.string.error_grid_conflict, a.name)
    is ScheduleError.AdjustmentSourceInvalid ->
        context.getString(R.string.error_adjust_source_invalid, courseName, fromWeek)
    is ScheduleError.AdjustmentTargetInvalid -> context.getString(R.string.error_adjust_target_invalid)
    is ScheduleError.AdjustmentUnchanged -> context.getString(R.string.error_adjust_unchanged)
    is ScheduleError.AdjustmentConflict -> context.getString(R.string.error_adjust_conflict, conflictName)
    is ScheduleError.AdjustmentDuplicate ->
        context.getString(R.string.error_adjust_duplicate, courseName, fromWeek)
    is ScheduleError.AdjustmentCourseMissing -> context.getString(R.string.error_adjust_course_missing)
    is ScheduleError.PersistFailed -> context.getString(R.string.error_persist_failed)
    is ScheduleError.NotFound -> context.getString(R.string.error_not_found)
    is ScheduleError.ImportFormatInvalid -> context.getString(R.string.error_import_format)
    is ScheduleError.ImportVersionUnsupported -> context.getString(R.string.error_import_version)
    is ScheduleError.ImportKindMismatch -> context.getString(R.string.error_import_kind)
    // 导入第三方数据可能触发的设置类错误：给出具体结论而非通用兜底
    is ScheduleError.InvalidScheduleName -> context.getString(R.string.error_schedule_name)
    is ScheduleError.TermRangeInvalid -> context.getString(R.string.error_term_range)
    is ScheduleError.DaysPerWeekInvalid -> context.getString(R.string.error_days_per_week)
    is ScheduleError.SlotCountInvalid -> context.getString(R.string.error_slot_count)
    is ScheduleError.SlotTimeFormatInvalid -> context.getString(R.string.error_slot_time_format)
    is ScheduleError.SlotOrderInvalid -> context.getString(R.string.error_slot_order)
    // 真正没覆盖到的形态：不把内部 message 抛给用户
    else -> context.getString(R.string.error_unexpected)
}

/** 粘贴预检错误 → 用户可读文案：跨节课程放不下时提示换位置，其余沿用编辑流程文案 */
private fun ScheduleError.toPasteMessage(context: Context, span: Int): String = when (this) {
    is ScheduleError.CourseFieldInvalid -> when (reason) {
        FieldReason.SpanOutOfRange ->
            context.getString(R.string.error_paste_span_out_of_range, span)
        else -> toEditMessage(context)
    }
    else -> toEditMessage(context)
}

/** 无激活课表时的空状态：图标 + 标题 + 说明 + 新建按钮（打开 [NewScheduleSheet]） */
@Composable
private fun EmptyScheduleContent(
    onCreateSchedule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        EmptyState(
            icon = R.drawable.ic_calendar_regular,
            title = stringResource(R.string.empty_title),
            description = stringResource(R.string.empty_desc),
            modifier = Modifier.padding(top = 250.dp),
        ) {
            EmptyStateActionButton(
                label = stringResource(R.string.create_schedule),
                onClick = onCreateSchedule,
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
