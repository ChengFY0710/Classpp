package com.fangyi.classpp.data.sync

import android.content.Context

/**
 * 自动同步的执行入口（校无关）：应用冷启动执行一次、待办页下拉执行，共用同一条静默导入路径。
 *
 * 单活语义：实现永远只作用于 [SchoolRegistry.activeSchoolId] 指向的学校与
 * [SchoolRegistry.dailySyncToggle] 的开关存储。UI 与宿主只依赖本契约，不接触任何学校的
 * 实现（依赖方向：`ui` → `data/sync`；学校适配层提供实现）。
 *
 * 行为不变量：[runAtStartup] 在「启动时自动同步」开关关 / 未连接时直接跳过；
 * [runOnPull] 不看启动开关（下拉开关由 UI 检查），仅要求已连接；
 * 两者所有失败一律静默（入口可安全重复调用，失败由下一次触发自愈）。
 */
interface DailySyncController {

    /**
     * 冷启动执行：应用每次冷启动调用一次。开关关或未连接时跳过；静默全量导入，
     * 有更新发系统通知汇报（无更新零提示）。
     */
    suspend fun runAtStartup(context: Context)

    /**
     * 待办页下拉执行：静默全量导入，不发通知，返回执行结果供 UI toast 反馈。
     * 不受「启动时自动同步」开关控制（下拉开关由 UI 检查）；未连接时返回
     * [DailySyncRunner.Outcome.Skipped]。
     */
    suspend fun runOnPull(context: Context): DailySyncRunner.Outcome
}
