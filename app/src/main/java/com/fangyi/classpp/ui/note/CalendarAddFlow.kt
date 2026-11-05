package com.fangyi.classpp.ui.note

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.TodoError
import com.fangyi.classpp.data.TodoReadResult
import com.fangyi.classpp.data.calendar.CalendarAccount
import com.fangyi.classpp.data.calendar.CalendarEventPlanner
import com.fangyi.classpp.data.calendar.SystemCalendar
import com.fangyi.classpp.data.model.Todo
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「添加到日历」流程的对外接口（由 [rememberCalendarAddState] 组装）：
 * - [begin]：总入口，单个待办传单元素列表——运行时权限、列设备日历账户、单账户直写 /
 *   多账户弹选择卡、写入与 Toast 全在此编排（宿主不再感知细节）。两个接入方：详情浮层的
 *   「添加到日历」菜单项（单条）与待办编辑顶栏的「同步」（所选整批）；
 * - [visible] / [accounts] / [selectedId] 与 [onSelect] / [onConfirm] / [onDismiss] /
 *   [onDismissed]：账户选择卡的受控接线，由 [CalendarPickerHost] 消费——宿主只需在
 *   详情浮层**之后**组合它（组合顺序即绘制顺序，选择卡盖在详情之上）。
 */
internal class CalendarAddState(
    val visible: Boolean,
    val accounts: List<CalendarAccount>,
    val selectedId: Long,
    val begin: (List<Todo>) -> Unit,
    val onSelect: (Long) -> Unit,
    val onConfirm: (Long) -> Unit,
    val onDismiss: () -> Unit,
    val onDismissed: () -> Unit,
)

/**
 * 组装「添加到日历」流程（接入方 → 权限 → 列账户 → 选卡/直写，写入目标支持待办页
 * TodoScreen 与日程页 DayScreen 两个详情浮层，以及待办编辑顶栏的整批「同步」）。纯状态
 * 与回调，无 UI——选择卡的组合与两段式挂载在 [CalendarPickerHost]。
 *
 * 流程口径：
 * - 入参是待办列表（详情菜单传单条）：所有待办都能产出日历事件——没有待办日期时
 *   事件落在截止日期，截止日期也没有则落在当天（口径见 CalendarEventPlanner.drafts）；
 *   空列表防御性不动作；
 * - 权限 READ+WRITE_CALENDAR 一起请求（Manifest 已静态声明）：已授权直接进写入；
 *   未授权先记下待写快照再请求，全部授予后续跑流程，拒绝 Toast（下次点按再请求）；
 * - 写入待办用点按那一刻的快照（[CalendarAddState.begin] 的入参）：目标要跨过权限弹窗/
 *   选择卡两个可能漫长的中间态，不随仓库回流换（期间待办被删则写入的仍是当时内容，
 *   属可接受口径）；
 * - 设备单账户跳过选择直接写入；多账户预选上次写入的账户（[CalendarPreferences]，
 *   无记录或账户已随系统侧消失则选第一个）弹选择卡；无可用账户 Toast 提示；
 * - 整条链路（权限 → 列账户 → 选择卡 → 写入）由在途闸门串行化：编辑顶栏的同步块点完
 *   不会消失，连点若无闸门会重复写入同一批事件；
 * - SystemCalendar 是阻塞 I/O 的无状态封装，调用点现造现用（包 Dispatchers.IO）；
 *   整批草稿一次写入，成功记下账户 id 并按事件数 Toast（多天待办每天一个事件，全部
 *   待办的事件数合计），错误统一映射（每条待办都会产出事件，CalendarNoTime 仅作
 *   SystemCalendar 侧的防御分支，权限类错误单独给文案）。
 */
@Composable
internal fun rememberCalendarAddState(): CalendarAddState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Toast 文案在组合期解析（回调里非组合上下文，同 TodoScreen 的 todoDeletedMessage
    // 约定）；成功文案带 %1$d 占位，回调里 String.format 补上事件数
    val deniedMessage = stringResource(R.string.calendar_permission_denied_toast)
    val noCalendarMessage = stringResource(R.string.calendar_error_no_calendar)
    val accessDeniedMessage = stringResource(R.string.calendar_error_access_denied)
    val insertFailedMessage = stringResource(R.string.calendar_error_insert_failed)
    val successTemplate = stringResource(R.string.calendar_success_toast)

    // 账户选择卡的可见请求与候选/选中态（两段式挂载的 mounted 在 CalendarPickerHost）
    var pickerVisible by rememberSaveable { mutableStateOf(false) }
    var pickerAccounts by remember { mutableStateOf(emptyList<CalendarAccount>()) }
    var pickerSelectedId by remember { mutableStateOf(-1L) }
    // 待写快照：见类 KDoc；选择卡退场或单账户直写后清掉，不留悬空引用
    var pendingTodos by remember { mutableStateOf(emptyList<Todo>()) }
    // 在途闸门：权限弹窗/列账户/选择卡/写入全链路只允许一条在跑（编辑顶栏的同步块
    // 点完不消失，连点必须不重复写入）；取消选择、权限拒绝、写入结束或异常都释放
    var inFlight by remember { mutableStateOf(false) }

    // 写入本体：待办列表 → 事件草稿（CalendarEventPlanner 纯函数，多天待办每天一个事件）
    // → ContentResolver 整批插入所选日历；成功记下账户 id（下次多账户预选）并按事件数 Toast
    val insertCalendarEvents: (List<Todo>, Long) -> Unit = { todos, calendarId ->
        scope.launch {
            try {
                val drafts = todos.flatMap { CalendarEventPlanner.drafts(it) }
                when (
                    val result = withContext(Dispatchers.IO) {
                        SystemCalendar(context.contentResolver).insertEvents(drafts, calendarId)
                    }
                ) {
                    is TodoReadResult.Ok -> {
                        CalendarPreferences.saveLastCalendarId(context, calendarId)
                        AppToasts.show(context, String.format(successTemplate, result.value.size))
                    }
                    is TodoReadResult.Err -> AppToasts.show(
                        context,
                        if (result.error == TodoError.CalendarAccessDenied) {
                            accessDeniedMessage
                        } else {
                            insertFailedMessage
                        },
                    )
                }
            } finally {
                inFlight = false
            }
        }
    }

    // 列出设备日历账户并分流（口径见本函数 KDoc）
    val startCalendarInsert: (List<Todo>) -> Unit = { todos ->
        scope.launch {
            val accounts = when (
                val result = withContext(Dispatchers.IO) {
                    SystemCalendar(context.contentResolver).listCalendars()
                }
            ) {
                is TodoReadResult.Err -> {
                    pendingTodos = emptyList()
                    inFlight = false
                    AppToasts.show(
                        context,
                        if (result.error == TodoError.CalendarAccessDenied) {
                            accessDeniedMessage
                        } else {
                            insertFailedMessage
                        },
                    )
                    return@launch
                }
                is TodoReadResult.Ok -> result.value
            }
            if (accounts.isEmpty()) {
                pendingTodos = emptyList()
                inFlight = false
                AppToasts.show(context, noCalendarMessage)
                return@launch
            }
            if (accounts.size == 1) {
                pendingTodos = emptyList()
                insertCalendarEvents(todos, accounts.first().id)
            } else {
                val lastId = CalendarPreferences.loadLastCalendarId(context)
                pickerSelectedId = accounts.firstOrNull { it.id == lastId }?.id
                    ?: accounts.first().id
                pickerAccounts = accounts
                pickerVisible = true
            }
        }
    }

    // 日历运行时权限（READ+WRITE 一起请求）：全部授予续跑写入流程（待写快照在点按
    // 那一刻记下），拒绝则 Toast
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            if (pendingTodos.isNotEmpty()) {
                startCalendarInsert(pendingTodos)
            } else {
                inFlight = false
            }
        } else {
            pendingTodos = emptyList()
            inFlight = false
            AppToasts.show(context, deniedMessage)
        }
    }

    val begin: (List<Todo>) -> Unit = { todos ->
        if (!inFlight && todos.isNotEmpty()) {
            // 所有待办都能产出事件（日期回退链见 CalendarEventPlanner）；空列表在这里兜底
            pendingTodos = todos
            inFlight = true
            val needed = arrayOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR,
            )
            val granted = needed.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
            if (granted) {
                startCalendarInsert(todos)
            } else {
                permissionLauncher.launch(needed)
            }
        }
    }

    return CalendarAddState(
        visible = pickerVisible,
        accounts = pickerAccounts,
        selectedId = pickerSelectedId,
        begin = begin,
        onSelect = { pickerSelectedId = it },
        onConfirm = { calendarId ->
            pickerVisible = false
            val todos = pendingTodos
            if (todos.isNotEmpty()) {
                insertCalendarEvents(todos, calendarId)
            } else {
                inFlight = false
            }
        },
        // 用户取消/遮罩/返回关闭选择卡：当场释放闸门（待写快照在退场播完后清）
        onDismiss = {
            pickerVisible = false
            inFlight = false
        },
        onDismissed = { pendingTodos = emptyList() },
    )
}

/**
 * 日历账户选择卡的宿主：「添加到日历」在多账户设备上弹出的选择卡（内容见
 * [CalendarAccountPickerSheet]）。宿主在详情浮层**之后**组合本组件（组合顺序即绘制
 * 顺序，选择卡盖在详情之上）；两段式挂载在此收口——[CalendarAddState.visible] 只
 * 请求显示/关闭，退场播完 onDismissed 才卸载。
 *
 * 卸载必须由本宿主完成（退场回调里把挂载标志置假）：[CalendarAccountPickerSheet] 滑出后
 * 外层仍是一层全屏可点击遮罩（滑到全隐只是画得透明，点击照吞），漏掉卸载会让页面
 * 从此点不动。
 */
@Composable
internal fun CalendarPickerHost(
    state: CalendarAddState,
    hazeState: HazeState? = null,
) {
    var pickerMounted by remember { mutableStateOf(false) }
    if (state.visible) pickerMounted = true
    if (pickerMounted) {
        CalendarAccountPickerSheet(
            visible = state.visible,
            accounts = state.accounts,
            selectedId = state.selectedId,
            onSelect = state.onSelect,
            onConfirm = state.onConfirm,
            onDismiss = state.onDismiss,
            onDismissed = {
                // 先清待写快照（流程自己的收尾），再撤掉挂载——顺序无依赖，但两者都不能漏
                state.onDismissed()
                pickerMounted = false
            },
            hazeState = hazeState,
        )
    }
}
