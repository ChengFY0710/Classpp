package com.fangyi.classpp.data.sync.webview

import com.fangyi.classpp.data.sync.WebViewLoginSpec
import com.fangyi.classpp.data.sync.WebViewTimetableSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebView 声明校验与加固判定（指导 §2.3/§四 Phase 4）：仅 https、默认 443、禁 userinfo/
 * IP/通配符、dataHost ∈ allowedHosts、跨校白名单不重叠；加固项没有 spec 开关。
 *
 * 纯函数测试（不依赖 Android WebView），评审不依赖真机。
 */
class WebViewPolicyTest {

    private fun validSpec(
        entryUrl: String = "https://lms.example.test/user/index?version=v1",
        allowedHosts: Set<String> = setOf("lms.example.test", "ids.example.test"),
        dataHost: String = "lms.example.test",
        profilePath: String = "/api/profile",
        todosPath: String = "/api/todos",
        userAgent: String? = "Mozilla/5.0",
    ) = WebViewLoginSpec(entryUrl, allowedHosts, dataHost, profilePath, todosPath, userAgent)

    @Test
    fun `valid spec passes`() {
        assertEquals(emptyList<String>(), WebViewPolicy.validate(validSpec()))
    }

    @Test
    fun `entry url must use https`() {
        val issues = WebViewPolicy.validate(validSpec(entryUrl = "http://lms.example.test/login"))
        assertTrue(issues.any { it.contains("entryUrl must use https") })
    }

    @Test
    fun `entry url must use default https port`() {
        val issues = WebViewPolicy.validate(
            validSpec(entryUrl = "https://lms.example.test:8443/login"),
        )
        assertTrue(issues.any { it.contains("default https port") })
        // 显式 443 合法
        assertEquals(
            emptyList<String>(),
            WebViewPolicy.validate(validSpec(entryUrl = "https://lms.example.test:443/login")),
        )
    }

    @Test
    fun `entry url must not contain userinfo`() {
        val issues = WebViewPolicy.validate(
            validSpec(entryUrl = "https://user:pass@lms.example.test/login"),
        )
        assertTrue(issues.any { it.contains("userinfo") })
    }

    @Test
    fun `entry url host must be whitelisted`() {
        val issues = WebViewPolicy.validate(
            validSpec(entryUrl = "https://evil.example.test/login"),
        )
        assertTrue(issues.any { it.contains("entryUrl host must be in allowedHosts") })
    }

    @Test
    fun `ip literals and wildcards are rejected`() {
        assertTrue(
            WebViewPolicy.validate(
                validSpec(
                    entryUrl = "https://10.0.0.1/login",
                    allowedHosts = setOf("10.0.0.1"),
                    dataHost = "10.0.0.1",
                ),
            ).any { it.contains("IP literal") },
        )
        assertTrue(
            WebViewPolicy.validate(
                validSpec(
                    entryUrl = "https://lms.example.test/login",
                    allowedHosts = setOf("*.example.test", "lms.example.test"),
                ),
            ).any { it.contains("invalid host") || it.contains("wildcard") },
        )
    }

    @Test
    fun `uppercase hosts are rejected to keep cookie matching deterministic`() {
        val issues = WebViewPolicy.validate(
            validSpec(
                entryUrl = "https://LMS.example.test/login",
                allowedHosts = setOf("LMS.example.test"),
                dataHost = "LMS.example.test",
            ),
        )
        assertTrue(issues.any { it.contains("lowercase") })
    }

    @Test
    fun `data host must be in allowed hosts`() {
        val issues = WebViewPolicy.validate(validSpec(dataHost = "data.example.test"))
        assertTrue(issues.any { it.contains("dataHost must be in allowedHosts") })
    }

    @Test
    fun `endpoint paths must be safe for in page fetch`() {
        assertTrue(
            WebViewPolicy.validate(validSpec(profilePath = "profile")).any {
                it.contains("invalid profilePath")
            },
        )
        assertTrue(
            WebViewPolicy.validate(validSpec(todosPath = "/api/todos');alert(1)//")).any {
                it.contains("invalid todosPath")
            },
        )
        assertTrue(
            WebViewPolicy.validate(validSpec(todosPath = "/api/todos?x=1&y=2")).isEmpty(),
        )
    }

    @Test
    fun `user agent must not contain line breaks`() {
        val issues = WebViewPolicy.validate(validSpec(userAgent = "Mozilla\r\nX-Injected: 1"))
        assertTrue(issues.any { it.contains("line breaks") })
    }

    @Test
    fun `whitelist overlap detection`() {
        val first = validSpec()
        val second = validSpec(
            entryUrl = "https://other.example.test/login",
            allowedHosts = setOf("ids.example.test", "other.example.test"),
            dataHost = "other.example.test",
        )
        assertEquals(setOf("ids.example.test"), WebViewPolicy.overlappingHosts(first, second))
    }

    @Test
    fun `host and identity classification`() {
        val spec = validSpec()
        assertTrue(WebViewPolicy.isAllowedHost(spec, "ids.example.test"))
        assertFalse(WebViewPolicy.isAllowedHost(spec, "evil.example.test"))
        assertTrue(WebViewPolicy.isDataHost(spec, "https://lms.example.test/api/todos"))
        assertFalse(WebViewPolicy.isDataHost(spec, "https://ids.example.test/login"))
        assertTrue(WebViewPolicy.isIdentityHost(spec, "https://ids.example.test/login"))
        assertFalse(WebViewPolicy.isIdentityHost(spec, "https://lms.example.test/"))
        assertFalse(WebViewPolicy.isIdentityHost(spec, null))
    }

    @Test
    fun `default profile validator requires a json object`() {
        assertTrue(WebViewPolicy.defaultProfileValidator("""{"id":9001}"""))
        assertFalse(WebViewPolicy.defaultProfileValidator("<html>login</html>"))
        assertFalse(WebViewPolicy.defaultProfileValidator("[]"))
        assertFalse(WebViewPolicy.defaultProfileValidator(""))
    }

    @Test
    fun `in page scripts carry spec endpoints`() {
        val spec = validSpec(profilePath = "/api/me", todosPath = "/api/items")
        assertTrue(WebLoginJs.profileFetchJs(spec).contains("fetch('/api/me'"))
        assertTrue(WebLoginJs.todosFetchJs(spec).contains("fetch('/api/items'"))
    }

    // ---------- 教务课表页（WebView 登录模式的网页导入入口） ----------

    private fun validTimetable(
        entryUrl: String = "https://jw.example.test/gsapp/sys/wdkbapp/*default/index.do",
        allowedHosts: Set<String> = setOf("jw.example.test", "ids.example.test"),
        userAgent: String? = "Mozilla/5.0",
    ) = WebViewTimetableSpec(entryUrl, allowedHosts, userAgent)

    @Test
    fun `valid timetable spec passes`() {
        assertEquals(emptyList<String>(), WebViewPolicy.validateTimetable(validTimetable()))
    }

    @Test
    fun `timetable entry url follows the same https and host rules`() {
        assertTrue(
            WebViewPolicy.validateTimetable(
                validTimetable(entryUrl = "http://jw.example.test/index.do"),
            ).any { it.contains("entryUrl must use https") },
        )
        assertTrue(
            WebViewPolicy.validateTimetable(
                validTimetable(entryUrl = "https://evil.example.test/index.do"),
            ).any { it.contains("entryUrl host must be in allowedHosts") },
        )
        assertTrue(
            WebViewPolicy.validateTimetable(
                validTimetable(entryUrl = "https://user:pass@jw.example.test/index.do"),
            ).any { it.contains("userinfo") },
        )
    }

    @Test
    fun `timetable hosts reject ip literals uppercase and wildcards`() {
        assertTrue(
            WebViewPolicy.validateTimetable(
                validTimetable(
                    entryUrl = "https://10.0.0.1/index.do",
                    allowedHosts = setOf("10.0.0.1"),
                ),
            ).any { it.contains("IP literal") },
        )
        assertTrue(
            WebViewPolicy.validateTimetable(
                validTimetable(
                    entryUrl = "https://JW.example.test/index.do",
                    allowedHosts = setOf("JW.example.test"),
                ),
            ).any { it.contains("lowercase") },
        )
        assertTrue(
            WebViewPolicy.validateTimetable(
                validTimetable(allowedHosts = setOf("*.example.test", "jw.example.test")),
            ).any { it.contains("invalid host") || it.contains("wildcard") },
        )
    }

    @Test
    fun `timetable user agent must not contain line breaks`() {
        assertTrue(
            WebViewPolicy.validateTimetable(
                validTimetable(userAgent = "Mozilla\r\nX-Injected: 1"),
            ).any { it.contains("line breaks") },
        )
    }
}
