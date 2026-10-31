package com.fangyi.classpp.data.xmu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Cookie 罐：域匹配、删除、持久化恢复 */
class XmuCookieJarTest {

    @Test
    fun `host cookie is sent to lnt only`() {
        val jar = XmuCookieJar()
        jar.store("https://lnt.xmu.edu.cn/login", listOf("session=S1; Path=/; HttpOnly"))

        assertEquals("session=S1", jar.headerFor("https://lnt.xmu.edu.cn/api/profile"))
        assertNull(jar.headerFor("https://ids.xmu.edu.cn/authserver/login"))
    }

    @Test
    fun `domain cookie matches subdomains`() {
        val jar = XmuCookieJar()
        jar.store("https://lnt.xmu.edu.cn/login", listOf("token=T; Domain=.xmu.edu.cn; Path=/"))

        assertEquals("token=T", jar.headerFor("https://c-identity.xmu.edu.cn/x"))
        assertEquals("token=T", jar.headerFor("https://lnt.xmu.edu.cn/api/todos"))
    }

    @Test
    fun `max age zero removes the cookie`() {
        val jar = XmuCookieJar()
        jar.store("https://lnt.xmu.edu.cn/login", listOf("session=S1; Path=/"))
        jar.store("https://lnt.xmu.edu.cn/logout", listOf("session=gone; Max-Age=0; Path=/"))

        assertNull(jar.headerFor("https://lnt.xmu.edu.cn/api/profile"))
    }

    @Test
    fun `lnt cookies include parent domain cookies`() {
        val jar = XmuCookieJar()
        jar.store("https://lnt.xmu.edu.cn/login", listOf("global=G; Domain=xmu.edu.cn"))

        assertEquals(mapOf("global" to "G"), jar.lntCookies())
    }
}
