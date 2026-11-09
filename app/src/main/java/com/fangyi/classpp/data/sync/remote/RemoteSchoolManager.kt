package com.fangyi.classpp.data.sync.remote

import android.content.Context
import com.fangyi.classpp.data.sync.SchoolProvider
import com.fangyi.classpp.data.sync.SchoolRegistry
import com.fangyi.classpp.data.sync.SyncDebugLog

/** 手动拉取结果（设置页据此提示） */
sealed interface RemoteRefreshOutcome {
    /**
     * 拉取成功且至少有一项状态变化：
     * @param added 本次新增并立即可用的学校数
     * @param changed 已注册学校的声明变化数（已写入缓存，**重启 App 后生效**）
     * @param skipped 声明无效/被拒/与已注册学校冲突而忽略的学校数
     */
    data class Refreshed(
        val added: Int,
        val changed: Int,
        val skipped: Int,
    ) : RemoteRefreshOutcome

    /** 拉取成功但没有任何变化（全部已注册且声明一致） */
    data object UpToDate : RemoteRefreshOutcome

    /** 清单拉取或解码失败（缓存保持原样） */
    data object Failed : RemoteRefreshOutcome
}

/**
 * 远端声明学校的用户侧入口（设置页学校选择浮层）：
 * [refresh] **仅由用户点击「更新学校列表」触发**，首次连接确认读写 [RemoteSchoolPrefs]。
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

    /**
     * 手动拉取、落盘并注册；失败保留缓存（[RemoteRefreshOutcome.Failed]）。
     * 声明坏/冲突的跳过数、已注册校的声明变化数都汇总进 [RemoteRefreshOutcome.Refreshed]。
     */
    suspend fun refresh(): RemoteRefreshOutcome {
        val result = catalog.refresh()
        if (result !is RemoteCatalogResult.Success) return RemoteRefreshOutcome.Failed
        val providers = result.declarations.toRemoteProviders()
        val registration = registry.registerRemote(providers)
        // 目录层跳过的条目 + provider 装配的防御性丢弃 + 注册表拒绝，统一计入 skipped
        val skipped = result.skipped.size +
            (result.declarations.size - providers.size) +
            registration.rejected
        SyncDebugLog.d(
            "remote refresh outcome added=${registration.added} " +
                "changed=${registration.changed} skipped=$skipped",
        )
        return remoteRefreshOutcome(
            added = registration.added,
            changed = registration.changed,
            skipped = skipped,
        )
    }
}

/** 三个计数 → 结果：全 0 才是「已是最新」，任一非 0 都按实际变化提示 */
internal fun remoteRefreshOutcome(
    added: Int,
    changed: Int,
    skipped: Int,
): RemoteRefreshOutcome =
    if (added == 0 && changed == 0 && skipped == 0) {
        RemoteRefreshOutcome.UpToDate
    } else {
        RemoteRefreshOutcome.Refreshed(added = added, changed = changed, skipped = skipped)
    }

/** 声明 → provider：坏声明（拉取层已校验，这里防御）单条跳过并留日志，不拖垮整批 */
internal fun List<SchoolDeclaration>.toRemoteProviders(): List<SchoolProvider> =
    mapNotNull { declaration ->
        runCatching { RemoteSchoolProvider(declaration) }
            .onFailure { error ->
                SyncDebugLog.d(
                    "remote school '${declaration.id}' provider build failed: ${error.message}",
                )
            }
            .getOrNull()
    }
