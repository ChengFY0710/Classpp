package com.fangyi.classpp.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.schedule.CourseColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间轴任务卡布局求解（[solveDayTaskPlacements]）：贴背/间距的接触角判定、时间重叠
 * 的均分与分列、紧凑形态切换与高度兜底、时间→纵坐标映射（DayTaskCard.kt）。
 */
class DayTaskPlacementTest {

    private val hourSpacing = 50.dp
    private val regionWidth = 328.dp

    private fun course(start: Int, end: Int) = DayTask.Course(
        name = "课程",
        teacher = null,
        location = null,
        note = null,
        color = CourseColor.Blue,
        startMinute = start,
        endMinute = end,
    )

    private fun todo(start: Int, end: Int) = DayTask.Todo(
        id = "t",
        name = "待办",
        completed = false,
        deadlineText = null,
        deadlineColor = Color.Black,
        onToggle = null,
        startMinute = start,
        endMinute = end,
    )

    @Test
    fun backToBackTasksContactAndShareFullWidth() {
        // 8:00-9:00 课 + 9:00-10:00 待办贴背（0 间距 < 6dp）：接触角收敛（贴背分属不同
        // 重叠链，靠全局纵向配对命中），不重叠各占全宽
        val placements = listOf(course(480, 540), todo(540, 600))
            .solveDayTaskPlacements(regionWidth, hourSpacing)
        assertEquals(2, placements.size)
        assertTrue(placements[0].bottomStartContact)
        assertTrue(placements[0].bottomEndContact)
        assertFalse(placements[0].topStartContact)
        assertFalse(placements[0].topEndContact)
        assertTrue(placements[1].topStartContact)
        assertTrue(placements[1].topEndContact)
        assertFalse(placements[1].bottomStartContact)
        assertFalse(placements[1].bottomEndContact)
        assertEquals(regionWidth.value, placements[0].width.value, 1e-3f)
        assertEquals(regionWidth.value, placements[1].width.value, 1e-3f)
    }

    @Test
    fun gapOverThresholdKeepsPlainCorners() {
        // 10 分钟间距 = 50dp × 10/60 ≈ 8.33dp > 6dp：不收敛
        val placements = listOf(course(480, 540), todo(550, 600))
            .solveDayTaskPlacements(regionWidth, hourSpacing)
        assertFalse(placements[0].bottomStartContact)
        assertFalse(placements[0].bottomEndContact)
        assertFalse(placements[1].topStartContact)
        assertFalse(placements[1].topEndContact)
    }

    @Test
    fun gapUnderThresholdContacts() {
        // 5 分钟间距 = 50dp × 5/60 ≈ 4.17dp < 6dp：收敛
        val placements = listOf(course(480, 540), todo(545, 600))
            .solveDayTaskPlacements(regionWidth, hourSpacing)
        assertTrue(placements[0].bottomStartContact)
        assertTrue(placements[0].bottomEndContact)
        assertTrue(placements[1].topStartContact)
        assertTrue(placements[1].topEndContact)
    }

    @Test
    fun overlappingTasksSplitRegionEvenlyWithColumnGap() {
        // 8:00-10:00 课 + 9:00-11:00 待办重叠：均分区域宽（各半、列间 2dp）、贴邻角收敛
        val placements = listOf(course(480, 600), todo(540, 660))
            .solveDayTaskPlacements(regionWidth, hourSpacing)
        val half = ((regionWidth - DayTaskColumnGap) / 2).value
        assertEquals(half, placements[0].width.value, 1e-3f)
        assertEquals(half, placements[1].width.value, 1e-3f)
        assertEquals(0f, placements[0].offsetX.value, 1e-3f)
        assertEquals((half + DayTaskColumnGap.value), placements[1].offsetX.value, 1e-3f)
        // 左卡收末侧两角、右卡收起侧两角
        assertTrue(placements[0].topEndContact)
        assertTrue(placements[0].bottomEndContact)
        assertFalse(placements[0].topStartContact)
        assertFalse(placements[0].bottomStartContact)
        assertTrue(placements[1].topStartContact)
        assertTrue(placements[1].bottomStartContact)
        assertFalse(placements[1].topEndContact)
        assertFalse(placements[1].bottomEndContact)
    }

    @Test
    fun overlappingCourseTakesLeftColumnOverTodo() {
        // 待办 16:10-19:00 先开始，课程 16:40-18:20 与之重叠：列序课程优先 →
        // 课程在左列、待办在右列（placements 保持输入顺序，[0] = 待办）
        val placements = listOf(todo(970, 1140), course(1000, 1100))
            .solveDayTaskPlacements(regionWidth, hourSpacing)
        val half = ((regionWidth - DayTaskColumnGap) / 2).value
        assertEquals(half + DayTaskColumnGap.value, placements[0].offsetX.value, 1e-3f)
        assertEquals(0f, placements[1].offsetX.value, 1e-3f)
        assertEquals(half, placements[0].width.value, 1e-3f)
        assertEquals(half, placements[1].width.value, 1e-3f)
        // 贴邻角依旧成立：课程收末侧、待办收起侧
        assertTrue(placements[0].topStartContact)
        assertTrue(placements[1].topEndContact)
    }

    @Test
    fun chainedOverlapReusesColumnForNonOverlappingPair() {
        // A(8:00-9:00) B(8:30-11:00) C(10:00-11:00)：B 与 A、C 都重叠而 A/C 不重叠
        // → 两列：A、C 复用列 0，B 独占列 1，三者等宽
        val placements = listOf(course(480, 540), course(510, 660), course(600, 660))
            .solveDayTaskPlacements(regionWidth, hourSpacing)
        val half = ((regionWidth - DayTaskColumnGap) / 2).value
        placements.forEach { assertEquals(half, it.width.value, 1e-3f) }
        assertEquals(0f, placements[0].offsetX.value, 1e-3f)
        assertEquals(half + DayTaskColumnGap.value, placements[1].offsetX.value, 1e-3f)
        assertEquals(0f, placements[2].offsetX.value, 1e-3f)
    }

    @Test
    fun halfWidthTodoSwitchesToNarrowButCourseStaysVertical() {
        // 课程 + 待办重叠各占半宽（163dp）：待办 < 180dp 切窄卡，课程 ≥ 100dp 保持竖排
        val placements = listOf(course(480, 600), todo(540, 660))
            .solveDayTaskPlacements(regionWidth, hourSpacing)
        val coursePlacement = placements.single { it.task is DayTask.Course }
        val todoPlacement = placements.single { it.task is DayTask.Todo }
        assertEquals(163f, todoPlacement.width.value, 1e-3f)
        assertTrue(todoPlacement.narrow)
        assertFalse(todoPlacement.compact)
        assertFalse(coursePlacement.narrow)
    }

    @Test
    fun quarterWidthCoursesSwitchToNarrow() {
        // 4 条同时段课程 → 列宽 (328 − 3×2) / 4 = 80.5dp < 100dp：全部窄卡
        val placements = List(4) { course(480, 540) }
            .solveDayTaskPlacements(regionWidth, hourSpacing)
        val columnWidth = ((regionWidth - DayTaskColumnGap * 3) / 4).value
        placements.forEach {
            assertEquals(columnWidth, it.width.value, 1e-3f)
            assertTrue(it.narrow)
            assertFalse(it.compact)
        }
    }

    @Test
    fun narrowTodoHeightUsesStackedContentMinimum() {
        // 20 分钟待办在半宽列（比例高度 ≈ 16.7dp）：窄卡内容（时间置顶 + 标题）撑到 60dp+
        val placements = listOf(course(480, 600), todo(540, 560))
            .solveDayTaskPlacements(regionWidth, hourSpacing)
        val narrowTodo = placements.single { it.task is DayTask.Todo }
        assertTrue(narrowTodo.narrow)
        assertTrue(narrowTodo.height > 40.dp)
    }

    @Test
    fun shortTasksTurnCompactWithHeightFloor() {
        // 60 分钟 = 50dp < 70dp 阈值 → 紧凑，高度按比例
        val hour = listOf(todo(780, 840)).solveDayTaskPlacements(regionWidth, hourSpacing)
        assertTrue(hour[0].compact)
        assertEquals(50f, hour[0].height.value, 1e-3f)

        // 20 分钟 ≈ 16.7dp → 紧凑且兜底到 40dp
        val tiny = listOf(todo(780, 800)).solveDayTaskPlacements(regionWidth, hourSpacing)
        assertTrue(tiny[0].compact)
        assertEquals(40f, tiny[0].height.value, 1e-3f)

        // 100 分钟 ≈ 83.3dp ≥ 70dp → 竖排
        val tall = listOf(course(480, 580)).solveDayTaskPlacements(regionWidth, hourSpacing)
        assertFalse(tall[0].compact)
        assertEquals(50f * 100 / 60, tall[0].height.value, 1e-3f)
    }

    @Test
    fun topAnchorsAtStartTickCenter() {
        // 8:00 → y = (8/60 + 0.5) × 50dp（DayTimeline 同一映射，默认锚点 0:00）
        val placements = listOf(course(480, 540)).solveDayTaskPlacements(regionWidth, hourSpacing)
        assertEquals(50f * (480 / 60f + 0.5f), placements[0].offsetY.value, 1e-3f)
    }

    @Test
    fun anchorShiftsVerticalOriginToAnchorTick() {
        // 锚点 7:00（420min）：8:00 顶边 = ((480 − 420)/60 + 0.5) × 50 = 75dp
        val placements = listOf(course(480, 540))
            .solveDayTaskPlacements(regionWidth, hourSpacing, anchorMinute = 420)
        assertEquals(50f * (60 / 60f + 0.5f), placements[0].offsetY.value, 1e-3f)
    }

    // ---------- 纵向间距：≥2dp、贴背 2dp、时间轴局部拉伸 ----------

    private fun richCourse(start: Int, end: Int) = DayTask.Course(
        name = "课程",
        teacher = "老师",
        location = "教室",
        note = "备注",
        color = CourseColor.Blue,
        startMinute = start,
        endMinute = end,
    )

    private fun DayTaskPlacement.bottom() = offsetY + height

    private fun xIntersects(a: DayTaskPlacement, b: DayTaskPlacement): Boolean =
        a.offsetX < b.offsetX + b.width && b.offsetX < a.offsetX + a.width

    @Test
    fun backToBackCardsKeepExactlyTwoDpGap() {
        // 8:00-9:00 + 9:00-10:00：贴背后下卡被下推 2dp，轴在 9:00 处局部拉伸
        val placements = listOf(course(480, 540), todo(540, 600))
            .solveDayTaskPlacements(regionWidth, hourSpacing)
        val gap = placements[1].offsetY - placements[0].bottom()
        assertEquals(DayTaskVerticalGap.value, gap.value, 1e-3f)
    }

    @Test
    fun shortTaskPushesNextCardAndStretchesHourSpan() {
        // 20 分钟待办按 40dp 兜底；贴背下卡从 16.7dp 处被推到 42dp，9:00→10:00 的
        // 刻度跨度随之变大
        val layout = listOf(todo(600, 620), todo(620, 680))
            .solveDayTaskLayout(regionWidth, hourSpacing, anchorMinute = 600)
        val first = layout.placements[0]
        val second = layout.placements[1]
        assertEquals(40f, first.height.value, 1e-3f)
        assertEquals(
            DayTaskVerticalGap.value,
            (second.offsetY - first.bottom()).value,
            1e-3f,
        )
        assertTrue(layout.extraAt(620) > 0.dp)
        assertTrue(layout.yAt(660) - layout.yAt(600) > hourSpacing)
    }

    @Test
    fun contentGrownCoursePushesFollowingCard() {
        // 100 分钟课程比例高度 ≈ 83.3dp，三行信息内容高 103dp：高度取内容高，
        // 贴背待办被推到课程底边下方 2dp
        val layout = listOf(richCourse(480, 580), todo(580, 640))
            .solveDayTaskLayout(regionWidth, hourSpacing)
        val course = layout.placements[0]
        val todo = layout.placements[1]
        assertFalse(course.compact)
        assertTrue(course.height > hourSpacing * (100 / 60f))
        assertEquals(
            DayTaskVerticalGap.value,
            (todo.offsetY - course.bottom()).value,
            1e-3f,
        )
        assertTrue(layout.extraAt(580) > 0.dp)
    }

    @Test
    fun measuredContentHeightsDriveCardHeightAndPushes() {
        // 渲染侧实测内容高度优先于常量估算：课程实测比估算高一行（18dp），卡片应
        // 精确按实测撑高，贴背的下卡同步下推，间距仍恰好 2dp，9:00 处的轴拉伸量变大
        val tasks = listOf(richCourse(480, 580), todo(580, 640))
        val estimated = tasks.solveDayTaskLayout(regionWidth, hourSpacing)
        val courseEstimate = estimated.placements[0].height
        val measuredCourseHeight = courseEstimate + 18.dp
        val measured = tasks.solveDayTaskLayout(
            regionWidth = regionWidth,
            hourSpacing = hourSpacing,
            contentHeights = listOf(measuredCourseHeight, 36.dp),
        )
        assertEquals(
            measuredCourseHeight.value,
            measured.placements[0].height.value,
            1e-3f,
        )
        assertEquals(
            DayTaskVerticalGap.value,
            (measured.placements[1].offsetY - measured.placements[0].bottom()).value,
            1e-3f,
        )
        assertTrue(measured.extraAt(580) > estimated.extraAt(580))
    }

    @Test
    fun measuredContentHeightKeepsCompactMinimum() {
        // 20 分钟待办比例高度 ≈ 16.7dp < 40dp：实测内容更矮时仍兜底到 40dp 呼吸高
        val layout = listOf(todo(780, 800)).solveDayTaskLayout(
            regionWidth = regionWidth,
            hourSpacing = hourSpacing,
            contentHeights = listOf(10.dp),
        )
        assertTrue(layout.placements[0].compact)
        assertEquals(40f, layout.placements[0].height.value, 1e-3f)
    }

    @Test
    fun measureKeyIgnoresCallbacksAndColors() {
        val todo = DayTask.Todo(
            id = "t",
            name = "待办",
            completed = false,
            deadlineText = "10/27 6:00",
            deadlineColor = Color.Black,
            repeatText = "每天",
            onToggle = null,
            startMinute = 480,
            endMinute = 540,
        )
        // 回调/颜色不影响内容高度：不入键，否则每次重建任务列表（新 lambda）都会失效
        assertEquals(todo.measureKey, todo.copy(onToggle = {}, onClick = {}).measureKey)
        assertEquals(todo.measureKey, todo.copy(deadlineColor = Color.Red).measureKey)
        // 影响排版的字段必须区分
        assertNotEquals(todo.measureKey, todo.copy(name = "改名").measureKey)
        assertNotEquals(todo.measureKey, todo.copy(repeatText = null).measureKey)
        assertNotEquals(todo.measureKey, todo.copy(completed = true).measureKey)
    }

    @Test
    fun axisStaysUniformWithoutPushes() {
        // 单卡没有后继，不需要插入空间：相邻整点仍为 50dp
        val layout = listOf(course(480, 540))
            .solveDayTaskLayout(regionWidth, hourSpacing, anchorMinute = 480)
        assertEquals(hourSpacing.value, (layout.yAt(540) - layout.yAt(480)).value, 1e-3f)
        assertEquals(hourSpacing.value, (layout.yAt(600) - layout.yAt(540)).value, 1e-3f)
    }

    @Test
    fun cardSpanningInsertedSpaceStretchesWithAxis() {
        // C(7:50-8:05) 与 B(8:10-8:30) 同列贴得近，B 在 8:10 推出一段轴空间；
        // A(8:00-10:00) 横跨该时刻 → A 跟着加高；D(10:00-10:20) 仍与 A 保持 2dp
        val layout = listOf(
            course(470, 485),
            course(480, 600),
            course(490, 510),
            course(600, 620),
        ).solveDayTaskLayout(regionWidth, hourSpacing, anchorMinute = 420)
        val spanning = layout.placements[1]
        val below = layout.placements[3]
        assertTrue(spanning.height > hourSpacing * (120 / 60f))
        assertEquals(
            DayTaskVerticalGap.value,
            (below.offsetY - spanning.bottom()).value,
            1e-3f,
        )
    }

    @Test
    fun horizontallyOverlappingCardsNeverOverlapVertically() {
        // 覆盖贴背、跨重叠链、半宽列与全宽列相交等组合：所有横向相交且时间不重叠
        // 的上下卡实际间距都必须 ≥ 2dp
        val layout = listOf(
            course(480, 540),
            todo(540, 560),
            course(570, 680),
            todo(600, 620),
            todo(690, 700),
        ).solveDayTaskLayout(regionWidth, hourSpacing)
        for (i in layout.placements.indices) {
            for (j in layout.placements.indices) {
                if (i == j) continue
                val upper = layout.placements[i]
                val lower = layout.placements[j]
                if (upper.task.endMinute > lower.task.startMinute) continue
                if (!xIntersects(upper, lower)) continue
                val gap = lower.offsetY - upper.bottom()
                assertTrue(
                    "cards $i/$j gap=$gap < ${DayTaskVerticalGap}",
                    gap >= DayTaskVerticalGap,
                )
            }
        }
    }
}
