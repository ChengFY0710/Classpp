package com.fangyi.classpp.data.sync.remote

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
 * 远端声明学校共享的每日同步到点 receiver：只处理 [ACTION_FIRE]（调度器自发的显式广播，
 * manifest 注册 exported=false 且**无** intent-filter——系统事件的重排由厦大冻结 receiver
 * 全局承担，本类不重复）。
 *
 * 与冻结 receiver 同模式（goAsync + 独立协程 + 异常兜底）：到点执行活跃学校的同步，
 * 随后续排次日闹钟。类名与 requestCode/action 是远端锚点的锁定项——发布后不可改。
 */
class SchoolDailySyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val appContext = context.applicationContext
        val pending = goAsync()
        // 后台路径兜底：崩溃静默吞掉，错过的重排/同步由下一次触发时机自愈
        val handler = CoroutineExceptionHandler { _, _ -> }
        CoroutineScope(SupervisorJob() + Dispatchers.Default + handler).launch {
            try {
                val dailySync = AppContainer.get(appContext).dailySync
                dailySync.runIfDue(appContext)
                dailySync.scheduleNext(appContext)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** 到点执行自动同步的显式广播 action（只有调度器自己发） */
        const val ACTION_FIRE = "com.fangyi.classpp.data.sync.action.REMOTE_DAILY_SYNC_FIRE"
    }
}
