package com.fangyi.classpp.data.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.fangyi.classpp.ui.settings.NotificationPreferences
import com.fangyi.classpp.ui.settings.NotificationSettings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 通知调度总入口 receiver，三职责共用一个类：
 * - 闹钟到点（[ACTION_FIRE] 显式广播，extras 为排期时的文案快照）：按**当前**通知方式发
 *   普通通知或实况通知（Live Updates，状态栏 chip 显示事件名），弹出成功才随续排带上
 *   「刚弹过」标记——重排时的补发据此跳过本条，提醒窗口内进 app 不会对已弹过的提醒
 *   再弹一遍；越过事件时刻的迟到闹钟（非精确降级/系统延迟）直接不弹，提醒已无意义；
 * - 实况到点清理（[ACTION_CANCEL]）：通知方式为 Live Updates 时移除对应持续通知——
 *   普通通知不自动清除，留在通知栏由用户处理；
 * - 系统事件（开机 / 时间·时区·日期变化，均为受保护广播仅系统可发）：重排。
 *
 * goAsync + 独立协程：reschedule 在 IO 上读轻量 JSON（通知关闭时直接短路）、发通知走
 * 系统调用，同步做完有超时风险；finish 放在 finally 保证收尾。后台路径崩溃会弹系统级
 * 「应用已停止」对话框——CoroutineExceptionHandler 兜底静默，错过的重排由下一次触发时机自愈。
 * 通知的构建与发布逻辑在 [NotificationPoster]（与重排补发、测试入口共用）。
 */
class NotificationAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        // 实况清理：不改排期窗口，同步完成即可，无需 goAsync/重排
        if (intent.action == ACTION_CANCEL) {
            if (intent.getBooleanExtra(EXTRA_FORCE, false) ||
                NotificationPreferences.load(appContext).method == NotificationSettings.METHOD_LIVE
            ) {
                NotificationManagerCompat.from(appContext).cancel(intent.getIntExtra(EXTRA_ID, 0))
            }
            return
        }
        val pending = goAsync()
        // 后台路径兜底：开机/到点等场景崩溃会弹系统级「应用已停止」对话框——
        // 任何未预期异常都静默吞掉，错过的重排由下一次触发时机（进 app/下次闹钟）自愈
        val handler = CoroutineExceptionHandler { _, _ -> }
        CoroutineScope(SupervisorJob() + Dispatchers.Default + handler).launch {
            try {
                var justFired: Pair<String, Long>? = null
                if (intent.action == ACTION_FIRE) {
                    val item = plannedFromIntent(intent)
                    val posted = item != null &&
                        NotificationPoster.post(appContext, item, NotificationPreferences.load(appContext))
                    if (posted && item != null) {
                        // 事件时刻缺失（旧版本排期的闹钟）无从记入补发表，只能放弃去重
                        if (item.eventAtMillis > 0) justFired = item.key to item.eventAtMillis
                    }
                }
                // 到点续排窗口 / 系统事件重排：同一出口，任何时刻调用都收敛到当前设置与数据
                NotificationScheduler.reschedule(appContext, justFired)
            } finally {
                pending.finish()
            }
        }
    }

    /** extras 快照 → 排期提醒：与调度器的补发/去重同构（triggerAt 用不到，恒为 0） */
    private fun plannedFromIntent(intent: Intent): PlannedNotification? {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return null
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return null
        val now = System.currentTimeMillis()
        val eventAt = intent.getLongExtra(EXTRA_EVENT_AT, 0L)
        // 闹钟迟到越过事件时刻（非精确降级/系统延迟）且超出宽限：上课/截止已发生，不再弹
        if (eventAt in 1..(now - LATE_FIRE_GRACE_MILLIS)) return null
        return PlannedNotification(
            key = key,
            triggerAtMillis = 0L,
            eventAtMillis = eventAt,
            kind = if (intent.getStringExtra(EXTRA_KIND) == NotificationKind.Course.name) {
                NotificationKind.Course
            } else {
                NotificationKind.TodoDeadline
            },
            title = title,
            text = intent.getStringExtra(EXTRA_TEXT).orEmpty(),
            chipText = intent.getStringExtra(EXTRA_CHIP_TEXT) ?: title,
        )
    }

    companion object {
        /** 闹钟到点的显式广播 action（不进 manifest intent-filter，只有调度器自己发） */
        const val ACTION_FIRE = "com.fangyi.classpp.notification.action.FIRE"

        /** 实况到点清理的显式广播 action（同上，由调度器的 cancel 闹钟发出） */
        const val ACTION_CANCEL = "com.fangyi.classpp.notification.action.CANCEL"

        const val EXTRA_KEY = "key"
        const val EXTRA_KIND = "kind"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"

        /** 提醒的事件时刻（补发去重落表 + 迟到越过事件时刻的弃弹判定） */
        const val EXTRA_EVENT_AT = "event_at"

        /** 迟到宽限：到点触发与事件时刻的时差在宽限内都照常弹（提前 0 分钟的准点抖动兜底） */
        const val LATE_FIRE_GRACE_MILLIS = 60_000L

        /** 实况 chip 的文字（课程名/上课地点口径在排期时已定）：Live Updates 专用 */
        const val EXTRA_CHIP_TEXT = "chip_text"

        /** 实况清理目标的通知 id（= key.hashCode()，与发通知侧同源） */
        const val EXTRA_ID = "id"

        /** 强制清理（测试实况的 60 秒自动移除，不受当前通知方式约束） */
        const val EXTRA_FORCE = "force"

        /** 通知点击的落页标记：MainActivity 读它切 tab */
        const val EXTRA_NOTIFICATION_TAB = "notification_target_tab"
        const val TAB_COURSE = "course"
        const val TAB_TODO = "todo"
    }
}
