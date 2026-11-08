package com.fangyi.classpp.data.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import java.util.Calendar

/**
 * 每日自动同步的调度层（校无关，AlarmManager 胶水）：每天本地时间 [TRIGGER_HOUR] 点注册
 * 一个一次性唤醒闹钟，到点由学校适配层的 receiver 执行同步并续排次日。
 *
 * 触发用 [AlarmManager.setAndAllowWhileIdle]：日同步允许分钟级漂移（doze 节流下最坏
 * 十几分钟），不值得占用精确闹钟额度；错过（关机/深度休眠被跳过）由进 App 补漏兜底，
 * 精确性不是硬约束。
 *
 * **单活语义**：开关只读 [activeSchoolId] 指向的学校（[dailySyncToggle]），切校后重排
 * 即按新校开关收敛；非活跃学校不参与每日同步。
 *
 * **不引用任何 receiver 类**（指导 §2.7）：显式广播的 PendingIntent 由注入的
 * [fireIntentFactory] 构造，学校适配层把接收类与 requestCode 锁在自己的薄壳里。
 *
 * [scheduleNext] 的全部调用时机——排期幂等（同 requestCode 覆盖注册），任何时机重复
 * 调用都收敛到当前设置：
 * - 闹钟到点（receiver 执行完同步后续排次日）；
 * - 开机完成 / 系统时间·时区·日期变化（receiver，仅重排不同步）；
 * - 进 App（MainActivity 装配完成后，自愈一切漏排）；
 * - 同步页「每日自动同步」开关变化；
 * - 切校成功（必须重排，否则闹钟停留在旧校开关上）。
 */
class DailySyncScheduler(
    private val activeSchoolId: () -> String,
    private val dailySyncToggle: (String) -> DailySyncToggle,
    private val fireIntentFactory: (Context) -> PendingIntent,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 幂等排下一次闹钟：开关关闭时取消，否则注册下一次 [TRIGGER_HOUR] 点的一次性唤醒闹钟 */
    fun scheduleNext(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        val pi = fireIntentFactory(appContext)
        if (!dailySyncToggle(activeSchoolId()).enabled.value) {
            alarmManager.cancel(pi)
            return
        }
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextTriggerMillis(clock()),
            pi,
        )
    }

    companion object {
        /** 每天自动同步的固定时刻（本地时区，24 小时制）——冻结契约，不得改动 */
        const val TRIGGER_HOUR = 6

        // ---------- 纯函数（JVM 单测覆盖） ----------

        /** 当天 [TRIGGER_HOUR] 点整（本地时区）的 epoch 毫秒 */
        internal fun scheduledAtOfDay(nowMillis: Long): Long {
            val cal = Calendar.getInstance()
            cal.timeInMillis = nowMillis
            cal.set(Calendar.HOUR_OF_DAY, TRIGGER_HOUR)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        /** 下一个触发时刻：今天 [TRIGGER_HOUR] 未到即今天，已过（含恰好到点）则明天 */
        internal fun nextTriggerMillis(nowMillis: Long): Long {
            val today = scheduledAtOfDay(nowMillis)
            if (nowMillis < today) return today
            // 加一天后重新锚定时/分字段：跨 DST 偏移（如 +1 天落到 5:00）也被拉回 6:00
            val cal = Calendar.getInstance()
            cal.timeInMillis = today
            cal.add(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, TRIGGER_HOUR)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        /**
         * 是否该立即执行一次自动同步（闹钟到点与进 App 补漏共用的判定）：
         * 开关开 + 已连接 + 已到当天触发时刻 + 上次成功同步早于当天触发时刻。
         * [lastSyncAtMillis] 由 commitSync/markSynced 维护，手动同步同样计入——
         * 「今天已同步过」不区分方式；6:00 前的手动同步不占用当天 6:00 的名额。
         */
        internal fun shouldRun(
            enabled: Boolean,
            connected: Boolean,
            lastSyncAtMillis: Long,
            nowMillis: Long,
        ): Boolean {
            if (!enabled || !connected) return false
            val todayTrigger = scheduledAtOfDay(nowMillis)
            return nowMillis >= todayTrigger && lastSyncAtMillis < todayTrigger
        }
    }
}
