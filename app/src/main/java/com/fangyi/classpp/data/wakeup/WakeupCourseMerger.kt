package com.fangyi.classpp.data.wakeup

/**
 * Wakeup 课程合并（纯函数）。Wakeup 下发的数据是逐条占位（同一门课会拆成多条），导入前
 * 按指南规则收敛成「一门课一条占位」：
 * 1. 同课程（名称/教师/地点/星期/自定义时间全同）且节次相同 → 周次取并集（1-2 周[1,2,3] +
 *    1-2 周[3,4] → 1-2 周[1..4]）；
 * 2. 同课程且周次完全一致、节次连续或重叠 → 合成一节（1-2 + 3-4 → 1-4；重叠合并避免
 *    落到 Classpp 网格后触发同格冲突校验）；迭代到不动点（1-2 + 3-4 + 5-6 需要合并两轮）；
 * 3. 完全重复的占位（周次并集后自然相同）在此过程中一并去重。
 */
object WakeupCourseMerger {

    /** 合并入口：返回结果按 星期/起始节次/课名 排序，输出稳定（便于测试与颜色轮询） */
    fun merge(courses: List<WakeupCourse>): List<WakeupCourse> =
        courses
            // 周次先归一：过滤非法周号、去重、排序——后续「周次一致」按列表相等判断
            .map { course -> course.copy(weeks = course.weeks.filter { it > 0 }.distinct().sorted()) }
            .groupBy {
                // 分组键：名称/教师/地点/星期/自定义起止时间全同才算同一门课
                Key(it.name, it.teacher, it.position, it.day, it.customStartTime, it.customEndTime)
            }
            .flatMap { (_, group) -> mergeGroup(group) }
            .sortedWith(compareBy({ it.day }, { it.startSection }, { it.name }, { it.teacher.orEmpty() }))

    private data class Key(
        val name: String,
        val teacher: String?,
        val position: String?,
        val day: Int,
        val customStart: String?,
        val customEnd: String?,
    )

    private fun mergeGroup(group: List<WakeupCourse>): List<WakeupCourse> {
        // ① 同节次 → 周次并集（保留首条的起止节次形态，周次归一）
        var current = group
            .groupBy { it.startSection to it.endSection }
            .map { (_, sameSlots) ->
                val first = sameSlots.first()
                first.copy(weeks = sameSlots.flatMapToSet())
            }

        // ② 周次一致且节次相邻/重叠 → 合并跨度，迭代到不动点
        while (true) {
            val mergedPair = findMergeable(current) ?: break
            val (a, b) = mergedPair
            current = current.filter { it !== a && it !== b } + a.mergeSpanWith(b)
        }
        return current
    }

    /** 找第一对「周次列表完全一致且节次相邻或重叠」的占位 */
    private fun findMergeable(courses: List<WakeupCourse>): Pair<WakeupCourse, WakeupCourse>? {
        for (i in courses.indices) {
            for (j in i + 1 until courses.size) {
                val a = courses[i]
                val b = courses[j]
                if (a.weeks == b.weeks && a.startSection <= b.endSection + 1 && b.startSection <= a.endSection + 1) {
                    return a to b
                }
            }
        }
        return null
    }

    private fun WakeupCourse.mergeSpanWith(other: WakeupCourse): WakeupCourse = copy(
        startSection = minOf(startSection, other.startSection),
        endSection = maxOf(endSection, other.endSection),
    )

    private fun List<WakeupCourse>.flatMapToSet(): List<Int> =
        flatMap { it.weeks }.distinct().sorted()
}
