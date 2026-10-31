package com.fangyi.classpp.data.xmu

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.fangyi.classpp.MainActivity
import com.fangyi.classpp.R
import com.fangyi.classpp.data.AppContainer
import com.fangyi.classpp.data.notification.CHANNEL_SYNC
import com.fangyi.classpp.data.notification.NotificationPoster
import com.fangyi.classpp.data.notification.ensureNotificationChannels

/**
 * 每日自动同步的执行体：后台闹钟到点与进 App 补漏共用同一条路径。
 *
 * 与手动同步的差异是**静默语义**：不开预览、不问确认——到期后按预览页「默认全选」
 * 的口径导入全部新条目（restoreItems 在 commitSync 内必然全部恢复），完成发系统通知
 * 汇报；无新增仅补基线（markSynced，与手动同步「已是最新」分支同口径）；
 * **所有失败一律静默**——登录过期（未开自动续期无法后台重登）、账号变化（清空记录
 * 必须用户确认）、服务/网络问题都只留诊断日志，下次触发自愈，绝不自动 resetSyncData。
 */
object XmuDailySyncRunner {

    /** 执行结果（诊断/测试用，UI 不消费） */
    sealed interface Outcome {
        /** 开关关 / 未连接 / 今天已同步：无需执行 */
        data object Skipped : Outcome

        /** 同步成功，无新增无恢复，仅补映射基线 */
        data object UpToDate : Outcome

        /** 已提交导入/恢复（数值可为 0：恢复条目全部失败时） */
        data class Imported(val imported: Int, val restored: Int) : Outcome

        /** 拉取或提交失败：已静默记录 */
        data object Failed : Outcome
    }

    /**
     * 到点执行入口：[XmuDailySyncScheduler.shouldRun] 判定 → prepareSync(force=true)
     * （绕过 3 秒去抖；路由按自动续期状态自动选原生/WebView 路径）→ 全量导入或补基线
     * → 发通知汇报。
     */
    suspend fun runIfDue(context: Context): Outcome {
        val appContext = context.applicationContext
        val sync = AppContainer.get(appContext).xmuSyncRepository
        if (!XmuDailySyncScheduler.shouldRun(
                enabled = XmuSyncPreferences.isDailySyncEnabled(appContext),
                connected = sync.state.value.uid.isNotEmpty(),
                lastSyncAtMillis = sync.state.value.lastSyncAtMillis,
                nowMillis = System.currentTimeMillis(),
            )
        ) {
            return Outcome.Skipped
        }
        XmuDebugLog.d("dailySync begin")
        return when (val outcome = sync.prepareSync(force = true)) {
            is XmuPrepareOutcome.Ready -> importAll(appContext, sync, outcome.plan)
            is XmuPrepareOutcome.Failed -> {
                XmuDebugLog.d("dailySync prepare failed ${outcome.error}")
                Outcome.Failed
            }
        }
    }

    private suspend fun importAll(
        context: Context,
        sync: XmuSyncRepository,
        plan: XmuSyncPlan,
    ): Outcome {
        // 无新增无恢复：与手动同步「已是最新」分支同口径，只补映射基线与 lastSyncAt；
        // 落盘失败按失败处理（下次触发重拉）
        if (plan.newItems.isEmpty() && plan.restoreItems.isEmpty()) {
            return if (sync.markSynced(plan)) Outcome.UpToDate else Outcome.Failed
        }
        return when (
            val result = sync.commitSync(plan, plan.newItems.mapTo(mutableSetOf()) { it.todo.id })
        ) {
            is XmuCommitOutcome.Done -> {
                XmuDebugLog.d(
                    "dailySync imported=${result.report.imported} restored=${result.report.restored}",
                )
                if (result.report.imported + result.report.restored > 0) {
                    notifyResult(context, result.report.imported, result.report.restored)
                }
                Outcome.Imported(result.report.imported, result.report.restored)
            }
            is XmuCommitOutcome.Failed -> {
                XmuDebugLog.d("dailySync commit failed ${result.error}")
                Outcome.Failed
            }
        }
    }

    /** 结果汇报通知：单 id 覆盖式；点按进 App 主页（落默认 tab），用户自行查看待办 */
    private fun notifyResult(context: Context, imported: Int, restored: Int) {
        ensureNotificationChannels(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val title = context.getString(R.string.xmu_auto_sync_notif_title)
        val text = context.getString(R.string.xmu_auto_sync_notif_text, imported, restored)
        val notification = NotificationPoster.buildStandard(
            context = context,
            channel = CHANNEL_SYNC,
            title = title,
            text = text,
            isCourse = false,
            contentIntent = openAppIntent(context),
        )
        try {
            manager.notify(title.hashCode(), notification)
        } catch (_: SecurityException) {
            // 通知权限恰在检查后被撤销等静默失败场景：汇报本身不关键，不重试
        }
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN_APP,
            Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 与通知落页 intent（tab extras、requestCode 0）区分开，避免 filterEquals 相同互相覆盖 */
    private const val REQUEST_CODE_OPEN_APP = 1002
}
