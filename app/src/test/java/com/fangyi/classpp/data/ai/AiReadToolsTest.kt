package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.evenWeeks
import com.fangyi.classpp.data.model.CourseAdjustment
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.testCourse
import com.fangyi.classpp.data.testSchedule
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三个只读工具的行为：日期/调课/单双周、待办过滤排序与 limit、周偏移与越界。
 * 纯 JVM，不依赖 Android。
 */
class AiReadToolsTest {

    private val wedWeek12: IsoDate = IsoDate.of(2026, 5, 20) // 第 12 周周三
    private val thuWeek12: IsoDate = IsoDate.of(2026, 5, 21)

    private fun args(vararg pairs: Pair<String, JsonElement>): JsonObject =
        JsonObject(pairs.toMap())

    private fun JsonObject.data(): JsonObject = getValue("data").jsonObject

    private fun JsonObject.ok(): Boolean = getValue("ok").jsonPrimitive.boolean

    // ---------- get_day_plan ----------

    @Test
    fun `day plan returns courses todos and free slots`() = kotlinx.coroutines.runBlocking {
        val schedule = testSchedule(
            courses = listOf(testCourse(day = 3, startSlot = 1, span = 2)),
        )
        val context = AiChatContext(
            schedule = schedule,
            currentWeek = 12,
            today = wedWeek12,
            todos = listOf(
                testTodo(id = "t1", name = "交报告", dates = listOf(wedWeek12), createdAtMillis = 10),
                testTodo(
                    id = "t2",
                    name = "截止项",
                    deadlineDate = wedWeek12,
                    deadlineMinute = 18 * 60,
                    createdAtMillis = 20,
                ),
                testTodo(id = "t3", name = "明天", dates = listOf(wedWeek12 + 1)),
            ),
        )

        val result = AiReadTools.executeDayPlan(args(), context)

        assertTrue(result.ok())
        val data = result.data()
        assertEquals("2026-05-20", data.getValue("date").jsonPrimitive.content)
        assertEquals("周三", data.getValue("weekday").jsonPrimitive.content)
        assertEquals(12, data.getValue("week").jsonPrimitive.int)
        val course = data.getValue("courses").jsonArray.single().jsonObject
        assertEquals("微积分", course.getValue("name").jsonPrimitive.content)
        assertEquals("08:00", course.getValue("startTime").jsonPrimitive.content)
        assertEquals("11:50", course.getValue("endTime").jsonPrimitive.content)
        assertEquals(2, data.getValue("todos").jsonArray.size)
        val deadlineTodo = data.getValue("todos").jsonArray
            .map { it.jsonObject }
            .first { it.getValue("id").jsonPrimitive.content == "t2" }
        assertEquals("2026-05-20 18:00", deadlineTodo.getValue("deadline").jsonPrimitive.content)
        val firstFree = data.getValue("freeSlots").jsonArray.first().jsonObject
        assertEquals("11:50", firstFree.getValue("start").jsonPrimitive.content)
        assertTrue(firstFree.getValue("minutes").jsonPrimitive.int > 0)
    }

    @Test
    fun `day plan follows adjustments and parity`() = kotlinx.coroutines.runBlocking {
        val schedule = testSchedule(
            courses = listOf(testCourse(id = "c1", day = 3, startSlot = 1, span = 1)),
            adjustments = listOf(
                CourseAdjustment(
                    id = "a1",
                    courseId = "c1",
                    fromWeek = 12,
                    toWeek = 12,
                    toDayOfWeek = 4,
                    toStartSlot = 3,
                    toSpan = 1,
                ),
            ),
        )
        val context = AiChatContext(schedule = schedule, currentWeek = 12, today = wedWeek12)

        val wed = AiReadTools.executeDayPlan(args("date" to JsonPrimitive("2026-05-20")), context)
        val thu = AiReadTools.executeDayPlan(args("date" to JsonPrimitive("2026-05-21")), context)

        assertTrue(wed.data().getValue("courses").jsonArray.isEmpty())
        assertEquals(1, thu.data().getValue("courses").jsonArray.size)
    }

    @Test
    fun `day plan respects odd even week pattern`() = kotlinx.coroutines.runBlocking {
        val schedule = testSchedule(courses = listOf(testCourse(weeks = evenWeeks())))
        val context = AiChatContext(schedule = schedule, currentWeek = 12, today = wedWeek12)

        val even = AiReadTools.executeDayPlan(args("date" to JsonPrimitive("2026-05-20")), context)
        val odd = AiReadTools.executeDayPlan(args("date" to JsonPrimitive("2026-05-27")), context)

        assertEquals(1, even.data().getValue("courses").jsonArray.size)
        assertTrue(odd.data().getValue("courses").jsonArray.isEmpty())
    }

    @Test
    fun `day plan handles invalid date and missing schedule`() = kotlinx.coroutines.runBlocking {
        val noSchedule = AiChatContext(today = wedWeek12)
        val notFound = AiReadTools.executeDayPlan(args(), noSchedule)
        assertFalse(notFound.ok())
        assertEquals("not_found", notFound.errorCode())

        val withSchedule = AiChatContext(schedule = testSchedule(), today = wedWeek12)
        val invalid = AiReadTools.executeDayPlan(args("date" to JsonPrimitive("2026-13-01")), withSchedule)
        assertFalse(invalid.ok())
        assertEquals("invalid_arguments", invalid.errorCode())
    }

    @Test
    fun `day plan includes repeating todo occurrence`() = kotlinx.coroutines.runBlocking {
        val todo = testTodo(
            id = "t1",
            name = "每周复盘",
            dates = listOf(wedWeek12 - 70), // 同为周三的锚日期
            repeat = RepeatRule(type = RepeatType.Weekly),
        )
        val context = AiChatContext(
            schedule = testSchedule(),
            currentWeek = 12,
            today = wedWeek12,
            todos = listOf(todo),
        )

        val result = AiReadTools.executeDayPlan(args("date" to JsonPrimitive("2026-05-20")), context)

        assertTrue(result.data().getValue("todos").jsonArray.isNotEmpty())
    }

    // ---------- search_todos ----------

    private fun searchContext(): AiChatContext {
        val d1 = IsoDate.of(2026, 5, 20)
        val d2 = IsoDate.of(2026, 5, 25)
        val active = listOf(
            testTodo(
                id = "t1",
                name = "交实验报告",
                dates = listOf(d1),
                deadlineDate = d1,
                deadlineMinute = 18 * 60,
                tags = listOf("物理"),
                group = "学习",
                note = "记得打印",
                location = "实验室",
                createdAtMillis = 10,
            ),
            testTodo(
                id = "t2",
                name = "已完成项",
                dates = listOf(d1),
                completed = true,
                createdAtMillis = 5,
            ),
            testTodo(
                id = "t3",
                name = "买牛奶",
                dates = listOf(d2),
                group = "生活",
                tags = listOf("杂事"),
                createdAtMillis = 20,
            ),
        )
        return AiChatContext(
            today = d1,
            todos = active,
            archivedTodos = listOf(testTodo(id = "a1", name = "归档项", archivedAtMillis = 1)),
            deletedTodos = listOf(testTodo(id = "d1", name = "删除项", deletedAtMillis = 1)),
        )
    }

    @Test
    fun `search defaults to active and sorts unfinished first`() = kotlinx.coroutines.runBlocking {
        val result = AiReadTools.executeSearchTodos(args(), searchContext())

        assertTrue(result.ok())
        val data = result.data()
        assertEquals(3, data.getValue("total").jsonPrimitive.int)
        assertFalse(data.getValue("truncated").jsonPrimitive.boolean)
        val ids = data.getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(listOf("t1", "t3", "t2"), ids)
    }

    @Test
    fun `search filters by query status group tag and due range`() = kotlinx.coroutines.runBlocking {
        val context = searchContext()

        val query = AiReadTools.executeSearchTodos(args("query" to JsonPrimitive("实验")), context)
        assertEquals(listOf("t1"), query.data().itemIds())

        val completed = AiReadTools.executeSearchTodos(args("status" to JsonPrimitive("completed")), context)
        assertEquals(listOf("t2"), completed.data().itemIds())

        val archived = AiReadTools.executeSearchTodos(args("status" to JsonPrimitive("archived")), context)
        assertEquals(listOf("a1"), archived.data().itemIds())

        val deleted = AiReadTools.executeSearchTodos(args("status" to JsonPrimitive("deleted")), context)
        assertEquals(listOf("d1"), deleted.data().itemIds())

        val all = AiReadTools.executeSearchTodos(args("status" to JsonPrimitive("all")), context)
        assertEquals(5, all.data().getValue("total").jsonPrimitive.int)

        val group = AiReadTools.executeSearchTodos(args("group" to JsonPrimitive("学习")), context)
        assertEquals(listOf("t1"), group.data().itemIds())

        val tag = AiReadTools.executeSearchTodos(args("tag" to JsonPrimitive("杂事")), context)
        assertEquals(listOf("t3"), tag.data().itemIds())

        val due = AiReadTools.executeSearchTodos(
            args(
                "dueFrom" to JsonPrimitive("2026-05-20"),
                "dueTo" to JsonPrimitive("2026-05-20"),
            ),
            context,
        )
        assertEquals(listOf("t1", "t2"), due.data().itemIds())
    }

    @Test
    fun `search limit truncates and includeDetail adds fields`() = kotlinx.coroutines.runBlocking {
        val limited = AiReadTools.executeSearchTodos(args("limit" to JsonPrimitive(1)), searchContext())
        val data = limited.data()
        assertEquals(3, data.getValue("total").jsonPrimitive.int)
        assertTrue(data.getValue("truncated").jsonPrimitive.boolean)
        assertEquals(1, data.getValue("items").jsonArray.size)

        val detailed = AiReadTools.executeSearchTodos(
            args("query" to JsonPrimitive("实验"), "includeDetail" to JsonPrimitive(true)),
            searchContext(),
        )
        val item = detailed.data().getValue("items").jsonArray.single().jsonObject
        assertEquals("记得打印", item.getValue("note").jsonPrimitive.content)
        assertEquals("实验室", item.getValue("location").jsonPrimitive.content)
    }

    @Test
    fun `search rejects invalid arguments`() = kotlinx.coroutines.runBlocking {
        val context = searchContext()

        val badStatus = AiReadTools.executeSearchTodos(args("status" to JsonPrimitive("nope")), context)
        assertEquals("invalid_arguments", badStatus.errorCode())

        val badLimit = AiReadTools.executeSearchTodos(args("limit" to JsonPrimitive(99)), context)
        assertEquals("invalid_arguments", badLimit.errorCode())

        val badRange = AiReadTools.executeSearchTodos(
            args(
                "dueFrom" to JsonPrimitive("2026-06-01"),
                "dueTo" to JsonPrimitive("2026-05-01"),
            ),
            context,
        )
        assertEquals("invalid_arguments", badRange.errorCode())
    }

    // ---------- get_week_schedule ----------

    @Test
    fun `week schedule groups days and applies offsets`() = kotlinx.coroutines.runBlocking {
        val schedule = testSchedule(
            courses = listOf(
                testCourse(id = "c1", name = "英语", day = 1, startSlot = 1),
                testCourse(id = "c2", name = "高数", day = 3, startSlot = 3, span = 2),
            ),
        )
        val context = AiChatContext(schedule = schedule, currentWeek = 12, today = wedWeek12)

        val thisWeek = AiReadTools.executeWeekSchedule(args(), context)
        val nextWeek = AiReadTools.executeWeekSchedule(args("weekOffset" to JsonPrimitive(1)), context)

        assertTrue(thisWeek.ok())
        assertEquals(12, thisWeek.data().getValue("week").jsonPrimitive.int)
        assertEquals(5, thisWeek.data().getValue("days").jsonArray.size)
        val monday = thisWeek.data().getValue("days").jsonArray.first().jsonObject
        assertEquals("2026-05-18", monday.getValue("date").jsonPrimitive.content)
        assertEquals(1, monday.getValue("courses").jsonArray.size)
        assertEquals(13, nextWeek.data().getValue("week").jsonPrimitive.int)
    }

    @Test
    fun `week schedule outside term returns empty days`() = kotlinx.coroutines.runBlocking {
        val context = AiChatContext(
            schedule = testSchedule(courses = listOf(testCourse())),
            currentWeek = 100,
            today = wedWeek12,
        )

        val result = AiReadTools.executeWeekSchedule(args("weekOffset" to JsonPrimitive(1)), context)

        assertTrue(result.ok())
        assertEquals(101, result.data().getValue("week").jsonPrimitive.int)
        assertTrue(
            result.data().getValue("days").jsonArray.all {
                it.jsonObject.getValue("courses").jsonArray.isEmpty()
            },
        )
    }

    @Test
    fun `week schedule rejects offset out of range and missing schedule`() = kotlinx.coroutines.runBlocking {
        val context = AiChatContext(schedule = testSchedule(), currentWeek = 12, today = wedWeek12)
        val outOfRange = AiReadTools.executeWeekSchedule(args("weekOffset" to JsonPrimitive(5)), context)
        assertEquals("invalid_arguments", outOfRange.errorCode())

        val missing = AiReadTools.executeWeekSchedule(args(), AiChatContext(today = wedWeek12))
        assertEquals("not_found", missing.errorCode())
    }

    // ---------- get_todo ----------

    @Test
    fun `get todo returns detail for a repeating todo`() = kotlinx.coroutines.runBlocking {
        val repeating = com.fangyi.classpp.data.testTodo(
            id = "t1",
            name = "交实验报告",
            dates = listOf(wedWeek12 - 7),
            repeat = RepeatRule(type = RepeatType.Weekly),
            deadlineDate = wedWeek12,
            deadlineMinute = 18 * 60,
            note = "实验楼 3 层提交",
            steps = listOf(
                com.fangyi.classpp.data.model.TodoStep(id = "s1", title = "查资料", done = true),
                com.fangyi.classpp.data.model.TodoStep(id = "s2", title = "写正文"),
            ),
            tags = listOf("物理"),
            group = "学习",
            location = "实验楼",
            urgency = com.fangyi.classpp.data.model.TodoUrgency.High,
        )
        val context = AiChatContext(today = wedWeek12, todos = listOf(repeating))

        val result = AiReadTools.executeGetTodo(args("id" to JsonPrimitive("t1")), context)

        assertTrue(result.ok())
        val data = result.data()
        assertEquals("t1", data.getValue("id").jsonPrimitive.content)
        assertEquals("active", data.getValue("status").jsonPrimitive.content)
        assertEquals(false, data.getValue("completed").jsonPrimitive.boolean)
        assertEquals("2026-05-20 18:00", data.getValue("deadline").jsonPrimitive.content)
        assertEquals("学习", data.getValue("group").jsonPrimitive.content)
        assertEquals("实验楼 3 层提交", data.getValue("note").jsonPrimitive.content)
        assertEquals(2, data.getValue("steps").jsonArray.size)
        assertEquals("每周", data.getValue("repeat").jsonPrimitive.content)
        assertEquals(
            "Weekly",
            data.getValue("repeatRule").jsonObject.getValue("type").jsonPrimitive.content,
        )
        // 期次与提示词同一口径：今天这一期必须在待完成里
        assertTrue(
            data.getValue("pendingOccurrences").jsonArray
                .any { it.jsonPrimitive.content == wedWeek12.toString() },
        )
    }

    @Test
    fun `get todo resolves archived and deleted entries by id`() = kotlinx.coroutines.runBlocking {
        val archived = testTodo(id = "a1", name = "旧任务", archivedAtMillis = 1L)
        val deleted = testTodo(id = "d1", name = "误删", deletedAtMillis = 2L)
        val context = AiChatContext(
            today = wedWeek12,
            archivedTodos = listOf(archived),
            deletedTodos = listOf(deleted),
        )

        val archivedResult = AiReadTools.executeGetTodo(args("id" to JsonPrimitive("a1")), context)
        val deletedResult = AiReadTools.executeGetTodo(args("id" to JsonPrimitive("d1")), context)

        assertEquals("archived", archivedResult.data().getValue("status").jsonPrimitive.content)
        assertEquals("deleted", deletedResult.data().getValue("status").jsonPrimitive.content)
    }

    @Test
    fun `get todo rejects blank id and reports not_found`() = kotlinx.coroutines.runBlocking {
        val context = AiChatContext(
            today = wedWeek12,
            todos = listOf(testTodo(id = "t1", name = "任务")),
        )

        val blank = AiReadTools.executeGetTodo(args("id" to JsonPrimitive("  ")), context)
        assertEquals("invalid_arguments", blank.errorCode())

        val missing = AiReadTools.executeGetTodo(args("id" to JsonPrimitive("nope")), context)
        assertEquals("not_found", missing.errorCode())
    }

    private fun JsonObject.itemIds(): List<String> =
        getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }

    private fun JsonObject.errorCode(): String =
        getValue("error").jsonObject.getValue("code").jsonPrimitive.content

    private fun testTodo(
        id: String,
        name: String,
        dates: List<IsoDate> = emptyList(),
        repeat: RepeatRule = RepeatRule(),
        deadlineDate: IsoDate? = null,
        deadlineMinute: Int? = null,
        tags: List<String> = emptyList(),
        group: String? = null,
        note: String = "",
        location: String = "",
        completed: Boolean = false,
        createdAtMillis: Long = 0,
        archivedAtMillis: Long? = null,
        deletedAtMillis: Long? = null,
    ) = com.fangyi.classpp.data.testTodo(
        id = id,
        name = name,
        dates = dates,
        repeat = repeat,
        deadlineDate = deadlineDate,
        deadlineMinute = deadlineMinute,
        tags = tags,
        group = group,
        note = note,
        location = location,
        completed = completed,
        createdAtMillis = createdAtMillis,
        deletedAtMillis = deletedAtMillis,
    ).copy(archivedAtMillis = archivedAtMillis)
}
