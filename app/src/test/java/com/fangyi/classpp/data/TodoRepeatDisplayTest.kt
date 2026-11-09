package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatEndType
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.RepeatUnit
import com.fangyi.classpp.data.model.Todo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重复待办列表展开（[expandRepeatOccurrences]）的显示测试：
 * - 合并口径（默认开，[RepeatRule.merge]）：整条系列只出一条滚动卡——今天起最近的未完成
 *   分期，过去的拖欠/已完成期不再逐期成卡；系列走完保留最后一期；
 * - 拆分口径（关闭）：拖欠期全显、清完拖欠才显示未来的"下一个"、完成自动前移、取消完成
 *   按"下一期与当前时间的关系"回退或共存；
 * 以及「下一次重复开始前」的逐期截止折算（两种口径同源）。
 */
class TodoRepeatDisplayTest {

    /** 拆分口径：逾期/已完成分期逐条展开（合并开关关闭） */
    private val splitWeekly = RepeatRule(type = RepeatType.Weekly, merge = false)

    /** 合并口径：整条系列一条滚动卡（默认开启） */
    private val mergedWeekly = RepeatRule(type = RepeatType.Weekly)

    private fun expand(todo: Todo, today: String): List<Todo> =
        listOf(todo).expandRepeatOccurrences(IsoDate.parse(today))

    private fun expandTimeline(todo: Todo, day: String): List<Todo> =
        listOf(todo).expandTimelineOccurrences(IsoDate.parse(day))

    private fun active(shown: List<Todo>): List<String> =
        shown.filterNot { it.completed }.map { it.dates.single().toString() }

    private fun done(shown: List<Todo>): List<String> =
        shown.filter { it.completed }.map { it.dates.single().toString() }

    @Test
    fun `overdue occurrences fill the window and hide the upcoming`() {
        // 锚 3/2（周一）每周，今天 3/20：拖欠 3/2、3/9、3/16 全显，未来的 3/23 不出现
        val todo = testTodo(dates = listOf(IsoDate.parse("2026-03-02")), repeat = splitWeekly)
        val shown = expand(todo, "2026-03-20")
        assertEquals(listOf("2026-03-02", "2026-03-09", "2026-03-16"), active(shown))
        assertTrue(done(shown).isEmpty())
    }

    @Test
    fun `completing occurrences in order advances the window`() {
        // 依次完成 3/2、3/9、3/16：清完拖欠后窗口前移到下一个 3/23，完成的三期落"已完成"
        val todo = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = splitWeekly,
            completedOccurrences = listOf(
                IsoDate.parse("2026-03-02"),
                IsoDate.parse("2026-03-09"),
                IsoDate.parse("2026-03-16"),
            ),
        )
        val shown = expand(todo, "2026-03-20")
        assertEquals(listOf("2026-03-23"), active(shown))
        assertEquals(listOf("2026-03-02", "2026-03-09", "2026-03-16"), done(shown))
    }

    @Test
    fun `un-completing the upcoming takes the window back from the next`() {
        // 今天 3/24，完成到 3/23：窗口在 3/30。取消 3/23（上一个，刚完成）——
        // 下一个 3/30 > 当前时间 → 隐藏，窗口回退到 3/23
        val base = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = splitWeekly,
            completedOccurrences = listOf(
                IsoDate.parse("2026-03-02"),
                IsoDate.parse("2026-03-09"),
                IsoDate.parse("2026-03-16"),
                IsoDate.parse("2026-03-23"),
            ),
        )
        assertEquals(listOf("2026-03-30"), active(expand(base, "2026-03-24")))

        val rolledBack = base.copy(completedOccurrences = base.completedOccurrences.dropLast(1))
        val shown = expand(rolledBack, "2026-03-24")
        assertEquals(listOf("2026-03-23"), active(shown))
        assertTrue(shown.none { it.dates.single() == IsoDate.parse("2026-03-30") })
    }

    @Test
    fun `un-completing keeps an already-overdue next visible`() {
        // 今天 3/31：3/30 也已过期（完成到 3/23 时窗口显示的 3/30 被时钟越过）。
        // 取消 3/23（上一个 < 当前时间）——下一个 3/30 不隐藏，两期同显
        val base = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = splitWeekly,
            completedOccurrences = listOf(
                IsoDate.parse("2026-03-02"),
                IsoDate.parse("2026-03-09"),
                IsoDate.parse("2026-03-16"),
                IsoDate.parse("2026-03-23"),
            ),
        )
        assertEquals(listOf("2026-03-30"), active(expand(base, "2026-03-31")))

        val rolledBack = base.copy(completedOccurrences = base.completedOccurrences.dropLast(1))
        assertEquals(
            listOf("2026-03-23", "2026-03-30"),
            active(expand(rolledBack, "2026-03-31")),
        )
    }

    @Test
    fun `non-repeat and whole-completed repeat pass through untouched`() {
        val plain = testTodo(name = "普通")
        val legacy = testTodo(
            name = "遗留",
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = splitWeekly,
            completed = true,
        )
        val shown = listOf(plain, legacy).expandRepeatOccurrences(IsoDate.parse("2026-03-20"))
        assertEquals(listOf(plain, legacy), shown)
    }

    // ---------- 合并口径（默认开启） ----------

    @Test
    fun `merge shows only the upcoming occurrence and lets the past go`() {
        // 锚 3/2 每周、今天 3/20：拖欠的 3/2、3/9、3/16 不再逐期成卡，只出最近的 3/23
        val todo = testTodo(dates = listOf(IsoDate.parse("2026-03-02")), repeat = mergedWeekly)
        val shown = expand(todo, "2026-03-20")
        assertEquals(listOf("2026-03-23"), active(shown))
        assertTrue(done(shown).isEmpty())
    }

    @Test
    fun `merge skips completed occurrences without emitting done cards`() {
        // 已完成的三期不各自成卡（历史只在完成情况日历里看），卡片前移到 3/23
        val todo = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = mergedWeekly,
            completedOccurrences = listOf(
                IsoDate.parse("2026-03-02"),
                IsoDate.parse("2026-03-09"),
                IsoDate.parse("2026-03-16"),
            ),
        )
        val shown = expand(todo, "2026-03-20")
        assertEquals(listOf("2026-03-23"), active(shown))
        assertTrue(done(shown).isEmpty())
    }

    @Test
    fun `merge advances past a completed upcoming occurrence`() {
        // 3/23 提前勾完：今天 3/24 时卡片直接前移到 3/30
        val todo = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = mergedWeekly,
            completedOccurrences = listOf(
                IsoDate.parse("2026-03-02"),
                IsoDate.parse("2026-03-09"),
                IsoDate.parse("2026-03-16"),
                IsoDate.parse("2026-03-23"),
            ),
        )
        assertEquals(listOf("2026-03-30"), active(expand(todo, "2026-03-24")))
    }

    @Test
    fun `merge folds the occurrence deadline`() {
        // 开始前 2 天：合并卡 3/23 的截止 = 下一期 3/30 − 2 = 3/28，时刻沿用；存储截止不动
        val todo = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = mergedWeekly,
            deadlineDate = IsoDate.parse("2026-03-05"),
            deadlineMinute = 600,
            deadlineBeforeRepeat = true,
            deadlineBeforeDays = 2,
        )
        val card = expand(todo, "2026-03-20").single()
        assertEquals(IsoDate.parse("2026-03-23"), card.dates.single())
        assertEquals(IsoDate.parse("2026-03-28"), card.deadlineDate)
        assertTrue(card.deadlineMinute == 600)
        assertEquals(IsoDate.parse("2026-03-05"), todo.deadlineDate)
    }

    @Test
    fun `merge keeps the last occurrence after the series ends`() {
        // 次数档 3 期走完：保留最后一期 3/16——已完成则进"已完成"，未完成则仍是一张待办卡
        val rule = RepeatRule(
            type = RepeatType.Weekly,
            endType = RepeatEndType.Count,
            endCount = 3,
        )
        val completed = listOf("2026-03-02", "2026-03-09", "2026-03-16")
            .map { IsoDate.parse(it) }
        val finished = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = rule,
            completedOccurrences = completed,
        )
        val shownFinished = expand(finished, "2026-03-20")
        assertEquals(listOf("2026-03-16"), shownFinished.map { it.dates.single().toString() })
        assertEquals(listOf("2026-03-16"), done(shownFinished))

        val unfinished = finished.copy(completedOccurrences = completed.dropLast(1))
        val shownUnfinished = expand(unfinished, "2026-03-20")
        assertEquals(listOf("2026-03-16"), active(shownUnfinished))
        assertTrue(done(shownUnfinished).isEmpty())
    }

    @Test
    fun `timeline keeps today's completed occurrence when merge rolls forward`() {
        // 每周锚 3/16、今天 3/16 完成后：列表卡前移到 3/23（合并口径「过去的就过去吧」）；
        // 时间轴口径额外补回 3/16 的完成态副本，勾选瞬间卡片原地划线置灰而不消失
        val todo = testTodo(
            dates = listOf(IsoDate.parse("2026-03-16")),
            repeat = mergedWeekly,
            completedOccurrences = listOf(IsoDate.parse("2026-03-16")),
        )
        assertEquals(listOf("2026-03-23"), active(expand(todo, "2026-03-16")))
        val timeline = expandTimeline(todo, "2026-03-16")
        assertEquals(listOf("2026-03-23"), active(timeline))
        assertEquals(listOf("2026-03-16"), done(timeline))
    }

    @Test
    fun `timeline keeps the completed occurrence of a viewed past day`() {
        // 回看 3/16（当天已完成后合并卡早滚到 3/23）：时间轴仍保留 3/16 的完成态
        val todo = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = mergedWeekly,
            completedOccurrences = listOf(IsoDate.parse("2026-03-16")),
        )
        assertEquals(listOf("2026-03-16"), done(expandTimeline(todo, "2026-03-16")))
        // 当天未完成的普通日期不受影响：仍只出滚动卡
        val fresh = testTodo(dates = listOf(IsoDate.parse("2026-03-02")), repeat = mergedWeekly)
        assertEquals(listOf("2026-03-16"), active(expandTimeline(fresh, "2026-03-16")))
    }

    @Test
    fun `timeline completed copy folds occurrence deadline`() {
        // 补回的完成态副本同样走「下一次重复开始前」折算：3/16 → 下一期 3/23 − 2 = 3/21
        val todo = testTodo(
            dates = listOf(IsoDate.parse("2026-03-09")),
            repeat = mergedWeekly,
            completedOccurrences = listOf(IsoDate.parse("2026-03-16")),
            deadlineDate = IsoDate.parse("2026-03-10"),
            deadlineMinute = 600,
            deadlineBeforeRepeat = true,
            deadlineBeforeDays = 2,
        )
        val card = expandTimeline(todo, "2026-03-16")
            .single { IsoDate.parse("2026-03-16") in it.dates }
        assertTrue(card.completed)
        assertEquals(IsoDate.parse("2026-03-21"), card.deadlineDate)
        assertEquals(600, card.deadlineMinute)
    }

    @Test
    fun `timeline does not duplicate split-mode completed occurrences`() {
        // 拆分口径已完成期本来就逐期成卡：3/16 只有一张完成态，不由时间轴口径重复补
        val todo = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = splitWeekly,
            completedOccurrences = listOf(IsoDate.parse("2026-03-16")),
        )
        val timeline = expandTimeline(todo, "2026-03-16")
        assertEquals(1, timeline.count { IsoDate.parse("2026-03-16") in it.dates })
    }

    @Test
    fun `before-next-repeat deadline folds per occurrence`() {
        // 每周（锚 3/2 周一）、开始前 2 天：每期截止 = 下一期 − 2 天（3/2→3/7、3/9→3/14、
        // 3/16→3/21），时刻沿用存储值；存储的原始截止只是载体，不被改动
        val todo = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = splitWeekly,
            deadlineDate = IsoDate.parse("2026-03-05"),
            deadlineMinute = 600,
            deadlineBeforeRepeat = true,
            deadlineBeforeDays = 2,
        )
        val shown = expand(todo, "2026-03-20")
        assertEquals(
            listOf("2026-03-07", "2026-03-14", "2026-03-21"),
            shown.map { it.deadlineDate!!.toString() },
        )
        assertTrue(shown.all { it.deadlineMinute == 600 })
        assertEquals(IsoDate.parse("2026-03-05"), todo.deadlineDate)
    }

    @Test
    fun `folded deadline never lands before its own occurrence`() {
        // 每周一+周五（锚 3/2）、开始前 4 天：周五期的下一期是下周一，3/9 − 4 = 3/5 早于
        // 期日 3/6 → 收拢到期日当天（周一期的 3/6 − 4 = 3/2 恰为期日，原样保留）
        val mondayFriday = RepeatRule(
            type = RepeatType.Custom,
            unit = RepeatUnit.Week,
            interval = 1,
            marks = setOf(1, 5),
            merge = false,
        )
        val todo = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = mondayFriday,
            deadlineDate = IsoDate.parse("2026-03-05"),
            deadlineMinute = 480,
            deadlineBeforeRepeat = true,
            deadlineBeforeDays = 4,
        )
        val shown = expand(todo, "2026-03-07")
        assertEquals(
            listOf("2026-03-02", "2026-03-06"),
            shown.map { it.deadlineDate!!.toString() },
        )
    }

    @Test
    fun `before-next-repeat off keeps stored deadline on every occurrence`() {
        // 开关关闭：各期副本原样携带存储的截止（现状行为）
        val todo = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = splitWeekly,
            deadlineDate = IsoDate.parse("2026-03-05"),
            deadlineMinute = 600,
        )
        val shown = expand(todo, "2026-03-20")
        assertTrue(shown.isNotEmpty())
        assertTrue(
            shown.all {
                it.deadlineDate == IsoDate.parse("2026-03-05") && it.deadlineMinute == 600
            },
        )
    }

    @Test
    fun `display overdue fills un-settled past occurrences`() {
        // 仓库尚未结算：锚 3/2 每周、今天 3/20 → 3/2、3/9、3/16 都算逾期（今天不算）
        val todo = testTodo(dates = listOf(IsoDate.parse("2026-03-02")), repeat = splitWeekly)
        assertEquals(
            listOf("2026-03-02", "2026-03-09", "2026-03-16"),
            todo.displayOverdueOccurrences(IsoDate.parse("2026-03-20")).map { it.toString() },
        )
    }

    @Test
    fun `display overdue excludes completed and keeps settled dates`() {
        val todo = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = splitWeekly,
            completedOccurrences = listOf(IsoDate.parse("2026-03-02")),
        ).copy(overdueOccurrences = listOf(IsoDate.parse("2026-03-09")))
        assertEquals(
            listOf("2026-03-09", "2026-03-16"),
            todo.displayOverdueOccurrences(IsoDate.parse("2026-03-20")).map { it.toString() },
        )
    }

    @Test
    fun `display overdue keeps late-completed settled occurrences`() {
        // 3/9 迟到补完成：同时留在完成集合与逾期集合（仓库口径），展示仍计逾期
        val todo = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = splitWeekly,
            completedOccurrences = listOf(
                IsoDate.parse("2026-03-02"),
                IsoDate.parse("2026-03-09"),
            ),
        ).copy(overdueOccurrences = listOf(IsoDate.parse("2026-03-09")))
        assertEquals(
            listOf("2026-03-09", "2026-03-16"),
            todo.displayOverdueOccurrences(IsoDate.parse("2026-03-20")).map { it.toString() },
        )
    }

    @Test
    fun `display overdue falls back to stored set for non-repeat or before anchor`() {
        val stored = listOf(IsoDate.parse("2026-03-09"))
        val plain = testTodo().copy(overdueOccurrences = stored)
        val repeatTodo = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = splitWeekly,
        ).copy(overdueOccurrences = stored)
        assertEquals(stored, plain.displayOverdueOccurrences(IsoDate.parse("2026-03-20")))
        // 今天 <= 锚日：还没有已经过去的分期
        assertEquals(stored, repeatTodo.displayOverdueOccurrences(IsoDate.parse("2026-03-02")))
    }

    // ---------- 打卡模式（按完成次数收口） ----------

    /** 打卡规则：每日、目标 3 次（期数不封顶，收口看完成次数） */
    private fun checkInRule(merge: Boolean = true) = RepeatRule(
        type = RepeatType.Daily,
        endType = RepeatEndType.Count,
        endCount = 3,
        merge = merge,
        checkIn = true,
    )

    @Test
    fun `check-in keeps one current card and never emits missed periods`() {
        // 锚 3/2 每日、今天 3/20：漏掉的 18 期既不成拖欠卡、也不成已完成卡，只出今天这一张
        val todo = testTodo(dates = listOf(IsoDate.parse("2026-03-02")), repeat = checkInRule())
        val shown = expand(todo, "2026-03-20")
        assertEquals(listOf("2026-03-20"), active(shown))
        assertTrue(done(shown).isEmpty())
    }

    @Test
    fun `check-in ignores the period cap and advances with completions`() {
        // 目标 3 次：完成 3/18、3/19（超过 3 期窗口）后卡片仍在今天 3/20，不被期数截断
        val todo = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = checkInRule(),
            completedOccurrences = listOf(IsoDate.parse("2026-03-18"), IsoDate.parse("2026-03-19")),
        )
        assertEquals(listOf("2026-03-20"), active(expand(todo, "2026-03-20")))
    }

    @Test
    fun `check-in ends on the last completed occurrence at the target`() {
        // 完成次数达 3：停在最后一期（3/20）并标完成（进已完成组），不再出未来期
        val todo = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = checkInRule(),
            completedOccurrences = listOf(
                IsoDate.parse("2026-03-18"),
                IsoDate.parse("2026-03-19"),
                IsoDate.parse("2026-03-20"),
            ),
        )
        val shown = expand(todo, "2026-03-20")
        assertEquals(listOf("2026-03-20"), shown.map { it.dates.single().toString() })
        assertEquals(listOf("2026-03-20"), done(shown))
    }

    @Test
    fun `check-in stays a single card with merge off`() {
        // 打卡优先于合并开关：关闭合并（拆分口径）也只出一条当前期卡，不逐期拆分
        val todo = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = checkInRule(merge = false),
            completedOccurrences = listOf(IsoDate.parse("2026-03-19")),
        )
        val shown = expand(todo, "2026-03-20")
        assertEquals(listOf("2026-03-20"), shown.map { it.dates.single().toString() })
        assertTrue(shown.none { it.completed })
    }

    @Test
    fun `timeline keeps today's check-in completion before the target`() {
        // 打卡目标 3 次、已勾 3/19 + 3/20（未达标）：列表卡前移到 3/21；
        // 时间轴补回 3/20 的完成态卡
        val todo = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = checkInRule(),
            completedOccurrences = listOf(IsoDate.parse("2026-03-19"), IsoDate.parse("2026-03-20")),
        )
        assertEquals(listOf("2026-03-21"), active(expand(todo, "2026-03-20")))
        val timeline = expandTimeline(todo, "2026-03-20")
        assertEquals(listOf("2026-03-21"), active(timeline))
        assertEquals(listOf("2026-03-20"), done(timeline))
    }

    @Test
    fun `timeline does not duplicate check-in card at the target`() {
        // 达标后列表口径已停在完成态的最后一天 3/20：时间轴口径不再补第二条
        val todo = testTodo(
            dates = listOf(IsoDate.parse("2026-03-02")),
            repeat = checkInRule(),
            completedOccurrences = listOf(
                IsoDate.parse("2026-03-18"),
                IsoDate.parse("2026-03-19"),
                IsoDate.parse("2026-03-20"),
            ),
        )
        val timeline = expandTimeline(todo, "2026-03-20")
        assertEquals(1, timeline.count { IsoDate.parse("2026-03-20") in it.dates })
        assertEquals(listOf("2026-03-20"), done(timeline))
    }

    @Test
    fun `check-in never accrues overdue occurrences`() {
        // 漏期不记逾期：落账集合原样返回，不做实时补算
        val todo = testTodo(dates = listOf(IsoDate.parse("2026-03-02")), repeat = checkInRule())
        assertTrue(todo.displayOverdueOccurrences(IsoDate.parse("2026-03-20")).isEmpty())
        val settled = todo.copy(overdueOccurrences = listOf(IsoDate.parse("2026-03-05")))
        assertEquals(
            listOf("2026-03-05"),
            settled.displayOverdueOccurrences(IsoDate.parse("2026-03-20")).map { it.toString() },
        )
    }

    @Test
    fun `check-in percent is completions over the target`() {
        val base = testTodo(dates = listOf(IsoDate.parse("2026-03-02")), repeat = checkInRule())
        assertEquals(0, base.checkInPercent())
        assertEquals(
            67,
            base.copy(
                completedOccurrences = listOf(IsoDate.parse("2026-03-18"), IsoDate.parse("2026-03-19")),
            ).checkInPercent(),
        )
        assertEquals(
            100,
            base.copy(
                completedOccurrences = listOf(
                    IsoDate.parse("2026-03-18"),
                    IsoDate.parse("2026-03-19"),
                    IsoDate.parse("2026-03-20"),
                ),
            ).checkInPercent(),
        )
    }

    @Test
    fun `check-in percent caps at 100 and is absent outside check-in`() {
        val base = testTodo(dates = listOf(IsoDate.parse("2026-03-02")), repeat = checkInRule())
        // 超额完成（历史遗留的更多完成记录）夹到 100
        assertEquals(
            100,
            base.copy(
                completedOccurrences = (18..23).map { IsoDate.parse("2026-03-$it") },
            ).checkInPercent(),
        )
        // 非打卡 / 无重复 / 次数缺失：不显示进度
        assertNull(base.copy(repeat = checkInRule().copy(checkIn = false)).checkInPercent())
        assertNull(base.copy(repeat = checkInRule().copy(endCount = null)).checkInPercent())
        assertNull(testTodo().checkInPercent())
    }

    @Test
    fun `check-in backfill spans the todo start through today`() {
        // 待办 3/10 开始、今天 3/20、展示整月：补勾位 = 3/10..3/20 连续整段（不按分期规则取）
        val todo = testTodo(dates = listOf(IsoDate.parse("2026-03-10")), repeat = checkInRule())
        val backfill = todo.checkInBackfillDates(
            fromInclusive = IsoDate.parse("2026-03-01"),
            toInclusive = IsoDate.parse("2026-03-31"),
            today = IsoDate.parse("2026-03-20"),
        )
        assertEquals(11, backfill.size)
        assertEquals(IsoDate.parse("2026-03-10"), backfill.minByOrNull { it.epochDay })
        assertEquals(IsoDate.parse("2026-03-20"), backfill.maxByOrNull { it.epochDay })

        // 窗口只覆盖月初：起点仍收在待办起始日
        assertEquals(
            listOf("2026-03-10", "2026-03-11", "2026-03-12"),
            todo.checkInBackfillDates(
                fromInclusive = IsoDate.parse("2026-03-01"),
                toInclusive = IsoDate.parse("2026-03-12"),
                today = IsoDate.parse("2026-03-20"),
            ).map { it.toString() },
        )

        // 待办起始日晚于今天（还没开始）：没有补勾位
        assertTrue(
            todo.copy(dates = listOf(IsoDate.parse("2026-03-25"))).checkInBackfillDates(
                fromInclusive = IsoDate.parse("2026-03-01"),
                toInclusive = IsoDate.parse("2026-03-31"),
                today = IsoDate.parse("2026-03-20"),
            ).isEmpty(),
        )

        // 非打卡模式无补勾位
        assertTrue(
            todo.copy(repeat = checkInRule().copy(checkIn = false)).checkInBackfillDates(
                fromInclusive = IsoDate.parse("2026-03-01"),
                toInclusive = IsoDate.parse("2026-03-31"),
                today = IsoDate.parse("2026-03-20"),
            ).isEmpty(),
        )
    }
}
