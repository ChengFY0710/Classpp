package com.fangyi.classpp.data.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 新校「每日自动同步」开关：所有学校**共用一个** `sync_prefs` prefs 文件，按
 * `<id>/daily_sync_enabled` 分键（指导 §2.6）。备份排除固定加一条
 * `sharedpref/sync_prefs.xml`，一次覆盖所有未来学校。
 *
 * 与既有开关同模式（单值不值得引入 DataStore，apply 异步落盘）：后台 receiver（闹钟
 * 到点）与同步页两侧读写，无 Compose 依赖，冷进程可直接读。
 *
 * provider 级与 session 级必须返回同一实例（注册表保证）：后台重排只读开关时不会
 * 装配完整会话。
 */
class PrefsDailySyncToggle(
    context: Context,
    schoolId: String,
) : DailySyncToggle {

    init {
        require(SAFE_ID.matches(schoolId)) { "invalid school id for sync prefs: '$schoolId'" }
    }

    private val appContext = context.applicationContext
    private val key = enabledKey(schoolId)
    private val _enabled = MutableStateFlow(read(appContext, key))

    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, enabled)
            .apply()
        _enabled.value = enabled
    }

    companion object {
        /** 所有新校共用的开关 prefs 文件（备份固定排除一条） */
        const val FILE_NAME = "sync_prefs"

        fun enabledKey(schoolId: String): String = "$schoolId/daily_sync_enabled"

        private val SAFE_ID = Regex("^[a-z][a-z0-9_]{0,31}$")

        private fun read(context: Context, key: String): Boolean =
            context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
                .getBoolean(key, false)
    }
}
