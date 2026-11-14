package com.fangyi.classpp.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写工具（P2）：参数解析 → 动作草稿 → 网关。
 *
 * 只测工具层：参数宽容解析、空动作拒绝、缺网关时的错误码；真正的写库/确认卡语义由
 * [AiChatRepositoryTest] 的原生写工具用例覆盖（工具层不写库）。
 */
class AiWriteToolsTest {

    private class FakeGateway : AiToolWriteGateway {
        var creates: List<AiTodoDraft> = emptyList()
        var updates: List<AiTodoUpdateDraft> = emptyList()
        var deletes: List<AiTodoDeleteDraft> = emptyList()
        var completes: List<AiTodoCompletionDraft> = emptyList()
        var reopens: List<AiTodoCompletionDraft> = emptyList()
        var archives: List<AiTodoLifecycleDraft> = emptyList()
        var unarchives: List<AiTodoLifecycleDraft> = emptyList()
        var restores: List<AiTodoLifecycleDraft> = emptyList()
        var groups: List<String> = emptyList()
        var tags: List<String> = emptyList()
        var result: JsonObject = toolSuccess(buildJsonObject { put("count", 1) })

        override suspend fun createTodos(drafts: List<AiTodoDraft>): JsonObject {
            creates = drafts
            return result
        }

        override suspend fun updateTodos(drafts: List<AiTodoUpdateDraft>): JsonObject {
            updates = drafts
            return result
        }

        override suspend fun deleteTodos(drafts: List<AiTodoDeleteDraft>): JsonObject {
            deletes = drafts
            return result
        }

        override suspend fun completeTodos(
            completes: List<AiTodoCompletionDraft>,
            reopens: List<AiTodoCompletionDraft>,
        ): JsonObject {
            this.completes = completes
            this.reopens = reopens
            return result
        }

        override suspend fun archiveTodos(
            archives: List<AiTodoLifecycleDraft>,
            unarchives: List<AiTodoLifecycleDraft>,
            restores: List<AiTodoLifecycleDraft>,
        ): JsonObject {
            this.archives = archives
            this.unarchives = unarchives
            this.restores = restores
            return result
        }

        override suspend fun createGroups(names: List<String>): JsonObject {
            groups = names
            return result
        }

        override suspend fun createTags(names: List<String>): JsonObject {
            tags = names
            return result
        }
    }

    private fun args(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun context(gateway: AiToolWriteGateway?): AiChatContext =
        AiChatContext(writes = gateway)

    private fun errorCode(result: JsonObject): String =
        result.getValue("error").jsonObject.getValue("code").jsonPrimitive.content

    private fun ok(result: JsonObject): Boolean =
        result.getValue("ok").jsonPrimitive.content.toBoolean()

    // ---------- create_todos ----------

    @Test
    fun `create parses draft fields and delegates`() = kotlinx.coroutines.runBlocking {
        val gateway = FakeGateway()
        val result = AiWriteTools.executeCreateTodos(
            args(
                """{"todos":[{"name":"交实验报告","dates":["2026-11-18"],"urgency":"High",""" +
                    """"repeat":{"type":"Weekly","marks":[3]},"group":"学习"}]}""",
            ),
            context(gateway),
        )

        assertTrue(ok(result))
        val draft = gateway.creates.single()
        assertEquals("交实验报告", draft.name)
        assertEquals(listOf("2026-11-18"), draft.dates)
        assertEquals("High", draft.urgency)
        assertEquals("Weekly", draft.repeat?.type)
        assertEquals(listOf(3), draft.repeat?.marks)
        assertEquals("学习", draft.group)
    }

    @Test
    fun `create rejects empty and non array payloads`() = kotlinx.coroutines.runBlocking {
        val gateway = FakeGateway()
        val empty = AiWriteTools.executeCreateTodos(args("""{"todos":[]}"""), context(gateway))
        assertEquals("invalid_arguments", errorCode(empty))
        assertFalse(ok(empty))

        // schema 声明的是 array：单个对象由执行器的类型校验拦下（这里直接调工具层同样拒绝）
        val wrongType = AiWriteTools.executeCreateTodos(args("""{"todos":{"name":"跑步"}}"""), context(gateway))
        assertEquals("invalid_arguments", errorCode(wrongType))
        assertTrue(gateway.creates.isEmpty())
    }

    // ---------- update / delete ----------

    @Test
    fun `update keeps partial patch semantics`() = kotlinx.coroutines.runBlocking {
        val gateway = FakeGateway()
        AiWriteTools.executeUpdateTodos(
            args("""{"updates":[{"id":"todo-1","patch":{"name":"新名字","urgency":"None"}}]}"""),
            context(gateway),
        )

        val draft = gateway.updates.single()
        assertEquals("todo-1", draft.id)
        assertEquals("新名字", draft.patch.name)
        assertEquals("None", draft.patch.urgency)
        // 未写的字段保持 null（= 不修改），与 JSON 信封的 patch 语义一致
        assertNull(draft.patch.dates)
        assertNull(draft.patch.note)
    }

    @Test
    fun `delete parses ids`() = kotlinx.coroutines.runBlocking {
        val gateway = FakeGateway()
        AiWriteTools.executeDeleteTodos(
            args("""{"deletes":[{"id":"a"},{"id":"b"}]}"""),
            context(gateway),
        )

        assertEquals(listOf("a", "b"), gateway.deletes.map { it.id })
    }

    @Test
    fun `update and delete reject empty actions`() = kotlinx.coroutines.runBlocking {
        val gateway = FakeGateway()
        assertEquals(
            "invalid_arguments",
            errorCode(AiWriteTools.executeUpdateTodos(args("{}"), context(gateway))),
        )
        assertEquals(
            "invalid_arguments",
            errorCode(AiWriteTools.executeDeleteTodos(args("""{"deletes":[]}"""), context(gateway))),
        )
    }

    // ---------- complete / archive ----------

    @Test
    fun `complete splits completes and reopens`() = kotlinx.coroutines.runBlocking {
        val gateway = FakeGateway()
        AiWriteTools.executeCompleteTodos(
            args(
                """{"completes":[{"id":"a","date":"2026-11-18"}],""" +
                    """"reopens":[{"id":"b"}]}""",
            ),
            context(gateway),
        )

        assertEquals(listOf("a"), gateway.completes.map { it.id })
        assertEquals("2026-11-18", gateway.completes.single().date)
        assertEquals(listOf("b"), gateway.reopens.map { it.id })
    }

    @Test
    fun `complete requires at least one section`() = kotlinx.coroutines.runBlocking {
        val result = AiWriteTools.executeCompleteTodos(args("{}"), context(FakeGateway()))
        assertEquals("invalid_arguments", errorCode(result))
    }

    @Test
    fun `archive reads all three sections`() = kotlinx.coroutines.runBlocking {
        val gateway = FakeGateway()
        AiWriteTools.executeArchiveTodos(
            args(
                """{"archives":[{"id":"a"}],"unarchives":[{"id":"b"}],""" +
                    """"restores":[{"id":"c"}]}""",
            ),
            context(gateway),
        )

        assertEquals(listOf("a"), gateway.archives.map { it.id })
        assertEquals(listOf("b"), gateway.unarchives.map { it.id })
        assertEquals(listOf("c"), gateway.restores.map { it.id })
    }

    @Test
    fun `archive requires at least one section`() = kotlinx.coroutines.runBlocking {
        val result = AiWriteTools.executeArchiveTodos(args("{}"), context(FakeGateway()))
        assertEquals("invalid_arguments", errorCode(result))
    }

    // ---------- create_todo_groups / create_todo_tags ----------

    @Test
    fun `organizer tools trim dedupe and delegate names`() = kotlinx.coroutines.runBlocking {
        val gateway = FakeGateway()
        val groupsResult = AiWriteTools.executeCreateTodoGroups(
            args("""{"groups":[" 学习 ","生活","学习","  ",""]}"""),
            context(gateway),
        )
        val tagsResult = AiWriteTools.executeCreateTodoTags(
            args("""{"tags":["期末"," 期末 ","考研"]}"""),
            context(gateway),
        )

        assertTrue(ok(groupsResult))
        assertTrue(ok(tagsResult))
        assertEquals(listOf("学习", "生活"), gateway.groups)
        assertEquals(listOf("期末", "考研"), gateway.tags)
    }

    @Test
    fun `organizer tools reject empty and non array payloads`() = kotlinx.coroutines.runBlocking {
        val gateway = FakeGateway()
        assertEquals(
            "invalid_arguments",
            errorCode(AiWriteTools.executeCreateTodoGroups(args("""{"groups":[]}"""), context(gateway))),
        )
        assertEquals(
            "invalid_arguments",
            errorCode(AiWriteTools.executeCreateTodoGroups(args("""{"groups":["  "]}"""), context(gateway))),
        )
        assertEquals(
            "invalid_arguments",
            errorCode(AiWriteTools.executeCreateTodoGroups(args("""{"groups":"学习"}"""), context(gateway))),
        )
        assertEquals(
            "invalid_arguments",
            errorCode(AiWriteTools.executeCreateTodoTags(args("""{"tags":[]}"""), context(gateway))),
        )
        assertEquals(
            "invalid_arguments",
            errorCode(AiWriteTools.executeCreateTodoTags(args("""{"tags":{"name":"期末"}}"""), context(gateway))),
        )
        assertTrue(gateway.groups.isEmpty())
        assertTrue(gateway.tags.isEmpty())
    }

    // ---------- 缺网关 ----------

    @Test
    fun `write tools report wrong state without a gateway`() = kotlinx.coroutines.runBlocking {
        val ctx = AiChatContext()
        val results = listOf(
            AiWriteTools.executeCreateTodos(args("""{"todos":[{"name":"x"}]}"""), ctx),
            AiWriteTools.executeUpdateTodos(args("""{"updates":[{"id":"a"}]}"""), ctx),
            AiWriteTools.executeDeleteTodos(args("""{"deletes":[{"id":"a"}]}"""), ctx),
            AiWriteTools.executeCompleteTodos(args("""{"completes":[{"id":"a"}]}"""), ctx),
            AiWriteTools.executeArchiveTodos(args("""{"archives":[{"id":"a"}]}"""), ctx),
            AiWriteTools.executeCreateTodoGroups(args("""{"groups":["学习"]}"""), ctx),
            AiWriteTools.executeCreateTodoTags(args("""{"tags":["期末"]}"""), ctx),
        )

        results.forEach { result ->
            assertFalse(ok(result))
            assertEquals("wrong_state", errorCode(result))
        }
    }

    // ---------- 信封 ----------

    @Test
    fun `pending confirmation envelope carries status and items`() {
        val result = pendingConfirmationResult(
            changes = listOf(
                AiTodoChange(
                    kind = AiTodoChangeKind.Update,
                    todoId = "todo-1",
                    todoName = "交实验报告",
                ),
                AiTodoChange(
                    kind = AiTodoChangeKind.Delete,
                    todoId = "todo-2",
                    todoName = "跑步",
                ),
            ),
            dropped = true,
        )

        assertTrue(ok(result))
        val data = result.getValue("data").jsonObject
        assertEquals(
            "pending_user_confirmation",
            data.getValue("status").jsonPrimitive.content,
        )
        assertEquals("2", data.getValue("count").jsonPrimitive.content)
        assertEquals("true", data.getValue("dropped").jsonPrimitive.content)
        assertTrue(data.toString().contains("\"kind\":\"delete\""))
    }

    @Test
    fun `failure envelope uses stable error codes`() {
        val result = toolFailure("not_found", "目标待办不存在")
        assertFalse(ok(result))
        assertEquals("not_found", errorCode(result))
    }
}
