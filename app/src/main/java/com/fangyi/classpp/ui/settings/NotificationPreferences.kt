package com.fangyi.classpp.ui.settings

import android.content.Context

/**
 * 通知与提醒设置全集（设置 → 通知与提醒页的页面状态）。
 *
 * 普通通知已实装：本类是通知调度的唯一真源——data/notification 的 NotificationPlanner /
 * NotificationScheduler 读它排系统闹钟，通知页每次变更落盘后即触发重排。Live Updates
 * 方式暂与普通通知同行为，后续阶段接入。
 *
 * [enabled] 为总开关，语义是「同步」而非「总闸禁用」：它变化时把两个子开关一并置为
 * 同值（关→全关、开→全开），因此持久化状态恒满足「总开关关 ⇒ 子开关全关」；
 * 子开关在总开关开启期间仍可单独切换。
 */
data class NotificationSettings(
    val enabled: Boolean = true,
    // 通知方式：普通通知 / Live Updates，二选一
    val method: Int = METHOD_NORMAL,
    // 实况 chip 的展示信息（仅 Live Updates 生效）：课程名 / 上课地点
    val liveInfo: Int = LIVE_INFO_COURSE,
    val courseEnabled: Boolean = true,
    val courseLeadMinutes: Int = DefaultLeadMinutes,
    val todoEnabled: Boolean = true,
    val todoLeadMinutes: Int = DefaultLeadMinutes,
    // 待办通知的类型口径：全部 / 截止日期 / 待办开始时间 / 待办结束时间
    val todoType: Int = TODO_TYPE_ALL,
) {
    companion object {
        const val METHOD_NORMAL = 0
        const val METHOD_LIVE = 1

        const val LIVE_INFO_COURSE = 0
        const val LIVE_INFO_LOCATION = 1

        const val TODO_TYPE_ALL = 0
        const val TODO_TYPE_DEADLINE = 1
        const val TODO_TYPE_START = 2
        const val TODO_TYPE_END = 3

        /** 提前时间默认值（分钟），与设计稿一致 */
        const val DefaultLeadMinutes = 30
    }
}

/**
 * 通知与提醒全局偏好：SharedPreferences 单文件多键存储
 * （同 [com.fangyi.classpp.ui.theme.ThemePreferences] 模式，单值不值得引入 DataStore；
 * apply 异步落盘，UI 状态由调用方同步更新）。只在通知设置页读写，进页 load 一次。
 */
object NotificationPreferences {
    private const val FILE_NAME = "notification_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_METHOD = "method"
    private const val KEY_LIVE_INFO = "live_info"
    private const val KEY_COURSE_ENABLED = "course_enabled"
    private const val KEY_COURSE_LEAD = "course_lead_minutes"
    private const val KEY_TODO_ENABLED = "todo_enabled"
    private const val KEY_TODO_LEAD = "todo_lead_minutes"
    private const val KEY_TODO_TYPE = "todo_type"

    /** 通知提前时间的可调范围（分钟）：0 = 不提前（届时通知），上限 60 */
    val LEAD_RANGE = 0..60

    /** 读取全部设置项；无记录回退默认值，id/分钟数按各自合法范围钳制（脏数据不外溢） */
    fun load(context: Context): NotificationSettings {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        val defaults = NotificationSettings()
        return NotificationSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, defaults.enabled),
            method = prefs.getInt(KEY_METHOD, defaults.method)
                .coerceIn(NotificationSettings.METHOD_NORMAL, NotificationSettings.METHOD_LIVE),
            liveInfo = prefs.getInt(KEY_LIVE_INFO, defaults.liveInfo)
                .coerceIn(NotificationSettings.LIVE_INFO_COURSE, NotificationSettings.LIVE_INFO_LOCATION),
            courseEnabled = prefs.getBoolean(KEY_COURSE_ENABLED, defaults.courseEnabled),
            courseLeadMinutes = prefs.getInt(KEY_COURSE_LEAD, defaults.courseLeadMinutes)
                .coerceIn(LEAD_RANGE.first, LEAD_RANGE.last),
            todoEnabled = prefs.getBoolean(KEY_TODO_ENABLED, defaults.todoEnabled),
            todoLeadMinutes = prefs.getInt(KEY_TODO_LEAD, defaults.todoLeadMinutes)
                .coerceIn(LEAD_RANGE.first, LEAD_RANGE.last),
            todoType = prefs.getInt(KEY_TODO_TYPE, defaults.todoType)
                .coerceIn(NotificationSettings.TODO_TYPE_ALL, NotificationSettings.TODO_TYPE_END),
        )
    }

    /** 保存全部设置项（apply 异步落盘，不阻塞切换） */
    fun save(context: Context, settings: NotificationSettings) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putInt(KEY_METHOD, settings.method)
            .putInt(KEY_LIVE_INFO, settings.liveInfo)
            .putBoolean(KEY_COURSE_ENABLED, settings.courseEnabled)
            .putInt(KEY_COURSE_LEAD, settings.courseLeadMinutes)
            .putBoolean(KEY_TODO_ENABLED, settings.todoEnabled)
            .putInt(KEY_TODO_LEAD, settings.todoLeadMinutes)
            .putInt(KEY_TODO_TYPE, settings.todoType)
            .apply()
    }
}
