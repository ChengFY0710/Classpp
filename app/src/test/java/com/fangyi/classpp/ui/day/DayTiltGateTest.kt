package com.fangyi.classpp.ui.day

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 壁纸视差门控：只有「活跃 + 浮层未开 + 停在 01 + 无整页 transform」全部成立才注册
 * 传感器。转场期必须暂停——shiftX/shiftY 是日程页冻结层的录制键，继续变化会让键
 * 逐帧失配、回退实时 Haze。
 */
class DayTiltGateTest {

    private fun gate(
        active: Boolean = true,
        sheetMounted: Boolean = false,
        stage: DayStage = DayStage.Calendar,
        pageTransforming: Boolean = false,
    ) = isDayTiltActive(
        active = active,
        sheetMounted = sheetMounted,
        stage = stage,
        pageTransforming = pageTransforming,
    )

    @Test
    fun activeCalendarPageAtRestEnablesTilt() {
        assertTrue(gate())
    }

    @Test
    fun inactivePageDisablesTilt() {
        assertFalse(gate(active = false))
    }

    @Test
    fun mountedSheetDisablesTilt() {
        assertFalse(gate(sheetMounted = true))
    }

    @Test
    fun compactAndScheduleStagesDisableTilt() {
        assertFalse(gate(stage = DayStage.Compact))
        assertFalse(gate(stage = DayStage.Schedule))
    }

    @Test
    fun pageTransformDisablesTilt() {
        assertFalse(gate(pageTransforming = true))
    }
}
