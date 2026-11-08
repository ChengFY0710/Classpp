package com.fangyi.classpp.data.sync

import android.content.Context

/**
 * 活跃学校 id 的持久化（单活账号，指导 §2.5 / §2.6）。
 *
 * 存储位置独立于任何学校：`school_prefs.xml` 只有 `active_school` 一个键。它不是敏感数据，
 * 也不排除备份——恢复到一台没有该校数据的设备上只会显示「未连接」，无害。
 *
 * 注册表在构造时读一次（失效 id 回退注册首项），切校成功后写回；后台重排闹钟的轻量路径
 * 也直接读它，不装配完整会话。
 */
interface ActiveSchoolStore {

    /** 上次选中的学校 id；未设置返回 null */
    fun read(): String?

    /** 落盘选中的学校 id（apply 异步，不阻塞切换） */
    fun write(schoolId: String)
}

/** `school_prefs.xml` / `active_school`（新增，非敏感；指导 §2.6 明确不排除备份） */
class PrefsActiveSchoolStore(context: Context) : ActiveSchoolStore {

    private val appContext = context.applicationContext

    override fun read(): String? = prefs().getString(KEY_ACTIVE_SCHOOL, null)

    override fun write(schoolId: String) {
        prefs().edit().putString(KEY_ACTIVE_SCHOOL, schoolId).apply()
    }

    private fun prefs() = appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    companion object {
        const val FILE_NAME = "school_prefs"
        const val KEY_ACTIVE_SCHOOL = "active_school"
    }
}

/**
 * 无持久化实现（默认值）：单元测试与不落盘的轻量场景使用。读恒为 null、写丢弃，
 * 注册表因此始终落在注册首项（厦大）。
 */
internal object NoPersistActiveSchoolStore : ActiveSchoolStore {
    override fun read(): String? = null
    override fun write(schoolId: String) = Unit
}
