package com.fangyi.classpp.data.xmu

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 原生/WebView 路由：只有 ENABLED + 有凭据 + Keystore 可用才走原生 */
class XmuTodoApiRouterTest {

    private class RecordingWebApi : XmuTodoApi {
        var calls = 0

        override suspend fun fetchSession(): XmuFetchResult {
            calls++
            return XmuFetchResult.Ok("{}", "{}")
        }
    }

    @Test
    fun `auto login off delegates to web fetcher`() = runBlocking {
        val h = XmuTestHarness(
            policy = XmuAutoLoginPolicy.USER_LOGGED_OUT,
            credentials = null,
            cookie = null,
        )
        h.manager.bootstrap()
        val web = RecordingWebApi()

        val result = XmuTodoApiRouter(web, h.manager).fetchSession()

        assertTrue(result is XmuFetchResult.Ok)
        assertEquals(1, web.calls)
        assertEquals(0, h.server.totalRequests)
    }

    @Test
    fun `enabled with credentials uses native session`() = runBlocking {
        val h = XmuTestHarness(sessionValid = true)
        h.manager.bootstrap()
        val web = RecordingWebApi()

        val result = XmuTodoApiRouter(web, h.manager).fetchSession()

        assertTrue(result is XmuFetchResult.Ok)
        assertEquals(0, web.calls)
        assertTrue(h.server.totalRequests > 0)
    }

    @Test
    fun `blocked policy delegates to web fetcher`() = runBlocking {
        val h = XmuTestHarness(
            policy = XmuAutoLoginPolicy.BLOCKED,
            credentials = null,
            cookie = null,
        )
        h.manager.bootstrap()
        val web = RecordingWebApi()

        XmuTodoApiRouter(web, h.manager).fetchSession()

        assertEquals(1, web.calls)
        assertEquals(0, h.server.totalRequests)
    }

    @Test
    fun `unavailable keystore delegates to web fetcher`() = runBlocking {
        val h = XmuTestHarness(sessionValid = true)
        h.store.available = false
        h.manager.bootstrap()
        val web = RecordingWebApi()

        XmuTodoApiRouter(web, h.manager).fetchSession()

        assertEquals(1, web.calls)
        assertEquals(0, h.server.totalRequests)
    }
}
