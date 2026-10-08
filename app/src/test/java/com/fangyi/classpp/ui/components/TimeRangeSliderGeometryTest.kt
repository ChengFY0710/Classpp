package com.fangyi.classpp.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 时间范围滑块的水平几何与拖动换算：
 *
 * - 位置解算 [timeRangeHandleXs]：0:00/23:59 贴住轨道两端、位置随时间连续不减、两胶囊永不
 *   重叠（重叠时各让开一半，只让位置不改时间）；
 * - 拖动换算 [timeRangeDragPxPerMinute]：手指走多少像素、胶囊就走多少像素——自由段用标称
 *   斜率，贴靠段斜率恰好减半（位置只走手指位移的一半）必须补偿回来，两端被按死时退回标称斜率；
 * - 拖动会话 [TimeRangeDrag]：同帧内的多次事件逐次累加（不从「上一帧的旧值」出发互相覆盖），
 *   被上下限夹掉的位移不攒着（反手时手指一动胶囊就动）。
 *
 * 断言直接引用 TimeRangeSlider.kt 里的常量与函数（同为 internal），调参时这里同步生效。
 */
class TimeRangeSliderGeometryTest {

    /** 浮层卡片内距后的轨道宽度（≈328dp @3x） */
    private val track = 984f

    /** 「8:00」/「18:00」胶囊宽度（≈54dp @3x） */
    private val width = 162f

    /** 标称斜率：每分钟在轨道行程上走的像素 */
    private val nominal = timeRangeTravelPx(track, width) / TimeRangeLastMinute

    private fun xs(startMinutes: Int, endMinutes: Int) =
        timeRangeHandleXs(track, width, width, startMinutes, endMinutes)

    private fun dragSlope(startMinutes: Int, endMinutes: Int, isStart: Boolean) =
        timeRangeDragPxPerMinute(track, width, width, startMinutes, endMinutes, isStart)

    /** 被拖胶囊左缘在给定时间对下的位置 */
    private fun draggedLeft(startMinutes: Int, endMinutes: Int, isStart: Boolean): Float =
        xs(startMinutes, endMinutes).let { if (isStart) it.startX else it.endX }

    /** 0:00 贴左端、23:59 右缘贴右端：两端胶囊都完整留在轨道内 */
    @Test
    fun zeroAndLastMinuteHugTheTrackEnds() {
        val both = xs(0, TimeRangeLastMinute)
        assertEquals(0f, both.startX, 0.001f)
        assertEquals(track - width, both.endX, 0.001f)
        assertEquals(0f, xs(0, 600).startX, 0.001f)
        assertEquals(track - width, xs(600, TimeRangeLastMinute).endX, 0.001f)
    }

    /** 两胶囊永不重叠：结束胶囊左缘不早于开始胶囊右缘（贴靠时正好相接） */
    @Test
    fun handlesNeverOverlap() {
        listOf(1, 2, 30, 150, 480, 780, 1080, TimeRangeLastMinute).forEach { end ->
            for (start in 0 until end) {
                val p = xs(start, end)
                assertTrue(
                    "start=$start end=$end startX=${p.startX} endX=${p.endX}",
                    p.endX >= p.startX + width - 0.001f,
                )
            }
        }
    }

    /** 位置是时间的连续不减函数：任一胶囊的时间增大，它的位置都不会回退（拖动不会位置突变） */
    @Test
    fun positionNeverMovesBackwardsAsTimeGrows() {
        listOf(480, 780, TimeRangeLastMinute).forEach { end ->
            for (start in 0 until end) {
                assertTrue(
                    "start=$start end=$end",
                    xs(start + 1, end).startX >= xs(start, end).startX - 0.001f,
                )
            }
        }
        listOf(0, 660, 1079).forEach { start ->
            for (end in (start + 1) until TimeRangeLastMinute) {
                assertTrue(
                    "start=$start end=$end",
                    xs(start, end + 1).endX >= xs(start, end).endX - 0.001f,
                )
            }
        }
    }

    /**
     * 贴靠段斜率恰好是标称值的一半：位置只走手指位移的一半，必须按斜率补偿才跟手；
     * 自由段斜率就是标称值。
     */
    @Test
    fun giveWayZoneRunsAtHalfTheNominalSlope() {
        // 11:00–13:00：时间差 120 分钟 ≈ 68.5px，远小于胶囊宽度 → 让位后贴在一起
        val giveWay = xs(660, 780)
        assertTrue(
            "应处于贴靠段 endX-startX=${giveWay.endX - giveWay.startX}",
            giveWay.endX - giveWay.startX <= width + 0.001f,
        )
        assertEquals(nominal / 2f, dragSlope(660, 780, isStart = true), 1e-4f)
        assertEquals(nominal / 2f, dragSlope(660, 780, isStart = false), 1e-4f)
        // 8:00–18:00：两胶囊拉开，斜率即标称行程斜率
        assertEquals(nominal, dragSlope(480, 1080, isStart = true), 1e-4f)
        assertEquals(nominal, dragSlope(480, 1080, isStart = false), 1e-4f)
    }

    /**
     * 跟手：手指横向走 dxPx，按斜率换算出的分钟数必须让被拖胶囊的左缘也走 dxPx。
     * 自由段与贴靠段都要成立——贴靠段不许半速落后。
     */
    @Test
    fun draggingMovesTheHandleAsFarAsTheFinger() {
        listOf(
            480 to 1080, // 自由段：8:00–18:00
            660 to 780, // 贴靠段：11:00–13:00
            300 to 400, // 贴靠段偏中：5:00–6:40
        ).forEach { (start, end) ->
            listOf(true, false).forEach { isStart ->
                listOf(8f, -8f).forEach { dx -> assertFollowsFinger(start, end, isStart, dx) }
            }
        }
    }

    private fun assertFollowsFinger(start: Int, end: Int, isStart: Boolean, dx: Float) {
        val minutes = (dx / dragSlope(start, end, isStart)).roundToInt()
        assertTrue("换算被取整吃掉：start=$start end=$end dx=$dx", minutes != 0)
        val after = draggedLeft(
            if (isStart) start + minutes else start,
            if (isStart) end else end + minutes,
            isStart,
        )
        val moved = after - draggedLeft(start, end, isStart)
        assertEquals("start=$start end=$end isStart=$isStart dx=$dx", dx, moved, 1f)
    }

    /** 贴靠段补偿之后：指尖下那颗走多少，另一颗也整体走多少（视觉上是一对胶囊整齐平移） */
    @Test
    fun giveWayZoneTranslatesBothHandlesTogether() {
        val dx = 8f
        val minutes = (dx / dragSlope(660, 780, isStart = true)).roundToInt()
        val before = xs(660, 780)
        val after = xs(660 + minutes, 780)
        assertEquals(dx, after.startX - before.startX, 1f)
        assertEquals(dx, after.endX - before.endX, 1f)
    }

    /** 两端挤不下时位置被按死（斜率为 0）：换算退回标称斜率——不除零，时间照常推进 */
    @Test
    fun pinnedCornersFallBackToTheNominalSlope() {
        // 2:00–2:30：胶囊太宽，整体被顶在轨道左端，位置对时间已经不动
        assertEquals(xs(119, 150).startX, xs(121, 150).startX, 0.001f)
        assertEquals(nominal, dragSlope(120, 150, isStart = true), 1e-4f)
        assertTrue(dragSlope(120, 150, isStart = true) > 0f)
    }

    /** 几何退化（胶囊比轨道还宽）：行程为 0、两胶囊重叠在原点，换算斜率仍为正、不除零 */
    @Test
    fun degenerateNarrowTrackStaysFinite() {
        assertEquals(0f, timeRangeTravelPx(10f, width), 0f)
        val p = timeRangeHandleXs(10f, width, width, 0, TimeRangeLastMinute)
        assertEquals(0f, p.startX, 0f)
        assertEquals(0f, p.endX, 0f)
        assertTrue(timeRangeDragPxPerMinute(10f, width, width, 0, TimeRangeLastMinute, true) > 0f)
    }

    /**
     * 同帧内的多次事件逐次累加：后一次不以「上一次的旧值」为基准覆盖前一次的位移
     * （旧实现从参数读当前分钟数，参数要等调用方重组才更新，事件密集时位移被整段丢掉，
     * 手指来回时胶囊还会朝反方向跳）。
     */
    @Test
    fun consecutiveEventsAccumulateInsteadOfOverwriting() {
        val perEvent = TimeRangeDrag(isStart = true, startMinutes = 480)
        repeat(4) { perEvent.advance(10f, nominal, lower = 0, upper = TimeRangeLastMinute) }
        val atOnce = TimeRangeDrag(isStart = true, startMinutes = 480)
        atOnce.advance(40f, nominal, lower = 0, upper = TimeRangeLastMinute)
        assertEquals(atOnce.minutes, perEvent.minutes)
        assertEquals(480 + (40f / nominal).roundToInt(), perEvent.minutes)
    }

    /** 顶到上下限：被夹掉的位移不攒着，反手一次就跟着走（不必先把「欠」的位移还完） */
    @Test
    fun displacementSwallowedAtTheLimitIsNotBanked() {
        val drag = TimeRangeDrag(isStart = true, startMinutes = 100)
        // 上限 110：连着推 60px（≈105 分钟）全被夹掉
        repeat(6) { drag.advance(10f, nominal, lower = 0, upper = 110) }
        assertEquals(110, drag.minutes)
        drag.advance(-1f, nominal, lower = 0, upper = 110)
        assertEquals(110 - (1f / nominal).roundToInt(), drag.minutes)
    }

    /** 慢拖：不足 1 分钟的位移逐次攒着，凑满 1 分钟才走（不会因逐次取整而整段丢失） */
    @Test
    fun subMinuteMovementsAccumulate() {
        val drag = TimeRangeDrag(isStart = true, startMinutes = 480)
        repeat(100) { drag.advance(0.01f, nominal, lower = 0, upper = TimeRangeLastMinute) }
        val advanced = drag.minutes - 480
        assertTrue("advanced=$advanced", advanced in 1..3)
        assertEquals(2, (100 * 0.01f / nominal).roundToInt())
    }
}
