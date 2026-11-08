package com.fangyi.classpp.data.sync.remote

import com.fangyi.classpp.data.sync.Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远端 provider 的装配前置：声明 → spec / WebView 声明的纯数据转换（不触碰 Android），
 * 以及坏声明在构造期即被拒绝（注册表不会拿到半成品）。
 */
class RemoteSchoolProviderTest {

    @Test
    fun `provider exposes inline remote spec and web view host`() {
        val provider = RemoteSchoolProvider(demoDeclaration())

        assertEquals("demo", provider.spec.id)
        assertEquals(setOf(Capability.WEBVIEW_LOGIN), provider.spec.capabilities)
        assertTrue(provider.spec.remote)
        assertEquals("lms.demo.edu.cn", provider.webViewLoginSpec?.dataHost)
        assertEquals("示例大学", provider.spec.displayName.let {
            (it as com.fangyi.classpp.data.sync.SchoolText.Inline).zh
        })
    }

    @Test
    fun `invalid declaration is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteSchoolProvider(demoDeclaration().copy(id = "Bad"))
        }
    }
}
