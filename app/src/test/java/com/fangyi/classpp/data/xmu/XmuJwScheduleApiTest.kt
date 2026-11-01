package com.fangyi.classpp.data.xmu

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 教务课表接口：真实抓包样本的解析、会话过期判定与错误分类 */
class XmuJwScheduleApiTest {

    private fun api(
        server: FakeXmuJwServer,
        jar: XmuCookieJar = XmuCookieJar(),
    ) = XmuJwScheduleApi(server.transport, jar)

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
        // 学号随表单上送；ZC 不传（与服务端网页一致，返回全学期全部记录）
        val pkjgRequest = server.requests.last { it.url.endsWith("queryXspkjg.do") }
        val formBody = pkjgRequest.formBody.orEmpty()
        assertTrue(formBody.contains("XH=20260001"))
        assertTrue(!formBody.contains("ZC="))
    }

    @Test
    fun `session cookie from jar rides on requests`() = runBlocking {
        val server = FakeXmuJwServer().apply { sessionValid = true }
        val jar = XmuCookieJar().apply {
            putCookies("jw.xmu.edu.cn", mapOf("JW_SESSION" to "S"))
        }
        api(server, jar).fetchSemesters()

        val request = server.requests.last { it.url.endsWith("kfdxnxqcx.do") }
        assertEquals("JW_SESSION=S", request.headers["Cookie"])
    }

    @Test
    fun `same host routing redirect is followed with newly issued cookies`() = runBlocking {
        // 真实形态：LB 首次请求下发 route_amp 并 302 回同一路径（相对 Location），
        // 带新 cookie 重放后才落到持有会话的节点
        val requests = mutableListOf<XmuHttpRequest>()
        val emapPath = "/gsapp/sys/wdkbapp/modules/xskcb/kfdxnxqcx.do"
        val transport = FakeXmuTransport { request ->
            requests += request
            val routeAmp = request.headers["Cookie"]?.contains("route_amp=1") == true
            when {
                // 第一次（无 route_amp）：下发路由 cookie 并 302 回同一路径（相对 Location）
                request.url.contains("kfdxnxqcx.do") && !routeAmp ->
                    XmuHttpResponse(
                        302,
                        location = emapPath,
                        setCookies = listOf("route_amp=1; Path=/"),
                    )
                // 带路由 cookie 重放：落到正确节点，返回数据
                request.url.contains("kfdxnxqcx.do") ->
                    XmuHttpResponse(200, body = XMU_JW_TEST_SEMESTERS)
                else -> error("unexpected ${request.method} ${request.url}")
            }
        }
        val result = XmuJwScheduleApi(transport, XmuCookieJar()).fetchSemesters()

        assertTrue(result is XmuJwApiResult.Ok)
        assertEquals(2, requests.size)
        assertEquals("POST", requests[0].method)
        assertEquals("GET", requests[1].method)
        assertEquals(emapPath, requests[0].url.substringAfter("jw.xmu.edu.cn"))
        assertEquals(emapPath, requests[1].url.substringAfter("jw.xmu.edu.cn"))
    }

    @Test
    fun `endpoints live inside the emap app context`() = runBlocking {
        // 根路径下的同名接口已随旧版教务下线（实测登录成功后 404）。目录两族：
        // 学期列表在 EMAP 标准模块 modules/xskcb/，课表三件套在应用自定义 wdkcb/
        //（与参考实现 democard/xmu_assistant 实测一致）
        val server = FakeXmuJwServer().apply { sessionValid = true }
        val jwApi = api(server)
        jwApi.fetchSemesters()
        jwApi.fetchSlotTimes("20261")
        jwApi.fetchCurrentWeek("20261")
        jwApi.fetchSchedule("20261", "20260001")

        val base = "https://jw.xmu.edu.cn/gsapp/sys/wdkbapp"
        val paths = server.requests.map { it.url }
        assertTrue(paths.contains("$base/modules/xskcb/kfdxnxqcx.do"))
        assertTrue(paths.contains("$base/wdkcb/queryXsskjc.do"))
        assertTrue(paths.contains("$base/wdkcb/getZcxx.do"))
        assertTrue(paths.contains("$base/wdkcb/queryXspkjg.do"))
        assertTrue(paths.none { it.startsWith("https://jw.xmu.edu.cn/modules/") })
        assertTrue(paths.none { it.startsWith("https://jw.xmu.edu.cn/wdkcb/") })
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
    fun `endless same host redirects are format changed`() = runBlocking {
        val transport = FakeXmuTransport {
            XmuHttpResponse(302, location = "/gsapp/sys/wdkbapp/modules/xskcb/kfdxnxqcx.do")
        }
        val result = XmuJwScheduleApi(transport, XmuCookieJar()).fetchSemesters()

        assertEquals(XmuJwApiResult.FormatChanged, result)
    }

    @Test
    fun `unauthorized and server error classifications`() = runBlocking {
        val unauthorized = FakeXmuTransport { XmuHttpResponse(401) }
        assertEquals(
            XmuJwApiResult.SessionExpired,
            XmuJwScheduleApi(unauthorized, XmuCookieJar()).fetchSemesters(),
        )
        val busy = FakeXmuTransport { XmuHttpResponse(503) }
        assertEquals(
            XmuJwApiResult.ServiceBusy,
            XmuJwScheduleApi(busy, XmuCookieJar()).fetchSemesters(),
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
