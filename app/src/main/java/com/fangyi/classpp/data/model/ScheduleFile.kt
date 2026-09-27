package com.fangyi.classpp.data.model

import kotlinx.serialization.Serializable

/** 本地存储文件信封：整库单文件，`activeScheduleId` 与课表列表原子一致 */
@Serializable
data class ScheduleFile(
    val formatVersion: Int = SCHEDULE_FORMAT_VERSION,
    /** 当前激活（正在查看）的课表 id；null = 无课表 */
    val activeScheduleId: String? = null,
    val schedules: List<Schedule> = emptyList(),
)
