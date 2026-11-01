package com.fangyi.classpp.data.xmu

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 教务站（jw）CAS 登录：成功链路、验证码、密码错误、各错误分类与提交纪律 */
class XmuJwLoginClientTest {

    private fun client(transport: XmuHttpTransport) = XmuJwLoginClient(transport, jvmCipher())

    @Test
    fun `happy path lands back on jw with cookies and submits encrypted password once`() = runBlocking {
        val server = FakeXmuJwServer()
        val outcome = client(server.transport).login("20260001", "secret")

        val ok = outcome as XmuLoginOutcome.Ok
        assertEquals("S", ok.cookies["JW_SESSION"])
        assertTrue(ok.cookieHeader.contains("JW_SESSION=S"))
        assertEquals(1, server.loginPosts)

        val form = server.loginForm!!
        assertTrue(form.contains("username=20260001"))
        assertTrue(form.contains("execution=e1s1"))
        assertTrue(form.contains("_eventId=submit"))
        assertTrue(form.contains("lt=lt-token"))
        assertTrue(form.contains("dllt=generalLogin"))
        assertTrue(form.contains("rmShown=1"))
        // 教务链路的密码同样走 AES 加密（密文非明文）
        val encodedPassword = URLDecoder.decode(
            form.split("&").first { it.startsWith("password=") }.substringAfter("="),
            "UTF-8",
        )
        assertTrue(!encodedPassword.contains("secret"))
    }

    @Test
    fun `captcha required stops before submitting password`() = runBlocking {
        val server = FakeXmuJwServer().apply { needCaptcha = true }
        val outcome = client(server.transport).login("20260001", "secret")

        assertEquals(XmuLoginOutcome.NeedCaptcha, outcome)
        assertEquals(0, server.loginPosts)
    }

    @Test
    fun `re-rendered login page means invalid credentials with a single attempt`() = runBlocking {
        val server = FakeXmuJwServer().apply { invalidCredentials = true }
        val outcome = client(server.transport).login("20260001", "wrong")

        assertEquals(XmuLoginOutcome.InvalidCredentials, outcome)
        assertEquals(1, server.loginPosts)
    }

    @Test
    fun `401 rendered login page is invalid credentials not format changed`() = runBlocking {
        // 本部署的 CAS 对失败登录返回 401 + 重渲染登录页（可能附验证码错误提示）
        val transport = FakeXmuTransport { request ->
            when {
                request.url.startsWith("https://jw.xmu.edu.cn/") ->
                    XmuHttpResponse(302, location = XMU_JW_TEST_LOGIN_REDIRECT)
                request.url.contains("checkNeedCaptcha") ->
                    XmuHttpResponse(200, body = """{"isNeed":false}""")
                request.url.contains("authserver/login") && request.method == "POST" ->
                    XmuHttpResponse(401, body = XMU_TEST_LOGIN_HTML)
                request.url.contains("authserver/login") ->
                    XmuHttpResponse(200, body = XMU_TEST_LOGIN_HTML)
                else -> error("unexpected ${request.method} ${request.url}")
            }
        }

        assertEquals(XmuLoginOutcome.InvalidCredentials, client(transport).login("u", "p"))
    }

    @Test
    fun `403 landing still succeeds when ticket issued a jw session cookie`() = runBlocking {
        // 真实链路形态：ticket 校验已下发 GS_SESSIONID，但落地页被拒 403。
        // 只要 jw 域已有会话类 cookie 就按成功返回，交给业务请求最终判定
        val transport = FakeXmuTransport { request ->
            when {
                request.url.startsWith("https://jw.xmu.edu.cn/") -> when {
                    request.url.contains("ticket=") ->
                        XmuHttpResponse(
                            302,
                            location = "https://jw.xmu.edu.cn/gsapp/sys/wdkbapp/*default/index.do",
                            setCookies = listOf("GS_SESSIONID=G1; Path=/"),
                        )
                    else -> XmuHttpResponse(403, body = "<html>denied</html>")
                }
                request.url.contains("checkNeedCaptcha") ->
                    XmuHttpResponse(200, body = """{"isNeed":false}""")
                request.url.contains("authserver/login") && request.method == "POST" ->
                    XmuHttpResponse(
                        302,
                        location = "https://jw.xmu.edu.cn/gsapp/sys/wdkbapp/*default/index.do?ticket=ST-1",
                    )
                request.url.contains("authserver/login") ->
                    XmuHttpResponse(200, body = XMU_TEST_LOGIN_HTML)
                else -> error("unexpected ${request.method} ${request.url}")
            }
        }

        val ok = client(transport).login("u", "p") as XmuLoginOutcome.Ok
        assertEquals("G1", ok.cookies["GS_SESSIONID"])
    }

    @Test
    fun `cas page without form fields is format changed`() = runBlocking {
        // 入口直达 CAS：拿到不含 execution/salt 的 200 页 → 链路改版
        val transport = FakeXmuTransport { request ->
            when {
                request.url.contains("authserver/login") ->
                    XmuHttpResponse(200, body = "<html>no login form</html>")
                else -> error("unexpected ${request.method} ${request.url}")
            }
        }
        assertEquals(XmuLoginOutcome.FormatChanged, client(transport).login("u", "p"))
    }

    @Test
    fun `server error is classified as busy`() = runBlocking {
        val transport = FakeXmuTransport { XmuHttpResponse(503) }
        assertEquals(XmuLoginOutcome.ServiceBusy, client(transport).login("u", "p"))
    }

    @Test
    fun `redirect outside school hosts is rejected`() = runBlocking {
        val transport = FakeXmuTransport {
            XmuHttpResponse(302, location = "https://evil.example/steal")
        }
        assertEquals(XmuLoginOutcome.FormatChanged, client(transport).login("u", "p"))
    }

    @Test
    fun `socket timeout is timeout`() = runBlocking {
        val transport = FakeXmuTransport { throw SocketTimeoutException("slow") }
        assertEquals(XmuLoginOutcome.Timeout, client(transport).login("u", "p"))
    }

    @Test
    fun `io exception is network error`() = runBlocking {
        val transport = FakeXmuTransport { throw IOException("offline") }
        assertEquals(XmuLoginOutcome.NetworkError, client(transport).login("u", "p"))
    }
}
