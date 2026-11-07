package com.fangyi.classpp.data.alarm

import com.fangyi.classpp.data.model.Todo

/**
 * 待办 → 系统时钟闹钟时刻的纯函数映射（只取「当天分钟数」，不取日期）：
 * 优先截止时刻（[Todo.deadlineMinute]），没有则回退待办开始时刻（[Todo.startMinute]）；
 * 两者都无返回 null（详情菜单据此置灰）。
 *
 * 系统时钟应用的一次性闹钟只能表达「时:分」，不携带具体日期——截止/开始落在哪一天
 * 不影响本映射（重复待办的「下一次重复开始前」也只折算日期，时刻沿用存储值）。
 */
object AlarmClockPlanner {

    /** 闹钟目标时刻（当天分钟数，0..1439）；截止时刻优先，其次开始时刻，都无返回 null */
    fun minuteOf(todo: Todo): Int? = todo.deadlineMinute ?: todo.startMinute
}
