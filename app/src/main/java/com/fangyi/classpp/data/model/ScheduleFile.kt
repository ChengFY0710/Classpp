package com.fangyi.classpp.data.model

import kotlinx.serialization.Serializable

/** 本地存储文件信封：整库单文件，`activeScheduleId` 与课表列表原子一致 */
@Serializable
data class ScheduleFile(
    val formatVersion: Int = SCHEDULE_FORMAT_VERSION,
    /** 当前激活（正在查看）的课表 id；null = 无课表 */
    val activeScheduleId: String? = null,
    val schedules: List<Schedule> = emptyList(),
    /**
     * 全局「课程时间方案」库（内置「默认」不落库，由 UI 构造）：
     * 跨课表复用。旧文件缺该字段按空列表反序列化，无需升 [SCHEDULE_FORMAT_VERSION]。
     */
    val timeSchemes: List<TimeScheme> = emptyList(),
)
