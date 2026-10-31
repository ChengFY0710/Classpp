package com.fangyi.classpp.ui.note

import android.content.Context

/**
 * 「添加到日历」偏好：SharedPreferences 单键存储（同 [TodoInboxPreferences] /
 * [com.fangyi.classpp.ui.settings.NotificationPreferences] 模式；apply 异步落盘，UI
 * 状态由调用方同步更新）。
 *
 * **上次写入的日历账户 id**（[loadLastCalendarId] / [saveLastCalendarId]）：设备有多个
 * 日历账户时，账户选择卡预选它；只有单账户时跳过选择卡直接写入、不读不写。默认 -1 =
 * 从未选过（或用户从未在多账户下确认过），调用方回退选第一个账户。账户可能随系统侧
 * 增删而失效，调用方须把 id 对回当前账户列表再使用。
 */
object CalendarPreferences {
    private const val FILE_NAME = "calendar_prefs"
    private const val KEY_LAST_CALENDAR_ID = "last_calendar_id"

    /** 读取上次写入的日历账户 id；无记录回退 -1（无选择） */
    fun loadLastCalendarId(context: Context): Long =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CALENDAR_ID, -1L)

    /** 保存上次写入的日历账户 id（apply 异步落盘，不阻塞点击） */
    fun saveLastCalendarId(context: Context, id: Long) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_CALENDAR_ID, id)
            .apply()
    }
}
