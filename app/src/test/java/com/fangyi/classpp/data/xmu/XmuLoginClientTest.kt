package com.fangyi.classpp.data.xmu

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 原生 OAuth 登录状态机（指导 §3.2）：成功链路、验证码、密码错误、各错误分类 */
class XmuLoginClientTest {

    private fun client(transport: XmuHttpTransport) = XmuLoginClient(transport, jvmCipher())

    @Test
    fun `happy path returns lnt cookies and submits encrypted password`() = runBlocking {
        val server = FakeXmuServer()
        val outcome = client(server.transport).login("20260001", "secret")

        val ok = outcome as XmuLoginOutcome.Ok
        assertEquals("S1", ok.cookies["session"])
        assertEquals("R1", ok.cookies["role_token"])
        assertTrue(ok.cookieHeader.contains("session=S1"))
        assertEquals(1, server.loginPosts)

        val form = server.loginForm!!
        assertTrue(form.contains("username=20260001"))
        assertTrue(form.contains("execution=e1s1"))
        assertTrue(form.contains("captcha="))
        assertTrue(form.contains("_eventId=submit"))
        assertTrue(form.contains("cllt=userNameLogin"))
        assertTrue(form.contains("dllt=generalLogin"))

        val encodedPassword = URLDecoder.decode(
            form.split("&").first { it.startsWith("password=") }.substringAfter("="),
            "UTF-8",
        )
        val plain = decrypt(encodedPassword, "0123456789abcdef")
        assertEquals(64 + "secret".length, plain.length)
        assertTrue(plain.endsWith("secret"))
    }

    @Test
    fun `captcha required stops before submitting password`() = runBlocking {
        val server = FakeXmuServer().apply { needCaptcha = true }
        val outcome = client(server.transport).login("20260001", "secret")

        assertEquals(XmuLoginOutcome.NeedCaptcha, outcome)
        assertEquals(0, server.loginPosts)
    }

    @Test
    fun `re-rendered login page means invalid credentials with a single attempt`() = runBlocking {
        val server = FakeXmuServer().apply { invalidCredentials = true }
        val outcome = client(server.transport).login("20260001", "wrong")

        assertEquals(XmuLoginOutcome.InvalidCredentials, outcome)
        assertEquals(1, server.loginPosts)
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
    fun `missing access token is format changed`() = runBlocking {
        val server = FakeXmuServer().apply { missingToken = true }
        assertEquals(XmuLoginOutcome.FormatChanged, client(server.transport).login("u", "p"))
    }

    @Test
    fun `login form carries lt and token request carries scope`() = runBlocking {
        val server = FakeXmuServer()
        client(server.transport).login("20260001", "secret")

        val form = server.loginForm!!
        assertTrue(form.contains("lt="))
        assertTrue(form.contains("execution=e1s1"))
        val tokenRequest = server.requests.first { it.url.contains("/protocol/openid-connect/token") }
        val tokenBody = requireNotNull(tokenRequest.formBody)
        assertTrue(tokenBody.contains("scope=openid"))
        assertTrue(tokenBody.contains("grant_type=authorization_code"))
        assertTrue(tokenBody.contains("code=CODE"))
    }

    @Test
    fun `no lnt cookies at all is format changed`() = runBlocking {
        val server = FakeXmuServer().apply { missingSession = true }
        assertEquals(XmuLoginOutcome.FormatChanged, client(server.transport).login("u", "p"))
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

    private fun decrypt(encoded: String, salt: String): String {
        // 校方 encrypt.js 输出纯密文；服务端用固定 IV 解密，前 64 位随机噪声被剥掉
        val ciphertext = Base64.getDecoder().decode(encoded)
        val aes = Cipher.getInstance("AES/CBC/PKCS5Padding")
        aes.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(salt.toByteArray(), "AES"),
            IvParameterSpec(ByteArray(16)),
        )
        return String(aes.doFinal(ciphertext), Charsets.UTF_8)
    }
}
