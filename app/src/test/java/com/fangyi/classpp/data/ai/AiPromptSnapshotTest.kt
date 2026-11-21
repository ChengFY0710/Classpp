package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.model.CourseColor
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Parity
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoStep
import com.fangyi.classpp.data.model.TodoUrgency
import com.fangyi.classpp.data.model.WeekPattern
import com.fangyi.classpp.data.model.WeekSegment
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * system prompt 快照回归（设计稿 §13 P0 验收）。
 *
 * 快照逐字节锁定 fallback 模式的当前基线：有意变更提示词文案时同步重生成并注明原因。
 * 版本沿革见 git 历史；v4 = 课程标签独立成行注入（与用户标签分命名空间），
 * v5 = 单时间点默认作截止、时间段默认作待办时段。
 * 行尾统一按 LF 比较（仓库 `core.autocrlf` 会在检出时改写资源文件的行尾）。
 */
class AiPromptSnapshotTest {

    private val now = IsoDate.of(2026, 11, 18)

    private fun course(
        id: String,
        name: String,
        dayOfWeek: Int,
        startSlot: Int,
        span: Int = 1,
        weeks: WeekPattern,
        teacher: String = "",
        location: String = "",
    ) = CourseEntry(
        id = id,
        name = name,
        teacher = teacher,
        location = location,
        dayOfWeek = dayOfWeek,
        startSlot = startSlot,
        span = span,
        weeks = weeks,
        color = CourseColor.Blue,
    )

    private fun todo(
        id: String,
        name: String,
        dates: List<IsoDate> = emptyList(),
        repeat: RepeatRule = RepeatRule(),
        completedOccurrences: List<IsoDate> = emptyList(),
        completed: Boolean = false,
        completedAtMillis: Long? = null,
        deadlineDate: IsoDate? = null,
        deadlineMinute: Int? = null,
        urgency: TodoUrgency = TodoUrgency.None,
        group: String? = null,
        tags: List<String> = emptyList(),
        steps: List<String> = emptyList(),
        archivedAtMillis: Long? = null,
        deletedAtMillis: Long? = null,
    ) = Todo(
        id = id,
        name = name,
        dates = dates,
        repeat = repeat,
        completedOccurrences = completedOccurrences,
        completed = completed,
        completedAtMillis = completedAtMillis,
        deadlineDate = deadlineDate,
        deadlineMinute = deadlineMinute,
        urgency = urgency,
        group = group,
        tags = tags,
        steps = steps.map { TodoStep(id = "step-$id-$it", title = it) },
        archivedAtMillis = archivedAtMillis,
        deletedAtMillis = deletedAtMillis,
    )

    @Test
    fun `system prompt matches pre-refactor snapshot`() {
        val schedule = Schedule(
            id = "s1",
            name = "2026 秋",
            termStart = IsoDate.of(2026, 9, 7),
            termEnd = IsoDate.of(2027, 1, 24),
            courses = listOf(
                course(
                    id = "c1",
                    name = "高等数学",
                    dayOfWeek = 1,
                    startSlot = 3,
                    span = 2,
                    weeks = WeekPattern(listOf(WeekSegment(1, 16))),
                    teacher = "张三",
                    location = "教学楼A-301",
                ),
                course(
                    id = "c2",
                    name = "大学英语",
                    dayOfWeek = 3,
                    startSlot = 2,
                    weeks = WeekPattern(
                        listOf(
                            WeekSegment(1, 8),
                            WeekSegment(10, 16, Parity.EVEN),
                        ),
                    ),
                    location = "南强楼-201",
                ),
                course(
                    id = "c3",
                    name = "体育",
                    dayOfWeek = 5,
                    startSlot = 4,
                    weeks = WeekPattern(listOf(WeekSegment(1, 16))),
                ),
            ),
        )

        val todos = listOf(
            todo(
                id = "t-active-1",
                name = "交实验报告",
                dates = listOf(IsoDate.of(2026, 11, 18)),
                repeat = RepeatRule(type = RepeatType.Weekly),
                completedOccurrences = listOf(IsoDate.of(2026, 11, 11)),
                deadlineDate = IsoDate.of(2026, 11, 18),
                deadlineMinute = 18 * 60,
                urgency = TodoUrgency.High,
                group = "学习",
                tags = listOf("物理"),
                steps = listOf("查资料", "写正文"),
            ),
            todo(
                id = "t-active-2",
                name = "背单词",
                urgency = TodoUrgency.Low,
                tags = listOf("英语", "期末"),
            ),
            todo(
                id = "t-done",
                name = "已交作业",
                completed = true,
                completedAtMillis = 2L,
            ),
        )
        val archived = listOf(
            todo(
                id = "t-archived",
                name = "旧任务",
                deadlineDate = IsoDate.of(2026, 11, 10),
                group = "生活",
                archivedAtMillis = 1L,
            ),
        )
        val deleted = listOf(
            todo(id = "t-deleted", name = "误删任务", deletedAtMillis = 2L),
        )

        val prompt = AiPromptBuilder.build(
            now = now,
            schedule = schedule,
            currentWeek = 12,
            todos = todos,
            groups = listOf("学习", "生活"),
            tags = listOf("物理", "英语", "期末"),
            archivedTodos = archived,
            deletedTodos = deleted,
            // 与装配口径一致：激活课表的课程名去重
            courseTags = schedule.courses.distinctBy { it.name }.map { it.name },
        )

        val expected = requireNotNull(javaClass.getResourceAsStream("/ai/prompt-snapshot-v5.txt")) {
            "missing snapshot resource: /ai/prompt-snapshot-v5.txt"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

        assertEquals(normalizeLineEndings(expected), normalizeLineEndings(prompt))
    }

    private fun normalizeLineEndings(text: String): String = text.replace("\r\n", "\n")
}
