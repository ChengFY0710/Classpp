package com.fangyi.classpp.data.wakeup

import com.fangyi.classpp.data.ReadResult
import com.fangyi.classpp.data.export.ShareCodec
import com.fangyi.classpp.data.model.TimeSlotDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真机抓取的真实 Wakeup 回传载荷回归测试（课程名/教师/教室已脱敏，结构与分布原样保留）：
 * - 20 条作息的 12-20 节是 Wakeup 对未配置节次下发的**重叠占位**（曾导致
 *   SlotOrderInvalid → 导入失败），课程实际只用到 11 节；
 * - 25 条课程占位含同课名拆分（同节次不同周 / 连续节次），验证合并在真实分布下成立。
 * 全链路断言：脚本 JSON 解析 → 合并 → 映射 → 分享 JSON → [ShareCodec.decodeForImport] 校验通过。
 */
class WakeupRealPayloadPipelineTest {

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/wakeup/$name")!!.bufferedReader().use { it.readText() }

    private fun realPayload(): WakeupSharePayload = WakeupSharePayload(
        courses = WakeupScriptJson.decodeFromString<List<WakeupCourse>>(resource("payload-courses.json")),
        timeSlots = WakeupScriptJson.decodeFromString<List<WakeupTimeSlot>>(resource("payload-slots.json")),
        config = WakeupScriptJson.decodeFromString<WakeupCourseConfig>(resource("payload-config.json")),
    )

    @Test
    fun `real payload with placeholder slots passes full pipeline`() {
        val schedule = WakeupScheduleMapper.toScheduleOrNull(
            realPayload(),
            "这是来自「WakeUp课程表」的课表分享，分享口令为「34d573fc7ece4c33ad7b4577d1bdbc9a」",
            "Wakeup 课表",
        )!!

        // 占位作息被截断：保留课程用到的 11 节（末节 21:00-21:45）
        assertEquals(11, schedule.slots.size)
        assertEquals(TimeSlotDef("21:00", "21:45"), schedule.slots.last())
        // 25 条占位收敛后仍有 25 门课（占位互不合并：课名/教师/周次有差异）
        assertEquals(25, schedule.courses.size)
        // 学期配置如实映射
        assertEquals(20, schedule.totalWeeks)
        assertEquals(45, schedule.slotDurationMinutes)
    }

    @Test
    fun `mapped real payload survives share codec validation`() {
        val json = WakeupScheduleMapper.toShareJson(
            WakeupScheduleMapper.toScheduleOrNull(realPayload(), "", "Wakeup 课表")!!,
        )
        val imported = ShareCodec.decodeForImport(json)
        assertTrue("import failed: $imported", imported is ReadResult.Ok)
        assertEquals(25, (imported as ReadResult.Ok).value.courses.size)
    }
}
