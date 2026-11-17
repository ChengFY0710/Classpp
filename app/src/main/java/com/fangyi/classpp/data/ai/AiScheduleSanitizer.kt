package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.WeekPattern

/**
 * AI 识别导入的课表清理：在 `ShareCodec` 的全量校验前，把「同一门课、同一天、同一节次范围、
 * 周次相交」的多条记录合并成一条。
 *
 * 典型来源：教务网页/截图把同一门课的多个平行班（不同教师/教室）都列在同一格，或把同一门课
 * 按周拆成多条。直接导入会被 `ScheduleValidator` 判成 `GridConflict`（界面提示「与某门课
 * 时间冲突」）。这里按「课程名 + 星期 + 起始节 + 跨度」分组，把周次相交的记录聚成一簇：
 * 保留字段最完整的一条，周次取簇内并集；周次不相交（单/双周、不同周段）的记录保持独立。
 *
 * 只处理**同名课程**的内部重复；不同课程名之间的真实冲突不做掩盖，仍交给校验报错。
 */
internal object AiScheduleSanitizer {

    fun sanitize(schedule: Schedule): Schedule {
        if (schedule.courses.size < 2) return schedule

        val keptIds = mutableSetOf<String>()
        val weekOverrides = mutableMapOf<String, WeekPattern>()

        schedule.courses
            .groupBy { Key(it.dayOfWeek, it.startSlot, it.span, it.name.trim()) }
            .values
            .forEach { group ->
                // 组内按「周次相交」求连通分量：A∩B、B∩C ⇒ A/B/C 同簇
                val remaining = group.toMutableList()
                while (remaining.isNotEmpty()) {
                    val cluster = mutableListOf(remaining.removeAt(0))
                    var changed = true
                    while (changed) {
                        changed = false
                        val iterator = remaining.iterator()
                        while (iterator.hasNext()) {
                            val candidate = iterator.next()
                            if (cluster.any { it.weeks.intersects(candidate.weeks) }) {
                                cluster += candidate
                                iterator.remove()
                                changed = true
                            }
                        }
                    }
                    val representative = cluster.maxByOrNull(::completeness) ?: cluster.first()
                    keptIds += representative.id
                    if (cluster.size > 1) {
                        // 并集语义：段可以直接拼接，允许重叠
                        weekOverrides[representative.id] =
                            WeekPattern(cluster.flatMap { it.weeks.segments })
                    }
                }
            }

        val courses = schedule.courses.mapNotNull { course ->
            if (course.id !in keptIds) return@mapNotNull null
            course.copy(weeks = weekOverrides[course.id] ?: course.weeks)
        }
        if (courses.size == schedule.courses.size) return schedule

        val kept = courses.mapTo(mutableSetOf()) { it.id }
        return schedule.copy(
            courses = courses,
            // AI 导入理论上不产出调课；若未来有，丢弃指向被合并课程的调课，避免悬空引用
            adjustments = schedule.adjustments.filter { it.courseId in kept },
        )
    }

    /** 保留字段更完整的一条：教师/地点优先，其次备注；相同时保留先出现的 */
    private fun completeness(course: CourseEntry): Int =
        (if (course.teacher.isNotBlank()) 2 else 0) +
            (if (course.location.isNotBlank()) 2 else 0) +
            (if (course.note.isNotBlank()) 1 else 0)

    private data class Key(
        val dayOfWeek: Int,
        val startSlot: Int,
        val span: Int,
        val name: String,
    )
}
