package com.fangyi.classpp.data.sync.remote

import android.content.Context
import com.fangyi.classpp.data.sync.SchoolProvider
import com.fangyi.classpp.data.sync.SchoolRegistry

/** 手动拉取结果（设置页据此提示；不区分「部分学校被跳过」的细节） */
sealed interface RemoteRefreshOutcome {
    /** 拉取成功且有新学校注册 */
    data class Updated(val added: Int) : RemoteRefreshOutcome

    /** 拉取成功但没有新增（已注册/声明未变化） */
    data object UpToDate : RemoteRefreshOutcome

    /** 清单拉取或解码失败（缓存保持原样） */
    data object Failed : RemoteRefreshOutcome
}

/**
 * 远端声明学校的用户侧入口（设置页学校选择浮层）：
 * [refresh] **仅由用户点击「更新远端学校列表」触发**，首次连接确认读写 [RemoteSchoolPrefs]。
 *
 * 纪律：远端学校**默认关闭**——没有总开关、没有后台/定时拉取；从未手动更新（无本地
 * 缓存）时启动不注册远端校。手动更新成功后声明落盘并注册，冷启动从本地缓存恢复
 * （只读磁盘，不联网）。
 */
class RemoteSchoolManager(context: Context, private val registry: SchoolRegistry) {

    private val appContext = context.applicationContext
    private val prefs = RemoteSchoolPrefs(appContext)
    private val catalog = SchoolRemoteCatalog(cacheDir = SchoolRemoteConfig.cacheDir(appContext))

    fun isConfirmed(schoolId: String): Boolean = prefs.isConfirmed(schoolId)

    fun confirm(schoolId: String) = prefs.confirm(schoolId)

    /** 手动拉取、落盘并注册；失败保留缓存（[RemoteRefreshOutcome.Failed]） */
    suspend fun refresh(): RemoteRefreshOutcome {
        val declarations = catalog.refresh() ?: return RemoteRefreshOutcome.Failed
        val added = registry.registerRemote(declarations.toRemoteProviders())
        return if (added > 0) RemoteRefreshOutcome.Updated(added) else RemoteRefreshOutcome.UpToDate
    }
}

/** 声明 → provider：坏声明（拉取层已校验，这里防御）单条跳过，不拖垮整批 */
internal fun List<SchoolDeclaration>.toRemoteProviders(): List<SchoolProvider> =
    mapNotNull { declaration -> runCatching { RemoteSchoolProvider(declaration) }.getOrNull() }
