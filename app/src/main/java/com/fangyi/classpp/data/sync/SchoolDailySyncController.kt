package com.fangyi.classpp.data.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context

/**
 * 每日同步的通用控制器（指导 §2.7）：把 [DailySyncScheduler] / [DailySyncRunner] 接到
 * **活跃学校**声明的闹钟锚点上（[SchoolProvider.dailySyncFireIntent]，由各校薄壳持有
 * 接收类与 requestCode，通用层不引用任何 receiver 类）。
 *
 * 换校 = 换锚点：不同学校的 PendingIntent（接收类/requestCode）互不等价，
 * `AlarmManager.cancel` 只对同锚点生效——锚点交换经 [SyncAlarmAnchorSwap] 统一
 * 「先取消上一锚点再交出新锚点」，同锚点时 cancel + set 幂等收敛，
 * 换校后旧校闹钟也不会残留。
 *
 * UI 与宿主只依赖 [DailySyncController] 契约：开关关闭时 [scheduleNext] 取消闹钟；
 * [runIfDue] 在「开关关 / 未连接 / 今天已同步」时跳过，失败静默自愈。
 */
class SchoolDailySyncController(
    private val registry: SchoolRegistry,
    private val logger: (String) -> Unit = SyncDebugLog::d,
) : DailySyncController {

    private val scheduler = DailySyncScheduler(
        activeSchoolId = { registry.activeSchoolId.value },
        dailySyncToggle = { id -> registry.dailySyncToggle(id) },
        fireIntentFactory = { context -> activeSchoolFireIntent(context) },
    )

    private val runner = DailySyncRunner(registry, logger)

    override fun scheduleNext(context: Context) {
        // 活跃学校未声明锚点 = 不参与每日同步：什么都不排（开关语义等价「常关」）
        if (registry.dailySyncFireIntent() == null) return
        scheduler.scheduleNext(context)
    }

    override suspend fun runIfDue(context: Context) {
        runner.runIfDue(context)
    }

    /** 活跃学校的闹钟锚点：经 [swap] 做「取消旧锚点 → 构造新锚点」的换轨 */
    private fun activeSchoolFireIntent(context: Context): PendingIntent {
        val appContext = context.applicationContext
        val factory = registry.dailySyncFireIntent()
            ?: error("active school '${registry.activeSchoolId.value}' has no daily sync alarm")
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        return swap.next(
            cancelLast = { last -> alarmManager?.cancel(last) },
        ) {
            factory(appContext)
        }
    }

    private val swap = SyncAlarmAnchorSwap<PendingIntent>()
}

/**
 * 闹钟锚点的换轨器：**先取消上一锚点、再构造新锚点**。
 *
 * 为什么不能只靠「同 requestCode 覆盖注册」：覆盖只对同锚点（intent filterEquals 相同）
 * 成立；换校后新锚点的 cancel/set 不触达旧锚点已发布的闹钟，必须用旧 PendingIntent
 * 显式取消。泛型化（锚点不绑定 PendingIntent）让取消语义可在 JVM 单测直接覆盖。
 */
internal class SyncAlarmAnchorSwap<A> {

    private var last: A? = null

    /** 取消上一锚点（若有）→ 构造并记住新锚点；取消动作由调用方注入 */
    fun next(cancelLast: ((A) -> Unit)?, make: () -> A): A {
        last?.let { old -> cancelLast?.invoke(old) }
        return make().also { last = it }
    }
}
