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
    fun collapseReverseUsesDistanceFrom04() {
        // 从 04 出发：collapse 往下走过 40%（≤0.6）才回 02；
        // 0.39 已走 61% → 去 02（forward=false），0.61 只走 39% → 回 04。
        assertFalse(
            snapCollapseForward(DayStage.Schedule, 0.39f, 0f, 800f),
        )
        assertTrue(
            snapCollapseForward(DayStage.Schedule, 0.61f, 0f, 800f),
        )
    }

    @Test
    fun collapseVelocityOverridesReverseProgress() {
        assertFalse(
            snapCollapseForward(DayStage.Schedule, 0.9f, 900f, 800f),
        )
        assertTrue(
            snapCollapseForward(DayStage.Schedule, 0.1f, -900f, 800f),
        )
    }
}
