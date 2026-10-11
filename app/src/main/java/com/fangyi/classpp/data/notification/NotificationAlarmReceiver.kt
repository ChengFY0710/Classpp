package com.fangyi.classpp.data.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fangyi.classpp.MainActivity
import com.fangyi.classpp.R
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 通知调度总入口 receiver，双职责共用一个类——两条路径最终都汇到
 * [NotificationScheduler.reschedule]（幂等整体替换）：
 * - 闹钟到点（[ACTION_FIRE] 显式广播，extras 为排期时的文案快照）：先发通知，再续排窗口；
 * - 系统事件（开机 / 时间·时区·日期变化，均为受保护广播仅系统可发）：直接重排。
 *
 * goAsync + 独立协程：reschedule 要重建仓库单例（冷进程首次含磁盘读）、发通知走系统调用，
 * 同步做完有超时风险；finish 放在 finally 保证收尾。
 */
class NotificationAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
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
        val kind = intent.getStringExtra(EXTRA_KIND)
        val channel = if (kind == NotificationKind.Course.name) CHANNEL_COURSE else CHANNEL_TODO
        // 点击落页：课程提醒进课表 tab、待办提醒进待办 tab（MainActivity 读 extra 切 tab）
        val targetTab = if (kind == NotificationKind.Course.name) TAB_COURSE else TAB_TODO
        val contentIntent = PendingIntent.getActivity(
            context,
            key.hashCode(),
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_NOTIFICATION_TAB, targetTab),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        // 权限在通知开关检查过、仍可能恰在两步之间被撤销：系统对静默失败场景抛 SecurityException
        try {
            manager.notify(key.hashCode(), notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        /** 闹钟到点的显式广播 action（不进 manifest intent-filter，只有调度器自己发） */
        const val ACTION_FIRE = "com.fangyi.classpp.notification.action.FIRE"

        const val EXTRA_KEY = "key"
        const val EXTRA_KIND = "kind"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"

        /** 通知点击的落页标记：MainActivity 读它切 tab */
        const val EXTRA_NOTIFICATION_TAB = "notification_target_tab"
        const val TAB_COURSE = "course"
        const val TAB_TODO = "todo"
    }
}
