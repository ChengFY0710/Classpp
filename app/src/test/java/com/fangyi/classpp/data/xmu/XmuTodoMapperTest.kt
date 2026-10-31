package com.fangyi.classpp.data.xmu

import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.model.isDeleted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 远端 DTO → Todo 的映射与过滤（指导 §11.2） */
class XmuTodoMapperTest {

    /** 「现在」固定在 2026-11-01 12:00 北京时间（早于夹具所有截止） */
    private val now: Long = java.util.GregorianCalendar(
        java.util.TimeZone.getTimeZone("Asia/Shanghai"),
    ).apply {
        clear()
        set(2026, 10, 1, 12, 0, 0)
    }.timeInMillis

    private fun decodeFixture(name: String): XmuTodoListDto =
        ScheduleJson.decodeStorage(
            javaClass.getResourceAsStream("/xmu/$name")!!.bufferedReader().use { it.readText() },
        )

    @Test
    fun `实测响应夹具解析 - course_code 23 位不丢精度`() {
        val list = decodeFixture("todos-2026-10-30.json")
        assertEquals(2, list.todoList.size)
        assertEquals("20262027113013001020702", list.todoList[0].courseCode)
        assertEquals(925512L, list.todoList[0].id)
        assertEquals(110846L, list.todoList[1].courseId)
    }

    @Test
    fun `正常 homework 映射 - 字段与 note 正确`() {
        val dto = decodeFixture("todos-2026-10-30.json").todoList.first()
        val todo = (XmuTodoMapper.map(dto, now) as XmuMapOutcome.Mapped).todo

        assertEquals("xmu-106160-925512", todo.id)
        assertEquals("第一次作业", todo.name)
        assertEquals(TodoTimeKind.None, todo.timeKind)
        assertEquals(1110, todo.deadlineMinute)
        assertEquals("2026-11-04", todo.deadlineDate.toString())
        assertTrue(todo.countdown)
        assertTrue(todo.note.contains("课程：高等数学"))
        assertTrue(todo.note.contains("类型：homework"))
        assertTrue(todo.note.contains("https://lnt.xmu.edu.cn/course/106160/learning-activity#/925512"))
        assertEquals("xmu", todo.source)
        assertEquals("高等数学", todo.sourceDetail)
        assertFalse(todo.isDeleted)
        assertTrue(todo.dates.isEmpty())
    }

    @Test
    fun `边缘夹具 - 各类跳过与放行`() {
        val list = decodeFixture("todos-edge.json").todoList
        val reasons = list.mapNotNull { dto ->
            (XmuTodoMapper.map(dto, now) as? XmuMapOutcome.Skipped)?.reason
        }
        // 跨日(2001)与秒截断(2002)正常映射；锁定(2003)、无截止(2004)、空标题(2007)、非法id(0) 跳过；
        // 非学生(2006)跳过；未知类型(2005)不跳过且保留原文
        assertTrue(list[0].id == 2001L && XmuTodoMapper.map(list[0], now) is XmuMapOutcome.Mapped)
        val crossDay = (XmuTodoMapper.map(list[0], now) as XmuMapOutcome.Mapped).todo
        assertEquals(0, crossDay.deadlineMinute)
        assertEquals("2026-11-05", crossDay.deadlineDate.toString())

        val truncated = (XmuTodoMapper.map(list[1], now) as XmuMapOutcome.Mapped).todo
        assertEquals(479, truncated.deadlineMinute)

        assertTrue(reasons.contains(SkipReason.Locked))
        assertTrue(reasons.contains(SkipReason.NoDeadline))
        assertTrue(reasons.contains(SkipReason.NotStudent))
        assertTrue(reasons.contains(SkipReason.BlankTitle))
        assertTrue(reasons.contains(SkipReason.InvalidId))
        assertFalse(reasons.contains(SkipReason.Overdue))
        // end_time 为 null 是合法「无截止」，不能计入格式异常
        assertFalse(reasons.contains(SkipReason.DeadlineUnparseable))

        val quiz = (XmuTodoMapper.map(list[4], now) as XmuMapOutcome.Mapped).todo
        assertTrue(quiz.note.contains("类型：quiz"))
    }

    @Test
    fun `已锁定条目 includeClosedOrOverdue 时放行`() {
        val locked = decodeFixture("todos-edge.json").todoList.first { it.id == 2003L }
        assertTrue(XmuTodoMapper.map(locked, now) is XmuMapOutcome.Skipped)
        val mapped = XmuTodoMapper.map(locked, now, includeClosedOrOverdue = true)
        assertTrue(mapped is XmuMapOutcome.Mapped)
    }

    @Test
    fun `已过期条目默认跳过、放行开关可导入`() {
        val dto = decodeFixture("todos-2026-10-30.json").todoList.first()
        val late: Long = now + 30L * 24 * 60 * 60 * 1000 // 一个月后：全部过期
        assertTrue(XmuTodoMapper.map(dto, late) is XmuMapOutcome.Skipped)
        assertTrue(XmuTodoMapper.map(dto, late, includeClosedOrOverdue = true) is XmuMapOutcome.Mapped)
    }

    @Test
    fun `截止格式无法识别与无截止分流`() {
        val base = decodeFixture("todos-2026-10-30.json").todoList.first()

        // 平台把 end_time 从 Z 改成 +08:00：是接口形态变化信号，不是「没有截止」
        val shifted = base.copy(endTime = "2026-11-04T18:30:00+08:00")
        assertEquals(
            SkipReason.DeadlineUnparseable,
            (XmuTodoMapper.map(shifted, now) as XmuMapOutcome.Skipped).reason,
        )

        // 空/空白仍是合法的「无截止」，不提示格式问题
        assertEquals(
            SkipReason.NoDeadline,
            (XmuTodoMapper.map(base.copy(endTime = null), now) as XmuMapOutcome.Skipped).reason,
        )
        assertEquals(
            SkipReason.NoDeadline,
            (XmuTodoMapper.map(base.copy(endTime = "  "), now) as XmuMapOutcome.Skipped).reason,
        )
    }

    @Test
    fun `确定性 id 与远端键`() {
        val dto = decodeFixture("todos-2026-10-30.json").todoList.first()
        assertEquals("xmu-106160-925512", XmuTodoMapper.localId(dto))
        assertEquals("xmu-925512", XmuTodoMapper.legacyLocalId(dto))
        assertEquals("xmu:106160:925512", XmuTodoMapper.remoteKey(dto))
        assertTrue(XmuTodoMapper.isSyncedLocalId(XmuTodoMapper.localId(dto)))
        assertFalse(XmuTodoMapper.isSyncedLocalId("some-uuid"))
    }

    @Test
    fun `同 activity id 不同课程映射到不同本地 id`() {
        val dto = decodeFixture("todos-2026-10-30.json").todoList.first()
        val otherCourse = dto.copy(courseId = dto.courseId + 1)

        assertEquals("xmu-106160-925512", XmuTodoMapper.localId(dto))
        assertEquals("xmu-106161-925512", XmuTodoMapper.localId(otherCourse))
        assertEquals("xmu:106160:925512", XmuTodoMapper.remoteKey(dto))
        assertEquals("xmu:106161:925512", XmuTodoMapper.remoteKey(otherCourse))
    }

    @Test
    fun `远端哈希对内容敏感且不含无关字段`() {
        val list = decodeFixture("todos-2026-10-30.json").todoList
        val a = XmuTodoMapper.remoteHash(list[0])
        val b = XmuTodoMapper.remoteHash(list[1])
        assertTrue(a != b)
        // course_code 不参与哈希；标题/截止/课程/类型参与
        assertEquals(a, XmuTodoMapper.remoteHash(list[0].copy(courseCode = "different")))
        assertTrue(a != XmuTodoMapper.remoteHash(list[0].copy(title = "改名")))
        assertTrue(a != XmuTodoMapper.remoteHash(list[0].copy(endTime = "2026-11-06T10:30:00Z")))
    }
}
