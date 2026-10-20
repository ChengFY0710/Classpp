package com.fangyi.classpp.ui.schedule

import android.content.Context

/**
 * 课程卡片「显示教师」全局开关：SharedPreferences 单键存储（同 [CardHeightPreferences] /
 * [com.fangyi.classpp.ui.theme.ThemePreferences] 模式，单值不值得引入 DataStore；apply
 * 异步落盘，UI 状态由调用方同步更新）。控制课程卡片上教师行的显隐，关闭后地点行上移、
 * 行高预算自动归还（见 CourseCard 的 hasTeacher 联动）。
 */
object TeacherDisplayPreferences {
    private const val FILE_NAME = "teacher_display_prefs"
    private const val KEY_SHOW_TEACHER = "show_teacher"

    /** 读取开关；无记录回退 true（默认显示教师，与既有卡片行为一致） */
    fun load(context: Context): Boolean =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_TEACHER, true)

    /** 保存开关（apply 异步落盘，不阻塞点击） */
    fun save(context: Context, show: Boolean) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_TEACHER, show)
            .apply()
    }
}
