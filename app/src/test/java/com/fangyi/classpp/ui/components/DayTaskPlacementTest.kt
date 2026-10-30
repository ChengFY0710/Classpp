package com.fangyi.classpp.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.schedule.CourseColor
import com.fangyi.classpp.ui.theme.ClassppTextTokens
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

    // ---------- 紧凑卡最低高度：在结束时刻拉伸时间轴 ----------

    @Test
    fun compactCardStretchesAxisAtItsEnd() {
        // 20 分钟待办比例高度 ≈ 16.7dp，被紧凑卡 40dp 最低高度兜底：差额插在结束
        // 时刻，卡底正好落到结束刻度，之后的刻度整体下移
        val layout = listOf(todo(780, 800))
            .solveDayTaskLayout(regionWidth, hourSpacing, anchorMinute = 780)
        val card = layout.placements[0]
        assertTrue(card.compact)
        assertEquals(40f, card.height.value, 1e-3f)
        assertEquals(
            layout.yAt(800).value,
            (card.offsetY + card.height).value,
            1e-3f,
        )
        assertEquals(
            40f - hourSpacing.value * 20 / 60,
            layout.extraAt(800).value,
            1e-3f,
        )
    }

    @Test
    fun compactAtMinimumHeightKeepsAxisUniform() {
        // 60 分钟待办比例高度 50dp ≥ 40dp 最低高度：无需插轴，刻度仍均匀
        val layout = listOf(todo(780, 840))
            .solveDayTaskLayout(regionWidth, hourSpacing, anchorMinute = 780)
        assertTrue(layout.placements[0].compact)
        assertTrue(layout.pushes.isEmpty())
        assertEquals(
            hourSpacing.value,
            (layout.yAt(840) - layout.yAt(780)).value,
            1e-3f,
        )
    }

    @Test
    fun parallelCardsEndingTogetherInsertMaxOnce() {
        // 两条同时段短课程并排、同一结束时刻：并行车道共用一段轴，按最大差额只插
        // 一次（不累加），两卡底边都落在结束刻度
        val layout = listOf(course(480, 500), course(480, 500))
            .solveDayTaskLayout(regionWidth, hourSpacing, anchorMinute = 480)
        assertEquals(
            40f - hourSpacing.value * 20 / 60,
            layout.extraAt(500).value,
            1e-3f,
        )
        assertEquals(1, layout.pushes.count { it.minute == 500 })
        layout.placements.forEach {
            assertEquals(
                layout.yAt(500).value,
                (it.offsetY + it.height).value,
                1e-3f,
            )
        }
    }

    @Test
    fun backToBackShortCardInsertsDeficitThenGap() {
        // 贴背：先在 10:20 结束时刻补最低高度差额，再在 10:20 开始组补 2dp 间距，
        // 两条插入同刻叠加，下卡与短卡恰好 2dp
        val layout = listOf(todo(600, 620), todo(620, 680))
            .solveDayTaskLayout(regionWidth, hourSpacing, anchorMinute = 600)
        val first = layout.placements[0]
        val second = layout.placements[1]
        assertEquals(
            40f - hourSpacing.value * 20 / 60 + DayTaskVerticalGap.value,
            layout.extraAt(620).value,
            1e-3f,
        )
        assertEquals(
            DayTaskVerticalGap.value,
            (second.offsetY - first.bottom()).value,
            1e-3f,
        )
    }

    @Test
    fun spanningCardGrowsWithEndInsertion() {
        // 长课程横跨短课程的结束时刻：差额插轴后长卡同步加高，卡底仍对齐自己的
        // 结束刻度
        val layout = listOf(course(480, 600), course(490, 510))
            .solveDayTaskLayout(regionWidth, hourSpacing, anchorMinute = 480)
        val spanning = layout.placements[0]
        val short = layout.placements[1]
        assertTrue(short.compact)
        assertEquals(
            hourSpacing.value * 2 + 40f - hourSpacing.value * 20 / 60,
            spanning.height.value,
            1e-3f,
        )
        assertEquals(
            layout.yAt(600).value,
            (spanning.offsetY + spanning.height).value,
            1e-3f,
        )
    }

    @Test
    fun lastCardEndInsertionExtendsAxis() {
        // 当天最后一张短卡也要撑轴：末刻度（次日 0:00）随之下移，卡底到末刻度
        // 恰好是真实的 20 分钟空档
        val layout = listOf(todo(1400, 1420))
            .solveDayTaskLayout(regionWidth, hourSpacing, anchorMinute = 1400)
        assertEquals(
            40f + hourSpacing.value * 20 / 60,
            (layout.yAt(1440) - layout.yAt(1400)).value,
            1e-3f,
        )
    }

    @Test
    fun narrowCardDoesNotInsertAtEnd() {
        // 窄卡的内容撑高不参与最低高度拉伸（本次范围外）：无冲突时不插轴
        val layout = listOf(course(480, 600), todo(540, 560))
            .solveDayTaskLayout(regionWidth, hourSpacing, anchorMinute = 480)
        val narrowTodo = layout.placements.single { it.task is DayTask.Todo }
        assertTrue(narrowTodo.narrow)
        assertTrue(narrowTodo.height > 40.dp)
        assertTrue(layout.pushes.isEmpty())
    }

    @Test
    fun narrowCourseLongNoteIsCappedAtTwoLines() {
        // 窄卡（90dp < 100dp）：备注上限两行，长备注不再按字符数无限撑高；
        // 与刚好两行的备注同高，避免把当天时间轴整体拉长
        fun narrowHeight(note: String): Dp = listOf(
            DayTask.Course(
                name = "课程",
                teacher = null,
                location = null,
                note = note,
                color = CourseColor.Blue,
                startMinute = 480,
                endMinute = 540,
            ),
        ).solveDayTaskLayout(regionWidth = 90.dp, hourSpacing = hourSpacing)
            .placements.single().height

        // 90dp 卡宽内容宽 66dp、约 4 字/行：「备注：」+ 4 字 = 两行
        val twoLines = narrowHeight("备注备注")
        val manyLines = narrowHeight("备注".repeat(30))
        assertEquals(twoLines.value, manyLines.value, 1e-3f)
    }

    @Test
    fun verticalCourseNoteReservesTwoLines() {
        // 竖排卡（90 分钟，比例高 75dp）备注上限两行：兜底估算按两行预留高度
        fun verticalHeight(note: String?): Dp = listOf(
            DayTask.Course(
                name = "课程",
                teacher = "老师",
                location = "教室",
                note = note,
                color = CourseColor.Blue,
                startMinute = 480,
                endMinute = 570,
            ),
        ).solveDayTaskLayout(regionWidth = regionWidth, hourSpacing = hourSpacing)
            .placements.single().height

        val metaLine = ClassppTextTokens.metaText.lineHeight.value.dp
        val diff = verticalHeight("这是一条足够长的备注，用来触发第二行").value -
            verticalHeight(null).value
        assertTrue(diff > metaLine.value * 1.5f)
        assertTrue(diff < metaLine.value * 2.5f)
    }

    @Test
    fun contentGrownCoursePushesFollowingCard() {
        // 100 分钟课程比例高度 ≈ 83.3dp，信息区（备注按两行兜底估算）内容更高：
        // 高度取内容高，贴背待办被推到课程底边下方 2dp
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
