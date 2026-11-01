package com.fangyi.classpp.data.xmu

import com.fangyi.classpp.data.model.IsoDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 教务导入编排：会话过期自动重登一次、瞬时失败退避、凭据缺失与降级规则 */
class XmuJwScheduleImporterTest {

    private val today = IsoDate.of(2026, 10, 31)

    private class JwHarness(
        sessionValid: Boolean = false,
        credentials: XmuCredentials? = XmuCredentials("20260001", "secret"),
    ) {
        var now = 1_000_000L
        val server = FakeXmuJwServer().apply { this.sessionValid = sessionValid }
        val jar = XmuCookieJar()

        val importer = XmuJwScheduleImporter(
            loginClient = XmuJwLoginClient(server.transport, jvmCipher()),
            api = XmuJwScheduleApi(server.transport, jar),
            credentialsProvider = { credentials },
            cookieJar = jar,
            nowMillis = { now },
        )
    }

    @Test
    fun `semesters auto login once on expired session then reuse it`() = runBlocking {
        val harness = JwHarness()
        val first = harness.importer.fetchSemesters()

        val semesters = (first as XmuJwSemestersResult.Ok).semesters
        assertEquals(2, semesters.size)
        assertEquals(XmuJwSemester("20261", "2026-2027学年 秋季学期"), semesters[0])
        assertEquals(1, harness.server.loginPosts)
        assertEquals("S", harness.jar.cookiesFor("jw.xmu.edu.cn")["JW_SESSION"])

        // 会话在内存复用：第二次不再登录
        harness.importer.fetchSemesters()
        assertEquals(1, harness.server.loginPosts)
    }

    @Test
    fun `missing credentials is need login without any request`() = runBlocking {
        val harness = JwHarness(credentials = null)
        val result = harness.importer.fetchSemesters()

        assertEquals(XmuJwImportError.NeedLogin, (result as XmuJwSemestersResult.Failed).error)
        assertTrue(harness.server.requests.isEmpty())
    }

    @Test
    fun `failed jw login surfaces invalid credentials and leaves no session`() = runBlocking {
        val harness = JwHarness().apply { server.invalidCredentials = true }
        val result = harness.importer.fetchSchedule("20261", "教务课表", today)

        assertEquals(
            XmuJwImportError.InvalidCredentials,
            (result as XmuJwScheduleResult.Failed).error,
        )
        assertTrue(harness.jar.cookiesFor("jw.xmu.edu.cn").isEmpty())
    }

    @Test
    fun `captcha demand surfaces need captcha without submitting password`() = runBlocking {
        val harness = JwHarness().apply { server.needCaptcha = true }
        val result = harness.importer.fetchSemesters()

        assertEquals(XmuJwImportError.NeedCaptcha, (result as XmuJwSemestersResult.Failed).error)
        assertEquals(0, harness.server.loginPosts)
        assertTrue(harness.jar.cookiesFor("jw.xmu.edu.cn").isEmpty())
    }

    @Test
    fun `schedule import maps full semester with derived term start`() = runBlocking {
        val harness = JwHarness()
        val result = harness.importer.fetchSchedule("20261", "教务课表", today)

        val schedule = (result as XmuJwScheduleResult.Ok).schedule
        // 课表名取学期文案（kfdxnxqcx 中 code 匹配项）
        assertEquals("2026-2027学年 秋季学期", schedule.name)
        assertEquals(2, schedule.courses.size)
        assertEquals(5, schedule.slots.size) // 中午行不进作息
        assertEquals(IsoDate.of(2026, 9, 7), schedule.termStart) // 今天(周六)所在周一 −(8−1)×7
        assertEquals(16, schedule.totalWeeks)
        assertEquals(5, schedule.daysPerWeek)
    }

    @Test
    fun `current week endpoint failure only degrades term start`() = runBlocking {
        val harness = JwHarness().apply { server.zcxxBody = null } // 404 → 格式外错误
        val result = harness.importer.fetchSchedule("20261", "教务课表", today)

        val schedule = (result as XmuJwScheduleResult.Ok).schedule
        assertEquals(IsoDate.of(2026, 10, 26), schedule.termStart) // 兜底今天所在周一
    }

    @Test
    fun `empty schedule rows means no courses`() = runBlocking {
        val harness = JwHarness().apply { server.pkjgBody = """{"success":true,"pkjgList":[],"ttbList":[]}""" }
        val result = harness.importer.fetchSchedule("20261", "教务课表", today)

        assertEquals(XmuJwScheduleResult.NoCourses, result)
    }

    @Test
    fun `missing slot times fall back to built-in xmu slots`() = runBlocking {
        // EMAP 对未知动作回 200 + 空数据（实测）：作息接口空应答时用内置作息兜底，
        // 导入不因此失败
        val harness = JwHarness().apply { server.skjcBody = """{"datas":null}""" }
        val result = harness.importer.fetchSchedule("20261", "教务课表", today)

        val schedule = (result as XmuJwScheduleResult.Ok).schedule
        assertEquals(11, schedule.slots.size) // 内置 11 节（「中午」占位行被剔除）
        assertEquals("8:00", schedule.slots[0].startTime)
    }

    @Test
    fun `transient failure backs off before hitting server again`() = runBlocking {
        val harness = JwHarness().apply { server.networkDown = true }

        val first = harness.importer.fetchSemesters()
        assertEquals(XmuJwImportError.NetworkError, (first as XmuJwSemestersResult.Failed).error)
        val requestsAfterFirst = harness.server.requests.size
        assertTrue(requestsAfterFirst > 0)

        // 退避期内不打网络
        val second = harness.importer.fetchSemesters()
        assertEquals(XmuJwImportError.NetworkError, (second as XmuJwSemestersResult.Failed).error)
        assertEquals(requestsAfterFirst, harness.server.requests.size)

        // 退避过期后恢复请求（仍失败，但确认没有永久卡死）
        harness.now += 61_000L
        harness.importer.fetchSemesters()
        assertTrue(harness.server.requests.size > requestsAfterFirst)
    }

    @Test
    fun `successful import clears backoff`() = runBlocking {
        val harness = JwHarness().apply { server.networkDown = true }
        harness.importer.fetchSemesters() // 进入退避
        harness.server.networkDown = false
        harness.now += 61_000L

        val result = harness.importer.fetchSchedule("20261", "教务课表", today)
        assertTrue(result is XmuJwScheduleResult.Ok)
        // 成功后立即再导不再退避
        val again = harness.importer.fetchSchedule("20252", "教务课表", today)
        assertTrue(again is XmuJwScheduleResult.Ok)
    }
}
