package com.fangyi.classpp.data.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 同步行为偏好的 SharedPreferences 实现：与「每日自动同步」开关共用 `sync_prefs`
 * 文件（备份排除规则已覆盖），按 `<id>/auto_complete_on_vanish` 分键。
 *
 * 默认关闭；单值不值得引入 DataStore，apply 异步落盘，冷进程可直接读。
 * 与 [PrefsDailySyncToggle] 同模式：provider 装配 session 时创建，UI 与引擎共用同一实例。
 */
class PrefsSyncPreferences(
    context: Context,
    schoolId: String,
) : SyncPreferences {

    init {
        require(SAFE_ID.matches(schoolId)) { "invalid school id for sync prefs: '$schoolId'" }
    }

    private val appContext = context.applicationContext
    private val key = autoCompleteOnVanishKey(schoolId)
    private val _autoCompleteOnVanish = MutableStateFlow(read(appContext, key))

    override val autoCompleteOnVanish: StateFlow<Boolean> =
        _autoCompleteOnVanish.asStateFlow()

    override fun setAutoCompleteOnVanish(enabled: Boolean) {
        appContext.getSharedPreferences(PrefsDailySyncToggle.FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, enabled)
            .apply()
        _autoCompleteOnVanish.value = enabled
    }

    companion object {
        fun autoCompleteOnVanishKey(schoolId: String): String =
            "$schoolId/auto_complete_on_vanish"

        private val SAFE_ID = Regex("^[a-z][a-z0-9_]{0,31}$")

        private fun read(context: Context, key: String): Boolean =
            context.getSharedPreferences(PrefsDailySyncToggle.FILE_NAME, Context.MODE_PRIVATE)
                .getBoolean(key, false)
    }
}
