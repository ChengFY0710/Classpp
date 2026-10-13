package com.fangyi.classpp.ui.note

import com.fangyi.classpp.data.model.TodoGroup
import com.fangyi.classpp.data.testTodo
import org.junit.Assert.assertEquals
import org.junit.Test

/** 顶栏分组下标 → 可见列表的派生规则（0 = 全部、1 = 未分组、2 起 = 用户分组） */
class TodoGroupFilterTest {

    private val groups = listOf(TodoGroup("作业"), TodoGroup("日常琐事"))
    private val todos = listOf(
        testTodo(id = "1", name = "未分组A"),
        testTodo(id = "2", name = "作业A", group = "作业"),
        testTodo(id = "3", name = "作业B", group = "作业"),
        testTodo(id = "4", name = "日常", group = "日常琐事"),
    )

    @Test
    fun `index 0 shows all todos`() {
        assertEquals(todos, filterTodosByGroup(todos, 0, groups))
    }

    @Test
    fun `index 1 shows ungrouped only`() {
        assertEquals(listOf("未分组A"), filterTodosByGroup(todos, 1, groups).map { it.name })
    }

    @Test
    fun `indexes from 2 show the matching group`() {
        assertEquals(listOf("作业A", "作业B"), filterTodosByGroup(todos, 2, groups).map { it.name })
        assertEquals(listOf("日常"), filterTodosByGroup(todos, 3, groups).map { it.name })
    }

    @Test
    fun `out of range index falls back to all`() {
        assertEquals(todos, filterTodosByGroup(todos, 9, groups))
        assertEquals(todos, filterTodosByGroup(todos, -1, groups))
    }
}
