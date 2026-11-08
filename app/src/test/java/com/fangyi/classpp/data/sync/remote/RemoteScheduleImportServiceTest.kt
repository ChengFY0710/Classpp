package com.fangyi.classpp.data.sync.remote

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.sync.ImportBlockReason
import com.fangyi.classpp.data.sync.ImportError
import com.fangyi.classpp.data.sync.ImportOutcome
import com.fangyi.classpp.data.sync.ImportSemester
import com.fangyi.classpp.data.sync.SemestersOutcome
import com.fangyi.classpp.data.sync.SyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 远端课表导入服务：连接态 → 阻断原因；原生学期路径不可用；网页原文走声明式映射。 */
class RemoteScheduleImportServiceTest {

    private fun service(state: MutableStateFlow<SyncState>) = RemoteScheduleImportService(
        syncState = state,
        timetable = demoTimetable(),
        fallbackName = "教务课表",
        webViewTimetable = demoTimetable().toWebViewTimetableSpec(
            RemoteTimetableScript.script(demoTimetable()),
        ),
        today = { IsoDate.of(2026, 10, 31) },
    )

    @Test
    fun `blocked reason follows connection state`() {
        val state = MutableStateFlow(SyncState())
        val service = service(state)

        assertEquals(ImportBlockReason.NotConnected, service.blockedReason.value)
        state.value = state.value.copy(uid = "u1")
        assertEquals(ImportBlockReason.NoCredentials, service.blockedReason.value)
    }

    @Test
    fun `native semester path is not supported`() = runBlocking {
        val service = service(MutableStateFlow(SyncState()))

        assertEquals(
            SemestersOutcome.Failed(ImportError.NeedLogin),
            service.fetchSemesters(),
        )
        assertEquals(
            ImportOutcome.Failed(ImportError.NeedLogin),
            service.fetchSchedule(ImportSemester("20261", "秋季学期")),
        )
    }

    @Test
    fun `web payload is mapped through the declarative mapper`() {
        val service = service(MutableStateFlow(SyncState()))
        val raw = """
            {
              "ok": true,
              "semesterCode": "20261",
              "semesterLabel": "2026-2027学年 秋季学期",
              "currentWeek": "1",
              "slots": [
                {"DM":"1","MC":"第1节","PX":1,"KSSJ":800,"JSSJ":845}
              ],
              "courses": [
                {"KCMC":"固定课","XQ":1,"KSJCDM":1,"JSJCDM":1,"ZCBH":"100000000000000000000000000000"}
              ]
            }
        """.trimIndent()

        val outcome = service.importFromWebTimetable(raw)

        assertTrue(outcome is ImportOutcome.Ok)
        assertEquals(
            "2026-2027学年 秋季学期",
            (outcome as ImportOutcome.Ok).schedule.name,
        )
    }
}
