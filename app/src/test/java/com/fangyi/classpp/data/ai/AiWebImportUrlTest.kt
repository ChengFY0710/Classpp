package com.fangyi.classpp.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 教务网址归一化：补协议、协议白名单、host 与 userinfo 校验 */
class AiWebImportUrlTest {

    @Test
    fun `adds https when scheme is missing`() {
        assertEquals("https://jw.example.edu.cn", AiWebImportUrl.normalize("jw.example.edu.cn"))
        assertEquals(
            "https://jw.example.edu.cn/path?x=1",
            AiWebImportUrl.normalize("  jw.example.edu.cn/path?x=1  "),
        )
    }

    @Test
    fun `keeps http and https schemes`() {
        assertEquals("http://jw.example.edu.cn", AiWebImportUrl.normalize("http://jw.example.edu.cn"))
        assertEquals(
            "https://jw.example.edu.cn:8443/login",
            AiWebImportUrl.normalize("https://jw.example.edu.cn:8443/login"),
        )
        // 裸 host + 端口：补 https 后仍合法
        assertEquals("https://jw.example.edu.cn:8080", AiWebImportUrl.normalize("jw.example.edu.cn:8080"))
    }

    @Test
    fun `rejects non http schemes`() {
        assertNull(AiWebImportUrl.normalize("ftp://jw.example.edu.cn"))
        assertNull(AiWebImportUrl.normalize("file:///sdcard/a.html"))
        assertNull(AiWebImportUrl.normalize("content://com.example/a"))
        assertNull(AiWebImportUrl.normalize("intent://scan/#Intent"))
        assertNull(AiWebImportUrl.normalize("javascript:alert(1)"))
        assertNull(AiWebImportUrl.normalize("data:text/html,hi"))
    }

    @Test
    fun `rejects userinfo and blank input`() {
        assertNull(AiWebImportUrl.normalize("https://user:pass@jw.example.edu.cn"))
        assertNull(AiWebImportUrl.normalize(""))
        assertNull(AiWebImportUrl.normalize("   "))
        assertNull(AiWebImportUrl.normalize("https://"))
        assertNull(AiWebImportUrl.normalize("jw.example.edu.cn/a b"))
    }
}
