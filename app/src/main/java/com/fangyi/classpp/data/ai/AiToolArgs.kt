package com.fangyi.classpp.data.ai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 工具参数的统一读取口径（读写工具共用）。
 *
 * 模型常把「没填」写成显式 `null`（`{"limit":null}`、`{"date":null}`、`{"tags":null}`）。
 * 在 kotlinx JSON 里 [JsonNull] 也是 [JsonPrimitive]，直接取 `content` 会拿到字符串
 * "null" —— 既让 `limit` 被当成「已提供」而只回 10 条，也会把 "null" 当日期解析报错。
 * 这里统一把显式 null 视为**参数缺席**。
 */
internal fun JsonElement?.argPrimitive(): JsonPrimitive? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }

/** 显式 null 视为缺席：null = 没有给这个参数 */
internal fun JsonObject.stringOrNull(name: String): String? =
    this[name].argPrimitive()?.content

/** 数字参数：显式 null / 非法数字都视为缺席（由调用方回落到默认值或报错） */
internal fun JsonObject.intOrNull(name: String): Int? =
    this[name].argPrimitive()?.content?.toIntOrNull()

/** 布尔参数：显式 null / 非法布尔都视为缺席 */
internal fun JsonObject.booleanOrNull(name: String): Boolean? =
    this[name].argPrimitive()?.content?.toBooleanStrictOrNull()

/** 参数是否「真的给了」（显式 null 不算）：分页参数据此判断模型有没有主动要求分页 */
internal fun JsonObject.hasArg(name: String): Boolean = this[name].argPrimitive() != null

/** 数组参数是否真的给了（显式 null 不算） */
internal fun JsonObject.elementOrNull(name: String): JsonElement? =
    this[name]?.takeIf { it !is JsonNull }
