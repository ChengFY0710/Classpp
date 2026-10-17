package com.fangyi.classpp.data.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.fangyi.classpp.R
import com.fangyi.classpp.data.AppContainer
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

    /** cancel 闹钟在持久化 key 集里的前缀（与 fire key 同源加前缀，requestCode 同 hash） */
    private const val CANCEL_KEY_PREFIX = "X:"

    /** 读设置与两个仓库的当前数据 → 排期 → 整体替换系统闹钟。设置全关时计划为空 = 全部取消。 */
    suspend fun reschedule(context: Context) {
        val appContext = context.applicationContext
        val settings: NotificationSettings = NotificationPreferences.load(appContext)
        val container = AppContainer.get(appContext)
        val schedule = container.scheduleRepository.activeSchedule.value
        val todos = container.todoRepository.todos.value
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
     * （extras 不参与匹配），旧闹钟必能精确命中取消。key 集混合 fire / cancel 两类
     * （cancel 带 [CANCEL_KEY_PREFIX] 前缀），按前缀分派取消。
     */
    private fun replaceAll(context: Context, planned: List<PlannedNotification>) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        for (key in prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()) {
            if (key.startsWith(CANCEL_KEY_PREFIX)) {
                alarmManager.cancel(
                    cancelPendingIntent(context, key.removePrefix(CANCEL_KEY_PREFIX).hashCode()),
                )
            } else {
                alarmManager.cancel(firePendingIntent(context, key.hashCode()))
            }
        }
        val scheduledKeys = HashSet<String>(planned.size * 2)
        for (item in planned) {
            scheduleExact(
                alarmManager,
                item.triggerAtMillis,
                firePendingIntent(context, item.key.hashCode(), item),
                showPendingIntent(context),
            )
            // 倒计时实况的到点清理：事件时刻移除持续通知。两种通知方式都常排此闹钟
            // （method 可随时切换，到点行为由 receiver 按当前设置决定；普通方式下空转无害）
            scheduleExact(
                alarmManager,
                item.eventAtMillis,
                cancelPendingIntent(context, item.key.hashCode()),
                showPi = null,
            )
            scheduledKeys += item.key
            scheduledKeys += cancelKey(item.key)
        }
        prefs.edit().putStringSet(KEY_SCHEDULED, scheduledKeys).apply()
    }

    private fun cancelKey(key: String): String = CANCEL_KEY_PREFIX + key

    /**
     * 注册单条精确闹钟：showIntent 非 null 走 [AlarmManager.setAlarmClock]（系统级精确 +
     * 状态栏闹钟指示），null 走 [AlarmManager.setExactAndAllowWhileIdle]（纯精确触发）。
     * canScheduleExactAlarms 为 false 或调用抛 SecurityException（ROM 层额外校验）时，
     * 降级为 setAndAllowWhileIdle——触发可能有偏差（doze 节流下最坏分钟级），
     * 但任何权限状况都不允许崩掉调用方。
     */
    private fun scheduleExact(
        alarmManager: AlarmManager,
        triggerAtMillis: Long,
        pi: PendingIntent,
        showPi: PendingIntent?,
    ) {
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        if (exactAllowed) {
            try {
                if (showPi != null) {
                    alarmManager.setAlarmClock(
                        // showIntent：点系统闹钟指示（状态栏图标/锁屏“下一个闹钟”）时打开 app
                        AlarmManager.AlarmClockInfo(triggerAtMillis, showPi),
                        pi,
                    )
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                }
                return
            } catch (_: SecurityException) {
                // 落到下方非精确降级
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
    }

    /** 实况到点清理闹钟：移除倒计时通知（receiver 侧按当前通知方式决定是否真删） */
    private fun cancelPendingIntent(context: Context, notificationId: Int, force: Boolean = false): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            notificationId,
            Intent(context, NotificationAlarmReceiver::class.java)
                .setAction(NotificationAlarmReceiver.ACTION_CANCEL)
                .putExtra(NotificationAlarmReceiver.EXTRA_ID, notificationId)
                .putExtra(NotificationAlarmReceiver.EXTRA_FORCE, force),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 测试实况的稳定 key（固定 id：重复点击覆盖同一条；60 秒后由强制清理闹钟移除） */
    private const val TEST_KEY = "TEST_LIVE"
    private const val TEST_CLEANUP_DELAY_MILLIS = 60_000L

    /**
     * 测试入口（通知页「测试 Live Updates」按钮）：立即发布一条实况预览——测试课程 /
     * 测试时间 / 测试地点，样式与真实提醒完全一致（API 36- 回退普通通知形态），
     * 60 秒后由强制清理闹钟移除（不受当前通知方式影响）。
     *
     * @return false = 通知未开启（权限被撤/渠道全关），未发布；调用方可据此提示
     */
    fun postTestLiveUpdate(context: Context): Boolean {
        val appContext = context.applicationContext
        ensureNotificationChannels(appContext)
        val manager = NotificationManagerCompat.from(appContext)
        if (!manager.areNotificationsEnabled()) return false
        val settings = NotificationPreferences.load(appContext)
        val title = appContext.getString(R.string.notification_test_course)
        val time = appContext.getString(R.string.notification_test_time)
        val location = appContext.getString(R.string.notification_test_location)
        val text = appContext.getString(R.string.notification_course_text).format(time, location)
        // chip 文字跟随「实时通知显示信息」口径（课程名/上课地点），与真实课程提醒同规则
        val chipText = if (settings.liveInfo == NotificationSettings.LIVE_INFO_LOCATION) {
            location
        } else {
            title
        }
        val id = TEST_KEY.hashCode()
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            NotificationPoster.buildLiveUpdate(
                appContext,
                CHANNEL_COURSE,
                title,
                text,
                chipText = chipText,
                isCourse = true,
            )
        } else {
            NotificationPoster.buildStandard(appContext, CHANNEL_COURSE, title, text, isCourse = true)
        }
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            return false
        }
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        if (alarmManager != null) {
            scheduleExact(
                alarmManager,
                System.currentTimeMillis() + TEST_CLEANUP_DELAY_MILLIS,
                cancelPendingIntent(appContext, id, force = true),
                showPi = null,
            )
        }
        return true
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
                .putExtra(NotificationAlarmReceiver.EXTRA_CHIP_TEXT, item.chipText)
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
