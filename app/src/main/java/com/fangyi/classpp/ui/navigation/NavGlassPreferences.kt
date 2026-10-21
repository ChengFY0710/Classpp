package com.fangyi.classpp.ui.navigation

import android.content.Context

/**
 * 底部导航栏玻璃样式持久化：SharedPreferences 单键存储（模式同 ThemePreferences）。
 *
 * 开关开启时底部三胶囊以 Backdrop 液态玻璃渲染（选中项染主题蓝、白图标，取消蓝底
 * 指示器）；关闭即原始白底实现。默认关闭——老用户升级后观感不变。
 *
 * 低版本降级由调用侧（MainActivity）门控：lens 折射需 API 33+、blur 需 API 31+，
 * API 31 以下玻璃效果不生效，维持原白底渲染，本偏好只管「用户想要玻璃」这件事本身。
 */
object NavGlassPreferences {
    private const val FILE_NAME = "nav_glass_prefs"
    private const val KEY_GLASS_STYLE = "glass_style"

    /** 读取玻璃样式开关；无记录时回退关闭（原白底实现） */
    fun load(context: Context): Boolean =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_GLASS_STYLE, false)

    /** 保存玻璃样式开关（apply 异步落盘；UI 状态由调用方同步更新，不等落盘） */
    fun save(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GLASS_STYLE, enabled)
            .apply()
    }
}
