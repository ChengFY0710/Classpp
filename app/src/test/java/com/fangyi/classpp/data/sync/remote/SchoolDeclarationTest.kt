package com.fangyi.classpp.data.sync.remote

import com.fangyi.classpp.data.sync.Capability
import com.fangyi.classpp.data.sync.SchoolText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远端声明 schema 的校验边界：id/capabilities/edu.cn 信任域/模板形态/schemaVersion。
 * 单校声明坏 → `validate()` 给出问题清单（装配层据此跳过），绝不带病注册。
 */
class SchoolDeclarationTest {

    @Test
    fun `demo declaration is valid`() {
        assertEquals(emptyList<String>(), demoDeclaration().validate())
    }

    @Test
    fun `invalid id is rejected`() {
        val issues = demoDeclaration().copy(id = "Demo").validate()
        assertTrue(issues.any { it.contains("invalid id") })
    }

    @Test
    fun `capabilities must be exactly webview login`() {
        val extra = demoDeclaration()
            .copy(capabilities = listOf("WEBVIEW_LOGIN", "NATIVE_LOGIN"))
            .validate()
        assertTrue(extra.any { it.contains("capabilities") })

        val empty = demoDeclaration().copy(capabilities = emptyList()).validate()
        assertTrue(empty.any { it.contains("capabilities") })
    }

    @Test
    fun `hosts outside edu cn are rejected`() {
        val nonEdu = demoDeclaration()
            .copy(
                webView = demoDeclaration().webView.copy(
                    allowedHosts = listOf("lms.example.com"),
                    dataHost = "lms.example.com",
                    entryUrl = "https://lms.example.com/login",
                ),
            )
            .validate()
        assertTrue(nonEdu.any { it.contains("outside .edu.cn") })
    }

    @Test
    fun `edu cn boundary rejects bare domain and disguised suffix`() {
        for (host in listOf("edu.cn", "lms.demo.edu.cn.evil.com")) {
            val issues = demoDeclaration()
                .copy(
                    webView = demoDeclaration().webView.copy(
                        allowedHosts = listOf(host),
                        dataHost = host,
                        entryUrl = "https://lms.demo.edu.cn/login",
                    ),
                )
                .validate()
            assertTrue("host '$host' must be rejected", issues.any { it.contains("outside .edu.cn") })
        }
    }

    @Test
    fun `local id template must use the school prefix`() {
        val issues = demoDeclaration().let { demo ->
            demo.copy(todo = demo.todo.copy(localIdTemplate = "other-{courseId}-{id}")).validate()
        }
        assertTrue(issues.any { it.contains("localIdTemplate must start with 'demo-'") })
    }

    @Test
    fun `dangerous templates are rejected`() {
        val slash = demoDeclaration().let { demo ->
            demo.copy(todo = demo.todo.copy(localIdTemplate = "demo-{courseId}/{id}")).validate()
        }
        assertTrue(slash.any { it.contains("localIdTemplate invalid") })

        val backslash = demoDeclaration().let { demo ->
            demo.copy(todo = demo.todo.copy(remoteKeyTemplate = "demo:\\{id}")).validate()
        }
        assertTrue(backslash.any { it.contains("remoteKeyTemplate invalid") })

        val unknown = demoDeclaration().let { demo ->
            demo.copy(todo = demo.todo.copy(remoteKeyTemplate = "demo:{nope}")).validate()
        }
        assertTrue(unknown.any { it.contains("unknown placeholder") })
    }

    @Test
    fun `unsupported schema version is rejected`() {
        val issues = demoDeclaration().copy(schemaVersion = 2).validate()
        assertTrue(issues.any { it.contains("unsupported schemaVersion") })
    }

    @Test
    fun `unknown end time zone is rejected`() {
        val issues = demoDeclaration().let { demo ->
            demo.copy(
                todo = demo.todo.copy(
                    endTime = DeclarationEndTime(
                        format = DeclarationTodo.FORMAT_ISO,
                        zone = "Mars/Olympus",
                    ),
                ),
            )
        }.validate()
        assertTrue(issues.any { it.contains("endTime.zone") })
    }

    @Test
    fun `pattern format requires a pattern`() {
        val issues = demoDeclaration().let { demo ->
            demo.copy(
                todo = demo.todo.copy(
                    endTime = DeclarationEndTime(format = DeclarationTodo.FORMAT_PATTERN),
                ),
            )
        }.validate()
        assertTrue(issues.any { it.contains("endTime.pattern required") })
    }

    @Test
    fun `missing required fields fail decoding`() {
        assertThrows(Exception::class.java) {
            SchoolDeclaration.decode("""{"id":"demo"}""")
        }
    }

    @Test
    fun `invalid declaration cannot be converted to a spec`() {
        assertThrows(IllegalArgumentException::class.java) {
            demoDeclaration().copy(id = "Bad").toSpec()
        }
    }

    @Test
    fun `to spec is inline remote webview only`() {
        val spec = demoDeclaration().toSpec()
        assertEquals("demo", spec.id)
        assertEquals(setOf(Capability.WEBVIEW_LOGIN), spec.capabilities)
        assertTrue(spec.remote)
        assertEquals("示例大学", (spec.displayName as SchoolText.Inline).zh)
        assertEquals(null, spec.timetableIntro)
        assertTrue(spec.loginImages.isEmpty())
        // 原生登录/教务导入槽位是中性占位，永不渲染
        assertTrue(spec.usernameLabel is SchoolText.Res)
    }

    @Test
    fun `timetable block derives schedule import capability and inline copy`() {
        val spec = demoDeclarationWithTimetable().toSpec()

        assertEquals(
            setOf(Capability.WEBVIEW_LOGIN, Capability.SCHEDULE_IMPORT),
            spec.capabilities,
        )
        assertEquals("导入教务课表", (spec.timetableIntro!!.title as SchoolText.Inline).zh)
        assertEquals(
            "从教务课表页导入本学期课程",
            (spec.timetableIntro.body as SchoolText.Inline).zh,
        )
        assertEquals("教务课表", (spec.defaultScheduleName as SchoolText.Inline).zh)
        assertTrue(spec.remote)
    }

    @Test
    fun `to web view login spec maps desktop alias to built-in ua`() {
        val spec = demoDeclaration().toWebViewLoginSpec()
        assertEquals("lms.demo.edu.cn", spec.dataHost)
        assertEquals(DECLARATION_DESKTOP_USER_AGENT, spec.userAgent)

        val custom = demoDeclaration().let { demo ->
            demo.copy(webView = demo.webView.copy(userAgent = "Custom UA"))
        }.toWebViewLoginSpec()
        assertEquals("Custom UA", custom.userAgent)

        val system = demoDeclaration().let { demo ->
            demo.copy(webView = demo.webView.copy(userAgent = null))
        }.toWebViewLoginSpec()
        assertEquals(null, system.userAgent)
    }

    // ---------- 清单 ----------

    @Test
    fun `index validation rejects unsafe paths and duplicates`() {
        val valid = SchoolRemoteIndex(
            schemaVersion = 1,
            schools = listOf(SchoolRemoteEntry(id = "demo", file = "schools/demo.json")),
        )
        assertEquals(emptyList<String>(), valid.validate())

        val traversal = SchoolRemoteIndex(
            schools = listOf(SchoolRemoteEntry(id = "demo", file = "../demo.json")),
        )
        assertTrue(traversal.validate().any { it.contains("invalid file path") })

        val absolute = SchoolRemoteIndex(
            schools = listOf(SchoolRemoteEntry(id = "demo", file = "/demo.json")),
        )
        assertTrue(absolute.validate().any { it.contains("invalid file path") })

        val duplicate = SchoolRemoteIndex(
            schools = listOf(
                SchoolRemoteEntry(id = "demo", file = "schools/demo.json"),
                SchoolRemoteEntry(id = "demo", file = "schools/demo2.json"),
            ),
        )
        assertTrue(duplicate.validate().any { it.contains("duplicate") })
    }
}
