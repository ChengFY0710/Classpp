package com.fangyi.classpp.data.export

import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.TodoError
import com.fangyi.classpp.data.TodoReadResult
import com.fangyi.classpp.data.TodoValidator
import com.fangyi.classpp.data.model.TODO_FORMAT_VERSION
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoSharePayload
import com.fangyi.classpp.data.model.newUuid

/**
 * 待办分享载荷编解码：导出 = 待办列表 → pretty JSON；导入 = 解析 → 版本/kind 闸门 →
 * **id 全量重铸**（todo 与 step，与本机已有待办零冲突的唯一保证机制）→ 全量校验。
 * 追加落盘由仓库负责（需要库内状态）。
 */
internal object TodoShareCodec {

    fun encode(todos: List<Todo>, exportedAtMillis: Long): String =
        ScheduleJson.encodeShare(
            TodoSharePayload(exportedAtMillis = exportedAtMillis, todos = todos),
        )

    /** 解析并产出可直接追加入库的待办列表（todo 与 step 均为新 id、逐条校验） */
    fun decodeForImport(json: String): TodoReadResult<List<Todo>> {
        val payload = try {
            ScheduleJson.decodeShare<TodoSharePayload>(json)
        } catch (e: Exception) {
            return TodoReadResult.Err(TodoError.ImportFormatInvalid(e.message ?: e::class.simpleName ?: "?"))
        }

        if (payload.formatVersion > TODO_FORMAT_VERSION) {
            return TodoReadResult.Err(TodoError.ImportVersionUnsupported(payload.formatVersion))
        }
        if (payload.kind != TodoSharePayload.SHARE_KIND) {
            return TodoReadResult.Err(TodoError.ImportKindMismatch(payload.kind))
        }

        val rebuilt = payload.todos.map { todo ->
            todo.copy(
                id = newUuid(),
                steps = todo.steps.map { it.copy(id = newUuid()) },
            )
        }
        for (todo in rebuilt) {
            val errors = TodoValidator.validate(todo)
            if (errors.isNotEmpty()) return TodoReadResult.Err(errors.first())
        }
        return TodoReadResult.Ok(rebuilt)
    }
}
