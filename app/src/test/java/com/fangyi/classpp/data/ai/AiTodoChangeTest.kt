package com.fangyi.classpp.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 待确认变更的稳定键与应用结果合并：应用过程有多个挂起点，期间流式消息可能追加新的
 * Pending——合并只能替换命中的项，不能拿旧快照整体覆盖。
 */
class AiTodoChangeTest {

    @Test
    fun `merge keeps changes appended during apply`() {
        val appliedChange = AiTodoChange(
            kind = AiTodoChangeKind.Update,
            todoId = "t1",
            patch = AiTodoPatch(name = "新名字"),
            id = "c1",
        )
        val appended = AiTodoChange(
            kind = AiTodoChangeKind.Delete,
            todoId = "t2",
            id = "c2",
        )
        val applied = appliedChange.copy(status = AiTodoChangeStatus.Applied)

        val merged = mergeTodoChangeResults(
            current = listOf(appliedChange, appended),
            applied = mapOf(appliedChange.stableKey() to applied),
        )

        assertEquals(AiTodoChangeStatus.Applied, merged[0].status)
        // 应用开始后才追加的 Pending 必须原样保留
        assertEquals(AiTodoChangeStatus.Pending, merged[1].status)
        assertEquals("t2", merged[1].todoId)
        assertEquals(listOf("c1", "c2"), merged.map { it.id })
    }

    @Test
    fun `legacy changes without id use compound key`() {
        val legacy = AiTodoChange(
            kind = AiTodoChangeKind.Update,
            todoId = "t1",
            patch = AiTodoPatch(name = "A"),
        )
        val differentPatch = AiTodoChange(
            kind = AiTodoChangeKind.Update,
            todoId = "t1",
            patch = AiTodoPatch(name = "B"),
        )
        val failed = legacy.copy(
            status = AiTodoChangeStatus.Failed,
            failure = AiTodoChangeFailure.InvalidPatch,
        )

        val merged = mergeTodoChangeResults(
            current = listOf(legacy, differentPatch),
            applied = mapOf(legacy.stableKey() to failed),
        )

        assertEquals(AiTodoChangeStatus.Failed, merged[0].status)
        // 同目标但补丁不同 = 另一条变更，不能被复合键误合并
        assertEquals(AiTodoChangeStatus.Pending, merged[1].status)
    }
}
