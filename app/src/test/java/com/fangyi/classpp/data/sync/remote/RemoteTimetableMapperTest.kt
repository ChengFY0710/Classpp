package com.fangyi.classpp.data.sync.remote

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.sync.ImportError
import com.fangyi.classpp.data.sync.ImportOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 声明式课表原文 → Schedule：位图周次、同课拆行合并、超长课拆块、两种开学日来源。 */
class RemoteTimetableMapperTest {

    private val today = IsoDate.of(2026, 10, 31) // 周六，第 8 周

    private val payload = """
        {
          "ok": true,
          "semesterCode": "20261",
          "semesterLabel": "2026-2027学年 秋季学期",
          "currentWeek": "8",
          "slots": [
            {"DM":"1","MC":"第1节","PX":1,"KSSJ":800,"JSSJ":845},
            {"DM":"2","MC":"第2节","PX":2,"KSSJ":855,"JSSJ":940},
            {"DM":"3","MC":"第3节","PX":3,"KSSJ":1010,"JSSJ":1055},
            {"DM":"4","MC":"第4节","PX":4,"KSSJ":1105,"JSSJ":1150},
            {"DM":"12","MC":"中午","PX":12,"KSSJ":1200,"JSSJ":1400},
            {"DM":"5","MC":"第5节","PX":5,"KSSJ":1430,"JSSJ":1515},
            {"DM":"6","MC":"第6节","PX":6,"KSSJ":1525,"JSSJ":1610},
            {"DM":"7","MC":"第7节","PX":7,"KSSJ":1640,"JSSJ":1725}
          ],
          "courses": [
            {"KCDM":"C1","KCMC":"数据结构","JSXM":"张三","JASMC":"A101","XQ":2,"KSJCDM":3,"JSJCDM":4,"ZCBH":"111111111111111100000000000000"},
            {"KCDM":"C1","KCMC":"数据结构","JSXM":"李四","JASMC":"A102","XQ":2,"KSJCDM":3,"JSJCDM":4,"ZCBH":"111111111111111100000000000000"},
            {"KCDM":"C2","KCMC":"大学物理","JSXM":"王五","JASMC":"B201","XQ":5,"KSJCDM":1,"JSJCDM":2,"ZCBH":"111100000000000000000000000000"},
            {"KCDM":"C3","KCMC":"长课","JSXM":"赵六","JASMC":"C301","XQ":3,"KSJCDM":1,"JSJCDM":7,"ZCBH":"111111111111111100000000000000"},
            {"KCDM":"C4","KCMC":"","JSXM":"","JASMC":"","XQ":4,"KSJCDM":1,"JSJCDM":2,"ZCBH":"111100000000000000000000000000"}
          ]
        }
    """.trimIndent()

    @Test
    fun `payload maps to schedule with merged rows and chunked spans`() {
        val outcome = RemoteTimetableMapper.toImportOutcome(
            payload, demoTimetable(), "教务课表", today,
        )

        assertTrue(outcome is ImportOutcome.Ok)
        val schedule = (outcome as ImportOutcome.Ok).schedule
        assertEquals("2026-2027学年 秋季学期", schedule.name)
        assertEquals(7, schedule.slots.size) // 中午行被剔除
        assertEquals(4, schedule.courses.size) // 2 行合并 + 1 + 长课拆 2
        assertEquals(16, schedule.totalWeeks)
        assertEquals(IsoDate.of(2026, 9, 7), schedule.termStart)
        assertEquals(IsoDate.of(2026, 12, 27), schedule.termEnd)
        assertEquals(5, schedule.daysPerWeek)

        val dataStructure = schedule.courses.first { it.name == "数据结构" }
        assertEquals("张三、李四", dataStructure.teacher)
        assertEquals("A101；A102", dataStructure.location)
        assertEquals(2, dataStructure.dayOfWeek)
        assertEquals(3, dataStructure.startSlot)
        assertEquals(2, dataStructure.span)

        val longCourse = schedule.courses.filter { it.name == "长课" }
        assertEquals(listOf(5, 2), longCourse.map { it.span })
        assertEquals(listOf(1, 6), longCourse.map { it.startSlot })
    }

    @Test
    fun `date term parses declared pattern`() {
        val timetable = demoTimetable().copy(
            term = DeclarationTerm(
                source = DeclarationTimetable.TERM_DATE,
                request = "schedule",
                path = "termStartDate",
                pattern = "yyyy-MM-dd",
            ),
        )
        val raw = payload.replace("\"currentWeek\": \"8\",", "\"termStart\": \"2026-09-07\",")

        val outcome = RemoteTimetableMapper.toImportOutcome(raw, timetable, "教务课表", today)

        assertTrue(outcome is ImportOutcome.Ok)
        assertEquals(IsoDate.of(2026, 9, 7), (outcome as ImportOutcome.Ok).schedule.termStart)
    }

    @Test
    fun `empty courses report no courses`() {
        val raw = payload.replace(Regex("(?s)\"courses\": \\[.*?]"), "\"courses\": []")

        assertEquals(
            ImportOutcome.NoCourses,
            RemoteTimetableMapper.toImportOutcome(raw, demoTimetable(), "教务课表", today),
        )
    }

    @Test
    fun `error envelope maps to import errors`() {
        assertEquals(
            ImportOutcome.Failed(ImportError.SessionExpired),
            RemoteTimetableMapper.toImportOutcome(
                """{"ok":false,"error":"session"}""", demoTimetable(), "教务课表", today,
            ),
        )
        assertEquals(
            ImportOutcome.Failed(ImportError.NetworkError),
            RemoteTimetableMapper.toImportOutcome(
                """{"ok":false,"error":"network"}""", demoTimetable(), "教务课表", today,
            ),
        )
        assertEquals(
            ImportOutcome.Failed(ImportError.ServiceBusy),
            RemoteTimetableMapper.toImportOutcome(
                """{"ok":false,"error":"busy"}""", demoTimetable(), "教务课表", today,
            ),
        )
        assertEquals(
            ImportOutcome.Failed(ImportError.FormatChanged),
            RemoteTimetableMapper.toImportOutcome("not json", demoTimetable(), "教务课表", today),
        )
    }

    @Test
    fun `fixed slots use index refs`() {
        val timetable = demoTimetable().copy(
            slots = null,
            fixedSlots = listOf(
                DeclarationFixedSlot("8:00", "9:40"),
                DeclarationFixedSlot("10:10", "11:50"),
            ),
            courses = demoTimetable().courses.copy(
                slotRef = DeclarationTimetable.SLOT_INDEX,
            ),
        )
        val raw = """
            {
              "ok": true,
              "semesterCode": "20261",
              "semesterLabel": "2026-2027学年 秋季学期",
              "currentWeek": "1",
              "slots": [],
              "courses": [
                {"KCMC":"固定课","XQ":1,"KSJCDM":1,"JSJCDM":2,"ZCBH":"100000000000000000000000000000"}
              ]
            }
        """.trimIndent()

        val outcome = RemoteTimetableMapper.toImportOutcome(raw, timetable, "教务课表", today)

        assertTrue(outcome is ImportOutcome.Ok)
        val schedule = (outcome as ImportOutcome.Ok).schedule
        assertEquals(2, schedule.slots.size)
        assertEquals(2, schedule.courses.single().span)
    }
}
