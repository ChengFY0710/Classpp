package com.fangyi.classpp.data.xmu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 每日自动同步的闹钟 receiver，两职责共用一个类：
 * - [ACTION_FIRE]（调度器自发的显式广播，不进 manifest intent-filter）：到点执行
 *   [XmuDailySyncRunner.runIfDue]，随后续排次日闹钟；
 * - 系统事件（开机 / 时间·时区·日期变化，均为受保护广播仅系统可发）：仅幂等重排，
 *   不触发同步——错过的 6:00 由进 App 补漏兜底。
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
                if (fire) XmuDailySyncRunner.runIfDue(appContext)
                // 到点续排 / 系统事件重排：幂等；开关关闭时内部自动取消
                XmuDailySyncScheduler.scheduleNext(appContext)
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
