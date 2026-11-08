package com.fangyi.classpp.schools.xmu

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.sync.ImportError
import com.fangyi.classpp.data.sync.ImportOutcome
import com.fangyi.classpp.data.sync.webview.WebViewTimetableJs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 厦大教务课表页导入适配：脚本契约（端点/页面学期/结果变量）与原文解析分类；
 * 映射结果应和原生导入一致（复用 [com.fangyi.classpp.data.xmu.XmuJwScheduleMapper]）。
 */
class XmuWebTimetableImportTest {

    private val today = IsoDate.of(2026, 10, 31) // 周六，2026-2027 秋季学期第 8 周

    private val slotsJson = """
        [
          {"DM":"1","MC":"第1节","PX":1,"KSSJ":800,"JSSJ":845},
          {"DM":"2","MC":"第2节","PX":2,"KSSJ":855,"JSSJ":940},
          {"DM":"3","MC":"第3节","PX":3,"KSSJ":1010,"JSSJ":1055},
          {"DM":"4","MC":"第4节","PX":4,"KSSJ":1105,"JSSJ":1150},
          {"DM":"12","MC":"中午","PX":12,"KSSJ":1200,"JSSJ":1400}
        ]
    """.trimIndent()

    private val coursesJson = """
        [
          {"KCMC":"大学物理B（下）","KCDM":"C1","JSXM":"李开航","JASMC":"学武楼（1号楼）A202",
           "XQ":2,"KSJCDM":3,"JSJCDM":4,"ZCBH":"111111111111111100000000000000"},
          {"KCMC":"大学物理实验","KCDM":"C2","JSXM":"程唤龄","JASMC":"学武楼（1号楼）C201",
           "XQ":5,"KSJCDM":1,"JSJCDM":4,"ZCBH":"110000000000000000000000000000"}
        ]
    """.trimIndent()

    private fun envelope(
        slots: String = slotsJson,
        courses: String = coursesJson,
        currentZc: String = "8",
    ): String = """
        {
          "ok": true,
          "semesterCode": "20261",
          "semesterLabel": "2026-2027学年 秋季学期",
          "currentZc": "$currentZc",
          "slots": $slots,
          "courses": $courses
        }
    """.trimIndent()

    @Test
    fun `web timetable raw maps through the native mapper`() {
        val outcome = XmuWebTimetableImport.toImportOutcome(
            raw = envelope(),
            fallbackName = "教务课表",
            today = today,
        )

        assertTrue(outcome is ImportOutcome.Ok)
        val schedule = (outcome as ImportOutcome.Ok).schedule
        assertEquals("2026-2027学年 秋季学期", schedule.name)
        assertEquals(2, schedule.courses.size)
        // 今天（周六）所在周一 −（8−1）×7
        assertEquals(IsoDate.of(2026, 9, 7), schedule.termStart)
        assertEquals(16, schedule.totalWeeks)
        assertTrue(schedule.courses.none { it.name.isBlank() })
    }

    @Test
    fun `empty slots fall back to the built-in timetable`() {
        val outcome = XmuWebTimetableImport.toImportOutcome(
            raw = envelope(slots = "[]"),
            fallbackName = "教务课表",
            today = today,
        )

        assertTrue(outcome is ImportOutcome.Ok)
        // 内置作息剔除「中午」后为 11 节
        assertEquals(11, (outcome as ImportOutcome.Ok).schedule.slots.size)
    }

    @Test
    fun `empty course list reports no courses`() {
        val outcome = XmuWebTimetableImport.toImportOutcome(
            raw = envelope(courses = "[]"),
            fallbackName = "教务课表",
            today = today,
        )

        assertEquals(ImportOutcome.NoCourses, outcome)
    }

    @Test
    fun `script error envelope maps to import errors`() {
        assertEquals(
            ImportOutcome.Failed(ImportError.SessionExpired),
            XmuWebTimetableImport.toImportOutcome("""{"ok":false,"error":"session"}""", "教务课表", today),
        )
        assertEquals(
            ImportOutcome.Failed(ImportError.NetworkError),
            XmuWebTimetableImport.toImportOutcome("""{"ok":false,"error":"network"}""", "教务课表", today),
        )
        assertEquals(
            ImportOutcome.Failed(ImportError.ServiceBusy),
            XmuWebTimetableImport.toImportOutcome("""{"ok":false,"error":"busy"}""", "教务课表", today),
        )
        assertEquals(
            ImportOutcome.Failed(ImportError.FormatChanged),
            XmuWebTimetableImport.toImportOutcome("""{"ok":false,"error":"semester"}""", "教务课表", today),
        )
    }

    @Test
    fun `malformed raw is a format change`() {
        assertEquals(
            ImportOutcome.Failed(ImportError.FormatChanged),
            XmuWebTimetableImport.toImportOutcome("<html>login</html>", "教务课表", today),
        )
        assertEquals(
            ImportOutcome.Failed(ImportError.FormatChanged),
            XmuWebTimetableImport.toImportOutcome(
                envelope().replace("\"semesterCode\": \"20261\"", "\"semesterCode\": \"\""),
                "教务课表",
                today,
            ),
        )
    }

    @Test
    fun `script targets the timetable endpoints and the agreed result variable`() {
        val script = XmuWebTimetableImport.script()

        assertTrue(script.contains(WebViewTimetableJs.RESULT_VAR))
        assertTrue(script.contains("myXnxqSelect"))
        assertTrue(script.contains("/wdkcb/initXsxx.do"))
        assertTrue(script.contains("/wdkcb/queryXspkjg.do"))
        assertTrue(script.contains("/wdkcb/queryXsskjc.do"))
        assertTrue(script.contains("/wdkcb/getZcxx.do"))
        assertTrue(script.contains("credentials: 'include'"))
    }
}
