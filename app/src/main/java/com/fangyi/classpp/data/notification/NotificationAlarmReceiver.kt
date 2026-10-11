package com.fangyi.classpp.data.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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
 *   普通通知或实况通知（Live Updates，状态栏 chip 显示事件名），随后续排窗口；
 * - 实况到点清理（[ACTION_CANCEL]）：通知方式为 Live Updates 时移除对应持续通知——
 *   普通通知不自动清除，留在通知栏由用户处理；
 * - 系统事件（开机 / 时间·时区·日期变化，均为受保护广播仅系统可发）：重排。
 *
 * goAsync + 独立协程：reschedule 要重建仓库单例（冷进程首次含磁盘读）、发通知走系统调用，
 * 同步做完有超时风险；finish 放在 finally 保证收尾。后台路径崩溃会弹系统级「应用已停止」
 * 对话框——CoroutineExceptionHandler 兜底静默，错过的重排由下一次触发时机自愈。
 * 通知的构建逻辑在 [NotificationPoster]（与测试入口共用）。
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
                if (intent.action == ACTION_FIRE) post(appContext, intent)
                // 到点续排窗口 / 系统事件重排：同一出口，任何时刻调用都收敛到当前设置与数据
                NotificationScheduler.reschedule(appContext)
            } finally {
                pending.finish()
            }
        }
    }

    /** 发通知：渠道就绪 + 通知开关（运行时权限/渠道可被用户在系统侧关闭）双检后才发 */
    private fun post(context: Context, intent: Intent) {
        ensureNotificationChannels(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val chipText = intent.getStringExtra(EXTRA_CHIP_TEXT) ?: title
        val isCourse = intent.getStringExtra(EXTRA_KIND) == NotificationKind.Course.name
        val channel = if (isCourse) CHANNEL_COURSE else CHANNEL_TODO
        // Live Updates 仅 API 36+ 存在；低版本设备按普通通知呈现（回退语义）
        val live = NotificationPreferences.load(context).method == NotificationSettings.METHOD_LIVE &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA
        val notification = if (live) {
            NotificationPoster.buildLiveUpdate(context, channel, title, text, chipText, isCourse)
        } else {
            NotificationPoster.buildStandard(context, channel, title, text, isCourse)
        }
        // 权限在通知开关检查过、仍可能恰在两步之间被撤销：系统对静默失败场景抛 SecurityException
        try {
            manager.notify(key.hashCode(), notification)
        } catch (_: SecurityException) {
        }
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
