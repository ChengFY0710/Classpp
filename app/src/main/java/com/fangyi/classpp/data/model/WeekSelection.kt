package com.fangyi.classpp.data.model

/**
 * 周数选择集 → [WeekPattern]（课表编辑的添加弹窗用）。
 *
 * 弹窗里用户点选的是一组周号（方格），模型存的是区间并集，这里做贪心合并：
 * 连续段合并成 [Parity.ALL]，等差 2（同奇偶）的段合并成 [Parity.ODD]/[Parity.EVEN]，
 * 输出与原集合**逐周等价**（见 WeekSelectionTest 的全量比对）。
 *
 * 与 TimeText 同风格：纯函数、不抛异常。空集合返回空 pattern（语义是"从未上课"，
 * 它不是校验错误，得由调用方自己拦）。
 */
fun weeksFromSelection(weeks: Collection<Int>): WeekPattern {
    val sorted = weeks.filter { it >= 1 }.distinct().sorted()
    if (sorted.isEmpty()) return WeekPattern()

    val segments = mutableListOf<WeekSegment>()
    var index = 0
    while (index < sorted.size) {
        // 从当前位置同时试"步长 1"与"步长 2"能延伸多远，取更长的那种：
        // 1,2,3 → [1-3,ALL]；1,3,5 → [1-5,ODD]；2,4 → [2-4,EVEN]
        val stepOne = runLength(sorted, index, step = 1)
        val stepTwo = runLength(sorted, index, step = 2)
        val start = sorted[index]
        if (stepTwo > stepOne) {
            val end = start + 2 * (stepTwo - 1)
            segments += WeekSegment(start, end, if (start % 2 == 1) Parity.ODD else Parity.EVEN)
            index += stepTwo
        } else {
            segments += WeekSegment(start, start + stepOne - 1, Parity.ALL)
            index += stepOne
        }
    }
    return WeekPattern(segments)
}

/** 从 [from] 起、按 [step] 连续命中的已选周个数（当前位置必然命中，故至少 1） */
private fun runLength(sorted: List<Int>, from: Int, step: Int): Int {
    var count = 1
    var expected = sorted[from] + step
    var index = from + 1
    while (index < sorted.size && sorted[index] == expected) {
        count++
        expected += step
        index++
    }
    return count
}
