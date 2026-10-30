package com.fangyi.classpp.data.model

/**
 * 交替课程的纯函数支持：同一天上 span 相交（占用节次有重合）的多门课互为交替课程，各自在不同的周出现。
 *
 * 不引入分组字段——判定完全落在"天 + span 相交"上：span 相交的课程周数必然不重合
 * （ScheduleValidator R13：同一天 span 相交且周数相交 ⇒ 冲突），故组内任意一周至多一门在课。
 * 跨节课程（span ≥ 2）会与其续格上起始节不同的课构成同组——这正是"设置跨节后，被占节次里
 * 周数不冲突的既有课程一起成为交替课程"的数据基础，原有课程的起止节次保持不变。
 * 列表顺序即添加顺序（草稿从课表列表起底，新加的课追加在末尾、编辑按 id 原位置换），
 * "首先添加的那门"由此确定。
 */

/** 同一天里 span 与 [startSlot..endSlot] 相交的全部课程（含自身）；空列表 = 该范围内没有课 */
fun List<CourseEntry>.overlappingCourses(
    dayOfWeek: Int,
    startSlot: Int,
    endSlot: Int,
): List<CourseEntry> =
    filter {
        it.dayOfWeek == dayOfWeek &&
            it.startSlot <= endSlot && it.endSlot >= startSlot
    }

/**
 * 同一天里与草稿范围 [startSlot..endSlot] 相交的**其它**课程占用的周号（1-based，截到 [totalWeeks]）。
 * [selfId] 为空 = 新建场景，把整组都算作占用。
 * 跨节时续格上的课也会计入——交替课程的周数不能与组内任何一门重合。
 */
fun List<CourseEntry>.weeksTakenByOthers(
    selfId: String,
    dayOfWeek: Int,
    startSlot: Int,
    endSlot: Int,
    totalWeeks: Int,
): Set<Int> = buildSet {
    for (course in overlappingCourses(dayOfWeek, startSlot, endSlot)) {
        if (course.id == selfId) continue
        for (week in 1..totalWeeks) if (course.weeks.contains(week)) add(week)
    }
}

/**
 * 组内没被用过的首个配色（按 [CourseColor.entries] 顺序）。
 * 新建交替课时用它做默认值，避免与已有课同色——同色时卡片上下两段色条分辨不出来。
 * 全被占用时回退首个配色。
 */
fun List<CourseEntry>.firstUnusedColor(
    dayOfWeek: Int,
    startSlot: Int,
    endSlot: Int,
): CourseColor {
    val used = overlappingCourses(dayOfWeek, startSlot, endSlot).mapTo(mutableSetOf()) { it.color }
    return CourseColor.entries.firstOrNull { it !in used } ?: CourseColor.entries.first()
}
