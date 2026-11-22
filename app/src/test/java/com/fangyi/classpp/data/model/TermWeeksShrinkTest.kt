package com.fangyi.classpp.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 学期周数缩小的候选结束日：目标周内找「最早没课的一天」；被剪掉的日期里还有课时
 * 点名阻塞周。样本学期 2026-08-31 周一开学、17 周收在周四 12-24（末周只上到周中），
 * 第 16 周 = 12-14(一) ~ 12-20(日)，第 15 周 = 12-7(一) ~ 12-13(日)。
 */
class TermWeeksShrinkTest {

    private val termStart = IsoDate.parse("2026-08-31") // 周一

    private fun course(day: Int, maxWeek: Int) = CourseEntry(
        id = "c-$day-$maxWeek",
        name = "课$day",
        teacher = "",
        location = "",
        dayOfWeek = day,
        startSlot = 1,
        span = 1,
        weeks = WeekPattern(listOf(WeekSegment(1, maxWeek))),
        color = CourseColor.Green,
    )

    private fun totalWeeksOf(end: IsoDate): Int =
        ((end.mondayOfWeek() - termStart.mondayOfWeek()).toInt() / 7) + 1

    @Test
    fun `tail free days let the term shrink into the target week`() {
        // 16 周的课只上到周五 12-18：收成 16 周放行，结束日取最早没课的 12-19（周六）
        val courses = listOf(course(day = 4, maxWeek = 16), course(day = 5, maxWeek = 16))

        val outcome = termEndForFewerWeeks(termStart, targetWeeks = 16, courses = courses)

        assertEquals(IsoDate.parse("2026-12-19"), outcome.candidateEnd)
        assertEquals(16, totalWeeksOf(outcome.candidateEnd!!))
        assertNull(outcome.blockingWeek)
    }

    @Test
    fun `target week occupied through sunday is rejected naming that week`() {
        // 16 周周日（12-20）仍有课：尾部空不出一天 → 点名第 16 周
        val courses = listOf(course(day = 5, maxWeek = 16), course(day = 7, maxWeek = 16))

        val outcome = termEndForFewerWeeks(termStart, targetWeeks = 16, courses = courses)

        assertNull(outcome.candidateEnd)
        assertEquals(16, outcome.blockingWeek)
    }

    @Test
    fun `classes in a week beyond the target are rejected naming that week`() {
        // 第 17 周周一（12-21）仍有课：收成 16 周必然裁掉它 → 点名第 17 周
        val courses = listOf(course(day = 5, maxWeek = 16), course(day = 1, maxWeek = 17))

        val outcome = termEndForFewerWeeks(termStart, targetWeeks = 16, courses = courses)

        assertNull(outcome.candidateEnd)
        assertEquals(17, outcome.blockingWeek)
    }

    @Test
    fun `empty target week ends on its monday`() {
        // 16 周整周没课（最后一次上课在 15 周周五 12-11）：收成 16 周取目标周周一 12-14
        val courses = listOf(course(day = 5, maxWeek = 15))

        val outcome = termEndForFewerWeeks(termStart, targetWeeks = 16, courses = courses)

        assertEquals(IsoDate.parse("2026-12-14"), outcome.candidateEnd)
        assertEquals(16, totalWeeksOf(outcome.candidateEnd!!))
    }

    @Test
    fun `shrink to the week of the last class ends on its first free day`() {
        // 16 周整周没课、15 周周六周日也没课：收成 15 周取最早没课的 15 周周六（12-12）
        val courses = listOf(course(day = 5, maxWeek = 15))

        val outcome = termEndForFewerWeeks(termStart, targetWeeks = 15, courses = courses)

        assertEquals(IsoDate.parse("2026-12-12"), outcome.candidateEnd)
        assertEquals(15, totalWeeksOf(outcome.candidateEnd!!))
    }

    @Test
    fun `no courses at all shrinks to the target week monday`() {
        for (weeks in listOf(1, 8, 16)) {
            val outcome = termEndForFewerWeeks(termStart, weeks, courses = emptyList())
            assertEquals(weeks, totalWeeksOf(outcome.candidateEnd!!))
        }
    }

    @Test
    fun `last class on the sunday before the target week still fits`() {
        // 15 周周日（12-13）最后一次课，收成 16 周：最早没课的一天即目标周周一 12-14
        val courses = listOf(course(day = 7, maxWeek = 15))

        val outcome = termEndForFewerWeeks(termStart, targetWeeks = 16, courses = courses)

        assertEquals(IsoDate.parse("2026-12-14"), outcome.candidateEnd)
    }
}
