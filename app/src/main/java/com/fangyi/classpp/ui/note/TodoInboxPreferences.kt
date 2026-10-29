package com.fangyi.classpp.ui.note

import android.content.Context

/**
 * 待办收集箱「瀑布流视图」开关持久化：SharedPreferences 单键存储（同
 * [com.fangyi.classpp.ui.settings.GlassPreferences] /
 * [com.fangyi.classpp.ui.schedule.TeacherDisplayPreferences] 模式，单值不值得引入
 * DataStore；apply 异步落盘，UI 状态由调用方同步更新）。
 *
 * 开启时收集箱视图在非空分组达到 3 个后自动切两列瀑布流（一至两组仍整宽竖排，
 * 半宽卡会挤窄标题与属性行）；关闭后无论几组都整宽竖排。默认开启——保持既有
 * 布局行为不变，设置项只提供关闭入口。
 */
object TodoInboxPreferences {
    private const val FILE_NAME = "todo_inbox_prefs"
    private const val KEY_MASONRY_VIEW = "masonry_view"

    /** 读取瀑布流视图开关；无记录回退 true（默认开启，与既有收集箱布局一致） */
    fun load(context: Context): Boolean =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MASONRY_VIEW, true)

    /** 保存开关（apply 异步落盘，不阻塞点击） */
    fun save(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MASONRY_VIEW, enabled)
            .apply()
    }
}
