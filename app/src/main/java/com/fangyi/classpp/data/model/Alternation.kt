package com.fangyi.classpp.data.model

/**
 * 交替课程的纯函数支持：同一格（同一天 + 同一起始节次）上的多门课，各自在不同的周出现。
 *
 * 不引入分组字段——判定完全落在"天 + 起始节"上：同格课程的 span 必然相交，故
 * ScheduleValidator R13（R13 同一天 span 相交且周数相交 ⇒ 冲突）已经把"周数不重合"
 * 变成硬约束，组内任意一周至多一门在课。列表顺序即添加顺序（草稿从课表列表起底，
 * 新加的课追加在末尾、编辑按 id 原位置换），"首先添加的那门"由此确定。
 */

/** 同一格（同一天 + 同一起始节）的全部课程；空列表 = 该格没有课 */
fun List<CourseEntry>.cellCourses(dayOfWeek: Int, startSlot: Int): List<CourseEntry> =
    filter { it.dayOfWeek == dayOfWeek && it.startSlot == startSlot }

/**
 * 同格**其它**课程占用的周号（1-based，截到 [totalWeeks]）。
 * [selfId] 为空 = 新建场景，把整组都算作占用。
 */
fun List<CourseEntry>.weeksTakenByOthers(
    selfId: String,
    dayOfWeek: Int,
    startSlot: Int,
    totalWeeks: Int,
): Set<Int> = buildSet {
    for (course in cellCourses(dayOfWeek, startSlot)) {
        if (course.id == selfId) continue
        for (week in 1..totalWeeks) if (course.weeks.contains(week)) add(week)
    }
}

/**
 * 组内没被用过的首个配色（按 [CourseColor.entries] 顺序）。
 * 新建交替课时用它做默认值，避免与已有课同色——同色时卡片上下两段色条分辨不出来。
 * 全被占用时回退首个配色。
 */
fun List<CourseEntry>.firstUnusedColor(dayOfWeek: Int, startSlot: Int): CourseColor {
    val used = cellCourses(dayOfWeek, startSlot).mapTo(mutableSetOf()) { it.color }
    return CourseColor.entries.firstOrNull { it !in used } ?: CourseColor.entries.first()
}
