package com.fangyi.classpp.schools.xmu

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.fangyi.classpp.data.sync.DailySyncController
import com.fangyi.classpp.data.sync.DailySyncRunner
import com.fangyi.classpp.data.sync.DailySyncScheduler
import com.fangyi.classpp.data.sync.SchoolRegistry
import com.fangyi.classpp.data.xmu.XmuDailySyncAlarmReceiver
import com.fangyi.classpp.data.xmu.XmuDebugLog

/**
 * 每日同步的厦大薄壳：把通用 [DailySyncScheduler] / [DailySyncRunner] 接到冻结的
 * [XmuDailySyncAlarmReceiver] 上（指导 §2.7 受控例外）。
 *
 * `data/sync` 因此不 import 任何 receiver 类：显式广播的 PendingIntent（接收类 +
 * requestCode 1001 + ACTION_FIRE）在这里构造，冻结常量与已发布的闹钟锚点全部不变。
 */
class XmuDailySyncController(registry: SchoolRegistry) : DailySyncController {

    private val scheduler = DailySyncScheduler(
        activeSchoolId = { registry.activeSchoolId.value },
        dailySyncToggle = { id -> registry.dailySyncToggle(id) },
        fireIntentFactory = ::firePendingIntent,
    )

    private val runner = DailySyncRunner(registry, XmuDebugLog::d)

    override fun scheduleNext(context: Context) {
        scheduler.scheduleNext(context)
    }

    override suspend fun runIfDue(context: Context) {
        runner.runIfDue(context)
    }
}

/** 到点执行广播：接收类、requestCode、action 三项是已发布闹钟的冻结锚点，不得改动 */
private fun firePendingIntent(context: Context): PendingIntent =
    PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, XmuDailySyncAlarmReceiver::class.java)
            .setAction(XmuDailySyncAlarmReceiver.ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

/** 单条闹钟的固定 requestCode（本功能只有一个闹钟位） */
private const val REQUEST_CODE = 1001
