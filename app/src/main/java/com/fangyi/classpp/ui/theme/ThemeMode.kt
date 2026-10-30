package com.fangyi.classpp.ui.theme

import android.content.Context
import androidx.annotation.StringRes
import com.fangyi.classpp.R

/**
 * 外观模式：设置页「深色模式」三选一。
 * [System] 跟随系统深色开关（系统切换时 Activity 重建/重组自动生效），
 * [Light] / [Dark] 手动覆盖系统设置。
 */
enum class ThemeMode(@StringRes val labelRes: Int) {
    System(R.string.appearance_theme_system),
    Light(R.string.appearance_theme_light),
    Dark(R.string.appearance_theme_dark),
}

/**
 * 外观偏好持久化：SharedPreferences 单键存储。
 *
 * 选 SharedPreferences 而非 DataStore：单个枚举值不值得为此引入依赖；且 [load] 同步
 * 返回，冷启动在 super.onCreate 之前就能拿到模式（定窗口背景主题）并传入首帧组合，
 * 主题从第一帧起就是正确的，无跳变。
 */
object ThemePreferences {
    private const val FILE_NAME = "theme_prefs"
    private const val KEY_MODE = "theme_mode"

    /** 读取外观模式；无记录或记录无法识别时回退 [ThemeMode.System] */
    fun load(context: Context): ThemeMode {
        val saved = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODE, null) ?: return ThemeMode.System
        return ThemeMode.entries.firstOrNull { it.name == saved } ?: ThemeMode.System
    }

    /** 保存外观模式（apply 异步落盘；UI 状态由调用方同步更新，不等落盘） */
    fun save(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()
    }
}
