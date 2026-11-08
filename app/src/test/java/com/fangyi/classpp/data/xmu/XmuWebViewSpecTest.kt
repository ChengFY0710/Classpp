package com.fangyi.classpp.data.xmu

import com.fangyi.classpp.data.sync.WebViewLoginSpec
import com.fangyi.classpp.data.sync.webview.WebViewPolicy
import com.fangyi.classpp.schools.xmu.XMU_WEB_VIEW_LOGIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 厦大 WebView 声明必须通过通用层的硬校验（指导 §2.3）：登录态确认口径、白名单、
 * 默认端口、端点路径逐项检查。这条测试是应用冷启动的安全网——声明一旦写错，
 * 注册表装配会抛异常、账号与同步页直接不可用。
 */
class XmuWebViewSpecTest {

    private val spec: WebViewLoginSpec = XMU_WEB_VIEW_LOGIN

    @Test
    fun `xmu web view spec passes generic validation`() {
        assertEquals(emptyList<String>(), WebViewPolicy.validate(spec))
    }

    @Test
    fun `xmu data host and endpoints are unchanged`() {
        assertEquals("lnt.xmu.edu.cn", spec.dataHost)
        assertTrue(spec.dataHost in spec.allowedHosts)
        assertEquals("/api/profile", spec.profilePath)
        assertEquals("/api/todos", spec.todosPath)
        assertEquals(
            setOf(
                "lnt.xmu.edu.cn",
                "ids.xmu.edu.cn",
                "c-identity.xmu.edu.cn",
                "pass.xmu.edu.cn",
            ),
            spec.allowedHosts,
        )
        assertTrue(spec.entryUrl.startsWith("https://lnt.xmu.edu.cn/"))
    }
}
