package com.fangyi.classpp.data.xmu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fangyi.classpp.data.AppContainer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 每日自动同步的闹钟 receiver，两职责共用一个类：
 * - [ACTION_FIRE]（调度器自发的显式广播，不进 manifest intent-filter）：到点执行
 *   活跃学校的同步，随后续排次日闹钟；
 * - 系统事件（开机 / 时间·时区·日期变化，均为受保护广播仅系统可发）：仅幂等重排，
 *   不触发同步——错过的 6:00 由进 App 补漏兜底。
 *
 * 类名与包名是**已发布 PendingIntent / Manifest 的冻结锚点**（指导 §2.7）：内部只做委托，
 * 调度与执行体已迁入 `data/sync`。系统事件走 [AppContainer.scheduleDailySyncInBackground]：
 * 只读 `school_prefs` 的活跃学校与各校开关，**不载入数据仓库、不装配会话**。
 *
 * goAsync + 独立协程（同 NotificationAlarmReceiver 模式）：同步含网络往返，可能超出
 * goAsync 的广播窗口——超窗最坏是本次中断，次日闹钟与进 App 补漏自愈；后台路径崩溃
 * 会弹系统级「应用已停止」对话框，CoroutineExceptionHandler 兜底静默。
 */
class XmuDailySyncAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val fire = intent.action == ACTION_FIRE
        val pending = goAsync()
        // 后台路径兜底：崩溃静默吞掉，错过的重排/同步由下一次触发时机自愈
        val handler = CoroutineExceptionHandler { _, _ -> }
        CoroutineScope(SupervisorJob() + Dispatchers.Default + handler).launch {
            try {
                if (fire) {
                    // 到点：活跃学校会话由注册表装配/复用；完成后续排次日
                    val dailySync = AppContainer.get(appContext).dailySync
                    dailySync.runIfDue(appContext)
                    dailySync.scheduleNext(appContext)
                } else {
                    // 系统事件：只重排（轻量路径，不装配会话）
                    AppContainer.scheduleDailySyncInBackground(appContext)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** 到点执行自动同步的显式广播 action（只有调度器自己发） */
        const val ACTION_FIRE = "com.fangyi.classpp.xmu.action.DAILY_SYNC_FIRE"
    }
}
