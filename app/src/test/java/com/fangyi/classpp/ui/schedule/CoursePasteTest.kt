package com.fangyi.classpp.ui.schedule

import com.fangyi.classpp.data.FieldReason
import com.fangyi.classpp.data.ScheduleError
import com.fangyi.classpp.data.ScheduleValidator
import com.fangyi.classpp.data.evenWeeks
import com.fangyi.classpp.data.oddWeeks
import com.fangyi.classpp.data.testCourse
import com.fangyi.classpp.data.testSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 跨节课程粘贴：跨度随源课保留；放不下 / 压到周数相交的课由整份预检拦下 */
class CoursePasteTest {

    @Test
    fun `pastedAt keeps span and only swaps id, day and start slot`() {
        val source = testCourse(id = "src", day = 2, startSlot = 2, span = 3)
            .copy(note = "带教材")
        val pasted = source.pastedAt(day = 5, startSlot = 4)

        assertNotEquals(source.id, pasted.id)
        assertEquals(source.copy(id = pasted.id, dayOfWeek = 5, startSlot = 4), pasted)
        assertEquals(3, pasted.span)
    }

    @Test
    fun `single slot paste still validates`() {
        val schedule = testSchedule()
        val pasted = testCourse(id = "p", span = 1).pastedAt(day = 1, startSlot = 1)

        assertTrue(ScheduleValidator.validateCourses(schedule.copy(courses = listOf(pasted))).isEmpty())
    }

    @Test
    fun `spanned paste at second to last slot fits`() {
        val schedule = testSchedule()
        val pasted = testCourse(id = "p", span = 2).pastedAt(day = 1, startSlot = 4)

        assertEquals(5, pasted.endSlot)
        assertTrue(ScheduleValidator.validateCourses(schedule.copy(courses = listOf(pasted))).isEmpty())
    }

    @Test
    fun `spanned paste at last slot is span out of range`() {
        val schedule = testSchedule()
        val pasted = testCourse(id = "p", span = 2).pastedAt(day = 1, startSlot = 5)

        val blocking = ScheduleValidator.validateCourses(schedule.copy(courses = listOf(pasted)))
            .firstOrNull { it.involvesCourse(pasted.id) }

        assertTrue(
            "expected SpanOutOfRange, got: $blocking",
            blocking is ScheduleError.CourseFieldInvalid &&
                blocking.reason == FieldReason.SpanOutOfRange,
        )
    }

    @Test
    fun `spanned paste over a course with intersecting weeks conflicts`() {
        val existing = testCourse(id = "existing", day = 1, startSlot = 3)
        val schedule = testSchedule(courses = listOf(existing))
        val pasted = testCourse(id = "p", day = 1, startSlot = 2, span = 2)
            .pastedAt(day = 1, startSlot = 2)

        val errors = ScheduleValidator.validateCourses(schedule.copy(courses = listOf(existing, pasted)))

        assertTrue(
            "expected GridConflict, got: $errors",
            errors.firstOrNull { it.involvesCourse(pasted.id) } is ScheduleError.GridConflict,
        )
    }

    @Test
    fun `spanned paste over an alternate course with disjoint weeks is allowed`() {
        val existing = testCourse(id = "existing", day = 1, startSlot = 3, weeks = oddWeeks())
        val schedule = testSchedule(courses = listOf(existing))
        val pasted = testCourse(id = "p", day = 1, startSlot = 2, span = 2, weeks = evenWeeks())
            .pastedAt(day = 1, startSlot = 2)

        val errors = ScheduleValidator.validateCourses(schedule.copy(courses = listOf(existing, pasted)))

        assertTrue("expected no errors, got: $errors", errors.isEmpty())
    }

    @Test
    fun `unrelated draft errors do not block paste`() {
        // 编辑中改小设置后草稿里可能残留无关的越界课：预检按 id 过滤后不应误拦本次粘贴
        val unrelated = testCourse(id = "stale", day = 2, startSlot = 5, span = 2)
        val schedule = testSchedule(courses = listOf(unrelated))
        val pasted = testCourse(id = "p", day = 1, startSlot = 1, span = 2)
            .pastedAt(day = 1, startSlot = 1)

        val blocking = ScheduleValidator
            .validateCourses(schedule.copy(courses = listOf(unrelated, pasted)))
            .firstOrNull { it.involvesCourse(pasted.id) }

        assertNull(blocking)
    }
}
