package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.model.TodoUrgency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `sort by urgency direction`() {
        val list = listOf(done1, essay, lab)
        // 降序 = 紧急度高在前（Critical > Medium > Low）
        assertEquals(listOf(lab, essay, done1), list.sortedFor(TodoSort.Urgency))
        // 升序 = 紧急度低在前
        assertEquals(listOf(done1, essay, lab), list.sortedFor(TodoSort.Urgency, descending = false))
    }

    @Test
    fun `sort by created direction`() {
        val list = listOf(lab, essay, done1) // 创建时刻 100 / 300 / 200
        // 降序 = 创建早的在前
        assertEquals(listOf(lab, done1, essay), list.sortedFor(TodoSort.Created))
        // 升序 = 新在前
        assertEquals(listOf(essay, done1, lab), list.sortedFor(TodoSort.Created, descending = false))
    }

    @Test
    fun `sort by name`() {
        val c = testTodo(id = "c", name = "Alpha")
        val a = testTodo(id = "a", name = "Beta")
        val b = testTodo(id = "b", name = "Alpha2")
        assertEquals(listOf(c, b, a), listOf(a, b, c).sortedFor(TodoSort.Name))
    }

    @Test
    fun `non-deadline sorts break tie on nearer deadline`() {
        // 同紧急度并列：截止近的在前（同日按时刻），无截止的排该组最后
        val far = testTodo(id = "far", name = "远截止", urgency = TodoUrgency.High, deadlineDate = d0620, deadlineMinute = 600)
        val near = testTodo(id = "near", name = "近截止", urgency = TodoUrgency.High, deadlineDate = d0618, deadlineMinute = 900)
        val none = testTodo(id = "none", name = "无截止", urgency = TodoUrgency.High)
        val sameDay = testTodo(id = "same", name = "同日按时刻", urgency = TodoUrgency.High, deadlineDate = d0618, deadlineMinute = 480)
        assertEquals(
            listOf(sameDay, near, far, none),
            listOf(far, none, near, sameDay).sortedFor(TodoSort.Urgency),
        )
    }

    @Test
    fun `non-deadline sorts keep own dimension as primary key`() {
        // 并列键只救并列：紧急度高但截止远的，仍排在紧急度低但截止近的前面
        val criticalFar = testTodo(id = "cf", name = "紧急但截止远", urgency = TodoUrgency.Critical, deadlineDate = d0620, deadlineMinute = 600)
        val lowNear = testTodo(id = "ln", name = "不急但截止近", urgency = TodoUrgency.Low, deadlineDate = d0618, deadlineMinute = 600)
        assertEquals(listOf(criticalFar, lowNear), listOf(lowNear, criticalFar).sortedFor(TodoSort.Urgency))
    }

    // ---------- 逾期判定 ----------

    @Test
    fun `overdue when deadline date has passed`() {
        // 截止 06-18 10:00：次日即逾期，当天时刻未到不算
        assertTrue(lab.isOverdue(IsoDate.parse("2026-06-19"), nowMinute = 0))
        assertFalse(lab.isOverdue(d0618, nowMinute = 599))
        assertFalse(essay.isOverdue(d0618, nowMinute = 1439)) // 截止 06-20，未到
    }

    @Test
    fun `overdue on deadline day only after the minute`() {
        val todo = testTodo(deadlineDate = d0618, deadlineMinute = 600)
        assertFalse(todo.isOverdue(d0618, nowMinute = 599))
        assertFalse(todo.isOverdue(d0618, nowMinute = 600)) // 截止那一分钟还没过
        assertTrue(todo.isOverdue(d0618, nowMinute = 601))
    }

    @Test
    fun `deadline day without minute is not overdue that day`() {
        val todo = testTodo(deadlineDate = d0618)
        assertFalse(todo.isOverdue(d0618, nowMinute = 1439))
        assertTrue(todo.isOverdue(IsoDate.parse("2026-06-19"), nowMinute = 0))
    }

    @Test
    fun `no deadline is never overdue`() {
        assertFalse(testTodo().isOverdue(d0618, nowMinute = 1439))
    }

    @Test
    fun `completed todo is never overdue`() {
        val todo = testTodo(deadlineDate = d0610, completed = true, completedAtMillis = 1)
        assertFalse(todo.isOverdue(d0618, nowMinute = 0))
    }

    // ---------- 待办时间覆盖日期（日历待办小点） ----------

    @Test
    fun `covered dates are explicit dates clipped to window`() {
        // lab dates = 06-10 / 06-15，截止 06-18——截止日期不算待办时间
        assertEquals(setOf(d0610, d0615), listOf(lab).datesCoveredWithin(d0610, d0615))
        assertEquals(setOf(d0615), listOf(lab).datesCoveredWithin(d0612, d0616))
        assertEquals(emptySet<IsoDate>(), listOf(lab).datesCoveredWithin(d0616, d0617))
    }

    @Test
    fun `repeat occurrences expand from anchor and clip to window`() {
        val weekly = testTodo(
            id = "r1",
            dates = listOf(d0610), // 2026-06-10 是周三：锚周起每周三一期
            repeat = RepeatRule(type = RepeatType.Weekly),
        )
        assertEquals(setOf(d0610, d0617), listOf(weekly).datesCoveredWithin(d0610, d0617))
        // 窗口晚于锚日（翻月浏览）：只剩窗内的分期
        assertEquals(setOf(d0617), listOf(weekly).datesCoveredWithin(d0616, d0620))
        // 锚日晚于窗口：没有任何分期
        assertEquals(emptySet<IsoDate>(), listOf(weekly).datesCoveredWithin(d0612, d0615))
    }

    @Test
    fun `recorded occurrences count even when rule no longer produces them`() {
        // 规则修改后不再产生的历史分期（已记账的完成分期）仍算那天有待办：
        // 周三规则不产生 06-15（周一），但记录在案就照样覆盖
        val moved = testTodo(
            id = "r2",
            dates = listOf(d0610),
            repeat = RepeatRule(type = RepeatType.Weekly),
            completedOccurrences = listOf(d0615),
        )
        assertEquals(setOf(d0610, d0615, d0617), listOf(moved).datesCoveredWithin(d0610, d0620))
    }

    @Test
    fun `todos without dates cover nothing`() {
        assertEquals(emptySet<IsoDate>(), listOf(essay, done1).datesCoveredWithin(d0610, d0620))
        assertEquals(emptySet<IsoDate>(), emptyList<com.fangyi.classpp.data.model.Todo>().datesCoveredWithin(d0610, d0620))
    }
}
