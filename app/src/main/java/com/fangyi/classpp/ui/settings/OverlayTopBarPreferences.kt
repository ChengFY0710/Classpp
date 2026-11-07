package com.fangyi.classpp.ui.settings

import android.content.Context

/**
 * 「浮层顶栏模糊」开关持久化：SharedPreferences 单键存储（模式同 [GlassPreferences]）。
 *
 * 开启时 OverlaySheet 顶栏恢复原来的 Haze 渐变磨砂；关闭时用 Background 同色渐变遮罩
 * （顶段不透明、底缘渐隐）。默认关闭——上一版顶栏已改为渐变遮罩，老用户升级后观感不变。
 * 开关值经组合根部的 [com.fangyi.classpp.ui.components.LocalOverlayTopBarBlur] 下发，
 * OverlaySheet 顶栏在叶子内读取；此处只管持久化。
 */
object OverlayTopBarPreferences {
    private const val FILE_NAME = "overlay_topbar_prefs"
    private const val KEY_TOPBAR_BLUR = "topbar_blur"

    /** 读取浮层顶栏模糊开关；无记录时回退关闭（渐变遮罩） */
    fun load(context: Context): Boolean =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TOPBAR_BLUR, false)

    /** 保存浮层顶栏模糊开关（apply 异步落盘；UI 状态由调用方同步更新，不等落盘） */
    fun save(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TOPBAR_BLUR, enabled)
            .apply()
    }
}
