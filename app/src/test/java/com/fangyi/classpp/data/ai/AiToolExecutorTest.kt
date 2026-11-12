package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.testCourse
import com.fangyi.classpp.data.testSchedule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 执行器边界：未知工具、非法参数、结果信封、单条结果截断与预算错误结果。 */
class AiToolExecutorTest {

    private val today = IsoDate.of(2026, 5, 20)
    private val context = AiChatContext(
        schedule = testSchedule(courses = listOf(testCourse(day = 3, startSlot = 1))),
        currentWeek = 12,
        today = today,
    )

    private fun JsonObject.errorCode(): String =
        getValue("error").jsonObject.getValue("code").jsonPrimitive.content

    private fun AiToolResult.errorCode(): String =
        contentJson.let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject }
            .errorCode()

    @Test
    fun `unknown tool returns unknown_tool`() = runBlocking {
        val result = AiToolExecutor.executeSafely(
            AiToolCall(id = "c1", name = "no_such_tool", argumentsJson = "{}"),
            context,
        )

        assertTrue(result.isError)
        assertEquals("unknown_tool", result.errorCode())
    }

    @Test
    fun `malformed arguments return invalid_arguments`() = runBlocking {
        val result = AiToolExecutor.executeSafely(
            AiToolCall(id = "c1", name = AiToolRegistry.GET_DAY_PLAN, argumentsJson = "{not-json"),
            context,
        )

        assertTrue(result.isError)
        assertEquals("invalid_arguments", result.errorCode())
    }

    @Test
    fun `schema type and enum mismatches return invalid_arguments`() = runBlocking {
        val wrongType = AiToolExecutor.executeSafely(
            AiToolCall(
                id = "c1",
                name = AiToolRegistry.GET_WEEK_SCHEDULE,
                argumentsJson = """{"weekOffset":"abc"}""",
            ),
            context,
        )
        assertEquals("invalid_arguments", wrongType.errorCode())

        val wrongEnum = AiToolExecutor.executeSafely(
            AiToolCall(
                id = "c2",
                name = AiToolRegistry.SEARCH_TODOS,
                argumentsJson = """{"status":"nope"}""",
            ),
            context,
        )
        assertEquals("invalid_arguments", wrongEnum.errorCode())
    }

    @Test
    fun `successful execution returns ok envelope and parses as json`() = runBlocking {
        val result = AiToolExecutor.executeSafely(
            AiToolCall(
                id = "c1",
                name = AiToolRegistry.GET_DAY_PLAN,
                argumentsJson = """{"date":"2026-05-20"}""",
            ),
            context,
        )

        assertFalse(result.isError)
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(result.contentJson).jsonObject
        assertTrue(payload.getValue("ok").jsonPrimitive.boolean)
        assertNotNull(payload["data"])
    }

    @Test
    fun `missing schedule returns not_found`() = runBlocking {
        val result = AiToolExecutor.executeSafely(
            AiToolCall(id = "c1", name = AiToolRegistry.GET_DAY_PLAN, argumentsJson = "{}"),
            AiChatContext(today = today),
        )

        assertTrue(result.isError)
        assertEquals("not_found", result.errorCode())
    }

    @Test
    fun `budget helpers produce paired error results`() {
        val call = AiToolCall(id = "c1", name = AiToolRegistry.SEARCH_TODOS, argumentsJson = "{}")

        val limited = AiToolExecutor.limitExceededResult(call, "too many")
        assertEquals("c1", limited.callId)
        assertTrue(limited.isError)
        assertEquals("limit_exceeded", limited.errorCode())

        val cancelled = AiToolExecutor.cancelledResult(call)
        assertTrue(cancelled.isError)
        assertEquals("cancelled", cancelled.errorCode())
    }

    @Test
    fun `oversized results are truncated and flagged`() {
        val items = buildJsonArray {
            repeat(200) { index ->
                add(
                    buildJsonObject {
                        put("id", "t$index")
                        put("name", "待办标题 $index ".repeat(8))
                    },
                )
            }
        }
        val huge = buildJsonObject {
            put("ok", true)
            put(
                "data",
                buildJsonObject {
                    put("total", 200)
                    put("items", items)
                },
            )
        }

        val bounded = AiToolExecutor.truncateResult(huge)

        assertTrue(bounded.toString().length <= AiToolExecutor.MAX_RESULT_CHARS)
        val data = bounded.getValue("data").jsonObject
        assertTrue(data.getValue("truncated").jsonPrimitive.boolean)
        assertTrue(data.getValue("items").jsonArray.size < 200)
    }

    @Test
    fun `small results are returned unchanged`() {
        val small = buildJsonObject {
            put("ok", true)
            put("data", buildJsonObject { put("total", 0) })
        }
        assertEquals(small, AiToolExecutor.truncateResult(small))
    }
}
