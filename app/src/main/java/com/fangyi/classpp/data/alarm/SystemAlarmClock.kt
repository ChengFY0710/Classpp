package com.fangyi.classpp.data.alarm

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

/**
 * 系统时钟应用的一次性闹钟写入器（[AlarmClock.ACTION_SET_ALARM]）：只封装 Intent 构造
 * 与启动失败映射，不含业务规则（目标时刻由 [AlarmClockPlanner] 决定）。
 *
 * 口径：
 * - 静默添加（`EXTRA_SKIP_UI = true`）：不打断用户；Intent 是单向的，是否真正落库由
 *   时钟应用决定，本类只保证「已成功交给系统」；
 * - 只响一次：不传 `EXTRA_DAYS`——系统时钟据此按一次性闹钟处理，不设重复星期；
 * - 标签取待办标题（`EXTRA_MESSAGE`）：部分时钟应用会在闹钟编辑页展示；
 * - 时钟应用的 SET_ALARM 入口以普通权限 `com.android.alarm.permission.SET_ALARM`
 *   校验调用方（Manifest 已声明，安装即授予）；个别 ROM 仍可能拒绝，与「没有可用
 *   时钟应用」一样返回 false，由调用方 Toast 提示，绝不崩调用方。启动发生在主线程
 *   （Compose 回调），与 ScheduleScreen 的分享路径同约定。
 */
object SystemAlarmClock {

    /** 添加一条指定「当天分钟数」的一次性闹钟；没有可用时钟应用或被系统拒绝返回 false */
    fun add(context: Context, minuteOfDay: Int, label: String): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, minuteOfDay / 60)
            putExtra(AlarmClock.EXTRA_MINUTES, minuteOfDay % 60)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            // ROM 侧的 SET_ALARM 调用方校验拒绝（Manifest 权限未授予等）
            false
        }
    }
}
