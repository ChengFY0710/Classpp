package com.fangyi.classpp.data.xmu

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 原生取数：Cookie 头、JSON/HTML/302/5xx/IO 分类 */
class XmuNativeTodoApiTest {

    private fun api(transport: XmuHttpTransport, cookie: String? = "session=S1"): XmuNativeTodoApi =
        XmuNativeTodoApi(
            transport = transport,
            session = XmuSessionCookieHolder().apply { header = cookie },
        )

    @Test
    fun `returns raw bodies and sends cookie on every request`() = runBlocking {
        val seen = mutableListOf<XmuHttpRequest>()
        val transport = FakeXmuTransport { request ->
            seen += request
            when (request.url) {
                "https://lnt.xmu.edu.cn/api/profile" ->
                    XmuHttpResponse(200, body = XMU_TEST_PROFILE)
                "https://lnt.xmu.edu.cn/api/todos" ->
                    XmuHttpResponse(200, body = XMU_TEST_TODOS)
                else -> error("unexpected ${request.url}")
            }
        }

        val result = api(transport).fetchSession()

        assertEquals(XmuFetchResult.Ok(XMU_TEST_PROFILE, XMU_TEST_TODOS), result)
        assertEquals(2, seen.size)
        assertTrue(seen.all { it.headers["Cookie"] == "session=S1" })
        assertTrue(seen.all { it.headers["Accept"] == "application/json" })
    }

    @Test
    fun `html profile means session expired`() = runBlocking {
        val transport = FakeXmuTransport { XmuHttpResponse(200, body = "<html>login</html>") }
        assertEquals(XmuFetchResult.SessionExpired, api(transport).fetchSession())
    }

    @Test
    fun `redirect to identity means session expired`() = runBlocking {
        val transport = FakeXmuTransport {
            XmuHttpResponse(302, location = "https://c-identity.xmu.edu.cn/auth")
        }
        assertEquals(XmuFetchResult.SessionExpired, api(transport).fetchSession())
    }

    @Test
    fun `unauthorized means session expired`() = runBlocking {
        val transport = FakeXmuTransport { XmuHttpResponse(401) }
        assertEquals(XmuFetchResult.SessionExpired, api(transport).fetchSession())
    }

    @Test
    fun `server error means busy`() = runBlocking {
        val transport = FakeXmuTransport { XmuHttpResponse(502) }
        assertEquals(XmuFetchResult.ServiceBusy, api(transport).fetchSession())
    }

    @Test
    fun `todos failure after valid profile is propagated`() = runBlocking {
        val transport = FakeXmuTransport { request ->
            if (request.url.endsWith("/api/profile")) {
                XmuHttpResponse(200, body = XMU_TEST_PROFILE)
            } else {
                XmuHttpResponse(500)
            }
        }
        assertEquals(XmuFetchResult.ServiceBusy, api(transport).fetchSession())
    }

    @Test
    fun `io exception means network error`() = runBlocking {
        val transport = FakeXmuTransport { throw IOException("offline") }
        assertEquals(XmuFetchResult.NetworkError, api(transport).fetchSession())
    }

    @Test
    fun `socket timeout means timeout`() = runBlocking {
        val transport = FakeXmuTransport { throw SocketTimeoutException("slow") }
        assertEquals(XmuFetchResult.Timeout, api(transport).fetchSession())
    }

    @Test
    fun `works without a cookie header`() = runBlocking {
        val seen = mutableListOf<XmuHttpRequest>()
        val transport = FakeXmuTransport { request ->
            seen += request
            when (request.url) {
                "https://lnt.xmu.edu.cn/api/profile" ->
                    XmuHttpResponse(200, body = XMU_TEST_PROFILE)
                "https://lnt.xmu.edu.cn/api/todos" ->
                    XmuHttpResponse(200, body = XMU_TEST_TODOS)
                else -> error("unexpected ${request.url}")
            }
        }

        assertEquals(
            XmuFetchResult.Ok(XMU_TEST_PROFILE, XMU_TEST_TODOS),
            api(transport, cookie = null).fetchSession(),
        )
        assertTrue(seen.none { it.headers.containsKey("Cookie") })
    }
}
