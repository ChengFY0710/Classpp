package com.fangyi.classpp.data.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** 本地存储文件信封：整库单文件，`activeScheduleId` 与课表列表原子一致 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ScheduleFile(
    // 整库用 storageLean（encodeDefaults=false）写入：版本号必须始终落盘，
    // 否则旧版本读到新文件时会用自己的默认版本误判为兼容
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
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

/**
 * 活跃课表：`activeScheduleId` 悬空（指向已不存在的课表）时回落首项，id 为空视为「无课表」。
 *
 * 仓库装载时会把悬空 id 修回落首项再发布，所以「发布态」与「磁盘原样」共用本规则；
 * 通知排期等只读旁路直接读文件，也必须走这里，避免两处各写一遍后漂移。
 */
val ScheduleFile.activeSchedule: Schedule?
    get() = schedules.firstOrNull { it.id == activeScheduleId }
        ?: if (activeScheduleId != null) schedules.firstOrNull() else null
