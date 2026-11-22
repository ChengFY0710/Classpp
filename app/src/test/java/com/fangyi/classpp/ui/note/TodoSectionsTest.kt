package com.fangyi.classpp.ui.note

import com.fangyi.classpp.data.TodoSort
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.testTodo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 列表四分组口径（[buildTodoSections]）的纯函数测试：宿主分钟级对表驱动 nowMinute——
 * 页面停留跨过今天的截止分钟后，卡片自动从「未完成」移进「已逾期」，不等下一次交互。
 */
class TodoSectionsTest {

    @Test
    fun `crossing the deadline minute moves the todo into the overdue section`() {
        // 今天 21:00 截止：21:00 整（截止时刻本身）仍算未完成，21:01 起进「已逾期」组
        val today = IsoDate.parse("2026-03-20")
        val todo = testTodo(
            name = "截止在今天",
            deadlineDate = today,
            deadlineMinute = 21 * 60,
        )
        fun sectionsAt(minute: Int) = buildTodoSections(
            todos = listOf(todo),
            sort = TodoSort.Deadline,
            sortDescending = true,
            today = today,
            nowMinute = minute,
            visibleSections = TodoSection.entries.toSet(),
        )

        assertEquals(listOf(TodoSection.Active), sectionsAt(21 * 60).map { it.first })
        assertEquals(listOf(TodoSection.Overdue), sectionsAt(21 * 60 + 1).map { it.first })
    }
}
