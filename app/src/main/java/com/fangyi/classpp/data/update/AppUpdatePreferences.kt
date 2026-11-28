package com.fangyi.classpp.data.update

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 更新相关偏好持久化：SharedPreferences 单文件两件事——
 *
 * - 「启动时检查更新」开关（模式同 GlassPreferences 等布尔设置项）：开启时 app 冷启动
 *   （`savedInstanceState == null` 的真冷启动，旋转等重建不算）静默检查一次远端清单，
 *   发现新版本弹「检查更新」浮层，已是最新或检查失败零提示；关闭时不发起任何启动期
 *   请求，更新检查回到纯手动（关于页入口）。
 * - 「已知新版本 versionCode」（[knownAvailableVersionCode] 状态流的磁盘真源）：检查
 *   命中即记录，「已是最新」清零；UI 据此在设置根页「关于」入口卡与关于页「检查更新」
 *   行右侧展示「新版本」徽标，判断口径 = 记录值 > 当前安装 versionCode（升级后自动失效）。
 */
object AppUpdatePreferences {
    private const val FILE_NAME = "app_update_prefs"
    private const val KEY_CHECK_ON_STARTUP = "check_on_startup"
    private const val KEY_KNOWN_AVAILABLE_VERSION_CODE = "known_available_version_code"

    /**
     * 进程内共享的「已知新版本 versionCode」状态：冷启动 / 手动检查两侧写入（写时同步
     * 落盘），UI collect 即时跟上，不逐层透传。0 = 无已知更新；磁盘值由
     * [loadKnownAvailableVersionCode] 首次调用播种。
     */
    private val knownAvailable = MutableStateFlow(0L)

    val knownAvailableVersionCode: StateFlow<Long> = knownAvailable.asStateFlow()

    private var seeded = false

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

    /**
     * 播种「已知新版本 versionCode」：冷启动读一次磁盘进共享状态流（MainActivity
     * onCreate 调用），此后 UI 一律 collect [knownAvailableVersionCode]，不再逐次查 prefs。
     * 返回播种前的磁盘值（仅便于测试/日志，UI 不消费）。
     */
    fun loadKnownAvailableVersionCode(context: Context): Long {
        val stored = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_KNOWN_AVAILABLE_VERSION_CODE, 0L)
        if (!seeded) {
            knownAvailable.value = stored
            seeded = true
        }
        return stored
    }

    /** 记录 / 清除（0 = 无）已知新版本：apply 异步落盘，状态流即时更新供徽标当帧刷新 */
    fun saveKnownAvailableVersionCode(context: Context, versionCode: Long) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_KNOWN_AVAILABLE_VERSION_CODE, versionCode)
            .apply()
        knownAvailable.value = versionCode
    }
}
