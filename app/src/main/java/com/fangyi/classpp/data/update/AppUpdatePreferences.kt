package com.fangyi.classpp.data.update

import android.content.Context

/**
 * 「启动时检查更新」开关持久化：SharedPreferences 单键存储（模式同 GlassPreferences 等
 * 布尔设置项）。
 *
 * 开启时 app 冷启动（`savedInstanceState == null` 的真冷启动，旋转等重建不算）静默检查
 * 一次远端清单：发现新版本弹「检查更新」浮层，已是最新或检查失败零提示；关闭时不发起
 * 任何启动期请求，更新检查回到纯手动（关于页入口）。
 */
object AppUpdatePreferences {
    private const val FILE_NAME = "app_update_prefs"
    private const val KEY_CHECK_ON_STARTUP = "check_on_startup"

    /** 读取「启动时检查更新」开关；无记录时默认开启 */
    fun loadCheckOnStartup(context: Context): Boolean =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CHECK_ON_STARTUP, true)

    /** 保存「启动时检查更新」开关（apply 异步落盘；UI 状态由调用方同步更新，不等落盘） */
    fun saveCheckOnStartup(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CHECK_ON_STARTUP, enabled)
            .apply()
    }
}
