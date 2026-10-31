package com.fangyi.classpp.data.xmu

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 每日自动同步的纯函数：6:00 触发时刻计算与 [XmuDailySyncScheduler.shouldRun] 各分支 */
class XmuDailySyncTest {

    @Before
    fun fixedTimeZone() {
        // scheduledAtOfDay/nextTriggerMillis 走系统默认时区：测试固定为北京时间
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    }

    /** 「现在」锚定 2026-11-01（dayOffset 可跨天），北京时间的 epoch 毫秒 */
    private fun at(hour: Int, minute: Int = 0, dayOffset: Int = 0): Long =
        GregorianCalendar(TimeZone.getTimeZone("Asia/Shanghai")).apply {
            clear()
            set(2026, 10, 1 + dayOffset, hour, minute, 0)
        }.timeInMillis

    // ---------- 触发时刻计算 ----------

    @Test
    fun `scheduledAtOfDay 取当天 6 点整`() {
        val cal = Calendar.getInstance().apply {
            timeInMillis = XmuDailySyncScheduler.scheduledAtOfDay(at(7, 30))
        }
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(6, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
    }

    @Test
    fun `nextTrigger 未到 6 点取今天`() {
        assertEquals(at(6), XmuDailySyncScheduler.nextTriggerMillis(at(5, 59)))
        assertEquals(at(6), XmuDailySyncScheduler.nextTriggerMillis(at(0)))
    }

    @Test
    fun `nextTrigger 已过 6 点取明天`() {
        assertEquals(at(6, dayOffset = 1), XmuDailySyncScheduler.nextTriggerMillis(at(6)))
        assertEquals(at(6, dayOffset = 1), XmuDailySyncScheduler.nextTriggerMillis(at(23, 59)))
    }

    // ---------- shouldRun 各分支 ----------

    private fun run(
        enabled: Boolean = true,
        connected: Boolean = true,
        lastSync: Long = 0,
        now: Long = at(7),
    ): Boolean = XmuDailySyncScheduler.shouldRun(enabled, connected, lastSync, now)

    @Test
    fun `开关关或未连接不跑`() {
        assertFalse(run(enabled = false))
        assertFalse(run(connected = false))
    }

    @Test
    fun `未到 6 点不跑`() {
        assertFalse(run(now = at(5, 59)))
    }

    @Test
    fun `到点且从未同步就跑`() {
        assertTrue(run(now = at(6), lastSync = 0))
        assertTrue(run(now = at(23), lastSync = at(6, dayOffset = -1)))
    }

    @Test
    fun `今天 6 点后已同步过不跑`() {
        assertFalse(run(now = at(7), lastSync = at(6)))
        assertFalse(run(now = at(7), lastSync = XmuDailySyncScheduler.scheduledAtOfDay(at(7))))
    }

    @Test
    fun `6 点前的手动同步不占用当天名额`() {
        assertTrue(run(now = at(7), lastSync = at(5, 59)))
    }
}
