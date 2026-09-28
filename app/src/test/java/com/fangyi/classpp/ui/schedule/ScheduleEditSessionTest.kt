package com.fangyi.classpp.ui.schedule

import androidx.compose.runtime.saveable.SaverScope
import com.fangyi.classpp.data.model.CourseColor
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.WeekPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 编辑态草稿：增/改/删操作与三类 diff（新增/修改/删除），Saver 往返（旋转/进程重建） */
class ScheduleEditSessionTest {

    private fun entry(id: String, name: String, startSlot: Int = 1) = CourseEntry(
        id = id,
        name = name,
        teacher = "老师",
        location = "@学武楼 101",
        dayOfWeek = 1,
        startSlot = startSlot,
        weeks = WeekPattern.everyWeek(20),
        color = CourseColor.Blue,
    )

    @Test
    fun addMarksCourseAsAdded() {
        val session = ScheduleEditSession(initial = listOf(entry("a", "已有")))
        session.add(entry("b", "新增"))

        assertEquals(listOf("a", "b"), session.courses.map { it.id })
        assertEquals(listOf("b"), session.addedCourses().map { it.id })
        assertTrue(session.updatedCourses().isEmpty())
        assertTrue(session.removedCourseIds().isEmpty())
    }

    @Test
    fun updateReplacesDraftEntryAndShowsInDiff() {
        val session = ScheduleEditSession(initial = listOf(entry("a", "旧名"), entry("b", "别的课")))
        session.update("a", entry("a", "新名"))

        assertEquals("新名", session.courses.first { it.id == "a" }.name)
        assertEquals("别的课", session.courses.first { it.id == "b" }.name)
        assertEquals(listOf("a"), session.updatedCourses().map { it.id })
        assertTrue(session.addedCourses().isEmpty())
        assertTrue(session.removedCourseIds().isEmpty())
    }

    @Test
    fun updateToSameContentIsNotADiff() {
        val session = ScheduleEditSession(initial = listOf(entry("a", "已有")))
        session.update("a", entry("a", "已有"))

        assertTrue(session.updatedCourses().isEmpty())
    }

    @Test
    fun updateUnknownIdIsNoOp() {
        val session = ScheduleEditSession(initial = listOf(entry("a", "已有")))
        session.update("zzz", entry("zzz", "不存在"))

        assertEquals(listOf("a"), session.courses.map { it.id })
    }

    @Test
    fun removeShowsInDiff() {
        val session = ScheduleEditSession(initial = listOf(entry("a", "已有"), entry("b", "另一门")))
        session.remove("a")

        assertEquals(listOf("b"), session.courses.map { it.id })
        assertEquals(listOf("a"), session.removedCourseIds())
    }

    @Test
    fun removeAddedCourseIsNotARemovalDiff() {
        val session = ScheduleEditSession(initial = emptyList())
        session.add(entry("b", "新增"))
        session.remove("b")

        assertTrue(session.courses.isEmpty())
        assertTrue(session.addedCourses().isEmpty())
        assertTrue(session.removedCourseIds().isEmpty())
    }

    @Test
    fun saverRoundTripKeepsDraftAndDiffs() {
        val session = ScheduleEditSession(initial = listOf(entry("a", "旧名"), entry("b", "要删的")))
        session.update("a", entry("a", "新名"))
        session.remove("b")
        session.add(entry("c", "新加的"))

        val saved = with(ScheduleEditSession.Saver) {
            SaverScope { true }.save(session)
        }
        val restored = ScheduleEditSession.Saver.restore(saved!!)

        assertTrue(restored!!.active)
        assertEquals(listOf("a", "c"), restored.courses.map { it.id })
        assertEquals("新名", restored.courses.first { it.id == "a" }.name)
        assertEquals(listOf("c"), restored.addedCourses().map { it.id })
        assertEquals(listOf("a"), restored.updatedCourses().map { it.id })
        assertEquals(listOf("b"), restored.removedCourseIds())
    }
}
