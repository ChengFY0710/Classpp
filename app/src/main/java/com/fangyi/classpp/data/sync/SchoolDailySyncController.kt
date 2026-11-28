package com.fangyi.classpp.data.sync

import android.content.Context

/**
 * 自动同步的通用控制器：把 [DailySyncRunner] 接到**活跃学校**的会话上（开关与连接
 * 状态由 runner 内部判定，本类不做额外门控）。
 *
 * 单活语义：实现永远只作用于 [SchoolRegistry.activeSchoolId] 指向的学校。换校无需
 * 重排或取消任何后台任务——同步只在冷启动（宿主调用 [runAtStartup]）与待办页下拉
 * （UI 调用 [runOnPull]）时发生，两者都按当时的活跃学校执行。
 *
 * UI 与宿主只依赖 [DailySyncController] 契约：所有失败静默自愈，可安全重复调用。
 */
class SchoolDailySyncController(
    private val registry: SchoolRegistry,
    private val logger: (String) -> Unit = SyncDebugLog::d,
) : DailySyncController {

    private val runner = DailySyncRunner(registry, logger)

    override suspend fun runAtStartup(context: Context) {
        runner.runAtStartup(context)
    }

    override suspend fun runOnPull(context: Context): DailySyncRunner.Outcome =
        runner.runOnPull(context)
}
