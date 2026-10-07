package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoFile
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.model.TodoUrgency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 待办模型 JSON round-trip 与前向/后向兼容 */
class TodoJsonRoundTripTest {

    @Test
    fun `full todo roundtrips losslessly`() {
        val todo = testTodo(
            id = "t1",
            name = "实验报告",
            dates = listOf(IsoDate.parse("2026-06-18"), IsoDate.parse("2026-06-20")),
            timeKind = TodoTimeKind.Period,
            startMinute = 540,
            endMinute = 600,
            deadlineDate = IsoDate.parse("2026-06-19"),
            deadlineMinute = 870,
            location = "实验楼 301",
            tags = listOf("物理", "报告"),
            urgency = TodoUrgency.Critical,
            note = "带上数据",
            steps = listOf(testStep(id = "s1"), testStep(id = "s2", title = "写结论", done = true)),
            completed = true,
            completedAtMillis = 1234567890L,
            createdAtMillis = 42L,
        )
        assertEquals(todo, ScheduleJson.decodeStorage<Todo>(ScheduleJson.encodeStorage(todo)))
    }

    @Test
    fun `defaults survive roundtrip`() {
        val todo = testTodo(id = "t1")
        assertEquals(todo, ScheduleJson.decodeStorage<Todo>(ScheduleJson.encodeStorage(todo)))
    }

    @Test
    fun `dates serialize as iso text`() {
        val json = ScheduleJson.encodeStorage(testTodo(id = "t1", dates = listOf(IsoDate.parse("2026-06-18"))))
        assertEquals("2026-06-18", Regex(""""dates":\["([^"]+)"""").find(json)!!.groupValues[1])
    }

    @Test
    fun `old file missing newer fields still loads`() {
        // 早期导出只有 id + name（当前模型的其余字段当时还不存在）
        val old = """{"id":"t1","name":"高数作业"}"""
        val todo = ScheduleJson.decodeStorage<Todo>(old)
        assertEquals("t1", todo.id)
        assertEquals("高数作业", todo.name)
        assertEquals(emptyList<Any>(), todo.dates)
        assertEquals(com.fangyi.classpp.data.model.TodoTimeKind.None, todo.timeKind)
        assertNull(todo.startMinute)
        assertNull(todo.deadlineDate)
        assertEquals("", todo.note)
        assertEquals(com.fangyi.classpp.data.model.TodoUrgency.None, todo.urgency)
        assertEquals(false, todo.completed)
        assertEquals(0L, todo.createdAtMillis)
    }

    @Test
    fun `unknown keys are ignored`() {
        val future = """{"id":"t1","name":"x","futureField":true}"""
        assertEquals("x", ScheduleJson.decodeStorage<Todo>(future).name)
    }

    @Test
    fun `file envelope roundtrips`() {
        val file = TodoFile(
            todos = listOf(testTodo(id = "t1"), testTodo(id = "t2", name = "另一个")),
        )
        assertEquals(file, ScheduleJson.decodeStorage<TodoFile>(ScheduleJson.encodeStorage(file)))
        assertEquals(1, file.formatVersion)
    }
}
