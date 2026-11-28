package com.fangyi.classpp.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 同步行为偏好（按校持久化；UI 与同步引擎共享同一实例）。
 *
 * 偏好只影响**后续同步**：切换后不需要重建会话，下一次 prepare 即按新值出计划。
 */
interface SyncPreferences {
    /**
     * 远端条目消失时是否自动把本地对应待办标记完成（默认关闭）。
     * 关闭后：远端不再返回的条目保持未完成；已由自动完成标记的条目在远端重新出现时
     * 仍会回滚（回滚属于清理既有自动结果，不属于“标记完成”）。
     */
    val autoCompleteOnVanish: StateFlow<Boolean>

    /**
     * 是否把所有远端内容（作业要求 + 附件元数据）同步到待办（默认开启）。
     * 开启后存量同步待办的备注会被最新抓取结果覆盖，附件列表随远端更新；附件文件
     * 只在用户点击时下载。关闭后完全不发内容请求，已有备注/附件不受影响。
     * 只影响**后续同步**，切换后下一次 prepare 生效。
     */
    val syncHomeworkNotes: StateFlow<Boolean>

    /**
     * 待办页下拉同步开关（默认关闭）：开启后在待办页下拉即可唤起一次同步
     * （静默自动导入，结果 toast 反馈）。纯 UI 手势开关，不影响其它同步路径。
     */
    val todoPullSync: StateFlow<Boolean>

    fun setAutoCompleteOnVanish(enabled: Boolean)

    fun setSyncHomeworkNotes(enabled: Boolean)

    fun setTodoPullSync(enabled: Boolean)
}

/** 内存实现：测试或未接入持久化的场景；作业要求同步默认开启，其余默认关闭 */
class MutableSyncPreferences(
    initialAutoCompleteOnVanish: Boolean = false,
    initialSyncHomeworkNotes: Boolean = true,
    initialTodoPullSync: Boolean = false,
) : SyncPreferences {

    private val _autoCompleteOnVanish = MutableStateFlow(initialAutoCompleteOnVanish)
    private val _syncHomeworkNotes = MutableStateFlow(initialSyncHomeworkNotes)
    private val _todoPullSync = MutableStateFlow(initialTodoPullSync)

    override val autoCompleteOnVanish: StateFlow<Boolean> =
        _autoCompleteOnVanish.asStateFlow()

    override val syncHomeworkNotes: StateFlow<Boolean> =
        _syncHomeworkNotes.asStateFlow()

    override val todoPullSync: StateFlow<Boolean> =
        _todoPullSync.asStateFlow()

    override fun setAutoCompleteOnVanish(enabled: Boolean) {
        _autoCompleteOnVanish.value = enabled
    }

    override fun setSyncHomeworkNotes(enabled: Boolean) {
        _syncHomeworkNotes.value = enabled
    }

    override fun setTodoPullSync(enabled: Boolean) {
        _todoPullSync.value = enabled
    }
}
