package com.fangyi.classpp.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「今日课程」组件的边界刷新。
 *
 * 「当前节高亮」只在节次边界变化，所以不靠 30 分钟周期轮询：取「今天下一节课开始 /
 * 当前课结束 / 次日零点」里最近的一个下**精确**闹钟（无精确闹钟权限时按
 * [NotificationScheduler][com.fangyi.classpp.data.notification.NotificationScheduler]
 * 同一口径降级为非精确）。每次触发后由接收器续排下一次，幂等整体替换。
 */
internal object WidgetRefresh {
    private const val ACTION_FIRE = "com.fangyi.classpp.widget.action.TODAY_COURSES_REFRESH"
    private const val REQUEST_CODE = 0x71C0

    /** 幂等续排下一次边界闹钟；读盘与排闹钟都在 IO 上。 */
    suspend fun scheduleNext(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
            ?: return@withContext
        val now = System.currentTimeMillis()
        val triggerAt = nextRefreshMillis(loadActiveSchedule(appContext), now)
        val pendingIntent = firePendingIntent(appContext)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        if (exactAllowed) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
                return@withContext
            } catch (_: SecurityException) {
                // 落到下方非精确降级
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
    }

    /** 组件被全部移除时调用：撤掉未到的边界闹钟。 */
    fun cancel(context: Context) {
        val appContext = context.applicationContext
        appContext.getSystemService(AlarmManager::class.java)
            ?.cancel(firePendingIntent(appContext))
    }

    private fun firePendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, TodayCoursesWidgetRefreshReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * 边界 / 系统事件 → 刷新全部「今日课程」组件实例并续排。
 *
 * - 自定义 FIRE：节次边界闹钟到点；
 * - BOOT_COMPLETED / TIME_SET / TIMEZONE_CHANGED / DATE_CHANGED / MY_PACKAGE_REPLACED：
 *   闹钟与「今天」的判定前提变了，整体自愈一遍。
 *
 * 无组件实例时只撤闹钟，不排新闹钟（避免卸载组件后仍常驻唤醒）。
 */
class TodayCoursesWidgetRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        val handler = CoroutineExceptionHandler { _, _ -> }
        CoroutineScope(SupervisorJob() + Dispatchers.Default + handler).launch {
            try {
                val hasWidget = GlanceAppWidgetManager(appContext)
                    .getGlanceIds(TodayCoursesWidget::class.java)
                    .isNotEmpty()
                if (hasWidget) {
                    // 与 App 内数据变化同一条路径：重写快照 + 重绘（只 updateAll 会渲染旧快照）
                    refreshTodayCoursesWidget(appContext)
                    WidgetRefresh.scheduleNext(appContext)
                } else {
                    WidgetRefresh.cancel(appContext)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
