package com.fangyi.classpp.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 工具执行器：把协议中性的 [AiToolCall] 变成回给模型的 [AiToolResult]。
 *
 * 边界（设计稿 §4.5/§6.3）：
 * - 未知/未接入工具 → `unknown_tool`，不崩溃；
 * - 参数不是合法 JSON / 不符合 schema → `invalid_arguments`（原始参数截断进 message）；
 * - 执行异常 → `internal_error`，绝不抛到工具循环外；
 * - 取消原样传播（调用方需要保留已完成的工具结果并落盘）；
 * - 结果统一包成 `{ok,data}` / `{ok:false,error:{code,message}}`，单条 8KB 上限。
 */
internal object AiToolExecutor {

    /** 单条结果字符上限（设计稿 §4.5） */
    const val MAX_RESULT_CHARS = 8_000

    /** 单轮工具结果总字符上限（由仓库在回填时执行） */
    const val MAX_ROUND_RESULT_CHARS = 24_000

    /** 参数校验失败时附回原文的上限（避免把超长畸形参数塞回模型） */
    private const val RAW_ARGUMENTS_MAX = 300

    suspend fun executeSafely(call: AiToolCall, ctx: AiChatContext): AiToolResult {
        val spec = AiToolRegistry.find(call.name)
        if (spec == null || spec.execute == null) {
            return errorResult(call, "unknown_tool", "未知工具：${call.name}")
        }
        val args = parseArguments(call.argumentsJson)
            ?: return errorResult(
                call,
                "invalid_arguments",
                "参数不是合法 JSON 对象：" + call.argumentsJson.take(RAW_ARGUMENTS_MAX),
            )
        validate(spec, args)?.let { message ->
            return errorResult(call, "invalid_arguments", message)
        }
        val result = try {
            spec.execute.invoke(args, ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure("internal_error", e.message ?: "工具执行失败")
        }
        val bounded = truncateResult(result)
        return AiToolResult(
            callId = call.id,
            name = call.name,
            contentJson = bounded.toString(),
            isError = (bounded["ok"] as? JsonPrimitive)?.booleanOrNull != true,
        )
    }

    /** 预算超限时给模型看的失败结果（仍需成对回填，保证 wire 配对完整） */
    fun limitExceededResult(call: AiToolCall, message: String): AiToolResult = errorResult(
        call = call,
        code = "limit_exceeded",
        message = message,
    )

    fun cancelledResult(call: AiToolCall): AiToolResult = errorResult(
        call = call,
        code = "cancelled",
        message = "工具调用已取消",
    )

    private fun errorResult(call: AiToolCall, code: String, message: String): AiToolResult {
        val content = failure(code, message)
        return AiToolResult(
            callId = call.id,
            name = call.name,
            contentJson = content.toString(),
            isError = true,
        )
    }

    private fun parseArguments(raw: String): JsonObject? {
        if (raw.isBlank()) return JsonObject(emptyMap())
        return try {
            AiJson.storage.parseToJsonElement(raw) as? JsonObject
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 最小 schema 校验：required + 基础类型 + enum。
     *
     * 整数/数字允许字符串形态（部分兼容网关把参数里的数字编码成字符串）；
     * 其余未知关键字不处理——schema 本身由注册表保持保守。
     */
    private fun validate(spec: AiToolSpec, args: JsonObject): String? {
        val required = (spec.parameters["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            .orEmpty()
        for (name in required) {
            val value = args[name]
            if (value == null || value is JsonNull) return "缺少必填参数：$name"
        }
        val properties = spec.parameters["properties"] as? JsonObject ?: return null
        for ((name, schemaElement) in properties) {
            val value = args[name] ?: continue
            if (value is JsonNull) continue
            val schema = schemaElement as? JsonObject ?: continue
            val type = (schema["type"] as? JsonPrimitive)?.content ?: continue
            if (!matchesType(type, value)) return "参数 $name 类型应为 $type"
            val allowed = (schema["enum"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                .orEmpty()
            if (allowed.isNotEmpty()) {
                val actual = (value as? JsonPrimitive)?.content
                if (actual == null || actual !in allowed) {
                    return "参数 $name 只能是 ${allowed.joinToString("/")}"
                }
            }
        }
        return null
    }

    private fun matchesType(type: String, value: JsonElement): Boolean = when (type) {
        "string" -> value is JsonPrimitive
        "boolean" -> (value as? JsonPrimitive)?.content?.toBooleanStrictOrNull() != null
        "integer" -> (value as? JsonPrimitive)?.content?.toIntOrNull() != null
        "number" -> (value as? JsonPrimitive)?.content?.toDoubleOrNull() != null
        "array" -> value is JsonArray
        "object" -> value is JsonObject
        else -> true
    }

    /**
     * 单条结果上限：超限时反复对半砍最大的 data 数组，尽量保留可读数据并标记
     * `truncated:true`；仍放不下则退化为 `limit_exceeded` 失败结果。
     */
    internal fun truncateResult(result: JsonObject): JsonObject {
        if (result.toString().length <= MAX_RESULT_CHARS) return result
        val data = result["data"] as? JsonObject
        if (data != null) {
            var current: JsonObject = data
            var guard = 0
            while (envelope(current).toString().length > MAX_RESULT_CHARS && guard++ < 16) {
                val largest = current.entries
                    .filter { it.value is JsonArray }
                    .maxByOrNull { (it.value as JsonArray).size }
                    ?: break
                val array = largest.value as JsonArray
                if (array.isEmpty()) break
                current = JsonObject(
                    current + (largest.key to JsonArray(array.take(array.size / 2))),
                )
            }
            val withFlag = JsonObject(current + ("truncated" to JsonPrimitive(true)))
            val candidate = envelope(withFlag)
            if (candidate.toString().length <= MAX_RESULT_CHARS) return candidate
        }
        return failure("limit_exceeded", "工具结果超过 ${MAX_RESULT_CHARS} 字符，已截断")
    }

    private fun envelope(data: JsonObject): JsonObject = JsonObject(
        mapOf(
            "ok" to JsonPrimitive(true),
            "data" to data,
        ),
    )

    private fun failure(code: String, message: String): JsonObject = JsonObject(
        mapOf(
            "ok" to JsonPrimitive(false),
            "error" to JsonObject(
                mapOf(
                    "code" to JsonPrimitive(code),
                    "message" to JsonPrimitive(message),
                ),
            ),
        ),
    )
}
