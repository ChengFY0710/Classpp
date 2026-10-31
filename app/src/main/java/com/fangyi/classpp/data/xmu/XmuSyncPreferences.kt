package com.fangyi.classpp.data.xmu

import android.content.Context

/**
 * 课程中心同步偏好：目前只有「每日自动同步」开关（设置 → 账号与同步）。
 *
 * SharedPreferences 单键存储（同 NotificationPreferences 模式——单值不值得引入
 * DataStore，apply 异步落盘）；后台 receiver（闹钟到点）与同步页两侧读写，无
 * Compose 依赖，冷进程可直接读。
 */
object XmuSyncPreferences {
    private const val FILE_NAME = "xmu_sync_prefs"
    private const val KEY_DAILY_SYNC_ENABLED = "daily_sync_enabled"

    fun isDailySyncEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DAILY_SYNC_ENABLED, false)

    /** 落盘每日自动同步开关（apply 异步，不阻塞切换） */
    fun setDailySyncEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DAILY_SYNC_ENABLED, enabled)
            .apply()
    }
}
