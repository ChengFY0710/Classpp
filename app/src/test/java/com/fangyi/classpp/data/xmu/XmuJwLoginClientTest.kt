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
        assertTrue(form.contains("cllt=userNameLogin"))
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
    fun `entry page without cas round trip is format changed`() = runBlocking {
        val server = FakeXmuJwServer().apply { sessionValid = true }
        // 入口直接 200（无 CAS 往返）拿不到教务会话：按改版处理
        assertEquals(XmuLoginOutcome.FormatChanged, client(server.transport).login("u", "p"))
        assertEquals(0, server.loginPosts)
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
