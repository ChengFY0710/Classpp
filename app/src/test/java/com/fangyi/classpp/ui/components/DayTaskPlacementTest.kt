package com.fangyi.classpp.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.schedule.CourseColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // 8:00 → y = (8/60 + 0.5) × 50dp（DayTimeline 同一映射）
        val placements = listOf(course(480, 540)).solveDayTaskPlacements(regionWidth, hourSpacing)
        assertEquals(50f * (480 / 60f + 0.5f), placements[0].offsetY.value, 1e-3f)
    }
}
