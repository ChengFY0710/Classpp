package com.fangyi.classpp.schools.xmu

import android.content.Context
import com.fangyi.classpp.data.sync.DailySyncController
import com.fangyi.classpp.data.xmu.XmuDailySyncRunner
import com.fangyi.classpp.data.xmu.XmuDailySyncScheduler

/**
 * 厦大的每日同步入口：委托冻结的三件套实现（调度/执行体/receiver 均保持原样）。
 *
 * 这是 P2 的过渡薄壳——通用 scheduler/runner 迁入 `data/sync` 后（指导 Phase 3），
 * 通用侧直接实现 [DailySyncController] 即可，UI/宿主调用点不变。
 */
object XmuDailySyncController : DailySyncController {

    override fun scheduleNext(context: Context) {
        XmuDailySyncScheduler.scheduleNext(context)
    }

    override suspend fun runIfDue(context: Context) {
        XmuDailySyncRunner.runIfDue(context)
    }
}
