package com.fangyi.classpp.data.xmu

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 教务课表接口：真实抓包样本的解析、会话过期判定与错误分类 */
class XmuJwScheduleApiTest {

    private fun api(
        server: FakeXmuJwServer,
        session: XmuSessionCookieHolder = XmuSessionCookieHolder(),
    ) = XmuJwScheduleApi(server.transport, session)

    @Test
    fun `semesters parse from emp envelope`() = runBlocking {
        val server = FakeXmuJwServer().apply { sessionValid = true }
        val result = api(server).fetchSemesters()

        val rows = (result as XmuJwApiResult.Ok).value
        assertEquals(2, rows.size)
        assertEquals("20261", rows[0].code)
        assertEquals("2026-2027学年 秋季学期", rows[0].label)
    }

    @Test
    fun `slot times keep api order and noon row`() = runBlocking {
        val server = FakeXmuJwServer().apply { sessionValid = true }
        val result = api(server).fetchSlotTimes("20261")

        val rows = (result as XmuJwApiResult.Ok).value
        // 原样返回（含中午行），剔除是映射器的职责
        assertEquals(6, rows.size)
        assertEquals("12", rows[4].code)
        assertEquals(800, rows[0].startTime)
    }

    @Test
    fun `current week parses leading form`() = runBlocking {
        val server = FakeXmuJwServer().apply { sessionValid = true }
        val result = api(server).fetchCurrentWeek("20261")

        assertEquals(8, (result as XmuJwApiResult.Ok).value)
    }

    @Test
    fun `schedule rows parse course fields`() = runBlocking {
        val server = FakeXmuJwServer().apply { sessionValid = true }
        val result = api(server).fetchSchedule("20261", "20260001")

        val rows = (result as XmuJwApiResult.Ok).value
        assertEquals(2, rows.size)
        assertEquals("大学物理B（下）", rows[0].courseName)
        assertEquals(2, rows[0].weekday)
        assertEquals(3, rows[0].startSlotCode)
        assertEquals(4, rows[0].endSlotCode)
        assertEquals(16, rows[0].weekBitmap?.count { it == '1' })
        // 学号随表单上送
        val pkjgRequest = server.requests.last { it.url.endsWith("queryXspkjg.do") }
        val formBody = pkjgRequest.formBody.orEmpty()
        assertTrue(formBody.contains("XH=20260001"))
        assertTrue(formBody.contains("ZC="))
    }

    @Test
    fun `session cookie from holder rides on requests`() = runBlocking {
        val server = FakeXmuJwServer().apply { sessionValid = true }
        val session = XmuSessionCookieHolder().apply { header = "JW_SESSION=S" }
        api(server, session).fetchSemesters()

        val request = server.requests.last { it.url.endsWith("kfdxnxqcx.do") }
        assertEquals("JW_SESSION=S", request.headers["Cookie"])
    }

    @Test
    fun `html response means session expired`() = runBlocking {
        val server = FakeXmuJwServer().apply {
            sessionValid = true
            semestersBody = "<html>login</html>"
        }
        val result = api(server).fetchSemesters()

        assertEquals(XmuJwApiResult.SessionExpired, result)
    }

    @Test
    fun `redirect to identity domain means session expired`() = runBlocking {
        val server = FakeXmuJwServer() // sessionValid = false → 302 到 CAS
        val result = api(server).fetchSemesters()

        assertEquals(XmuJwApiResult.SessionExpired, result)
    }

    @Test
    fun `redirect elsewhere is format changed`() = runBlocking {
        val transport = FakeXmuTransport {
            XmuHttpResponse(302, location = "https://jw.xmu.edu.cn/somewhere")
        }
        val result = XmuJwScheduleApi(transport, XmuSessionCookieHolder()).fetchSemesters()

        assertEquals(XmuJwApiResult.FormatChanged, result)
    }

    @Test
    fun `unauthorized and server error classifications`() = runBlocking {
        val unauthorized = FakeXmuTransport { XmuHttpResponse(401) }
        assertEquals(
            XmuJwApiResult.SessionExpired,
            XmuJwScheduleApi(unauthorized, XmuSessionCookieHolder()).fetchSemesters(),
        )
        val busy = FakeXmuTransport { XmuHttpResponse(503) }
        assertEquals(
            XmuJwApiResult.ServiceBusy,
            XmuJwScheduleApi(busy, XmuSessionCookieHolder()).fetchSemesters(),
        )
    }

    @Test
    fun `malformed json is format changed`() = runBlocking {
        val server = FakeXmuJwServer().apply {
            sessionValid = true
            semestersBody = """{"unexpected":true}"""
        }
        val result = api(server).fetchSemesters()

        assertEquals(XmuJwApiResult.FormatChanged, result)
    }
}
