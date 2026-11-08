package com.fangyi.classpp.data.sync

import android.content.Context

/**
 * 每日同步的调度入口（校无关）：重排下一次闹钟、到点/进 App 补漏执行一次。
 *
 * 单活语义：实现永远只作用于 [SchoolRegistry.activeSchoolId] 指向的学校与
 * [SchoolRegistry.dailySyncToggle] 的开关存储。UI 与宿主只依赖本契约，不接触任何学校的
 * 调度实现（依赖方向：`ui` → `data/sync`；学校适配层提供实现）。
 *
 * 行为不变量：开关关闭时 [scheduleNext] 取消闹钟；[runIfDue] 在「开关关 / 未连接 /
 * 今天已同步」时直接跳过，所有失败静默（后台路径不能弹 UI，下次触发自愈）。
 */
interface DailySyncController {

    /** 幂等重排：任何时机调用都收敛到活跃学校的当前开关状态 */
    fun scheduleNext(context: Context)

    /** 到点执行 / 进 App 补漏：内部自行判定是否应当执行，可安全重复调用 */
    suspend fun runIfDue(context: Context)
}
