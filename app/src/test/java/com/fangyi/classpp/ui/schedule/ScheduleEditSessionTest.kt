package com.fangyi.classpp.ui.schedule

import androidx.compose.runtime.saveable.SaverScope
import com.fangyi.classpp.data.model.CourseAdjustment
import com.fangyi.classpp.data.model.CourseColor
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.Parity
import com.fangyi.classpp.data.model.WeekPattern
import com.fangyi.classpp.data.model.WeekSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 编辑态草稿：增/改/删只作用于草稿本身，Saver 往返（旋转/进程重建） */
class ScheduleEditSessionTest {

    private fun entry(
        id: String,
        name: String,
        startSlot: Int = 1,
        weeks: WeekPattern = WeekPattern.everyWeek(20),
    ) = CourseEntry(
        id = id,
        name = name,
        teacher = "老师",
        location = "@学武楼 101",
        dayOfWeek = 1,
        startSlot = startSlot,
        weeks = weeks,
        color = CourseColor.Blue,
    )

    private fun adjustment(
        id: String = "adj1",
        courseId: String = "a",
        fromWeek: Int = 1,
        toWeek: Int = 2,
    ) = CourseAdjustment(
        id = id,
        courseId = courseId,
        fromWeek = fromWeek,
        toWeek = toWeek,
        toDayOfWeek = 3,
        toStartSlot = 2,
        toSpan = 1,
    )

    @Test
    fun addAppendsToDraft() {
        val session = ScheduleEditSession(draft = listOf(entry("a", "已有")))
        session.add(entry("b", "新增"))

        assertEquals(listOf("a", "b"), session.courses.map { it.id })
    }

    @Test
    fun updateReplacesDraftEntryInPlace() {
        val session = ScheduleEditSession(
            draft = listOf(entry("a", "旧名"), entry("b", "别的课")),
        )
        session.update("a", entry("a", "新名"))

        assertEquals(listOf("a", "b"), session.courses.map { it.id })
        assertEquals("新名", session.courses.first { it.id == "a" }.name)
        assertEquals("别的课", session.courses.first { it.id == "b" }.name)
    }

    @Test
    fun updateUnknownIdIsNoOp() {
        val session = ScheduleEditSession(draft = listOf(entry("a", "已有")))
        session.update("zzz", entry("zzz", "不存在"))

        assertEquals(listOf("a"), session.courses.map { it.id })
    }

    @Test
    fun removeDropsDraftEntry() {
        val session = ScheduleEditSession(
            draft = listOf(entry("a", "已有"), entry("b", "另一门")),
        )
        session.remove("a")

        assertEquals(listOf("b"), session.courses.map { it.id })
    }

    @Test
    fun removeUnknownIdIsNoOp() {
        val session = ScheduleEditSession(draft = listOf(entry("a", "已有")))
        session.remove("zzz")

        assertEquals(listOf("a"), session.courses.map { it.id })
    }

    @Test
    fun saverRoundTripKeepsDraftAndActiveFlag() {
        val session = ScheduleEditSession(draft = listOf(entry("a", "旧名"), entry("b", "要删的")))
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
    }

    @Test
    fun inactiveSessionIsRestoredAsInactive() {
        val saved = with(ScheduleEditSession.Saver) {
            SaverScope { true }.save(ScheduleEditSession.Inactive)
        }
        val restored = ScheduleEditSession.Saver.restore(saved!!)

        assertTrue(restored!!.courses.isEmpty())
    }

    // ---------- 调课草稿 ----------

    @Test
    fun addUpdateRemoveAdjustment() {
        val session = ScheduleEditSession(draft = listOf(entry("a", "已有")))
        session.addAdjustment(adjustment(id = "adj1"))

        assertEquals(listOf("adj1"), session.adjustments.map { it.id })
        session.updateAdjustment("adj1", adjustment(id = "adj1", toWeek = 5))
        assertEquals(5, session.adjustments.single().toWeek)

        session.removeAdjustment("adj1")
        assertTrue(session.adjustments.isEmpty())
    }

    @Test
    fun removingCoursePrunesItsAdjustments() {
        val session = ScheduleEditSession(
            draft = listOf(entry("a", "已有"), entry("b", "另一门")),
            draftAdjustments = listOf(
                adjustment(id = "adj1", courseId = "a"),
                adjustment(id = "adj2", courseId = "b"),
            ),
        )
        session.remove("a")

        assertEquals(listOf("adj2"), session.adjustments.map { it.id })
    }

    @Test
    fun narrowingCourseWeeksPrunesAdjustmentsWithoutAnOccurrence() {
        val everyWeek = entry("a", "已有", weeks = WeekPattern.everyWeek(20))
        val session = ScheduleEditSession(
            draft = listOf(everyWeek),
            draftAdjustments = listOf(adjustment(id = "adj1", fromWeek = 4)),
        )
        // 课程只剩单周：第 4 周（双周）的原课次不存在了，调课同步清理
        session.update(
            "a",
            entry("a", "已有", weeks = WeekPattern(listOf(WeekSegment(1, 20, Parity.ODD)))),
        )

        assertTrue(session.adjustments.isEmpty())
    }

    @Test
    fun saverRoundTripKeepsAdjustments() {
        val session = ScheduleEditSession(
            draft = listOf(entry("a", "已有")),
            draftAdjustments = listOf(adjustment(id = "adj1")),
        )
        session.addAdjustment(adjustment(id = "adj2", fromWeek = 3, toWeek = 4))

        val saved = with(ScheduleEditSession.Saver) {
            SaverScope { true }.save(session)
        }
        val restored = ScheduleEditSession.Saver.restore(saved!!)

        assertEquals(listOf("adj1", "adj2"), restored!!.adjustments.map { it.id })
    }
}
