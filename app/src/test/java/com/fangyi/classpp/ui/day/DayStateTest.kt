package com.fangyi.classpp.ui.day

import com.fangyi.classpp.data.model.IsoDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 日程页恢复口径与松手吸附方向：纯逻辑，不依赖 Compose/Android。 */
class DayStateTest {

    private val today = IsoDate.of(2026, 10, 22)
    private val yesterday = today - 1

    @Test
    fun sameDayRestoresCommittedStage() {
        assertEquals(
            DayStage.Compact,
            DayOpenRecord(today, DayStage.Compact).restoredStage(today),
        )
        assertEquals(
            DayStage.Schedule,
            DayOpenRecord(today, DayStage.Schedule).restoredStage(today),
        )
    }

    @Test
    fun otherDayOrMissingRecordResetsToCalendar() {
        assertEquals(
            DayStage.Calendar,
            DayOpenRecord(yesterday, DayStage.Compact).restoredStage(today),
        )
        assertEquals(
            DayStage.Calendar,
            DayOpenRecord(yesterday, DayStage.Schedule).restoredStage(today),
        )
        assertEquals(DayStage.Calendar, DayOpenRecord.Empty.restoredStage(today))
    }

    @Test
    fun persistedCalendarNeverRestoresAsOpened() {
        assertEquals(
            DayStage.Calendar,
            DayOpenRecord(today, DayStage.Calendar).restoredStage(today),
        )
    }

    @Test
    fun snapUsesProgressThresholdWithoutVelocity() {
        assertFalse(snapForward(progress = 0.39f, velocityY = 0f, flingThresholdPx = 800f))
        assertTrue(snapForward(progress = 0.40f, velocityY = 0f, flingThresholdPx = 800f))
        assertTrue(snapForward(progress = 0.80f, velocityY = 0f, flingThresholdPx = 800f))
    }

    @Test
    fun fastFlingOverridesProgress() {
        // 快速上甩（负速度）即使进度很小也判越过
        assertTrue(snapForward(progress = 0.1f, velocityY = -900f, flingThresholdPx = 800f))
        // 快速下甩（正速度）即使进度很大也判回弹
        assertFalse(snapForward(progress = 0.9f, velocityY = 900f, flingThresholdPx = 800f))
    }

    @Test
    fun belowFlingThresholdFallsBackToProgress() {
        assertTrue(snapForward(progress = 0.6f, velocityY = -400f, flingThresholdPx = 800f))
        assertFalse(snapForward(progress = 0.2f, velocityY = 400f, flingThresholdPx = 800f))
    }

    @Test
    fun collapseForwardUsesDistanceFrom02() {
        // 从 02 出发：collapse 过半才回 04
        assertFalse(
            snapCollapseForward(DayStage.Compact, 0.39f, 0f, 800f),
        )
        assertTrue(
            snapCollapseForward(DayStage.Compact, 0.40f, 0f, 800f),
        )
    }

    @Test
    fun scheduleSettles02ByFinalProgressAtRelease() {
        // 仍停在 04：不启动吸附，保持 04。
        assertTrue(snapCollapseForward(DayStage.Schedule, 1f, 0f, 800f))
        // 下滑途中越过阈值、但松手前又拉回来（最终进度未越过往 02 的 0.4）：
        // 按最终位置回弹 04，不因途中越过而锁定。
        assertTrue(snapCollapseForward(DayStage.Schedule, 0.61f, 0f, 800f))
        assertTrue(snapCollapseForward(DayStage.Schedule, 0.8f, 0f, 800f))
        // 松手时最终进度越过阈值：提交 02。
        assertFalse(snapCollapseForward(DayStage.Schedule, 0.6f, 0f, 800f))
        assertFalse(snapCollapseForward(DayStage.Schedule, 0.2f, 0f, 800f))
    }

    @Test
    fun scheduleFlingStillOverridesFinalProgress() {
        // 快速下滑一甩：即使最终进度没过阈值也回 02。
        assertFalse(snapCollapseForward(DayStage.Schedule, 0.9f, 900f, 800f))
        // 快速上甩：即使已经越过阈值也回 04。
        assertTrue(snapCollapseForward(DayStage.Schedule, 0.5f, -900f, 800f))
    }

    @Test
    fun collapseGestureRoutingFollowsStageAndListPosition() {
        // 02 空占位：上下都不可滚时由根级驱动
        assertTrue(
            shouldDriveCollapse(DayStage.Compact, canScrollForward = false, canScrollBackward = false),
        )
        assertFalse(
            shouldDriveCollapse(DayStage.Compact, canScrollForward = true, canScrollBackward = false),
        )
        assertFalse(
            shouldDriveCollapse(DayStage.Compact, canScrollForward = false, canScrollBackward = true),
        )

        // 04：列表在顶部（不能向后滚）就由根级驱动；长列表已下滚时让给列表
        assertTrue(
            shouldDriveCollapse(DayStage.Schedule, canScrollForward = false, canScrollBackward = false),
        )
        assertTrue(
            shouldDriveCollapse(DayStage.Schedule, canScrollForward = true, canScrollBackward = false),
        )
        assertFalse(
            shouldDriveCollapse(DayStage.Schedule, canScrollForward = true, canScrollBackward = true),
        )
        assertFalse(
            shouldDriveCollapse(DayStage.Schedule, canScrollForward = false, canScrollBackward = true),
        )

        // 01 由整屏 draggable 驱动
        assertFalse(
            shouldDriveCollapse(DayStage.Calendar, canScrollForward = false, canScrollBackward = false),
        )
    }
}
