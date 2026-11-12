package com.fangyi.classpp.data.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具注册表（P0 契约层）：
 * - 四段 JSON 回退契约与重构前逐字节一致（system prompt 快照的另一半防线）；
 * - OpenAI / Anthropic 两种 wire 形态由同一份 schema 生成；
 * - schema 保持保守（只 type/properties/required/enum/description），不引入网关不认的关键字。
 */
class AiToolRegistryTest {

    // ---------- 回退契约：与重构前文本逐字节一致 ----------

    private val legacyCreateTodos =
        """{"todos":[{"name":"...","dates":["yyyy-MM-dd"],"allDay":false,"startTime":"HH:mm",""" +
            """"endTime":"HH:mm","deadlineDate":"yyyy-MM-dd","deadlineTime":"HH:mm",""" +
            """"deadlineBeforeRepeat":false,"deadlineBeforeDays":0,""" +
            """"urgency":"Critical|High|Medium|Low","tags":["..."],"group":"...",""" +
            """"note":"...","steps":["..."],"location":"...","countdown":false,"pinned":false,""" +
            """"repeat":{"type":"None|Daily|Weekly|Monthly|Yearly|Custom","unit":"Day|Week|Month|Year",""" +
            """"interval":1,"marks":[1],"endType":"Never|Date|Count","endDate":"yyyy-MM-dd",""" +
            """"endCount":5,"merge":true,"checkIn":false}}]}"""

    private val legacyUpdateAndDelete =
        """{"updates":[{"id":"<待办 id>","patch":{"name":"...","dates":["yyyy-MM-dd"],""" +
            """"allDay":true,"startTime":"HH:mm","endTime":"HH:mm",""" +
            """"deadlineDate":"yyyy-MM-dd","deadlineTime":"HH:mm",""" +
            """"deadlineBeforeRepeat":false,"deadlineBeforeDays":0,""" +
            """"urgency":"Critical|High|Medium|Low|None","tags":["..."],"group":"...",""" +
            """"note":"...","steps":["..."],"location":"...","countdown":false,"pinned":false,""" +
            """"repeat":{"type":"None|Daily|Weekly|Monthly|Yearly|Custom","unit":"Day|Week|Month|Year",""" +
            """"interval":1,"marks":[1],"endType":"Never|Date|Count","endDate":"yyyy-MM-dd",""" +
            """"endCount":5,"merge":true,"checkIn":false}}}],""" +
            """"deletes":[{"id":"<待办 id>"}]}"""

    private val legacyComplete =
        """{"completes":[{"id":"<待办 id>","date":"yyyy-MM-dd"}],""" +
            """"reopens":[{"id":"<待办 id>","date":"yyyy-MM-dd"}]}"""

    private val legacyLifecycle =
        """{"archives":[{"id":"<待办 id>"}],""" +
            """"unarchives":[{"id":"<已归档 id>"}],""" +
            """"restores":[{"id":"<回收站 id>"}]}"""

    @Test
    fun `fallback contracts match pre-refactor prompt text`() {
        val contracts = AiToolRegistry.fallbackContracts
        assertEquals(legacyCreateTodos, contracts.createTodos)
        assertEquals(legacyUpdateAndDelete, contracts.updateAndDelete)
        assertEquals(legacyComplete, contracts.complete)
        assertEquals(legacyLifecycle, contracts.lifecycle)
    }

    @Test
    fun `repair system prompt matches pre-refactor text`() {
        val expected = "待办动作必须是可解析的 json 代码块。\n" +
            "新建（立即落库）格式：\n" + legacyCreateTodos + "\n" +
            "修改/删除（等待用户确认）格式：\n" + legacyUpdateAndDelete + "\n" +
            "完成/取消完成（立即生效）格式：\n" + legacyComplete + "\n" +
            "归档/取消归档/恢复（立即生效）格式：\n" + legacyLifecycle
        assertEquals(expected, AiToolRegistry.repairSystemPrompt)
    }

    @Test
    fun `fallback shape keeps requested section order`() {
        // 顺序是契约的一部分（updates 在 deletes 前、completes 在 reopens 前）
        assertTrue(
            AiToolRegistry.fallbackShape("updates", "deletes")
                .startsWith("""{"updates":["""),
        )
        assertTrue(
            AiToolRegistry.fallbackShape("deletes", "updates")
                .startsWith("""{"deletes":["""),
        )
    }

    @Test
    fun `unknown fallback section fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            AiToolRegistry.fallbackShape("not_a_section")
        }
    }

    // ---------- 注册表元数据 ----------

    @Test
    fun `tool names are unique snake_case and descriptions are non-blank`() {
        val names = AiToolRegistry.specs.map { it.name }
        assertEquals(names.size, names.toSet().size)
        for (spec in AiToolRegistry.specs) {
            assertTrue("tool name must be snake_case: ${spec.name}", SNAKE_CASE.matches(spec.name))
            assertTrue("tool description must not be blank: ${spec.name}", spec.description.isNotBlank())
            assertEquals(
                "parameters must be an object: ${spec.name}",
                "object",
                spec.parameters.getValue("type").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `find resolves registered tools and returns null for unknown names`() {
        assertNotNull(AiToolRegistry.find(AiToolRegistry.CREATE_TODOS))
        assertEquals(AiToolRegistry.UPDATE_TODOS, AiToolRegistry.find(AiToolRegistry.UPDATE_TODOS)?.name)
        assertNull(AiToolRegistry.find("no_such_tool"))
    }

    @Test
    fun `permissions follow existing write semantics`() {
        val permissions = AiToolRegistry.specs.associate { it.name to it.permission }
        // 新建/完成/归档：可逆写，自动执行 + 撤销
        assertEquals(AiToolPermission.WriteReversible, permissions[AiToolRegistry.CREATE_TODOS])
        assertEquals(AiToolPermission.WriteReversible, permissions[AiToolRegistry.COMPLETE_TODOS])
        assertEquals(AiToolPermission.WriteReversible, permissions[AiToolRegistry.ARCHIVE_TODOS])
        // 修改/删除：破坏性写，只生成待确认记录
        assertEquals(AiToolPermission.WriteConfirm, permissions[AiToolRegistry.UPDATE_TODOS])
        assertEquals(AiToolPermission.WriteConfirm, permissions[AiToolRegistry.DELETE_TODOS])
    }

    // ---------- Wire 形态 ----------

    @Test
    fun `openai tools wrap schemas in function objects`() {
        val tools = AiToolRegistry.openAiTools()
        assertEquals(AiToolRegistry.nativeSpecs.size, tools.size)
        tools.forEachIndexed { index, element ->
            val spec = AiToolRegistry.nativeSpecs[index]
            val tool = element.jsonObject
            assertEquals("function", tool.getValue("type").jsonPrimitive.content)
            val function = tool.getValue("function").jsonObject
            assertEquals(spec.name, function.getValue("name").jsonPrimitive.content)
            assertEquals(spec.description, function.getValue("description").jsonPrimitive.content)
            val parameters = function.getValue("parameters").jsonObject
            assertEquals(spec.parameters, parameters)
            assertConservativeSchema(parameters)
        }
    }

    @Test
    fun `anthropic tools wrap schemas in input_schema`() {
        val tools = AiToolRegistry.anthropicTools()
        assertEquals(AiToolRegistry.nativeSpecs.size, tools.size)
        tools.forEachIndexed { index, element ->
            val spec = AiToolRegistry.nativeSpecs[index]
            val tool = element.jsonObject
            assertEquals(spec.name, tool.getValue("name").jsonPrimitive.content)
            assertEquals(spec.description, tool.getValue("description").jsonPrimitive.content)
            assertEquals(spec.parameters, tool.getValue("input_schema").jsonObject)
            assertNull("Anthropic tools must not carry OpenAI function envelope", tool["function"])
            assertConservativeSchema(tool.getValue("input_schema"))
        }
    }

    @Test
    fun `native tools cover the three read tools and five write tools`() {
        assertEquals(
            listOf(
                AiToolRegistry.GET_DAY_PLAN,
                AiToolRegistry.SEARCH_TODOS,
                AiToolRegistry.GET_WEEK_SCHEDULE,
                AiToolRegistry.CREATE_TODOS,
                AiToolRegistry.UPDATE_TODOS,
                AiToolRegistry.DELETE_TODOS,
                AiToolRegistry.COMPLETE_TODOS,
                AiToolRegistry.ARCHIVE_TODOS,
            ),
            AiToolRegistry.nativeSpecs.map { it.name },
        )
        // P2 起写工具也进入原生 wire：每个 spec 都必须有执行入口
        assertTrue(AiToolRegistry.nativeSpecs.all { it.execute != null })
        assertEquals(AiToolRegistry.specs.size, AiToolRegistry.nativeSpecs.size)
        assertTrue(
            AiToolRegistry.nativeSpecs.filter { it.permission == AiToolPermission.Read }
                .map { it.name } == listOf(
                AiToolRegistry.GET_DAY_PLAN,
                AiToolRegistry.SEARCH_TODOS,
                AiToolRegistry.GET_WEEK_SCHEDULE,
            ),
        )
    }

    @Test
    fun `wireTools dispatches by protocol`() {
        assertEquals(AiToolRegistry.openAiTools(), AiToolRegistry.wireTools(AiProtocol.OpenAiCompat))
        assertEquals(AiToolRegistry.anthropicTools(), AiToolRegistry.wireTools(AiProtocol.Anthropic))
    }

    @Test
    fun `schemas avoid gateway-hostile keywords`() {
        for (spec in AiToolRegistry.specs) assertConservativeSchema(spec.parameters)
    }

    /** 递归断言：只用 type/properties/required/enum/description，不出现 $ref/oneOf/strict 等 */
    private fun assertConservativeSchema(element: JsonElement, path: String = "parameters") {
        when (element) {
            is JsonObject -> {
                for ((key, value) in element) {
                    assertFalse("$path uses unsupported keyword: $key", key in FORBIDDEN_KEYWORDS)
                    assertConservativeSchema(value, "$path.$key")
                }
            }
            is JsonArray -> element.forEachIndexed { index, item ->
                assertConservativeSchema(item, "$path[$index]")
            }
            else -> Unit
        }
    }

    private companion object {
        val SNAKE_CASE = Regex("^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$")

        /** 设计稿 §4.2/§10.3：这些关键字在自定义网关/Ollama/国产兼容端点上兼容性差 */
        val FORBIDDEN_KEYWORDS = setOf(
            "\$ref",
            "\$defs",
            "oneOf",
            "anyOf",
            "allOf",
            "patternProperties",
            "additionalProperties",
            "strict",
            "format",
            "default",
        )
    }
}
