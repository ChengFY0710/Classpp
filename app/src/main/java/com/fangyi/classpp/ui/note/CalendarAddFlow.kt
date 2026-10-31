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
import com.fangyi.classpp.data.model.TodoTimeKind
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「添加到日历」流程的对外接口（由 [rememberCalendarAddState] 组装）：
 * - [begin]：总入口，交给 TodoDetailSheet 的 onAddToCalendar——运行时权限、列设备日历
 *   账户、单账户直写 / 多账户弹选择卡、写入与 Toast 全在此编排（宿主不再感知细节）；
 * - [visible] / [accounts] / [selectedId] 与 [onSelect] / [onConfirm] / [onDismiss] /
 *   [onDismissed]：账户选择卡的受控接线，由 [CalendarPickerHost] 消费——宿主只需在
 *   详情浮层**之后**组合它（组合顺序即绘制顺序，选择卡盖在详情之上）。
 */
internal class CalendarAddState(
    val visible: Boolean,
    val accounts: List<CalendarAccount>,
    val selectedId: Long,
    val begin: (Todo) -> Unit,
    val onSelect: (Long) -> Unit,
    val onConfirm: (Long) -> Unit,
    val onDismiss: () -> Unit,
    val onDismissed: () -> Unit,
)

/**
 * 组装「添加到日历」流程（详情浮层菜单项 → 权限 → 列账户 → 选卡/直写，写入目标支持
 * 待办页 TodoScreen 与日程页 DayScreen 两个详情浮层共用）。纯状态与回调，无 UI——
 * 选择卡的组合与两段式挂载在 [CalendarPickerHost]。
 *
 * 流程口径：
 * - 时间为「无」的待办无可写入日历的时刻，详情菜单项已置灰（TodoDetailSheet 口径），
 *   [CalendarAddState.begin] 防御性不动作；
 * - 权限 READ+WRITE_CALENDAR 一起请求（Manifest 已静态声明）：已授权直接进写入；
 *   未授权先记下待写快照再请求，全部授予后续跑流程，拒绝 Toast（下次点按再请求）；
 * - 写入待办用点菜单那一刻的详情快照（[CalendarAddState.begin] 的入参）：目标要跨过
 *   权限弹窗/选择卡两个可能漫长的中间态，不随仓库回流换（期间待办被删则写入的仍是
 *   当时内容，属可接受口径）；
 * - 设备单账户跳过选择直接写入；多账户预选上次写入的账户（[CalendarPreferences]，
 *   无记录或账户已随系统侧消失则选第一个）弹选择卡；无可用账户 Toast 提示；
 * - SystemCalendar 是阻塞 I/O 的无状态封装，调用点现造现用（包 Dispatchers.IO）；
 *   成功记下账户 id 并按事件数 Toast（多天待办每天一个事件），错误统一映射（置灰
 *   口径保证不会触发 CalendarNoTime，权限类单独给文案）。
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
    var pendingTodo by remember { mutableStateOf<Todo?>(null) }

    // 写入本体：待办 → 事件草稿（CalendarEventPlanner 纯函数）→ ContentResolver 插入
    // 所选日历；成功记下账户 id（下次多账户预选）并按事件数 Toast
    val insertCalendarEvents: (Todo, Long) -> Unit = { todo, calendarId ->
        scope.launch {
            when (
                val result = withContext(Dispatchers.IO) {
                    SystemCalendar(context.contentResolver)
                        .insertEvents(CalendarEventPlanner.drafts(todo), calendarId)
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
        }
    }

    // 列出设备日历账户并分流（口径见本函数 KDoc）
    val startCalendarInsert: (Todo) -> Unit = { todo ->
        scope.launch {
            val accounts = when (
                val result = withContext(Dispatchers.IO) {
                    SystemCalendar(context.contentResolver).listCalendars()
                }
            ) {
                is TodoReadResult.Err -> {
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
                AppToasts.show(context, noCalendarMessage)
                return@launch
            }
            if (accounts.size == 1) {
                insertCalendarEvents(todo, accounts.first().id)
                pendingTodo = null
            } else {
                val lastId = CalendarPreferences.loadLastCalendarId(context)
                pickerSelectedId = accounts.firstOrNull { it.id == lastId }?.id
                    ?: accounts.first().id
                pickerAccounts = accounts
                pickerVisible = true
            }
        }
    }

    // 日历运行时权限（READ+WRITE 一起请求）：全部授予续跑写入流程（待写快照在点菜单
    // 那一刻记下），拒绝则 Toast
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            pendingTodo?.let(startCalendarInsert)
        } else {
            AppToasts.show(context, deniedMessage)
        }
    }

    val begin: (Todo) -> Unit = { todo ->
        if (todo.timeKind != TodoTimeKind.None) {
            val needed = arrayOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR,
            )
            val granted = needed.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
            if (granted) {
                startCalendarInsert(todo)
            } else {
                pendingTodo = todo
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
            pendingTodo?.let { todo -> insertCalendarEvents(todo, calendarId) }
        },
        onDismiss = { pickerVisible = false },
        onDismissed = { pendingTodo = null },
    )
}

/**
 * 日历账户选择卡的宿主：「添加到日历」在多账户设备上弹出的选择卡（内容见
 * [CalendarAccountPickerSheet]）。宿主在详情浮层**之后**组合本组件（组合顺序即绘制
 * 顺序，选择卡盖在详情之上）；两段式挂载在此收口——[CalendarAddState.visible] 只
 * 请求显示/关闭，退场播完 onDismissed 才卸载。
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
            onDismissed = state.onDismissed,
            hazeState = hazeState,
        )
    }
}
