package com.fangyi.classpp.data.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.data.TodoRepository
import com.fangyi.classpp.ui.settings.NotificationPreferences
import com.fangyi.classpp.ui.settings.NotificationSettings

/**
 * 通知排期的系统层（AlarmManager 胶水）：把 [NotificationPlanner] 的排期结果注册成系统闹钟，
 * 到点由 [NotificationAlarmReceiver] 发通知并续排窗口。
 *
 * 触发机制用 [AlarmManager.setAlarmClock]（系统级精确、doze 下准点、无需用户去系统设置
 * 授权）——精确能力由 manifest 的 USE_EXACT_ALARM（13+ 自动授予）与 SCHEDULE_EXACT_ALARM
 * （maxSdk=32）支撑。个别 ROM 对 setAlarmClock 也执行精确闹钟校验（实测未声明权限时直接
 * 抛 SecurityException），因此注册一律走 [registerAlarm]：先查 canScheduleExactAlarms、
 * 再 try/catch 兜底，任何权限异常都降级为 setAndAllowWhileIdle 非精确闹钟，绝不崩调用方。
 *
 * [reschedule] 的全部调用时机——排期是幂等整体替换，任何时机重复调用都收敛到同一状态：
 * - 闹钟到点（receiver 发完通知后续排窗口）；
 * - 开机完成 / 系统时间·时区·日期变化（receiver）；
 * - 进 app：仓库数据流首次与每次变化（MainActivity 的 combine 收集）；
 * - 通知设置变化（NotificationScreen 每次落盘后）。
 */
object NotificationScheduler {

    private const val PREFS_NAME = "notification_alarms"
    private const val KEY_SCHEDULED = "scheduled_keys"

    /** 读设置与两个仓库的当前数据 → 排期 → 整体替换系统闹钟。设置全关时计划为空 = 全部取消。 */
    suspend fun reschedule(context: Context) {
        val appContext = context.applicationContext
        val settings: NotificationSettings = NotificationPreferences.load(appContext)
        val schedule = ScheduleRepository.get(appContext).activeSchedule.value
        val todos = TodoRepository.get(appContext).todos.value
        val planned = NotificationPlanner.plan(
            schedule = schedule,
            todos = todos,
            settings = settings,
            texts = NotificationTexts.from(appContext),
            nowMillis = System.currentTimeMillis(),
        )
        replaceAll(appContext, planned)
    }

    /**
     * 整体替换：先按持久化的 key 集取消全部旧闹钟，再注册新计划、回写 key 集。
     * 取消用的 PendingIntent 与注册的 filterEquals（action + 组件）一致、requestCode 同源
     * （extras 不参与匹配），旧闹钟必能精确命中取消。
     */
    private fun replaceAll(context: Context, planned: List<PlannedNotification>) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        for (key in prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()) {
            alarmManager.cancel(firePendingIntent(context, key.hashCode()))
        }
        for (item in planned) {
            registerAlarm(
                alarmManager,
                item,
                firePendingIntent(context, item.key.hashCode(), item),
                showPendingIntent(context),
            )
        }
        prefs.edit().putStringSet(KEY_SCHEDULED, planned.map { it.key }.toSet()).apply()
    }

    /**
     * 注册单条精确闹钟：优先 [AlarmManager.setAlarmClock]；canScheduleExactAlarms 为 false
     * 或调用抛 SecurityException（ROM 层额外校验）时，降级为 setAndAllowWhileIdle——
     * 触发可能有偏差（doze 节流下最坏分钟级），但任何权限状况都不允许崩掉调用方。
     */
    private fun registerAlarm(
        alarmManager: AlarmManager,
        item: PlannedNotification,
        firePi: PendingIntent,
        showPi: PendingIntent,
    ) {
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        if (exactAllowed) {
            try {
                alarmManager.setAlarmClock(
                    // showIntent：点系统闹钟指示（状态栏图标/锁屏“下一个闹钟”）时打开 app
                    AlarmManager.AlarmClockInfo(item.triggerAtMillis, showPi),
                    firePi,
                )
                return
            } catch (_: SecurityException) {
                // 落到下方非精确降级
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.triggerAtMillis, firePi)
    }

    /** 到点广播：extras 携带文案快照（排期即定格，后续编辑经重排刷新），key 供取消/去重 */
    private fun firePendingIntent(
        context: Context,
        requestCode: Int,
        item: PlannedNotification? = null,
    ): PendingIntent {
        val intent = Intent(context, NotificationAlarmReceiver::class.java)
            .setAction(NotificationAlarmReceiver.ACTION_FIRE)
        if (item != null) {
            intent
                .putExtra(NotificationAlarmReceiver.EXTRA_KEY, item.key)
                .putExtra(NotificationAlarmReceiver.EXTRA_KIND, item.kind.name)
                .putExtra(NotificationAlarmReceiver.EXTRA_TITLE, item.title)
                .putExtra(NotificationAlarmReceiver.EXTRA_TEXT, item.text)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 系统闹钟指示的展示 intent：打开 app 主页（落到默认 tab） */
    private fun showPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, com.fangyi.classpp.MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
