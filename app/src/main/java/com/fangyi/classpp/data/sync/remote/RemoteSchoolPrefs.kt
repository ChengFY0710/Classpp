package com.fangyi.classpp.data.sync.remote

import android.content.Context

/**
 * 远端声明分发链路的本地偏好（非敏感，与 `school_prefs` 同级，**不**进备份排除）。
 *
 * 只有首次连接确认记录：[isConfirmed] / [confirm] 按校分键
 * （`remote/<id>/confirmed`）。信任边界：远端白名单不再来自编译期代码，用户必须先确认
 * 目标域名。
 *
 * 远端学校**默认关闭**：没有总开关、没有自动联网；用户手动更新后声明落盘，
 * 冷启动从本地缓存恢复（见 [RemoteSchoolManager]）。
 */
class RemoteSchoolPrefs(context: Context) {

    private val appContext = context.applicationContext

    fun isConfirmed(schoolId: String): Boolean {
        require(SAFE_ID.matches(schoolId)) { "invalid school id for remote prefs: '$schoolId'" }
        return prefs().getBoolean(confirmedKey(schoolId), false)
    }

    fun confirm(schoolId: String) {
        require(SAFE_ID.matches(schoolId)) { "invalid school id for remote prefs: '$schoolId'" }
        prefs().edit().putBoolean(confirmedKey(schoolId), true).apply()
    }

    private fun prefs() = appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    companion object {
        /** 远端适配专用 prefs 文件（非敏感；与 school_prefs 同为「恢复后无害」语义） */
        const val FILE_NAME = "remote_sync_prefs"

        /** 首次连接确认键：`remote/<id>/confirmed` */
        fun confirmedKey(schoolId: String): String = "remote/$schoolId/confirmed"

        private val SAFE_ID = Regex("^[a-z][a-z0-9_]{0,31}$")
    }
}
