package com.fangyi.classpp.data.xmu

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自动续期状态机（指导 §6.2/§6.3）：单飞、失败即停、瞬时错误退避 */
class XmuAutoLoginManagerTest {

    @Test
    fun `valid session does not log in`() = runBlocking {
        val h = XmuTestHarness(sessionValid = true)
        h.manager.bootstrap()

        val result = h.manager.fetchSession()

        assertTrue(result is XmuFetchResult.Ok)
        assertEquals(0, h.server.loginPosts)
        assertEquals(XmuAutoLoginPolicy.ENABLED, h.manager.state.value.policy)
    }

    @Test
    fun `expired session logs in once and refetches`() = runBlocking {
        val h = XmuTestHarness(sessionValid = false)
        h.manager.bootstrap()

        val result = h.manager.fetchSession()

        assertTrue(result is XmuFetchResult.Ok)
        assertEquals(1, h.server.loginPosts)
        assertEquals(XmuAutoLoginPolicy.ENABLED, h.manager.state.value.policy)
        assertTrue(h.manager.state.value.hasCredentials)
        assertNotNull(h.store.cookie)
    }

    @Test
    fun `missing cookie logs in without a doomed probe`() = runBlocking {
        val h = XmuTestHarness(sessionValid = false, cookie = null)
        h.manager.bootstrap()

        val result = h.manager.fetchSession()

        assertTrue(result is XmuFetchResult.Ok)
        assertEquals(1, h.server.loginPosts)
    }

    @Test
    fun `concurrent syncs share a single login`() = runBlocking {
        val h = XmuTestHarness(sessionValid = false)
        h.server.loginDelayMillis = 20
        h.manager.bootstrap()

        val results = coroutineScope {
            listOf(
                async { h.manager.fetchSession() },
                async { h.manager.fetchSession() },
            ).awaitAll()
        }

        assertTrue(results.all { it is XmuFetchResult.Ok })
        assertEquals(1, h.server.loginPosts)
    }

    @Test
    fun `captcha blocks and deletes credentials without submitting password`() = runBlocking {
        val h = XmuTestHarness(sessionValid = false)
        h.server.needCaptcha = true
        h.manager.bootstrap()

        val result = h.manager.fetchSession()

        assertEquals(XmuFetchResult.SessionExpired, result)
        assertEquals(XmuAutoLoginPolicy.BLOCKED, h.manager.state.value.policy)
        assertFalse(h.manager.state.value.hasCredentials)
        assertNull(h.store.credentials)
        assertEquals(0, h.server.loginPosts)
    }

    @Test
    fun `wrong password blocks after one attempt`() = runBlocking {
        val h = XmuTestHarness(sessionValid = false)
        h.server.invalidCredentials = true
        h.manager.bootstrap()

        val result = h.manager.fetchSession()

        assertEquals(XmuFetchResult.SessionExpired, result)
        assertEquals(1, h.server.loginPosts)
        assertEquals(XmuAutoLoginPolicy.BLOCKED, h.manager.state.value.policy)
        assertNull(h.store.credentials)
    }

    @Test
    fun `transient failure keeps policy and backs off for 60 seconds`() = runBlocking {
        val h = XmuTestHarness(sessionValid = false)
        h.manager.bootstrap()
        h.server.networkDown = true

        assertEquals(XmuFetchResult.NetworkError, h.manager.fetchSession())
        val requestsAfterFailure = h.server.totalRequests
        assertEquals(XmuAutoLoginPolicy.ENABLED, h.manager.state.value.policy)
        assertNotNull(h.store.credentials)

        // 退避期内重复点击不再打网络
        assertEquals(XmuFetchResult.NetworkError, h.manager.fetchSession())
        assertEquals(requestsAfterFailure, h.server.totalRequests)

        h.now += 60_001
        h.server.networkDown = false
        assertTrue(h.manager.fetchSession() is XmuFetchResult.Ok)
        assertTrue(h.server.totalRequests > requestsAfterFailure)
    }

    @Test
    fun `clock rollback does not pin the backoff forever`() = runBlocking {
        val h = XmuTestHarness(sessionValid = false)
        h.manager.bootstrap()
        h.server.networkDown = true
        assertEquals(XmuFetchResult.NetworkError, h.manager.fetchSession())

        h.server.networkDown = false
        h.now -= 3_600_000 // 系统时间回拨一小时
        assertTrue(h.manager.fetchSession() is XmuFetchResult.Ok)
    }

    @Test
    fun `login succeeds but session stays expired blocks`() = runBlocking {
        val h = XmuTestHarness(sessionValid = false)
        h.server.loginSucceedsButSessionStaysInvalid = true
        h.manager.bootstrap()

        val result = h.manager.fetchSession()

        assertEquals(XmuFetchResult.SessionExpired, result)
        assertEquals(XmuAutoLoginPolicy.BLOCKED, h.manager.state.value.policy)
        assertNull(h.store.credentials)
    }

    @Test
    fun `enable saves credentials and returns the fetched session`() = runBlocking {
        val h = XmuTestHarness(
            policy = XmuAutoLoginPolicy.USER_LOGGED_OUT,
            credentials = null,
            cookie = null,
        )
        h.manager.bootstrap()

        val outcome = h.manager.enable("20260001", "secret")

        assertTrue(outcome is XmuEnableOutcome.Ready)
        assertEquals("20260001", h.store.credentials?.username)
        assertEquals("secret", h.store.credentials?.password)
        assertEquals(XmuAutoLoginPolicy.ENABLED, h.store.policy)
        assertEquals(XmuAutoLoginPolicy.ENABLED, h.manager.state.value.policy)
        assertNotNull(h.store.cookie)
    }

    @Test
    fun `enable with captcha blocks and saves nothing`() = runBlocking {
        val h = XmuTestHarness(
            policy = XmuAutoLoginPolicy.USER_LOGGED_OUT,
            credentials = null,
            cookie = null,
        )
        h.server.needCaptcha = true
        h.manager.bootstrap()

        val outcome = h.manager.enable("20260001", "secret")

        assertEquals(XmuEnableOutcome.Failed(XmuAutoLoginError.NeedCaptcha), outcome)
        assertEquals(XmuAutoLoginPolicy.BLOCKED, h.store.policy)
        assertNull(h.store.credentials)
    }

    @Test
    fun `enable with wrong password blocks`() = runBlocking {
        val h = XmuTestHarness(
            policy = XmuAutoLoginPolicy.USER_LOGGED_OUT,
            credentials = null,
            cookie = null,
        )
        h.server.invalidCredentials = true
        h.manager.bootstrap()

        assertEquals(
            XmuEnableOutcome.Failed(XmuAutoLoginError.InvalidCredentials),
            h.manager.enable("20260001", "wrong"),
        )
        assertEquals(XmuAutoLoginPolicy.BLOCKED, h.store.policy)
        assertNull(h.store.credentials)
    }

    @Test
    fun `enable reports storage unavailable without saving`() = runBlocking {
        val h = XmuTestHarness(
            policy = XmuAutoLoginPolicy.USER_LOGGED_OUT,
            credentials = null,
            cookie = null,
        )
        h.manager.bootstrap()
        h.store.saveFailed = true

        val outcome = h.manager.enable("20260001", "secret")

        assertEquals(XmuEnableOutcome.Failed(XmuAutoLoginError.StorageUnavailable), outcome)
        assertNull(h.store.credentials)
        assertEquals(XmuAutoLoginPolicy.USER_LOGGED_OUT, h.manager.state.value.policy)
    }

    @Test
    fun `disable clears credentials and policy`() = runBlocking {
        val h = XmuTestHarness(sessionValid = true)
        h.manager.bootstrap()

        h.manager.disable()

        assertEquals(XmuAutoLoginPolicy.USER_LOGGED_OUT, h.manager.state.value.policy)
        assertFalse(h.manager.state.value.hasCredentials)
        assertNull(h.store.credentials)
        assertNull(h.store.cookie)
    }

    @Test
    fun `bootstrap normalizes enabled without credentials to blocked`() = runBlocking {
        val store = FakeXmuSecureStore(
            policy = XmuAutoLoginPolicy.ENABLED,
            credentials = null,
            cookie = null,
        )
        val server = FakeXmuServer()
        val session = XmuSessionCookieHolder()
        val manager = XmuAutoLoginManager(
            store = store,
            loginClient = XmuLoginClient(server.transport, jvmCipher()),
            nativeApi = XmuNativeTodoApi(server.transport, session),
            session = session,
            cookieMirror = XmuCookieMirror {},
            nowMillis = { 1_000L },
        )

        manager.bootstrap()

        assertEquals(XmuAutoLoginPolicy.BLOCKED, manager.state.value.policy)
        assertEquals(XmuAutoLoginPolicy.BLOCKED, store.policy)
        assertFalse(manager.state.value.hasCredentials)
    }

    @Test
    fun `disabled policy never touches the network`() = runBlocking {
        val h = XmuTestHarness(
            policy = XmuAutoLoginPolicy.USER_LOGGED_OUT,
            credentials = null,
            cookie = null,
        )
        h.manager.bootstrap()

        assertEquals(XmuFetchResult.SessionExpired, h.manager.fetchSession())
        assertEquals(0, h.server.totalRequests)
    }
}
