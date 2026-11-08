package com.fangyi.classpp.data.sync.credential

import com.fangyi.classpp.data.sync.AutoRenewPolicy
import com.fangyi.classpp.data.sync.AutoRenewState
import com.fangyi.classpp.data.sync.EnableOutcome
import com.fangyi.classpp.data.sync.LoginError
import com.fangyi.classpp.data.sync.LoginRaw
import com.fangyi.classpp.data.sync.engine.FakeRemoteTodo
import com.fangyi.classpp.data.sync.engine.FakeSessionApi
import com.fangyi.classpp.data.sync.engine.FetchError
import com.fangyi.classpp.data.sync.engine.FetchOutcome
import com.fangyi.classpp.data.sync.engine.SessionApi
import com.fangyi.classpp.data.sync.net.CookieMirror
import com.fangyi.classpp.data.sync.net.SessionCookieHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通用自动续期编排（指导 §2.3 / §四 Phase 4）：单飞、失败即停、瞬时退避、策略恢复归一化。
 * 与既有的厦大实现同纪律；这里用假登录客户端 + 假取数接口覆盖编排分支。
 */
class AutoRenewManagerTest {

    private class FakeSecureStore(
        var policy: AutoRenewPolicy = AutoRenewPolicy.USER_LOGGED_OUT,
        var credentials: StoredCredentials? = null,
        var cookie: String? = null,
        override var available: Boolean = true,
    ) : SecureStore {
        var saveFailed = false

        override fun loadPolicy(): AutoRenewPolicy = policy

        override fun savePolicy(policy: AutoRenewPolicy): Boolean {
            if (saveFailed) return false
            this.policy = policy
            return true
        }

        override fun loadCredentials(): StoredCredentials? = credentials

        override fun saveCredentials(credentials: StoredCredentials?): Boolean {
            if (saveFailed) return false
            this.credentials = credentials
            return true
        }

        override fun loadCookieHeader(): String? = cookie

        override fun saveCookieHeader(header: String?): Boolean {
            if (saveFailed) return false
            cookie = header
            return true
        }

        override fun clear(): Boolean {
            credentials = null
            cookie = null
            return true
        }
    }

    private class FakeLoginClient : LoginClient {
        var result: LoginResult = LoginResult.Ok(LoginSession("session=S1"))
        var calls = 0
            private set
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun login(username: String, password: String): LoginResult {
            calls++
            gate?.await()
            return result
        }
    }

    private class FakeCookieMirror : CookieMirror {
        var host: String? = null
        var cookies: Map<String, String> = emptyMap()

        override fun mirror(host: String, cookies: Map<String, String>) {
            this.host = host
            this.cookies = cookies
        }
    }

    private var nowMillis = 1_000_000L

    private data class Harness(
        val store: FakeSecureStore,
        val login: FakeLoginClient,
        val api: FakeSessionApi,
        val holder: SessionCookieHolder,
        val mirror: FakeCookieMirror,
        val manager: AutoRenewManager,
    )

    private fun harness(
        store: FakeSecureStore = FakeSecureStore(),
        api: FakeSessionApi = FakeSessionApi().apply {
            respond(todos = emptyList<FakeRemoteTodo>())
        },
    ): Harness {
        val login = FakeLoginClient()
        val holder = SessionCookieHolder()
        val mirror = FakeCookieMirror()
        return Harness(
            store = store,
            login = login,
            api = api,
            holder = holder,
            mirror = mirror,
            manager = AutoRenewManager(
                store = store,
                loginClient = login,
                sessionApi = api,
                cookieHolder = holder,
                cookieMirror = mirror,
                mirrorHost = "lms.example.test",
                nowMillis = { nowMillis },
            ),
        )
    }

    @Test
    fun `enable stores credentials cookie and returns fetched raw`() = runBlocking {
        val h = harness()
        h.login.result = LoginResult.Ok(
            LoginSession("session=S1; role=R1", mapOf("session" to "S1")),
        )

        val outcome = h.manager.enable(" 20260001 ", "secret")

        assertTrue(outcome is EnableOutcome.Ready)
        assertEquals("20260001", h.store.credentials?.username)
        assertEquals("secret", h.store.credentials?.password)
        assertEquals(AutoRenewPolicy.ENABLED, h.store.policy)
        assertEquals("session=S1; role=R1", h.store.cookie)
        assertEquals("session=S1; role=R1", h.holder.header)
        assertEquals("lms.example.test", h.mirror.host)
        assertEquals(mapOf("session" to "S1"), h.mirror.cookies)
        assertEquals(AutoRenewState(AutoRenewPolicy.ENABLED, true, true), h.manager.state.value)
    }

    @Test
    fun `invalid credentials blocks once and clears stored secrets`() = runBlocking {
        val store = FakeSecureStore(
            policy = AutoRenewPolicy.ENABLED,
            credentials = StoredCredentials("u", "p"),
            cookie = "session=OLD",
        )
        val h = harness(store)
        h.login.result = LoginResult.InvalidCredentials

        val outcome = h.manager.enable("u", "p")

        assertEquals(EnableOutcome.Failed(LoginError.InvalidCredentials), outcome)
        assertEquals(AutoRenewPolicy.BLOCKED, store.policy)
        assertNull(store.credentials)
        assertNull(store.cookie)
        assertNull(h.holder.header)
    }

    @Test
    fun `captcha blocks and never retries automatically`() = runBlocking {
        val h = harness()
        h.login.result = LoginResult.NeedCaptcha

        assertEquals(EnableOutcome.Failed(LoginError.NeedCaptcha), h.manager.enable("u", "p"))
        assertEquals(AutoRenewPolicy.BLOCKED, h.store.policy)
        assertEquals(1, h.login.calls)
    }

    @Test
    fun `login ok but session still expired blocks as not established`() = runBlocking {
        val api = FakeSessionApi().apply {
            outcome = FetchOutcome.Failed(FetchError.SessionExpired)
        }
        val h = harness(api = api)

        val outcome = h.manager.enable("u", "p")

        assertEquals(EnableOutcome.Failed(LoginError.SessionNotEstablished), outcome)
        assertEquals(AutoRenewPolicy.BLOCKED, h.store.policy)
        assertNull(h.store.credentials)
    }

    @Test
    fun `storage unavailable fails fast`() = runBlocking {
        val h = harness(FakeSecureStore(available = false))

        assertEquals(
            EnableOutcome.Failed(LoginError.StorageUnavailable),
            h.manager.enable("u", "p"),
        )
        assertEquals(0, h.login.calls)
    }

    @Test
    fun `disable clears credentials but keeps policy logged out`() = runBlocking {
        val store = FakeSecureStore(
            policy = AutoRenewPolicy.ENABLED,
            credentials = StoredCredentials("u", "p"),
            cookie = "session=S1",
        )
        val h = harness(store).also { it.holder.header = "session=S1" }

        h.manager.disable()

        assertEquals(AutoRenewPolicy.USER_LOGGED_OUT, store.policy)
        assertNull(store.credentials)
        assertNull(h.holder.header)
        assertEquals(AutoRenewState(AutoRenewPolicy.USER_LOGGED_OUT, false, true), h.manager.state.value)
    }

    @Test
    fun `bootstrap normalizes enabled without credentials to blocked`() = runBlocking {
        val store = FakeSecureStore(policy = AutoRenewPolicy.ENABLED, credentials = null)
        val h = harness(store)

        h.manager.bootstrap()

        assertEquals(AutoRenewPolicy.BLOCKED, store.policy)
        assertEquals(AutoRenewPolicy.BLOCKED, h.manager.state.value.policy)
        assertFalse(h.manager.state.value.hasCredentials)
    }

    @Test
    fun `bootstrap clears stale secrets when logged out`() = runBlocking {
        val store = FakeSecureStore(
            policy = AutoRenewPolicy.USER_LOGGED_OUT,
            credentials = StoredCredentials("u", "p"),
            cookie = "session=STALE",
        )
        val h = harness(store)

        h.manager.bootstrap()

        assertNull(store.credentials)
        assertNull(store.cookie)
        assertNull(h.holder.header)
    }

    @Test
    fun `bootstrap restores cookie holder and state`() = runBlocking {
        val store = FakeSecureStore(
            policy = AutoRenewPolicy.ENABLED,
            credentials = StoredCredentials("u", "p"),
            cookie = "session=S1",
        )
        val h = harness(store)

        h.manager.bootstrap()

        assertEquals("session=S1", h.holder.header)
        assertEquals(AutoRenewState(AutoRenewPolicy.ENABLED, true, true), h.manager.state.value)
    }

    @Test
    fun `fetch session refuses when auto renew is off`() = runBlocking {
        val h = harness()

        assertEquals(
            FetchOutcome.Failed(FetchError.SessionExpired),
            h.manager.fetchSession(),
        )
        assertEquals(0, h.login.calls)
    }

    @Test
    fun `existing cookie probes first and avoids login`() = runBlocking {
        val api = FakeSessionApi().apply { respond(todos = emptyList()) }
        val store = FakeSecureStore(
            policy = AutoRenewPolicy.ENABLED,
            credentials = StoredCredentials("u", "p"),
            cookie = "session=S1",
        )
        val h = harness(store, api).also { it.holder.header = "session=S1" }
        h.manager.bootstrap()

        val outcome = h.manager.fetchSession()

        assertTrue(outcome is FetchOutcome.Ok)
        assertEquals(1, api.calls)
        assertEquals(0, h.login.calls)
    }

    @Test
    fun `expired probe relogs once and refreshes cookie`() = runBlocking {
        val store = FakeSecureStore(
            policy = AutoRenewPolicy.ENABLED,
            credentials = StoredCredentials("u", "p"),
            cookie = "session=OLD",
        )
        val login = FakeLoginClient().apply {
            result = LoginResult.Ok(LoginSession("session=NEW"))
        }
        val holder = SessionCookieHolder().apply { header = "session=OLD" }
        val mirror = FakeCookieMirror()
        val scripted = object : SessionApi {
            var calls = 0
            override suspend fun fetchSession(): FetchOutcome {
                calls++
                return if (calls == 1) {
                    FetchOutcome.Failed(FetchError.SessionExpired)
                } else {
                    FetchOutcome.Ok(LoginRaw("9001\t张三", ""))
                }
            }
        }
        val manager = AutoRenewManager(
            store = store,
            loginClient = login,
            sessionApi = scripted,
            cookieHolder = holder,
            cookieMirror = mirror,
            mirrorHost = "lms.example.test",
            nowMillis = { nowMillis },
        )
        manager.bootstrap()

        val outcome = manager.fetchSession()

        assertTrue(outcome is FetchOutcome.Ok)
        assertEquals(1, login.calls)
        assertEquals("session=NEW", store.cookie)
        assertEquals("session=NEW", holder.header)
        assertEquals(2, scripted.calls)
    }

    @Test
    fun `transient login failure backs off before another attempt`() = runBlocking {
        val store = FakeSecureStore(
            policy = AutoRenewPolicy.ENABLED,
            credentials = StoredCredentials("u", "p"),
            cookie = null,
        )
        val h = harness(store)
        h.login.result = LoginResult.NetworkError
        h.manager.bootstrap()

        assertEquals(FetchOutcome.Failed(FetchError.NetworkError), h.manager.fetchSession())
        assertEquals(1, h.login.calls)

        // 退避期内不再打身份域
        nowMillis += 30_000
        assertEquals(FetchOutcome.Failed(FetchError.NetworkError), h.manager.fetchSession())
        assertEquals(1, h.login.calls)

        // 退避到期后允许重试
        nowMillis += 31_000
        h.manager.fetchSession()
        assertEquals(2, h.login.calls)
        assertEquals(AutoRenewPolicy.ENABLED, store.policy)
    }

    @Test
    fun `relogin auth failure blocks and drops credentials`() = runBlocking {
        val store = FakeSecureStore(
            policy = AutoRenewPolicy.ENABLED,
            credentials = StoredCredentials("u", "p"),
            cookie = null,
        )
        val h = harness(store)
        h.login.result = LoginResult.InvalidCredentials
        h.manager.bootstrap()

        assertEquals(FetchOutcome.Failed(FetchError.SessionExpired), h.manager.fetchSession())
        assertEquals(AutoRenewPolicy.BLOCKED, store.policy)
        assertNull(store.credentials)
        assertEquals(AutoRenewPolicy.BLOCKED, h.manager.state.value.policy)
    }

    @Test
    fun `busy is true while enable is in flight`() = runBlocking {
        val h = harness()
        val gate = CompletableDeferred<Unit>()
        h.login.gate = gate

        val job = async { h.manager.enable("u", "p") }
        // 让协程跑到登录挂起点
        while (h.login.calls == 0) kotlinx.coroutines.yield()
        assertTrue(h.manager.busy.value)

        gate.complete(Unit)
        assertTrue(job.await() is EnableOutcome.Ready)
        assertFalse(h.manager.busy.value)
    }
}
