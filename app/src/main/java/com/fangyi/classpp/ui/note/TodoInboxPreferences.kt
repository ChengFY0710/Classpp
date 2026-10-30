package com.fangyi.classpp.ui.note

import android.content.Context

/**
 * 待办收集箱显示偏好：SharedPreferences 单文件双键存储（同
 * [com.fangyi.classpp.ui.settings.GlassPreferences] /
 * [com.fangyi.classpp.ui.schedule.TeacherDisplayPreferences] 模式，单值不值得引入
 * DataStore；apply 异步落盘，UI 状态由调用方同步更新）。
 *
 * - **瀑布流视图开关**（[loadMasonryView] / [saveMasonryView]）：开启时收集箱在
 *   非空分组达到 3 个后自动切两列瀑布流（一至两组仍整宽竖排，半宽卡会挤窄标题与
 *   属性行）；关闭后无论几组都整宽竖排。默认开启——保持既有布局行为不变。
 * - **列表/收集箱视图选择**（[loadInboxView] / [saveInboxView]）：记录用户最后一次
 *   选择的视图，普通冷启动也回到上次视图（rememberSaveable 只覆盖旋转/进程重建）。
 *   默认列表。
 */
object TodoInboxPreferences {
    private const val FILE_NAME = "todo_inbox_prefs"
    private const val KEY_MASONRY_VIEW = "masonry_view"
    private const val KEY_INBOX_VIEW = "inbox_view"

    /** 读取瀑布流视图开关；无记录回退 true（默认开启，与既有收集箱布局一致） */
    fun loadMasonryView(context: Context): Boolean =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MASONRY_VIEW, true)

    /** 保存瀑布流视图开关（apply 异步落盘，不阻塞点击） */
    fun saveMasonryView(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MASONRY_VIEW, enabled)
            .apply()
    }

    /** 读取上次选择的视图：true = 收集箱、false = 列表；无记录回退列表 */
    fun loadInboxView(context: Context): Boolean =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_INBOX_VIEW, false)

    /** 保存视图选择（apply 异步落盘，不阻塞切换） */
    fun saveInboxView(context: Context, inbox: Boolean) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_INBOX_VIEW, inbox)
            .apply()
    }
}
