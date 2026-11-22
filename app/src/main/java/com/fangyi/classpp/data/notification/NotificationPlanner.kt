package com.fangyi.classpp.data.notification

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.model.occurrencesBetween
import com.fangyi.classpp.data.occurrencesOn
import com.fangyi.classpp.data.withOccurrenceDeadline
import com.fangyi.classpp.ui.settings.NotificationSettings
import java.util.GregorianCalendar
import java.util.TimeZone

/** 通知类别：决定渠道（课程/待办）与点击落地页 */
enum class NotificationKind { Course, TodoDeadline, TodoStart, TodoEnd }

/**
 * 一条已排期的提醒。[key] 是取消/去重/requestCode 的唯一来源，必须对同一提醒稳定
 * （同一门课同一天、同一条待办同一天同一种时刻），不同提醒之间互不相同。
 *
 * [eventAtMillis] = 提醒的事件时刻（上课开始 / 待办截止·开始·结束），与 [triggerAtMillis]
 * 的差即提前量。通知方式为 Live Updates 时，它是持续通知的移除时刻（见
 * [NotificationAlarmReceiver] 的 ACTION_CANCEL）。
 *
 * [chipText] = 实况状态栏 chip 上的文字（[Notification.Builder.setShortCriticalText]），
 * 口径由 [NotificationSettings.liveInfo] 决定且仅对课程提醒生效（课程名 / 上课地点，
 * 地点为空回退课程名）；待办提醒恒为待办名。
 */
data class PlannedNotification(
    val key: String,
    val triggerAtMillis: Long,
    val eventAtMillis: Long,
    val kind: NotificationKind,
    val title: String,
    val text: String,
    val chipText: String,
)

/**
 * 通知文案模板：由调用方从 Context 装配（[from]）后传给 [NotificationPlanner.plan]，
 * planner 因此保持无 Android 依赖、可 JVM 单测。带一个 %1 占位的模板用 String.format 填充。
 */
data class NotificationTexts(
    val courseText: String,
    val courseTextNoLocation: String,
    val todoDeadlineText: String,
    val todoStartText: String,
    val todoEndText: String,
    /** 事件不在今天时拼在时刻前的日期前缀（zh「M月d日 」/ en「M/d 」） */
    val datePrefix: String,
) {
    companion object {
        fun from(context: android.content.Context): NotificationTexts = NotificationTexts(
            courseText = context.getString(com.fangyi.classpp.R.string.notification_course_text),
            courseTextNoLocation = context.getString(com.fangyi.classpp.R.string.notification_course_text_no_location),
            todoDeadlineText = context.getString(com.fangyi.classpp.R.string.notification_todo_deadline_text),
            todoStartText = context.getString(com.fangyi.classpp.R.string.notification_todo_start_text),
            todoEndText = context.getString(com.fangyi.classpp.R.string.notification_todo_end_text),
            datePrefix = context.getString(com.fangyi.classpp.R.string.notification_date_prefix),
        )
    }
}

/**
 * 提醒排期（纯函数）：按通知设置与当前数据，算出滚动窗口内所有需要触发的提醒。
 *
 * 窗口为「今天」起 [WINDOW_DAYS] 天、每类上限 [CATEGORY_CAP] 条（按触发时刻就近截断）——
 * 不一次排满整学期：闹钟数量有界，窗口由到点续排/进 app/开机/改时间等时机滚动前移
 * （见 [com.fangyi.classpp.data.notification.NotificationScheduler.reschedule] 的调用方清单）。
 *
 * 资格与语义：
 * - 课程：窗口内某日历日「第 N 周（1..totalWeeks）且课程周规律含 N、星期匹配」才提醒；
 *   开学前（周号 ≤ 0）、放假周（周规律不含）自然跳过，与课表网格同口径；
 * - 待办：完成的排除；[NotificationSettings.todoType] 单选口径下——截止提醒看
 *   `deadlineDate + deadlineMinute`（validator 保证成对、必带精确时刻）；开始/结束提醒
 *   仅 [TodoTimeKind.Period] 有精确分钟，**AllDay/None 无时刻语义、不参与**（截止提醒不受影响）；
 *   重复待办按期次序列逐期排点（已完成的期次跳过、截止随重复按期折算，见 [planTodos]）；
 * - 提前量直接从事件时刻前移，可为负偏移（跨天）：0:30 的课提前 60 分钟 = 前一天 23:30 触发；
 * - 触发时刻已过（≤ now）的不再补发。
 */
object NotificationPlanner {

    /** 排期窗口天数：一次排这么多天，之后靠续排前移 */
    const val WINDOW_DAYS = 14

    /** 单类上限：闹钟数量有界（PendingIntent 数量与系统闹钟列表都不会失控） */
    const val CATEGORY_CAP = 64

    fun plan(
        schedule: Schedule?,
        todos: List<Todo>,
        settings: NotificationSettings,
        texts: NotificationTexts,
        nowMillis: Long,
        today: IsoDate = IsoDate.today(nowMillis),
    ): List<PlannedNotification> {
        if (!settings.enabled) return emptyList()
        val planned = buildList {
            if (settings.courseEnabled && schedule != null) {
                addAll(planCourses(schedule, settings, texts, nowMillis, today))
            }
            if (settings.todoEnabled) {
                addAll(planTodos(todos, settings.todoType, settings.todoLeadMinutes, texts, nowMillis, today))
            }
        }
        return planned
    }

    /**
     * 课程提醒：窗口内每天取「周规律 + 单次调课」后的实际课次，触发点 = 上课开始 − 提前量。
     *
     * 调课口径统一走 [occurrencesOn]：源课次在源日期不再排提醒，调课卡在目标日期按目标节次
     * 排提醒。周末不按 daysPerWeek 跳过：那只是网格的显示列数（5 天模式下周末列不画），
     * 周六/周日真实排了课仍要提醒（与日程页上课提醒卡同口径）。
     */
    private fun planCourses(
        schedule: Schedule,
        settings: NotificationSettings,
        texts: NotificationTexts,
        nowMillis: Long,
        today: IsoDate,
    ): List<PlannedNotification> {
        val leadMinutes = settings.courseLeadMinutes
        val result = ArrayList<PlannedNotification>()
        for (dayOffset in 0 until WINDOW_DAYS) {
            val date = today + dayOffset
            for (occurrence in schedule.occurrencesOn(date)) {
                val course = occurrence.course
                val startSlot = schedule.slots.getOrNull(occurrence.startSlot - 1) ?: continue
                // 跨节课的结束时间取末节，与课程详情 / 日程页同一口径
                val endSlot = schedule.slots.getOrNull(occurrence.endSlot - 1) ?: continue
                val startMinute = TimeText.parseMinutes(startSlot.startTime) ?: continue
                val endMinute = TimeText.parseMinutes(endSlot.endTime) ?: continue
                val eventAt = localMillis(date, startMinute)
                val trigger = localMillis(date, startMinute - leadMinutes)
                if (trigger <= nowMillis) continue
                val timeRange = TimeText.format(startMinute) + "-" + TimeText.format(endMinute)
                val text = if (course.location.isBlank()) {
                    texts.courseTextNoLocation.format(timeRange)
                } else {
                    texts.courseText.format(timeRange, course.location)
                }
                // chip 文字口径：上课地点（地点为空回退课程名）/ 课程名
                val chipText = if (settings.liveInfo == NotificationSettings.LIVE_INFO_LOCATION &&
                    course.location.isNotBlank()
                ) {
                    course.location
                } else {
                    course.name
                }
                // 调课卡带调课 id：同一天同一门课可能既有原课次又有调课卡（搬到同一天），
                // key 必须唯一，否则两条闹钟会互相覆盖
                val key = occurrence.adjustment?.let { adjustment ->
                    "C:${course.id}:${date.epochDay}:A:${adjustment.id}"
                } ?: "C:${course.id}:${date.epochDay}"
                result += PlannedNotification(
                    key = key,
                    triggerAtMillis = trigger,
                    eventAtMillis = eventAt,
                    kind = NotificationKind.Course,
                    title = course.name,
                    text = text,
                    chipText = chipText,
                )
            }
        }
        return result.sortedBy { it.triggerAtMillis }.take(CATEGORY_CAP)
    }

    /**
     * 待办提醒：按 [NotificationSettings.todoType] 口径产出截止/开始/结束三类触发点。
     *
     * 非重复待办按存储字段排一次。重复待办按「期次序列」逐期排点：窗口内每个应完成日期
     * 都是独立触发点（`occurrencesBetween` 已消化日期/次数档与打卡模式；窗口从今天起，
     * 过去期次不补发），已完成的期次整期跳过——该期的开始/结束/折算截止都不再触发。
     * 「截止随重复」经 [withOccurrenceDeadline] 按期折算（不早于期日）、截止提醒 key 用
     * 期次日期（多标记等规则折算后两期可能收拢到同一天，用截止日做 key 会互相覆盖）；
     * 固定截止是整条系列共享的一次性事件，按存储日期只排一次、不随期次重复。
     */
    private fun planTodos(
        todos: List<Todo>,
        todoType: Int,
        leadMinutes: Int,
        texts: NotificationTexts,
        nowMillis: Long,
        today: IsoDate,
    ): List<PlannedNotification> {
        val result = ArrayList<PlannedNotification>()
        // 窗口终点（不含）：今天起 WINDOW_DAYS 天，重复期次只排窗口内的触发点
        val windowEnd = today + WINDOW_DAYS
        for (todo in todos) {
            if (todo.completed) continue
            if (todo.repeat.type == RepeatType.None) {
                addTodoReminders(todo, todo.dates, todo.deadlineDate, todo.deadlineMinute, todo.deadlineDate,
                    todoType, leadMinutes, texts, nowMillis, today, result)
                continue
            }
            // 重复待办：锚日期缺失的异常形态展不出期次（TodoValidator 已拦写入），防御跳过
            val anchor = todo.dates.minByOrNull { it.epochDay } ?: continue
            val completed = todo.completedOccurrences.toSet()
            // 期次序列：不经展示口径（expandRepeatOccurrences 依赖 today 并按 merge/拆分/打卡
            // 收口，只出一张卡或逐期成卡，不适合「窗口内全部触发点」）
            val occurrences = todo.repeat.occurrencesBetween(anchor, today, windowEnd)
                .filter { it !in completed }
            if (occurrences.isEmpty()) continue
            if (todo.deadlineBeforeRepeat) {
                for (occurrence in occurrences) {
                    val view = todo.withOccurrenceDeadline(anchor, occurrence)
                    addTodoReminders(todo, listOf(occurrence), view.deadlineDate, view.deadlineMinute,
                        occurrence, todoType, leadMinutes, texts, nowMillis, today, result)
                }
            } else {
                addTodoReminders(todo, occurrences, todo.deadlineDate, todo.deadlineMinute, todo.deadlineDate,
                    todoType, leadMinutes, texts, nowMillis, today, result)
            }
        }
        return result.sortedBy { it.triggerAtMillis }.take(CATEGORY_CAP)
    }

    /**
     * 一组触发日期上的待办提醒：截止提醒至多一条（[deadlineDate]/[deadlineMinute] 成对
     * 才成立，[deadlineKey] 是 key 的日期段），开始/结束提醒按 [dates] 每个日期各一条。
     */
    private fun addTodoReminders(
        todo: Todo,
        dates: List<IsoDate>,
        deadlineDate: IsoDate?,
        deadlineMinute: Int?,
        deadlineKey: IsoDate?,
        todoType: Int,
        leadMinutes: Int,
        texts: NotificationTexts,
        nowMillis: Long,
        today: IsoDate,
        result: MutableList<PlannedNotification>,
    ) {
        if (todoType == NotificationSettings.TODO_TYPE_ALL ||
            todoType == NotificationSettings.TODO_TYPE_DEADLINE
        ) {
            if (deadlineDate != null && deadlineMinute != null && deadlineKey != null) {
                val eventAt = localMillis(deadlineDate, deadlineMinute)
                val trigger = localMillis(deadlineDate, deadlineMinute - leadMinutes)
                if (trigger > nowMillis) {
                    result += PlannedNotification(
                        key = "T:${todo.id}:${deadlineKey.epochDay}:D",
                        triggerAtMillis = trigger,
                        eventAtMillis = eventAt,
                        kind = NotificationKind.TodoDeadline,
                        title = todo.name,
                        text = texts.todoDeadlineText.format(timeText(deadlineDate, deadlineMinute, texts, today)),
                        chipText = todo.name,
                    )
                }
            }
        }
        // 开始/结束提醒：仅时段（Period）待办有精确分钟；AllDay/None 无时刻语义不参与。
        // dates 可多天共用同一套时刻，每个日期都是独立触发点
        val startMinute = if (todo.timeKind == TodoTimeKind.Period) todo.startMinute else null
        val endMinute = if (todo.timeKind == TodoTimeKind.Period) todo.endMinute else null
        for (date in dates) {
            if (todoType == NotificationSettings.TODO_TYPE_ALL ||
                todoType == NotificationSettings.TODO_TYPE_START
            ) {
                if (startMinute != null) {
                    val eventAt = localMillis(date, startMinute)
                    val trigger = localMillis(date, startMinute - leadMinutes)
                    if (trigger > nowMillis) {
                        result += PlannedNotification(
                            key = "T:${todo.id}:${date.epochDay}:S",
                            triggerAtMillis = trigger,
                            eventAtMillis = eventAt,
                            kind = NotificationKind.TodoStart,
                            title = todo.name,
                            text = texts.todoStartText.format(timeText(date, startMinute, texts, today)),
                            chipText = todo.name,
                        )
                    }
                }
            }
            if (todoType == NotificationSettings.TODO_TYPE_ALL ||
                todoType == NotificationSettings.TODO_TYPE_END
            ) {
                if (endMinute != null) {
                    val eventAt = localMillis(date, endMinute)
                    val trigger = localMillis(date, endMinute - leadMinutes)
                    if (trigger > nowMillis) {
                        result += PlannedNotification(
                            key = "T:${todo.id}:${date.epochDay}:E",
                            triggerAtMillis = trigger,
                            eventAtMillis = eventAt,
                            kind = NotificationKind.TodoEnd,
                            title = todo.name,
                            text = texts.todoEndText.format(timeText(date, endMinute, texts, today)),
                            chipText = todo.name,
                        )
                    }
                }
            }
        }
    }

    /** 待办正文里的时刻：今天只写时刻，非今天补日期前缀（跨天的截止一眼能认出是哪天） */
    private fun timeText(date: IsoDate, minute: Int, texts: NotificationTexts, today: IsoDate): String {
        val time = TimeText.format(minute)
        if (date == today) return time
        val (month, day) = calendarMonthDay(date)
        return texts.datePrefix.format(month, day) + time
    }

    /**
     * 日历日期 + 当天分钟数（可越界：lead 前移后可 < 0 或 > 1439）→ 本地时区 epoch millis。
     * 年月日按 UTC 解释（与 [IsoDate.of] 同一日历语义），时刻用设备本地时区拼装；
     * GregorianCalendar 宽松模式自动滚动越界字段，即跨天语义。
     */
    internal fun localMillis(date: IsoDate, minuteOfDay: Int): Long {
        val utc = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = date.epochDay * MILLIS_PER_DAY
        }
        val local = GregorianCalendar().apply {
            clear()
            set(
                utc.get(GregorianCalendar.YEAR),
                utc.get(GregorianCalendar.MONTH),
                utc.get(GregorianCalendar.DAY_OF_MONTH),
                minuteOfDay / 60,
                minuteOfDay % 60,
                0,
            )
        }
        return local.timeInMillis
    }

    /** 日历日期 → 本地无涉的（月, 日）；年月日提取与 [IsoDate.toString] 同一 UTC 解释 */
    private fun calendarMonthDay(date: IsoDate): Pair<Int, Int> {
        val utc = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = date.epochDay * MILLIS_PER_DAY
        }
        return utc.get(GregorianCalendar.MONTH) + 1 to utc.get(GregorianCalendar.DAY_OF_MONTH)
    }

    private const val MILLIS_PER_DAY = 86_400_000L
}
