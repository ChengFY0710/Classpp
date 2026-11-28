package com.fangyi.classpp.data.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 同步行为偏好的 SharedPreferences 实现：与「启动时自动同步」开关共用 `sync_prefs`
 * 文件（备份排除规则已覆盖），按 `<id>/auto_complete_on_vanish`、
 * `<id>/sync_homework_notes`、`<id>/todo_pull_sync_enabled` 分键。
 *
 * 「远端消失自动完成」默认关闭；「同步作业要求与附件」默认开启（新增能力按默认有用的方向）；
 * 「待办页下拉同步」默认开启（与「启动时自动同步」同向：登录后即可用，未登录时各触发
 * 路径按连接状态门控，等效关闭）。
 * 单值不值得引入 DataStore，apply 异步落盘，冷进程可直接读。
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
    private val autoCompleteKey = autoCompleteOnVanishKey(schoolId)
    private val homeworkNotesKey = syncHomeworkNotesKey(schoolId)
    private val todoPullSyncKey = todoPullSyncKey(schoolId)
    private val _autoCompleteOnVanish = MutableStateFlow(
        readBoolean(appContext, autoCompleteKey, default = false),
    )
    private val _syncHomeworkNotes = MutableStateFlow(
        readBoolean(appContext, homeworkNotesKey, default = true),
    )
    private val _todoPullSync = MutableStateFlow(
        readBoolean(appContext, todoPullSyncKey, default = true),
    )

    override val autoCompleteOnVanish: StateFlow<Boolean> =
        _autoCompleteOnVanish.asStateFlow()

    override val syncHomeworkNotes: StateFlow<Boolean> =
        _syncHomeworkNotes.asStateFlow()

    override val todoPullSync: StateFlow<Boolean> =
        _todoPullSync.asStateFlow()

    override fun setAutoCompleteOnVanish(enabled: Boolean) {
        writeBoolean(autoCompleteKey, enabled)
        _autoCompleteOnVanish.value = enabled
    }

    override fun setSyncHomeworkNotes(enabled: Boolean) {
        writeBoolean(homeworkNotesKey, enabled)
        _syncHomeworkNotes.value = enabled
    }

    override fun setTodoPullSync(enabled: Boolean) {
        writeBoolean(todoPullSyncKey, enabled)
        _todoPullSync.value = enabled
    }

    private fun writeBoolean(key: String, value: Boolean) {
        appContext.getSharedPreferences(PrefsDailySyncToggle.FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, value)
            .apply()
    }

    companion object {
        fun autoCompleteOnVanishKey(schoolId: String): String =
            "$schoolId/auto_complete_on_vanish"

        fun syncHomeworkNotesKey(schoolId: String): String =
            "$schoolId/sync_homework_notes"

        fun todoPullSyncKey(schoolId: String): String =
            "$schoolId/todo_pull_sync_enabled"

        private val SAFE_ID = Regex("^[a-z][a-z0-9_]{0,31}$")

        private fun readBoolean(
            context: Context,
            key: String,
            default: Boolean,
        ): Boolean =
            context.getSharedPreferences(PrefsDailySyncToggle.FILE_NAME, Context.MODE_PRIVATE)
                .getBoolean(key, default)
    }
}
