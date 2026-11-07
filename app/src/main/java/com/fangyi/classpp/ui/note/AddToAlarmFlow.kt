package com.fangyi.classpp.ui.note

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.alarm.AlarmClockPlanner
import com.fangyi.classpp.data.alarm.SystemAlarmClock
import com.fangyi.classpp.data.model.Todo

/**
 * 「添加到闹钟」动作的共享封装（待办页 / 日程页 / AI 浮层三处详情共用）：
 * 组合期解析成功/失败 Toast 文案，返回的回调把待办映射成系统时钟的一次性闹钟并提示结果。
 *
 * 目标时刻口径见 [AlarmClockPlanner]（截止时刻优先、回退开始时刻）；时刻缺失时防御性
 * 不动作——详情菜单已按同一口径置灰，正常不会走到。Toast 文案在组合期解析（回调里不是
 * 组合上下文，同 CalendarAddFlow 的约定）。
 */
@Composable
internal fun rememberAddToAlarm(): (Todo) -> Unit {
    val context = LocalContext.current
    val successMessage = stringResource(R.string.alarm_add_success_toast)
    val failedMessage = stringResource(R.string.alarm_add_failed_toast)
    return remember(context, successMessage, failedMessage) {
        { todo ->
            AlarmClockPlanner.minuteOf(todo)?.let { minute ->
                val added = SystemAlarmClock.add(context, minute, todo.name)
                AppToasts.show(context, if (added) successMessage else failedMessage)
            }
        }
    }
}
