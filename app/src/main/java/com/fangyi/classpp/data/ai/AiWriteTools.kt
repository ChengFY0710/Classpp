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

    /** 新建待办：立即落库，结果挂到消息的 `todoIds`（可撤销） */
    suspend fun createTodos(drafts: List<AiTodoDraft>): JsonObject

    /** 修改待办：只生成 `AiTodoChange(Pending)`，不写库 */
    suspend fun updateTodos(drafts: List<AiTodoUpdateDraft>): JsonObject

    /** 删除待办（软删除）：只生成待确认记录，不写库 */
    suspend fun deleteTodos(drafts: List<AiTodoDeleteDraft>): JsonObject

    /** 完成/取消完成：立即执行，结果挂到消息的 `todoCompletions`（可撤销） */
    suspend fun completeTodos(
        completes: List<AiTodoCompletionDraft>,
        reopens: List<AiTodoCompletionDraft>,
    ): JsonObject

    /** 归档/取消归档/恢复：立即执行，结果挂到消息的 `todoLifecycles`（可撤销） */
    suspend fun archiveTodos(
        archives: List<AiTodoLifecycleDraft>,
        unarchives: List<AiTodoLifecycleDraft>,
        restores: List<AiTodoLifecycleDraft>,
    ): JsonObject

    /** 新建待办分组：立即落库；同名已存在视为成功（幂等） */
    suspend fun createGroups(names: List<String>): JsonObject

    /** 新建待办标签：立即落库到标签库；同名已存在视为成功（幂等） */
    suspend fun createTags(names: List<String>): JsonObject
}

/**
 * 写工具（P2）：与现有 JSON 动作一一对应，语义不升级。
 *
 * - 新建/完成/归档 → 立即执行，UI 沿用 NoteCard / 完成列表 / 归档列表的撤销入口；
 * - 修改/删除 → 只生成待确认记录，模型收到 `pending_user_confirmation` 后应请用户点
 *   确认卡，**不得声称已经改好/删好**。
 *
 * 参数解析保持宽容（未知字段忽略、解析不出的条目丢弃）；整批不可用时返回
 * `invalid_arguments` 而不是抛异常。schema 仍由 [AiToolRegistry] 持有（与回退契约
 * 共用同一份定义），这里只负责执行。
 */
internal object AiWriteTools {

    suspend fun executeCreateTodos(args: JsonObject, ctx: AiChatContext): JsonObject {
        val gateway = ctx.writes ?: return wrongState()
        val drafts = args.decodeItems("todos", AiTodoDraft.serializer())
            ?: return invalidArguments("todos 必须是对象数组")
        if (drafts.isEmpty()) return invalidArguments("todos 不能为空")
        return gateway.createTodos(drafts)
    }

    suspend fun executeUpdateTodos(args: JsonObject, ctx: AiChatContext): JsonObject {
        val gateway = ctx.writes ?: return wrongState()
        val drafts = args.decodeItems("updates", AiTodoUpdateDraft.serializer())
            ?: return invalidArguments("updates 必须是对象数组")
        if (drafts.isEmpty()) return invalidArguments("updates 不能为空")
        return gateway.updateTodos(drafts)
    }

    suspend fun executeDeleteTodos(args: JsonObject, ctx: AiChatContext): JsonObject {
        val gateway = ctx.writes ?: return wrongState()
        val drafts = args.decodeItems("deletes", AiTodoDeleteDraft.serializer())
            ?: return invalidArguments("deletes 必须是对象数组")
        if (drafts.isEmpty()) return invalidArguments("deletes 不能为空")
        return gateway.deleteTodos(drafts)
    }

    suspend fun executeCompleteTodos(args: JsonObject, ctx: AiChatContext): JsonObject {
        val gateway = ctx.writes ?: return wrongState()
        val completes = args.decodeItems("completes", AiTodoCompletionDraft.serializer())
            ?: return invalidArguments("completes 必须是对象数组")
        val reopens = args.decodeItems("reopens", AiTodoCompletionDraft.serializer())
            ?: return invalidArguments("reopens 必须是对象数组")
        if (completes.isEmpty() && reopens.isEmpty()) {
            return invalidArguments("completes 与 reopens 至少要有一个")
        }
        return gateway.completeTodos(completes, reopens)
    }

    suspend fun executeArchiveTodos(args: JsonObject, ctx: AiChatContext): JsonObject {
        val gateway = ctx.writes ?: return wrongState()
        val archives = args.decodeItems("archives", AiTodoLifecycleDraft.serializer())
            ?: return invalidArguments("archives 必须是对象数组")
        val unarchives = args.decodeItems("unarchives", AiTodoLifecycleDraft.serializer())
            ?: return invalidArguments("unarchives 必须是对象数组")
        val restores = args.decodeItems("restores", AiTodoLifecycleDraft.serializer())
            ?: return invalidArguments("restores 必须是对象数组")
        if (archives.isEmpty() && unarchives.isEmpty() && restores.isEmpty()) {
            return invalidArguments("archives / unarchives / restores 至少要有一个")
        }
        return gateway.archiveTodos(archives, unarchives, restores)
    }

    /** 新建分组：names trim / 去空 / 去重后交给网关；建分组不改任何待办，因此无需确认卡。 */
    suspend fun executeCreateTodoGroups(args: JsonObject, ctx: AiChatContext): JsonObject {
        val gateway = ctx.writes ?: return wrongState()
        val names = args.decodeNames("groups") ?: return invalidArguments("groups 必须是字符串数组")
        if (names.isEmpty()) return invalidArguments("groups 不能为空")
        return gateway.createGroups(names)
    }

    /** 新建标签：语义同 [executeCreateTodoGroups]；标签名是标签库的唯一键。 */
    suspend fun executeCreateTodoTags(args: JsonObject, ctx: AiChatContext): JsonObject {
        val gateway = ctx.writes ?: return wrongState()
        val names = args.decodeNames("tags") ?: return invalidArguments("tags 必须是字符串数组")
        if (names.isEmpty()) return invalidArguments("tags 不能为空")
        return gateway.createTags(names)
    }

    /**
     * 读取一个动作数组：缺席/`null` 视为空列表（只有 optional 段会走到这里；
     * required 段在 [AiToolExecutor] 的 schema 校验里已被拦下），不是数组时返回 null。
     *
     * 单条动作不要写成对象：schema 声明的是 array，执行器按类型校验后会直接回
     * `invalid_arguments`，模型据此自行改正比默默兼容更可预期。
     */
    private fun <T> JsonObject.decodeItems(name: String, serializer: KSerializer<T>): List<T>? {
        val element = this[name] ?: return emptyList()
        if (element is JsonNull) return emptyList()
        val items = element as? JsonArray ?: return null
        return items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            runCatching { AiJson.storage.decodeFromJsonElement(serializer, obj) }.getOrNull()
        }
    }

    /**
     * 读取一个字符串数组（分组/标签名）：非字符串条目丢弃，trim 后去空、按出现顺序去重；
     * 不是数组时返回 null（由执行器回 `invalid_arguments`）。
     */
    private fun JsonObject.decodeNames(name: String): List<String>? {
        val element = this[name] ?: return emptyList()
        if (element is JsonNull) return emptyList()
        val items = element as? JsonArray ?: return null
        return items
            .mapNotNull { item ->
                (item as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
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
internal fun pendingConfirmationResult(changes: List<AiTodoChange>, dropped: Boolean): JsonObject =
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
            if (dropped) put("dropped", true)
            put("message", "已在消息下方生成确认卡，等待用户点击「应用变更」")
        },
    )

private fun wrongState(): JsonObject = toolFailure("wrong_state", "当前对话未启用写工具")

private fun invalidArguments(message: String): JsonObject = toolFailure("invalid_arguments", message)
