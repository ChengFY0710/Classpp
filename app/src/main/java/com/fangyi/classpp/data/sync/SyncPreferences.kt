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

    fun setAutoCompleteOnVanish(enabled: Boolean)
}

/** 内存实现：测试或未接入持久化的场景；默认关闭 */
class MutableSyncPreferences(
    initialAutoCompleteOnVanish: Boolean = false,
) : SyncPreferences {

    private val _autoCompleteOnVanish = MutableStateFlow(initialAutoCompleteOnVanish)

    override val autoCompleteOnVanish: StateFlow<Boolean> =
        _autoCompleteOnVanish.asStateFlow()

    override fun setAutoCompleteOnVanish(enabled: Boolean) {
        _autoCompleteOnVanish.value = enabled
    }
}
