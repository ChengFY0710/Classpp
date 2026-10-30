package com.fangyi.classpp.ui.settings

import android.content.Context

/**
 * 「更高级的玻璃材质」开关持久化：SharedPreferences 单键存储（模式同 ThemePreferences）。
 *
 * 开关开启时玻璃材质应用于底部导航栏三胶囊、待办页顶栏胶囊、设置页顶栏返回按钮、
 * 课表页顶栏周数胶囊与 OverlaySheet / BottomSelectSheet 顶栏按钮（Backdrop
 * 液态玻璃渲染 + Q弹按压）；关闭即各自的原实现。默认关闭——老用户升级后观感不变。
 * 样式参数与组合本地（[com.fangyi.classpp.ui.theme.LocalGlassStyle]）收在 ui.theme
 * 的 LiquidGlass.kt，此处只管持久化。
 *
 * 低版本降级由各调用侧门控：lens 折射需 API 33+、blur 需 API 31+，
 * API 31 以下玻璃效果不生效，维持原渲染，本偏好只管「用户想要玻璃材质」这件事本身。
 */
object GlassPreferences {
    // 文件/键名沿用初版（nav_ 前缀是当时只管导航栏的遗留）：改名会让已存档的
    // 开关状态静默丢失，存储标识保持稳定，语义由类名与本注释承载
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
