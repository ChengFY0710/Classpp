package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.model.TodoUrgency
import org.junit.Assert.assertEquals
import org.junit.Test

class TodoQueryTest {

    private val d0610 = IsoDate.parse("2026-06-10")
    private val d0612 = IsoDate.parse("2026-06-12")
    private val d0615 = IsoDate.parse("2026-06-15")
    private val d0616 = IsoDate.parse("2026-06-16")
    private val d0617 = IsoDate.parse("2026-06-17")
    private val d0618 = IsoDate.parse("2026-06-18")
    private val d0620 = IsoDate.parse("2026-06-20")

    private val lab = testTodo(
        id = "t1",
        name = "Physics Lab",
        note = "带上数据",
        location = "实验楼 301",
        tags = listOf("物理"),
        urgency = TodoUrgency.Critical,
        dates = listOf(d0610, d0615),
        timeKind = TodoTimeKind.AllDay,
        deadlineDate = d0618,
        deadlineMinute = 600,
        steps = listOf(testStep("s1", title = "打印讲义")),
        createdAtMillis = 100,
    )
    private val essay = testTodo(
        id = "t2",
        name = "写论文",
        tags = listOf("写作"),
        urgency = TodoUrgency.Medium,
        deadlineDate = d0620,
        deadlineMinute = 1439,
        createdAtMillis = 300,
    )
    private val done1 = testTodo(id = "t3", name = "买书", completed = true, completedAtMillis = 500, createdAtMillis = 200)

    private val all = listOf(lab, essay, done1)

    @Test
    fun `default filter returns everything`() {
        assertEquals(all, all.filterTodos(TodoFilter()))
    }

    @Test
    fun `keyword matches name note location and step titles case-insensitively`() {
        assertEquals(listOf(lab), all.filterTodos(TodoFilter(keyword = "physics")))
        assertEquals(listOf(lab), all.filterTodos(TodoFilter(keyword = "数据")))
        assertEquals(listOf(lab), all.filterTodos(TodoFilter(keyword = "实验楼")))
        assertEquals(listOf(lab), all.filterTodos(TodoFilter(keyword = "讲义")))
        assertEquals(emptyList<com.fangyi.classpp.data.model.Todo>(), all.filterTodos(TodoFilter(keyword = "不存在")))
    }

    @Test
    fun `tags match any`() {
        assertEquals(listOf(lab, essay), all.filterTodos(TodoFilter(tags = setOf("物理", "写作"))))
        assertEquals(listOf(lab), all.filterTodos(TodoFilter(tags = setOf("物理"))))
    }

    @Test
    fun `urgencies match any`() {
        assertEquals(
            listOf(lab, essay),
            all.filterTodos(TodoFilter(urgencies = setOf(TodoUrgency.Critical, TodoUrgency.Medium))),
        )
    }

    @Test
    fun `completed filter`() {
        assertEquals(listOf(done1), all.filterTodos(TodoFilter(completed = true)))
        assertEquals(listOf(lab, essay), all.filterTodos(TodoFilter(completed = false)))
    }

    @Test
    fun `date range hits if any date falls inside`() {
        val filter = TodoFilter(datesFrom = d0612, datesTo = d0618)
        assertEquals(listOf(lab), all.filterTodos(filter)) // 06-15 命中

        val narrow = TodoFilter(datesFrom = d0616, datesTo = d0617)
        assertEquals(emptyList<com.fangyi.classpp.data.model.Todo>(), all.filterTodos(narrow))
    }

    @Test
    fun `deadline range excludes todos without deadline`() {
        assertEquals(listOf(lab), all.filterTodos(TodoFilter(deadlineFrom = d0610, deadlineTo = d0618)))
        assertEquals(listOf(essay), all.filterTodos(TodoFilter(deadlineFrom = d0620, deadlineTo = d0620)))
        assertEquals(
            emptyList<com.fangyi.classpp.data.model.Todo>(),
            all.filterTodos(TodoFilter(deadlineFrom = IsoDate.parse("2026-07-01"))),
        )
    }

    @Test
    fun `conditions combine with and`() {
        val filter = TodoFilter(urgencies = setOf(TodoUrgency.Critical), completed = false, keyword = "lab")
        assertEquals(listOf(lab), all.filterTodos(filter))
    }

    // ---------- 排序 ----------

    @Test
    fun `sort by deadline puts no-deadline last`() {
        val list = listOf(done1, essay, lab) // 乱序
        assertEquals(listOf(lab, essay, done1), list.sortedFor(TodoSort.Deadline)) // 06-18 10:00 < 06-20
    }

    @Test
    fun `sort by deadline breaks tie on minute`() {
        val a = testTodo(id = "a", name = "A", deadlineDate = d0618, deadlineMinute = 900)
        val b = testTodo(id = "b", name = "B", deadlineDate = d0618, deadlineMinute = 540)
        assertEquals(listOf(b, a), listOf(a, b).sortedFor(TodoSort.Deadline))
    }

    @Test
    fun `sort by urgency puts critical first`() {
        val list = listOf(done1, essay, lab)
        assertEquals(listOf(lab, essay, done1), list.sortedFor(TodoSort.Urgency)) // Critical > Medium > Low
    }

    @Test
    fun `sort by created newest first`() {
        val list = listOf(lab, essay, done1)
        assertEquals(listOf(essay, done1, lab), list.sortedFor(TodoSort.Created)) // 300 > 200 > 100
    }

    @Test
    fun `sort by name`() {
        val c = testTodo(id = "c", name = "Alpha")
        val a = testTodo(id = "a", name = "Beta")
        val b = testTodo(id = "b", name = "Alpha2")
        assertEquals(listOf(c, b, a), listOf(a, b, c).sortedFor(TodoSort.Name))
    }
}
