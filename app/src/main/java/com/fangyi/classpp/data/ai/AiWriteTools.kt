package com.fangyi.classpp.data.ai

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 写工具的执行通道（设计稿 §4.4/§7.1）：由仓库在原生工具循环里按「当前 assistant
 * 消息」绑定实现，[AiChatContext.writes] 只是把它带进工具执行层。
 *
 * 工具层只做「参数 → 动作草稿」，**不写库**：写库、确认卡生成、撤销记录全部复用 JSON
 * 信封那条路径（`createTodos` / `buildTodoChanges` / `applyCompletions` /
 * `applyLifecycleActions`），因此工具卡与业务卡片看到的是同一份记录，行为不会分叉。
 *
 * 返回值统一是 `{ok,data}` / `{ok:false,error:{code,message}}` 信封（设计稿 §4.5）。
 */
interface AiToolWriteGateway {

    /**
     * 新建待办：立即落库，结果挂到消息的 `todoIds`（可撤销）。
     *
     * @param dropped 工具层参数解码时已经丢弃的条目数（与映射层丢弃数合并进结果信封）
     */
    suspend fun createTodos(drafts: List<AiTodoDraft>, dropped: Int = 0): JsonObject

    /** 修改待办：只生成 `AiTodoChange(Pending)`，不写库 */
    suspend fun updateTodos(drafts: List<AiTodoUpdateDraft>, dropped: Int = 0): JsonObject

    /** 删除待办（软删除）：只生成待确认记录，不写库 */
    suspend fun deleteTodos(drafts: List<AiTodoDeleteDraft>, dropped: Int = 0): JsonObject

    /** 完成/取消完成：立即执行，结果挂到消息的 `todoCompletions`（可撤销） */
    suspend fun completeTodos(
        completes: List<AiTodoCompletionDraft>,
        reopens: List<AiTodoCompletionDraft>,
        dropped: Int = 0,
    ): JsonObject

    /** 归档/取消归档/恢复：立即执行，结果挂到消息的 `todoLifecycles`（可撤销） */
    suspend fun archiveTodos(
        archives: List<AiTodoLifecycleDraft>,
        unarchives: List<AiTodoLifecycleDraft>,
        restores: List<AiTodoLifecycleDraft>,
        dropped: Int = 0,
    ): JsonObject

    /** 新建待办分组：立即落库；同名已存在视为成功（幂等） */
    suspend fun createGroups(names: List<String>, dropped: Int = 0): JsonObject

    /** 新建待办标签：立即落库到标签库；同名已存在视为成功（幂等） */
    suspend fun createTags(names: List<String>, dropped: Int = 0): JsonObject
}

/**
 * 写工具（P2）：与现有 JSON 动作一一对应，语义不升级。
 *
 * - 新建/完成/归档 → 立即执行，UI 沿用 NoteCard / 完成列表 / 归档列表的撤销入口；
 * - 修改/删除 → 只生成待确认记录，模型收到 `pending_user_confirmation` 后应请用户点
 *   确认卡，**不得声称已经改好/删好**。
 *
 * 参数解析保持宽容（未知字段忽略、显式 null 回落默认值）；单条解码失败会计入
 * `dropped` 随结果信封回给模型（整批不可用时回 `invalid_arguments`），不再静默少执行。
 * schema 仍由 [AiToolRegistry] 持有（与回退契约共用同一份定义），这里只负责执行。
 */
internal object AiWriteTools {

    suspend fun executeCreateTodos(args: JsonObject, ctx: AiChatContext): JsonObject {
        val gateway = ctx.writes ?: return wrongState()
        val decoded = args.decodeItems("todos", AiTodoDraft.serializer())
            ?: return invalidArguments("todos 必须是对象数组")
        if (decoded.items.isEmpty()) return emptyOrInvalid("todos", decoded.dropped)
        return gateway.createTodos(decoded.items, decoded.dropped)
    }

    suspend fun executeUpdateTodos(args: JsonObject, ctx: AiChatContext): JsonObject {
        val gateway = ctx.writes ?: return wrongState()
        val decoded = args.decodeItems("updates", AiTodoUpdateDraft.serializer())
            ?: return invalidArguments("updates 必须是对象数组")
        // 空补丁（缺省或显式 null）不可能产生变更：计入参数丢弃，全部为空时回
        // invalid_arguments，而不是让仓库报 not_found 误导模型去换 id
        val usable = decoded.items.filter { it.patch != AiTodoPatch() }
        val dropped = decoded.dropped + (decoded.items.size - usable.size)
        if (usable.isEmpty()) return emptyOrInvalid("updates", dropped)
        return gateway.updateTodos(usable, dropped)
    }

    suspend fun executeDeleteTodos(args: JsonObject, ctx: AiChatContext): JsonObject {
        val gateway = ctx.writes ?: return wrongState()
        val decoded = args.decodeItems("deletes", AiTodoDeleteDraft.serializer())
            ?: return invalidArguments("deletes 必须是对象数组")
        if (decoded.items.isEmpty()) return emptyOrInvalid("deletes", decoded.dropped)
        return gateway.deleteTodos(decoded.items, decoded.dropped)
    }

    suspend fun executeCompleteTodos(args: JsonObject, ctx: AiChatContext): JsonObject {
        val gateway = ctx.writes ?: return wrongState()
        val completes = args.decodeItems("completes", AiTodoCompletionDraft.serializer())
            ?: return invalidArguments("completes 必须是对象数组")
        val reopens = args.decodeItems("reopens", AiTodoCompletionDraft.serializer())
            ?: return invalidArguments("reopens 必须是对象数组")
        val dropped = completes.dropped + reopens.dropped
        if (completes.items.isEmpty() && reopens.items.isEmpty()) {
            return if (dropped > 0) {
                invalidArguments("completes 与 reopens 有 $dropped 条参数无法解析，未执行")
            } else {
                invalidArguments("completes 与 reopens 至少要有一个")
            }
        }
        return gateway.completeTodos(completes.items, reopens.items, dropped)
    }

    suspend fun executeArchiveTodos(args: JsonObject, ctx: AiChatContext): JsonObject {
        val gateway = ctx.writes ?: return wrongState()
        val archives = args.decodeItems("archives", AiTodoLifecycleDraft.serializer())
            ?: return invalidArguments("archives 必须是对象数组")
        val unarchives = args.decodeItems("unarchives", AiTodoLifecycleDraft.serializer())
            ?: return invalidArguments("unarchives 必须是对象数组")
        val restores = args.decodeItems("restores", AiTodoLifecycleDraft.serializer())
            ?: return invalidArguments("restores 必须是对象数组")
        val dropped = archives.dropped + unarchives.dropped + restores.dropped
        if (archives.items.isEmpty() && unarchives.items.isEmpty() && restores.items.isEmpty()) {
            return if (dropped > 0) {
                invalidArguments("archives / unarchives / restores 有 $dropped 条参数无法解析，未执行")
            } else {
                invalidArguments("archives / unarchives / restores 至少要有一个")
            }
        }
        return gateway.archiveTodos(archives.items, unarchives.items, restores.items, dropped)
    }

    /** 新建分组：names trim / 去空 / 去重后交给网关；建分组不改任何待办，因此无需确认卡。 */
    suspend fun executeCreateTodoGroups(args: JsonObject, ctx: AiChatContext): JsonObject {
        val gateway = ctx.writes ?: return wrongState()
        val decoded = args.decodeNames("groups") ?: return invalidArguments("groups 必须是字符串数组")
        if (decoded.names.isEmpty()) return emptyOrInvalid("groups", decoded.dropped)
        return gateway.createGroups(decoded.names, decoded.dropped)
    }

    /** 新建标签：语义同 [executeCreateTodoGroups]；标签名是标签库的唯一键。 */
    suspend fun executeCreateTodoTags(args: JsonObject, ctx: AiChatContext): JsonObject {
        val gateway = ctx.writes ?: return wrongState()
        val decoded = args.decodeNames("tags") ?: return invalidArguments("tags 必须是字符串数组")
        if (decoded.names.isEmpty()) return emptyOrInvalid("tags", decoded.dropped)
        return gateway.createTags(decoded.names, decoded.dropped)
    }

    /**
     * 读取一个动作数组：缺席/`null` 视为空列表（只有 optional 段会走到这里；
     * required 段在 [AiToolExecutor] 的 schema 校验里已被拦下），不是数组时返回 null。
     *
     * 单条解码失败不再静默吞掉：计入 [DecodedItems.dropped]，由调用方写进结果信封，
     * 让模型知道「有 N 条没执行」。参数解析用 [AiJson.toolArgs]（容忍显式 null 与
     * 非法枚举），持久化读取仍用严格模式。
     */
    private fun <T> JsonObject.decodeItems(
        name: String,
        serializer: KSerializer<T>,
    ): DecodedItems<T>? {
        val element = this[name] ?: return DecodedItems(emptyList(), 0)
        if (element is JsonNull) return DecodedItems(emptyList(), 0)
        val items = element as? JsonArray ?: return null
        val decoded = mutableListOf<T>()
        var dropped = 0
        items.forEach { item ->
            val obj = item as? JsonObject
            if (obj == null) {
                dropped++
                return@forEach
            }
            val value = runCatching {
                AiJson.toolArgs.decodeFromJsonElement(serializer, obj)
            }.getOrNull()
            if (value == null) dropped++ else decoded += value
        }
        return DecodedItems(decoded, dropped)
    }

    /**
     * 读取一个字符串数组（分组/标签名）：非字符串条目计入 dropped，字符串 trim 后去空、
     * 按出现顺序去重；不是数组时返回 null（由执行器回 `invalid_arguments`）。
     */
    private fun JsonObject.decodeNames(name: String): DecodedNames? {
        val element = this[name] ?: return DecodedNames(emptyList(), 0)
        if (element is JsonNull) return DecodedNames(emptyList(), 0)
        val items = element as? JsonArray ?: return null
        var dropped = 0
        val names = items
            .mapNotNull { item ->
                if (item is JsonNull) return@mapNotNull null
                val primitive = item as? JsonPrimitive
                if (primitive == null || !primitive.isString) {
                    dropped++
                    return@mapNotNull null
                }
                primitive.content
            }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return DecodedNames(names, dropped)
    }

    private data class DecodedItems<T>(val items: List<T>, val dropped: Int)

    private data class DecodedNames(val names: List<String>, val dropped: Int)

    /** 段内没有任何可用条目：有解码失败时给出条数，否则按「不能为空」 */
    private fun emptyOrInvalid(section: String, dropped: Int): JsonObject =
        if (dropped > 0) {
            invalidArguments("$section 有 $dropped 条参数无法解析，未执行")
        } else {
            invalidArguments("$section 不能为空")
        }
}

// ---------- 结果信封（工具层与仓库共用；错误码见设计稿 §4.5） ----------

internal fun toolSuccess(data: JsonObject): JsonObject = buildJsonObject {
    put("ok", true)
    put("data", data)
}

internal fun toolFailure(code: String, message: String): JsonObject = buildJsonObject {
    put("ok", false)
    put(
        "error",
        buildJsonObject {
            put("code", code)
            put("message", message)
        },
    )
}

/** 待确认动作的结果：模型据此请用户点确认卡，而不是声称已经写入 */
internal fun pendingConfirmationResult(changes: List<AiTodoChange>, dropped: Int): JsonObject =
    toolSuccess(
        buildJsonObject {
            put("status", "pending_user_confirmation")
            put("count", changes.size)
            putJsonArray("items") {
                changes.forEach { change ->
                    add(
                        buildJsonObject {
                            put("id", change.todoId)
                            put("name", change.todoName)
                            put("kind", if (change.kind == AiTodoChangeKind.Delete) "delete" else "update")
                        },
                    )
                }
            }
            if (dropped > 0) put("dropped", dropped)
            put(
                "message",
                if (dropped > 0) {
                    "已在消息下方生成确认卡，等待用户点击「应用变更」；另有 $dropped 条未能解析或未生成确认项"
                } else {
                    "已在消息下方生成确认卡，等待用户点击「应用变更」"
                },
            )
        },
    )

private fun wrongState(): JsonObject = toolFailure("wrong_state", "当前对话未启用写工具")

private fun invalidArguments(message: String): JsonObject = toolFailure("invalid_arguments", message)
