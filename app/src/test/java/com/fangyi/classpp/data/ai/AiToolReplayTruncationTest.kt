package com.fangyi.classpp.data.ai

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史回放的工具结果截断：必须保持外层 JSON 合法。旧实现直接 take(2000) 会把
 * 下一次请求里的 tool 消息切成非法 JSON，模型无法解析。
 */
class AiToolReplayTruncationTest {

    @Test
    fun `large success result is reduced to valid json with truncated flag`() {
        val items = (1..200).map { index ->
            """{"id":"t$index","name":"${"长".repeat(50)}$index"}"""
        }
        val raw = """{"ok":true,"data":{"total":200,"items":[${items.joinToString(",")}]}}"""

        val text = truncateJsonForReplay(raw, 2_000)

        val obj = AiJson.storage.parseToJsonElement(text).jsonObject
        assertTrue(obj.getValue("ok").jsonPrimitive.boolean)
        val data = obj.getValue("data").jsonObject
        assertTrue("截断必须显式标记", data.getValue("truncated").jsonPrimitive.boolean)
        // 数组被对半砍，仍是合法结构且保留部分条目
        val kept = (data["items"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0
        assertTrue(kept in 1 until 200)
    }

    @Test
    fun `error envelope stays structurally valid`() {
        val raw = """{"ok":false,"error":{"code":"limit_exceeded","message":"${"错".repeat(5000)}"}}"""

        val text = truncateJsonForReplay(raw, 500)

        val obj = AiJson.storage.parseToJsonElement(text).jsonObject
        assertEquals("false", obj.getValue("ok").jsonPrimitive.content)
        val error = obj.getValue("error").jsonObject
        assertEquals("limit_exceeded", error.getValue("code").jsonPrimitive.content)
        assertTrue(error.getValue("message").jsonPrimitive.content.endsWith("（已截断）"))
    }
}
