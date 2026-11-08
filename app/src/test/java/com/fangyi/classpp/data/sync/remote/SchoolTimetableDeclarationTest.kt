package com.fangyi.classpp.data.sync.remote

import com.fangyi.classpp.data.sync.Capability
import com.fangyi.classpp.data.sync.SchoolText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 声明式课表块的 schema 边界：合法块推导 SCHEDULE_IMPORT，危险/不完整声明被拒。 */
class SchoolTimetableDeclarationTest {

    @Test
    fun `valid timetable block derives schedule import spec`() {
        val declaration = demoDeclarationWithTimetable()
        assertEquals(emptyList<String>(), declaration.validate())

        val spec = declaration.toSpec()
        assertEquals(
            setOf(Capability.WEBVIEW_LOGIN, Capability.SCHEDULE_IMPORT),
            spec.capabilities,
        )
        assertNotNull(spec.timetableIntro)
        assertEquals("教务课表", (spec.defaultScheduleName as SchoolText.Inline).zh)
        assertEquals(
            "https://jw.demo.edu.cn/gsapp/sys/wdkbapp/*default/index.do",
            declaration.timetable?.entryUrl,
        )
    }

    @Test
    fun `invalid timetable declarations are rejected`() {
        fun issues(block: DeclarationTimetable.() -> DeclarationTimetable): List<String> =
            demoDeclaration().copy(timetable = demoTimetable().block()).validate()

        assertTrue(
            issues { copy(entryUrl = "http://jw.demo.edu.cn/x") }
                .any { it.contains("must use https") },
        )
        assertTrue(
            issues {
                copy(
                    entryUrl = "https://jw.example.com/x",
                    allowedHosts = listOf("jw.example.com"),
                )
            }.any { it.contains("outside .edu.cn") },
        )
        assertTrue(
            issues { copy(slots = null) }.any { it.contains("slots or fixedSlots") },
        )
        assertTrue(
            issues { copy(fixedSlots = listOf(DeclarationFixedSlot("8:00", "9:40"))) }
                .any { it.contains("mutually exclusive") },
        )
        assertTrue(
            issues {
                copy(slots = null, fixedSlots = listOf(DeclarationFixedSlot("8:00", "9:40")))
            }.any { it.contains("slotRef") },
        )
        assertTrue(
            issues {
                copy(
                    requests = demoTimetable().requests.copy(
                        schedule = DeclarationTimetableRequest(
                            path = "https://evil.example.com/x",
                        ),
                    ),
                )
            }.any { it.contains("requests.schedule.path") },
        )
        assertTrue(
            issues {
                copy(
                    requests = demoTimetable().requests.copy(
                        schedule = DeclarationTimetableRequest(
                            path = "/x",
                            fields = mapOf("XNXQDM" to "{unknown}"),
                        ),
                    ),
                )
            }.any { it.contains("invalid value") },
        )
        assertTrue(
            issues { copy(term = DeclarationTerm(source = DeclarationTimetable.TERM_CURRENT_WEEK)) }
                .any { it.contains("term.request") },
        )
        assertTrue(
            issues { copy(courses = demoTimetable().courses.copy(weeksFormat = "RANGES")) }
                .any { it.contains("weeksFormat") },
        )
        assertTrue(
            issues { copy(semester = DeclarationTimetableSemester()) }
                .any { it.contains("semester") },
        )
        assertTrue(
            issues {
                copy(semester = DeclarationTimetableSemester(global = "current", codePath = null))
            }.any { it.contains("codePath") },
        )
    }

    @Test
    fun `student id placeholder requires a student request`() {
        val timetable = demoTimetable().let { block ->
            block.copy(
                requests = block.requests.copy(
                    student = null,
                    schedule = DeclarationTimetableRequest(
                        path = "/x",
                        fields = mapOf("XH" to "{studentId}"),
                    ),
                ),
            )
        }

        assertTrue(
            demoDeclaration().copy(timetable = timetable).validate()
                .any { it.contains("without a student request") },
        )
    }
}
