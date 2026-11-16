package com.fangyi.classpp.ui.settings

import android.content.Context

/**
 * 日程页提醒卡「其他日期显示逾期待办」开关持久化：SharedPreferences 单键存储
 * （模式同 [OverlayTopBarPreferences]）。
 *
 * 开启时 [com.fangyi.classpp.ui.components.DayTodoAlert] 在查看非今天的日期时仍带上
 * 已逾期（截止早于今天）的未完成待办；关闭（默认）时只看该日期前后截止的待办，
 * 逾期拖欠不跨日期常驻。看今天的卡片不受开关影响（「今明后天 + 逾期」是原口径）。
 * 关闭时全天任务提醒卡（[com.fangyi.classpp.ui.components.DayTaskAlldayAlert]）的
 * 让位口径同步变化，同一期不会在两卡重复。
 */
object AgendaPreferences {
    private const val FILE_NAME = "agenda_prefs"
    private const val KEY_SHOW_OVERDUE_TODOS = "show_overdue_todos"

    /** 读取「其他日期显示逾期待办」开关；无记录时回退关闭（逾期不跨日期常驻） */
    fun loadShowOverdueTodos(context: Context): Boolean =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_OVERDUE_TODOS, false)

    /** 保存「其他日期显示逾期待办」开关（apply 异步落盘；UI 状态由调用方同步更新） */
    fun saveShowOverdueTodos(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_OVERDUE_TODOS, enabled)
            .apply()
    }
}
