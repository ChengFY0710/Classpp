package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.evenWeeks
import com.fangyi.classpp.data.model.CourseAdjustment
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.testCourse
import com.fangyi.classpp.data.testSchedule
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三个只读工具的行为：日期/调课/单双周、待办过滤排序与 limit/offset 分页、周偏移/绝对周次/课表概况与越界。
 * 纯 JVM，不依赖 Android。
 */
class AiReadToolsTest {

    private val wedWeek12: IsoDate = IsoDate.of(2026, 5, 20) // 第 12 周周三
    private val thuWeek12: IsoDate = IsoDate.of(2026, 5, 21)

    private fun args(vararg pairs: Pair<String, JsonElement>): JsonObject =
        JsonObject(pairs.toMap())

    private fun JsonObject.data(): JsonObject = getValue("data").jsonObject

    private fun JsonObject.ok(): Boolean = getValue("ok").jsonPrimitive.boolean

    // ---------- get_current_time ----------

    @Test
    fun `current time returns date weekday clock and week`() = kotlinx.coroutines.runBlocking {
        val context = AiChatContext(
            schedule = testSchedule(),
            currentWeek = 12,
            today = wedWeek12,
            nowMinute = 14 * 60 + 32,
        )

        val result = AiReadTools.executeCurrentTime(args(), context)

        assertTrue(result.ok())
        val data = result.data()
        assertEquals("2026-05-20", data.getValue("date").jsonPrimitive.content)
        assertEquals("周三", data.getValue("weekday").jsonPrimitive.content)
        assertEquals("14:32", data.getValue("time").jsonPrimitive.content)
        assertEquals("2026-05-20 14:32", data.getValue("dateTime").jsonPrimitive.content)
        assertEquals(872, data.getValue("minuteOfDay").jsonPrimitive.int)
        assertEquals("下午", data.getValue("partOfDay").jsonPrimitive.content)
        assertEquals(12, data.getValue("week").jsonPrimitive.int)
    }

    @Test
    fun `current time omits week without schedule and clamps bad minute`() = kotlinx.coroutines.runBlocking {
        val midnight = AiReadTools.executeCurrentTime(
            args(),
            AiChatContext(today = wedWeek12, nowMinute = 0),
        )
        assertTrue(midnight.ok())
        assertNull(midnight.data()["week"])
        assertEquals("00:00", midnight.data().getValue("time").jsonPrimitive.content)
        assertEquals("凌晨", midnight.data().getValue("partOfDay").jsonPrimitive.content)

        // 注入值越界时钳到当天最后一分钟，不抛错
        val clamped = AiReadTools.executeCurrentTime(
            args(),
            AiChatContext(today = wedWeek12, nowMinute = 2_000),
        )
        assertEquals("23:59", clamped.data().getValue("time").jsonPrimitive.content)
        assertEquals("晚上", clamped.data().getValue("partOfDay").jsonPrimitive.content)
    }

    @Test
    fun `current time maps part of day boundaries`() = kotlinx.coroutines.runBlocking {
        suspend fun partOfDay(minute: Int): String = AiReadTools.executeCurrentTime(
            args(),
            AiChatContext(today = wedWeek12, nowMinute = minute),
        ).data().getValue("partOfDay").jsonPrimitive.content

        assertEquals("凌晨", partOfDay(0))
        assertEquals("凌晨", partOfDay(4 * 60 + 59))
        assertEquals("早上", partOfDay(5 * 60))
        assertEquals("上午", partOfDay(8 * 60))
        assertEquals("中午", partOfDay(11 * 60))
        assertEquals("下午", partOfDay(13 * 60))
        assertEquals("晚上", partOfDay(18 * 60))
        assertEquals("晚上", partOfDay(23 * 60 + 59))
    }

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

    @Test
    fun `day plan range returns consecutive days with courses todos and free slots`() =
        kotlinx.coroutines.runBlocking {
            val monday = IsoDate.of(2026, 5, 18)
            val schedule = testSchedule(
                courses = listOf(testCourse(day = 3, startSlot = 1, span = 2)),
            )
            val context = AiChatContext(
                schedule = schedule,
                currentWeek = 12,
                today = wedWeek12,
                todos = listOf(
                    testTodo(
                        id = "t1",
                        name = "周一作业",
                        dates = listOf(monday),
                        createdAtMillis = 10,
                    ),
                    testTodo(
                        id = "t2",
                        name = "周三作业",
                        dates = listOf(wedWeek12),
                        createdAtMillis = 20,
                    ),
                ),
            )

            val result = AiReadTools.executeDayPlan(
                args(
                    "from" to JsonPrimitive("2026-05-18"),
                    "to" to JsonPrimitive("2026-05-22"),
                ),
                context,
            )

            assertTrue(result.ok())
            val data = result.data()
            assertEquals("2026-05-18", data.getValue("from").jsonPrimitive.content)
            assertEquals("2026-05-22", data.getValue("to").jsonPrimitive.content)
            val days = data.getValue("days").jsonArray
            assertEquals(5, days.size)
            assertEquals(
                listOf("2026-05-18", "2026-05-19", "2026-05-20", "2026-05-21", "2026-05-22"),
                days.map { it.jsonObject.getValue("date").jsonPrimitive.content },
            )

            val mondayPlan = days[0].jsonObject
            assertEquals("周一", mondayPlan.getValue("weekday").jsonPrimitive.content)
            assertTrue(mondayPlan.getValue("courses").jsonArray.isEmpty())
            assertEquals(
                listOf("t1"),
                mondayPlan.getValue("todos").jsonArray
                    .map { it.jsonObject.getValue("id").jsonPrimitive.content },
            )

            val wednesdayPlan = days[2].jsonObject
            assertEquals("周三", wednesdayPlan.getValue("weekday").jsonPrimitive.content)
            assertEquals(12, wednesdayPlan.getValue("week").jsonPrimitive.int)
            assertEquals(1, wednesdayPlan.getValue("courses").jsonArray.size)
            assertEquals(
                listOf("t2"),
                wednesdayPlan.getValue("todos").jsonArray
                    .map { it.jsonObject.getValue("id").jsonPrimitive.content },
            )
            assertTrue(wednesdayPlan.getValue("freeSlots").jsonArray.isNotEmpty())

            val fridayPlan = days[4].jsonObject
            assertTrue(fridayPlan.getValue("courses").jsonArray.isEmpty())
            assertTrue(fridayPlan.getValue("todos").jsonArray.isEmpty())
        }

    @Test
    fun `day plan range validates pairing order and span`() = kotlinx.coroutines.runBlocking {
        val context = AiChatContext(schedule = testSchedule(), currentWeek = 12, today = wedWeek12)

        val oneSided = AiReadTools.executeDayPlan(
            args("from" to JsonPrimitive("2026-05-18")),
            context,
        )
        assertFalse(oneSided.ok())
        assertEquals("invalid_arguments", oneSided.errorCode())

        val reversed = AiReadTools.executeDayPlan(
            args(
                "from" to JsonPrimitive("2026-05-22"),
                "to" to JsonPrimitive("2026-05-18"),
            ),
            context,
        )
        assertFalse(reversed.ok())
        assertEquals("invalid_arguments", reversed.errorCode())

        val tooLong = AiReadTools.executeDayPlan(
            args(
                "from" to JsonPrimitive("2026-05-01"),
                "to" to JsonPrimitive("2026-05-15"),
            ),
            context,
        )
        assertFalse(tooLong.ok())
        assertEquals("invalid_arguments", tooLong.errorCode())

        val invalid = AiReadTools.executeDayPlan(
            args(
                "from" to JsonPrimitive("2026-13-01"),
                "to" to JsonPrimitive("2026-13-02"),
            ),
            context,
        )
        assertFalse(invalid.ok())
        assertEquals("invalid_arguments", invalid.errorCode())

        val exactlyMax = AiReadTools.executeDayPlan(
            args(
                "from" to JsonPrimitive("2026-05-01"),
                "to" to JsonPrimitive("2026-05-14"),
            ),
            context,
        )
        assertTrue(exactlyMax.ok())
        assertEquals(
            AiReadTools.MAX_DAY_PLAN_DAYS,
            exactlyMax.data().getValue("days").jsonArray.size,
        )
    }

    @Test
    fun `day plan range keeps odd even week filtering`() = kotlinx.coroutines.runBlocking {
        val schedule = testSchedule(courses = listOf(testCourse(weeks = evenWeeks())))
        val context = AiChatContext(schedule = schedule, currentWeek = 12, today = wedWeek12)

        val result = AiReadTools.executeDayPlan(
            args(
                "from" to JsonPrimitive("2026-05-20"),
                "to" to JsonPrimitive("2026-05-27"),
            ),
            context,
        )

        assertTrue(result.ok())
        val days = result.data().getValue("days").jsonArray.map { it.jsonObject }
        assertEquals(8, days.size)
        assertEquals(1, days[0].getValue("courses").jsonArray.size) // 第 12 周（双周）
        assertEquals(0, days[7].getValue("courses").jsonArray.size) // 第 13 周（单周）
    }

    @Test
    fun `day plan discrete dates returns requested days in input order`() =
        kotlinx.coroutines.runBlocking {
            val schedule = testSchedule(
                courses = listOf(testCourse(day = 3, startSlot = 1, span = 2)),
            )
            val context = AiChatContext(
                schedule = schedule,
                currentWeek = 12,
                today = wedWeek12,
                todos = listOf(
                    testTodo(
                        id = "t1",
                        name = "11月4号作业",
                        dates = listOf(IsoDate.of(2026, 11, 4)),
                        createdAtMillis = 10,
                    ),
                ),
            )

            val result = AiReadTools.executeDayPlan(
                args(
                    "dates" to JsonArray(
                        listOf(
                            JsonPrimitive("2026-11-04"),
                            JsonPrimitive("2026-05-20"),
                            JsonPrimitive("2026-06-03"),
                        ),
                    ),
                ),
                context,
            )

            assertTrue(result.ok())
            val days = result.data().getValue("days").jsonArray
            assertEquals(3, days.size)
            assertEquals(
                listOf("2026-11-04", "2026-05-20", "2026-06-03"),
                days.map { it.jsonObject.getValue("date").jsonPrimitive.content },
            )
            // 学期外的日期也返回当天信息（只是没有课）；待办照常列出
            val november = days[0].jsonObject
            assertEquals("周三", november.getValue("weekday").jsonPrimitive.content)
            assertTrue(november.getValue("courses").jsonArray.isEmpty())
            assertEquals(
                listOf("t1"),
                november.getValue("todos").jsonArray
                    .map { it.jsonObject.getValue("id").jsonPrimitive.content },
            )
            // 学期内第 12 周的周三仍有课
            val may = days[1].jsonObject
            assertEquals("周三", may.getValue("weekday").jsonPrimitive.content)
            assertEquals(1, may.getValue("courses").jsonArray.size)
        }

    @Test
    fun `day plan discrete dates dedupes and validates arguments`() =
        kotlinx.coroutines.runBlocking {
            val context = AiChatContext(schedule = testSchedule(), currentWeek = 12, today = wedWeek12)

            val deduped = AiReadTools.executeDayPlan(
                args(
                    "date" to JsonPrimitive("2026-01-01"), // 给了 dates 时忽略
                    "dates" to JsonArray(
                        listOf(
                            JsonPrimitive("2026-11-04"),
                            JsonPrimitive("2026-11-04"),
                            JsonPrimitive("2026-11-05"),
                        ),
                    ),
                ),
                context,
            )
            assertTrue(deduped.ok())
            assertEquals(
                listOf("2026-11-04", "2026-11-05"),
                deduped.data().getValue("days").jsonArray
                    .map { it.jsonObject.getValue("date").jsonPrimitive.content },
            )

            val notArray = AiReadTools.executeDayPlan(
                args("dates" to JsonPrimitive("2026-11-04")),
                context,
            )
            assertFalse(notArray.ok())
            assertEquals("invalid_arguments", notArray.errorCode())

            val empty = AiReadTools.executeDayPlan(
                args("dates" to JsonArray(emptyList<JsonElement>())),
                context,
            )
            assertFalse(empty.ok())
            assertEquals("invalid_arguments", empty.errorCode())

            val invalidDate = AiReadTools.executeDayPlan(
                args("dates" to JsonArray(listOf(JsonPrimitive("2026-13-01")))),
                context,
            )
            assertFalse(invalidDate.ok())
            assertEquals("invalid_arguments", invalidDate.errorCode())

            val nonString = AiReadTools.executeDayPlan(
                args("dates" to JsonArray(listOf(JsonPrimitive(1)))),
                context,
            )
            assertFalse(nonString.ok())
            assertEquals("invalid_arguments", nonString.errorCode())

            val tooMany = AiReadTools.executeDayPlan(
                args(
                    "dates" to JsonArray(
                        (1..AiReadTools.MAX_DAY_PLAN_DAYS + 1).map { day ->
                            JsonPrimitive(IsoDate.of(2026, 5, day).toString())
                        },
                    ),
                ),
                context,
            )
            assertFalse(tooMany.ok())
            assertEquals("invalid_arguments", tooMany.errorCode())

            val mixed = AiReadTools.executeDayPlan(
                args(
                    "dates" to JsonArray(listOf(JsonPrimitive("2026-11-04"))),
                    "from" to JsonPrimitive("2026-11-01"),
                    "to" to JsonPrimitive("2026-11-07"),
                ),
                context,
            )
            assertFalse(mixed.ok())
            assertEquals("invalid_arguments", mixed.errorCode())
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

        // “未完成”必须用 incomplete：active 含已完成，不能代替
        val incomplete = AiReadTools.executeSearchTodos(args("status" to JsonPrimitive("incomplete")), context)
        assertEquals(listOf("t1", "t3"), incomplete.data().itemIds())

        val archived = AiReadTools.executeSearchTodos(args("status" to JsonPrimitive("archived")), context)
        assertEquals(listOf("a1"), archived.data().itemIds())

        val deleted = AiReadTools.executeSearchTodos(args("status" to JsonPrimitive("deleted")), context)
        assertEquals(listOf("d1"), deleted.data().itemIds())

        // all 只等同于「全部活跃待办」：归档/回收站必须用显式 status 才返回，
        // 否则模型会把回收站条目当成活跃待办列进查询结果。
        val all = AiReadTools.executeSearchTodos(args("status" to JsonPrimitive("all")), context)
        assertEquals(3, all.data().getValue("total").jsonPrimitive.int)
        assertEquals(listOf("t1", "t3", "t2"), all.data().itemIds())

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
    fun `default and all searches exclude archived and recycled todos`() = kotlinx.coroutines.runBlocking {
        val context = searchContext()

        for (parameters in listOf(args(), args("status" to JsonPrimitive("all")))) {
            val ids = AiReadTools.executeSearchTodos(parameters, context).data().itemIds()
            assertFalse(ids.contains("a1"))
            assertFalse(ids.contains("d1"))
        }
    }

    @Test
    fun `search offset pages through all matches without overlap`() = kotlinx.coroutines.runBlocking {
        val todos = (1..35).map { i ->
            testTodo(
                id = "p$i",
                name = "任务$i",
                deadlineDate = wedWeek12 + i,
                createdAtMillis = i.toLong(),
            )
        }
        val context = AiChatContext(today = wedWeek12, todos = todos)

        val first = AiReadTools.executeSearchTodos(args("limit" to JsonPrimitive(30)), context).data()
        assertEquals(35, first.getValue("total").jsonPrimitive.int)
        assertEquals(0, first.getValue("offset").jsonPrimitive.int)
        assertTrue(first.getValue("truncated").jsonPrimitive.boolean)
        val firstIds = first.itemIds()
        assertEquals(30, firstIds.size)

        val second = AiReadTools.executeSearchTodos(
            args("limit" to JsonPrimitive(30), "offset" to JsonPrimitive(30)),
            context,
        ).data()
        assertEquals(35, second.getValue("total").jsonPrimitive.int)
        assertEquals(30, second.getValue("offset").jsonPrimitive.int)
        assertFalse(second.getValue("truncated").jsonPrimitive.boolean)
        val secondIds = second.itemIds()
        assertEquals(5, secondIds.size)
        // 两页无重叠：offset 以排序后的稳定顺序切分，不会重复或漏条
        assertTrue(firstIds.intersect(secondIds.toSet()).isEmpty())

        val beyond = AiReadTools.executeSearchTodos(
            args("offset" to JsonPrimitive(100)),
            context,
        ).data()
        assertEquals(35, beyond.getValue("total").jsonPrimitive.int)
        assertTrue(beyond.itemIds().isEmpty())
        assertFalse(beyond.getValue("truncated").jsonPrimitive.boolean)
    }

    @Test
    fun `search without limit or offset defaults to complete result`() = kotlinx.coroutines.runBlocking {
        val todos = (1..35).map { i ->
            testTodo(
                id = "p$i",
                name = "任务$i",
                deadlineDate = wedWeek12 + i,
                createdAtMillis = i.toLong(),
            )
        }
        val context = AiChatContext(today = wedWeek12, todos = todos)

        val data = AiReadTools.executeSearchTodos(args(), context).data()

        assertEquals(35, data.getValue("total").jsonPrimitive.int)
        assertEquals(35, data.getValue("items").jsonArray.size)
        assertFalse(data.getValue("truncated").jsonPrimitive.boolean)
    }

    @Test
    fun `explicit limit keeps completed search paged`() = kotlinx.coroutines.runBlocking {
        val todos = (1..35).map { i ->
            testTodo(
                id = "c$i",
                name = "完成$i",
                completed = true,
                completedAtMillis = millisOf(2026, 5, (i % 28) + 1),
                createdAtMillis = i.toLong(),
            )
        }
        val context = AiChatContext(today = wedWeek12, todos = todos)

        val data = AiReadTools.executeSearchTodos(
            args(
                "status" to JsonPrimitive("completed"),
                "limit" to JsonPrimitive(10),
            ),
            context,
        ).data()

        assertEquals(35, data.getValue("total").jsonPrimitive.int)
        assertEquals(10, data.getValue("items").jsonArray.size)
        assertTrue(data.getValue("truncated").jsonPrimitive.boolean)
    }

    @Test
    fun `all mode returns every match in one page`() = kotlinx.coroutines.runBlocking {
        val todos = (1..35).map { i ->
            testTodo(
                id = "p$i",
                name = "任务$i",
                deadlineDate = wedWeek12 + i,
                createdAtMillis = i.toLong(),
            )
        }
        val context = AiChatContext(today = wedWeek12, todos = todos)

        val data = AiReadTools.executeSearchTodos(args("all" to JsonPrimitive(true)), context).data()

        assertEquals(35, data.getValue("total").jsonPrimitive.int)
        assertEquals(0, data.getValue("offset").jsonPrimitive.int)
        assertFalse(data.getValue("truncated").jsonPrimitive.boolean)
        assertEquals(35, data.getValue("items").jsonArray.size)
    }

    @Test
    fun `completed search defaults to complete result when no paging args`() = kotlinx.coroutines.runBlocking {
        val todos = (1..35).map { i ->
            testTodo(
                id = "c$i",
                name = "完成$i",
                completed = true,
                completedAtMillis = millisOf(2026, 5, (i % 28) + 1),
                createdAtMillis = i.toLong(),
            )
        }
        val context = AiChatContext(today = wedWeek12, todos = todos)

        val data = AiReadTools.executeSearchTodos(
            args("status" to JsonPrimitive("completed")),
            context,
        ).data()

        assertEquals(35, data.getValue("total").jsonPrimitive.int)
        assertEquals(35, data.getValue("items").jsonArray.size)
        assertFalse(data.getValue("truncated").jsonPrimitive.boolean)
    }

    @Test
    fun `all mode falls back to compact items to keep every match`() = kotlinx.coroutines.runBlocking {
        // 完整字段超预算，但精简字段装得下：必须优先保证条数完整
        val todos = (1..800).map { i ->
            testTodo(
                id = "p$i",
                name = "任务$i",
                repeat = RepeatRule(type = RepeatType.Weekly),
                deadlineDate = wedWeek12 + 1,
                tags = listOf("标签A", "标签B"),
                group = "学习",
                createdAtMillis = i.toLong(),
            )
        }
        val context = AiChatContext(today = wedWeek12, todos = todos)

        val data = AiReadTools.executeSearchTodos(args("all" to JsonPrimitive(true)), context).data()

        assertEquals(800, data.getValue("total").jsonPrimitive.int)
        assertEquals(800, data.getValue("items").jsonArray.size)
        assertTrue(data.getValue("compact").jsonPrimitive.boolean)
        assertFalse(data.getValue("truncated").jsonPrimitive.boolean)
    }

    @Test
    fun `all mode fails explicitly when result exceeds budget`() = kotlinx.coroutines.runBlocking {
        // 800 条 × 400 字名称，远超 120K 字符的单次完整返回上限
        val todos = (1..800).map { i ->
            testTodo(id = "p$i", name = "任".repeat(400), createdAtMillis = i.toLong())
        }
        val context = AiChatContext(today = wedWeek12, todos = todos)

        val result = AiReadTools.executeSearchTodos(args("all" to JsonPrimitive(true)), context)

        assertEquals("limit_exceeded", result.errorCode())
    }

    @Test
    fun `completed filters use completion date and sort newest first`() = kotlinx.coroutines.runBlocking {
        val context = AiChatContext(
            today = wedWeek12,
            todos = listOf(
                testTodo(
                    id = "c1",
                    name = "五月一日完成",
                    completed = true,
                    completedAtMillis = millisOf(2026, 5, 1),
                    createdAtMillis = 1,
                ),
                testTodo(
                    id = "c2",
                    name = "六月一日完成",
                    completed = true,
                    completedAtMillis = millisOf(2026, 6, 1),
                    createdAtMillis = 2,
                ),
                testTodo(
                    id = "c3",
                    name = "五月二十日完成",
                    completed = true,
                    completedAtMillis = millisOf(2026, 5, 20),
                    createdAtMillis = 3,
                ),
                testTodo(id = "a1", name = "未完成", createdAtMillis = 4),
            ),
        )

        val data = AiReadTools.executeSearchTodos(
            args(
                "status" to JsonPrimitive("completed"),
                "completedFrom" to JsonPrimitive("2026-05-15"),
                "completedTo" to JsonPrimitive("2026-06-30"),
            ),
            context,
        ).data()

        assertEquals(2, data.getValue("total").jsonPrimitive.int)
        assertEquals(listOf("c2", "c3"), data.itemIds())
        val first = data.getValue("items").jsonArray.first().jsonObject
        assertEquals("2026-06-01", first.getValue("completedAt").jsonPrimitive.content)
    }

    @Test
    fun `search completed includes repeating todos by occurrence`() = kotlinx.coroutines.runBlocking {
        // 重复待办整条 completed 恒 false：期次有完成记录即入已完成候选；完成日期过滤按
        // 分期命中（completedAtMillis 恒 null，按完成时刻搜修复前永远不命中）
        val today = IsoDate.of(2026, 5, 20)
        val withDonePeriods = testTodo(
            id = "r1",
            name = "每日打卡",
            dates = listOf(IsoDate.of(2026, 5, 18)),
            repeat = RepeatRule(type = RepeatType.Daily),
            createdAtMillis = 9,
        ).copy(completedOccurrences = listOf(IsoDate.of(2026, 5, 18), IsoDate.of(2026, 5, 19)))
        val withoutDonePeriods = testTodo(
            id = "r2",
            name = "没有完成期次",
            dates = listOf(IsoDate.of(2026, 5, 18)),
            repeat = RepeatRule(type = RepeatType.Daily),
            createdAtMillis = 8,
        )
        val wholeCompleted = testTodo(
            id = "t2",
            name = "整条完成",
            completed = true,
            completedAtMillis = millisOf(2026, 5, 19),
            createdAtMillis = 5,
        )
        val context = AiChatContext(today = today, todos = listOf(withDonePeriods, withoutDonePeriods, wholeCompleted))

        // status=completed：整条完成 + 有完成期次的重复入列，r2 不入
        val completed = AiReadTools.executeSearchTodos(args("status" to JsonPrimitive("completed")), context)
        assertEquals(listOf("t2", "r1"), completed.data().itemIds())

        // 完成日期过滤：r1 的完成分期 5/19 落在区间内 → 命中
        val byDate = AiReadTools.executeSearchTodos(
            args(
                "status" to JsonPrimitive("completed"),
                "completedFrom" to JsonPrimitive("2026-05-19"),
                "completedTo" to JsonPrimitive("2026-05-19"),
            ),
            context,
        )
        assertEquals(listOf("t2", "r1"), byDate.data().itemIds())

        // 部分完成的重复在结果项里带完成期次列表，completed 仍是 false（不误读成整条完成）
        val item = completed.data().getValue("items").jsonArray
            .single { it.jsonObject.getValue("id").jsonPrimitive.content == "r1" }
            .jsonObject
        assertFalse(item.getValue("completed").jsonPrimitive.boolean)
        assertEquals(
            listOf("2026-05-18", "2026-05-19"),
            item.getValue("completedOccurrences").jsonArray.map { it.jsonPrimitive.content },
        )
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

        val badOffset = AiReadTools.executeSearchTodos(args("offset" to JsonPrimitive(-1)), context)
        assertEquals("invalid_arguments", badOffset.errorCode())

        val badRange = AiReadTools.executeSearchTodos(
            args(
                "dueFrom" to JsonPrimitive("2026-06-01"),
                "dueTo" to JsonPrimitive("2026-05-01"),
            ),
            context,
        )
        assertEquals("invalid_arguments", badRange.errorCode())

        val badCompletedRange = AiReadTools.executeSearchTodos(
            args(
                "completedFrom" to JsonPrimitive("2026-06-01"),
                "completedTo" to JsonPrimitive("2026-05-01"),
            ),
            context,
        )
        assertEquals("invalid_arguments", badCompletedRange.errorCode())
    }

    // ---------- get_week_schedule ----------

    @Test
    fun `week schedule resolves offsets and absolute weeks across the term`() = kotlinx.coroutines.runBlocking {
        val schedule = testSchedule(
            courses = listOf(
                testCourse(id = "c1", name = "英语", day = 1, startSlot = 1),
                testCourse(id = "c2", name = "高数", day = 3, startSlot = 3, span = 2),
            ),
        )
        val context = AiChatContext(schedule = schedule, currentWeek = 12, today = wedWeek12)

        val thisWeek = AiReadTools.executeWeekSchedule(args(), context)
        val nextWeek = AiReadTools.executeWeekSchedule(args("weekOffset" to JsonPrimitive(1)), context)
        val firstWeek = AiReadTools.executeWeekSchedule(args("week" to JsonPrimitive(1)), context)
        val lastWeek = AiReadTools.executeWeekSchedule(args("week" to JsonPrimitive(16)), context)
        // week 优先于 weekOffset：两者同时出现时不按偏移回退
        val weekWins = AiReadTools.executeWeekSchedule(
            args("week" to JsonPrimitive(3), "weekOffset" to JsonPrimitive(-2)),
            context,
        )

        assertTrue(thisWeek.ok())
        assertEquals(12, thisWeek.data().getValue("week").jsonPrimitive.int)
        assertEquals(16, thisWeek.data().getValue("totalWeeks").jsonPrimitive.int)
        assertEquals(5, thisWeek.data().getValue("days").jsonArray.size)
        val monday = thisWeek.data().getValue("days").jsonArray.first().jsonObject
        assertEquals("2026-05-18", monday.getValue("date").jsonPrimitive.content)
        assertEquals(1, monday.getValue("courses").jsonArray.size)
        assertEquals(13, nextWeek.data().getValue("week").jsonPrimitive.int)

        assertTrue(firstWeek.ok())
        assertEquals(1, firstWeek.data().getValue("week").jsonPrimitive.int)
        assertEquals(
            "2026-03-02",
            firstWeek.data().getValue("days").jsonArray.first().jsonObject
                .getValue("date").jsonPrimitive.content,
        )
        assertTrue(lastWeek.ok())
        assertEquals(16, lastWeek.data().getValue("week").jsonPrimitive.int)
        assertEquals(3, weekWins.data().getValue("week").jsonPrimitive.int)
    }

    @Test
    fun `week schedule overview returns term facts without days`() = kotlinx.coroutines.runBlocking {
        val context = AiChatContext(schedule = testSchedule(), currentWeek = 12, today = wedWeek12)

        val result = AiReadTools.executeWeekSchedule(args("overview" to JsonPrimitive(true)), context)

        assertTrue(result.ok())
        val data = result.data()
        assertEquals(true, data.getValue("overview").jsonPrimitive.boolean)
        assertEquals(16, data.getValue("totalWeeks").jsonPrimitive.int)
        assertEquals(12, data.getValue("currentWeek").jsonPrimitive.int)
        assertEquals("2026-03-02", data.getValue("termStart").jsonPrimitive.content)
        assertEquals("2026-06-19", data.getValue("termEnd").jsonPrimitive.content)
        assertEquals(5, data.getValue("daysPerWeek").jsonPrimitive.int)
        assertNull(data["days"])

        // overview 忽略 week / weekOffset：越界周也不影响概况查询
        val withWeek = AiReadTools.executeWeekSchedule(
            args("overview" to JsonPrimitive(true), "week" to JsonPrimitive(99)),
            context,
        )
        assertTrue(withWeek.ok())

        // 学期外没有当前周时省略 currentWeek
        val offTerm = AiReadTools.executeWeekSchedule(
            args("overview" to JsonPrimitive(true)),
            AiChatContext(schedule = testSchedule(), today = wedWeek12),
        )
        assertNull(offTerm.data()["currentWeek"])
    }

    @Test
    fun `week schedule rejects weeks outside the term and missing schedule`() = kotlinx.coroutines.runBlocking {
        val context = AiChatContext(schedule = testSchedule(), currentWeek = 12, today = wedWeek12)
        val outOfRange = AiReadTools.executeWeekSchedule(args("weekOffset" to JsonPrimitive(5)), context)
        assertEquals("invalid_arguments", outOfRange.errorCode())

        val badAbsolute = AiReadTools.executeWeekSchedule(args("week" to JsonPrimitive(0)), context)
        assertEquals("invalid_arguments", badAbsolute.errorCode())

        // 学期外不再静默返回空周：目标周超出课表周次即报错
        val afterTerm = AiReadTools.executeWeekSchedule(
            args("weekOffset" to JsonPrimitive(1)),
            AiChatContext(schedule = testSchedule(), currentWeek = 100, today = wedWeek12),
        )
        assertEquals("invalid_arguments", afterTerm.errorCode())

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

    // ---------- 显式 null / 详情限长 / 周课表预算 ----------

    @Test
    fun `explicit null arguments behave as absent`() = kotlinx.coroutines.runBlocking {
        val dayContext = AiChatContext(schedule = testSchedule(), currentWeek = 12, today = wedWeek12)

        // date:null → 按默认「今天」处理（不能报 yyyy-MM-dd 格式错）
        val day = AiReadTools.executeDayPlan(args("date" to JsonNull), dayContext)
        assertTrue(day.ok())
        assertEquals(wedWeek12.toString(), day.data().getValue("date").jsonPrimitive.content)

        // dates:null + from/to:null → 不误报「不能同时使用」
        val mixed = AiReadTools.executeDayPlan(
            args("dates" to JsonNull, "from" to JsonNull, "to" to JsonNull),
            dayContext,
        )
        assertTrue(mixed.ok())

        // status:null / limit:null → 默认完整返回（不能因为「显式给了 limit」只回 10 条）
        val search = AiReadTools.executeSearchTodos(
            args("status" to JsonNull, "limit" to JsonNull),
            searchContext(),
        )
        assertTrue(search.ok())
        val data = search.data()
        assertEquals(3, data.getValue("items").jsonArray.size)
        assertFalse(data.getValue("truncated").jsonPrimitive.boolean)
    }

    @Test
    fun `search includeDetail caps note and steps and marks truncation`() =
        kotlinx.coroutines.runBlocking {
            val todo = testTodo(
                id = "long",
                name = "长详情",
                note = "备".repeat(2_000),
                steps = (1..30).map { index ->
                    com.fangyi.classpp.data.model.TodoStep(
                        id = "s$index",
                        title = "步".repeat(300),
                    )
                },
            )
            val result = AiReadTools.executeSearchTodos(
                args("includeDetail" to JsonPrimitive(true), "all" to JsonPrimitive(true)),
                AiChatContext(today = wedWeek12, todos = listOf(todo)),
            )

            val item = result.data().getValue("items").jsonArray.single().jsonObject
            assertEquals(800, item.getValue("note").jsonPrimitive.content.length)
            assertTrue(item.getValue("noteTruncated").jsonPrimitive.boolean)
            assertEquals(20, item.getValue("steps").jsonArray.size)
            assertTrue(item.getValue("stepsTruncated").jsonPrimitive.boolean)
            // 详情被截断但条目本身仍在（不能把 items 砍空）
            assertEquals(1, result.data().getValue("total").jsonPrimitive.int)
        }

    @Test
    fun `search result budget scales with small context window`() = kotlinx.coroutines.runBlocking {
        // 100 条带长名称的待办：完整结果远超小窗口预算
        val todos = (1..100).map { index ->
            testTodo(
                id = "t$index",
                name = "待办${index}" + "长".repeat(100),
                createdAtMillis = index.toLong(),
            )
        }
        val result = AiReadTools.executeSearchTodos(
            args("all" to JsonPrimitive(true)),
            AiChatContext(
                today = wedWeek12,
                todos = todos,
                modelContextWindowTokens = 9_000,
            ),
        )

        // 小窗口不能一次返回完整字段：要么降级 compact，要么显式 limit_exceeded
        val data = result["data"] as? JsonObject
        val compact = data?.get("compact")?.jsonPrimitive?.boolean == true
        val rejected = result["error"] != null && result.errorCode() == "limit_exceeded"
        assertTrue(
            "小窗口应压缩或拒绝，实际：${result.toString().take(120)}",
            compact || rejected,
        )
    }

    @Test
    fun `week schedule reports missing dates when over budget`() = kotlinx.coroutines.runBlocking {
        val longText = "课".repeat(200)
        val courses = (1..7).flatMap { day ->
            (1..10).map { index ->
                testCourse(
                    id = "c-$day-$index",
                    name = "$longText$index",
                    day = day,
                    startSlot = ((index - 1) % 5) + 1,
                ).copy(location = longText)
            }
        }
        val schedule = testSchedule(daysPerWeek = 7, courses = courses)
        val context = AiChatContext(
            schedule = schedule,
            currentWeek = 1,
            today = com.fangyi.classpp.data.TestTermStart,
        )

        val result = AiReadTools.executeWeekSchedule(args("week" to JsonPrimitive(1)), context)

        val data = result.data()
        assertTrue("超预算时必须显式标记截断", data.getValue("truncated").jsonPrimitive.boolean)
        val returned = data.getValue("returnedDays").jsonPrimitive.int
        assertTrue(returned < 7)
        assertEquals(7 - returned, data.getValue("missingDates").jsonArray.size)
    }

    /** 本地时区某天中午的 epoch millis：用于构造稳定的 completedAtMillis */
    private fun millisOf(year: Int, month: Int, day: Int): Long =
        java.util.GregorianCalendar(year, month - 1, day, 12, 0).timeInMillis

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
        steps: List<com.fangyi.classpp.data.model.TodoStep> = emptyList(),
        completed: Boolean = false,
        completedAtMillis: Long? = null,
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
        steps = steps,
        completed = completed,
        completedAtMillis = completedAtMillis,
        createdAtMillis = createdAtMillis,
        deletedAtMillis = deletedAtMillis,
    ).copy(archivedAtMillis = archivedAtMillis)
}
