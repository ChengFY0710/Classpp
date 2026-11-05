package com.fangyi.classpp.ui.note

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoTimeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待办编辑顶栏同步钮的候选契约（[todoSyncCandidates]）：
 *
 * - 只取选中集里的待办，未选中的不参与；
 * - 所有待办都可同步（没有日期时事件落在截止日期、再退到当天），无时间/无日期不再
 *   被剔除——只选中这类待办时同样有候选；
 * - 候选按待办列表顺序排列（批量写入按此顺序产出事件）。
 */
class TodoEditBarSyncTest {

    private val date = IsoDate.parse("2026-11-02")

    /** A 时段、B 无时间无日期、C 全天、D 全天却没有日期（防御口径，正常存储不会出现） */
    private val todos = listOf(
        Todo(
            id = "A",
            name = "A",
            dates = listOf(date),
            timeKind = TodoTimeKind.Period,
            startMinute = 540,
            endMinute = 600,
        ),
        Todo(id = "B", name = "B"),
        Todo(id = "C", name = "C", dates = listOf(date), timeKind = TodoTimeKind.AllDay),
        Todo(id = "D", name = "D", timeKind = TodoTimeKind.AllDay),
    )

    @Test
    fun `empty selection has no candidates`() {
        assertTrue(todoSyncCandidates(todos, emptySet()).isEmpty())
        // 选中集里的 id 已不在待办列表（例如刚被删除）：同样没有候选
        assertTrue(todoSyncCandidates(todos, setOf("missing")).isEmpty())
    }

    @Test
    fun `todos without date or time are still candidates`() {
        assertEquals(listOf("B"), todoSyncCandidates(todos, setOf("B")).map { it.id })
        assertEquals(listOf("D"), todoSyncCandidates(todos, setOf("D")).map { it.id })
    }

    @Test
    fun `candidates keep the list order`() {
        assertEquals(
            listOf("A", "C"),
            todoSyncCandidates(todos, setOf("A", "C")).map { it.id },
        )
    }

    @Test
    fun `mixed selection keeps every selected todo`() {
        assertEquals(
            listOf("A", "B", "C", "D"),
            todoSyncCandidates(todos, setOf("A", "B", "C", "D", "missing")).map { it.id },
        )
    }
}
