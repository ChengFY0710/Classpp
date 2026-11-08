package com.fangyi.classpp.data.sync.remote

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.sync.engine.SkipReason
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 声明式编码器（[DeclarativeSyncCodec]）的映射口径：dot-path、模板、哈希配方、
 * 跳过规则、ISO/PATTERN 时间与自动分组。夹具 JSON 由 [demoTodosJson] 按 demo 声明
 * 的路径产出——声明与数据必须严格对齐，否则这里先红。
 */
class DeclarativeSyncCodecTest {

    /** 「现在」固定在 2026-11-01 12:00 北京时间（早于夹具所有截止） */
    private val nowMillis: Long = GregorianCalendar(TimeZone.getTimeZone("Asia/Shanghai")).apply {
        clear()
        set(2026, 10, 1, 12, 0, 0)
    }.timeInMillis

    private val codec = DeclarativeSyncCodec(demoDeclaration()) { nowMillis }

    // ---------- 解析 ----------

    @Test
    fun `profile parses uid name and relative avatar against declared base`() {
        val profile = codec.parseProfile(
            demoProfileJson(uid = "9001", name = "张三", avatarUrl = "/uploads/avatar.png"),
        )
        assertNotNull(profile)
        assertEquals("9001", profile!!.uid)
        assertEquals("张三", profile.accountName)
        assertEquals("https://lms.demo.edu.cn/uploads/avatar.png", profile.avatarUrl)
    }

    @Test
    fun `profile rejects blank uid and malformed json`() {
        assertNull(codec.parseProfile(demoProfileJson(uid = "")))
        assertNull(codec.parseProfile("not-json"))
        assertFalse(codec.profileValidator()("""{"user":{"name":"张三"}}"""))
        assertTrue(codec.profileValidator()(demoProfileJson(uid = "9001")))
    }

    @Test
    fun `avatar falls back to container keys and ignores non http schemes`() {
        val container = """{"user":{"uid":"1","name":"n"},"data":{"avatar_url":"/img/a.png"}}"""
        assertEquals(
            "https://lms.demo.edu.cn/img/a.png",
            codec.parseProfile(container)?.avatarUrl,
        )
        val dataUrl = """{"user":{"uid":"1","name":"n","avatar_url":"data:image/png;base64,xx"}}"""
        assertNull(codec.parseProfile(dataUrl)?.avatarUrl)
    }

    @Test
    fun `todos parse only when list path is an array`() {
        val remotes = codec.parseTodos(
            demoTodosJson(listOf(DemoRemoteTodo(id = 1, courseId = 2, courseName = "课", title = "题"))),
        )
        assertEquals(1, remotes?.size)
        assertNull(codec.parseTodos("""{"todo_list":{}}"""))
        assertNull(codec.parseTodos("not-json"))
    }

    // ---------- 键与哈希 ----------

    @Test
    fun `remote key and local id render declared templates`() {
        val remote = remote(
            DemoRemoteTodo(id = 925512, courseId = 106160, courseName = "高等数学", title = "第一次作业"),
        )
        assertEquals("demo:106160:925512", codec.remoteKey(remote))
        assertEquals("demo-106160-925512", codec.localId(codec.remoteKey(remote)))
        assertTrue(codec.matchesLocalId("demo-106160-925512"))
        assertFalse(codec.matchesLocalId("xmu-1"))
    }

    @Test
    fun `remote hash uses the frozen title endtime course type recipe`() {
        val remote = remote(
            DemoRemoteTodo(
                id = 925512,
                courseId = 106160,
                courseName = "高等数学",
                title = "第一次作业",
                type = "homework",
                endTime = "2026-11-04T10:30:00Z",
            ),
        )
        assertEquals(
            "38e0700daa8cee69950ed0047e05ca3ea3893bea5915cad3d1388022862f558f",
            codec.remoteHash(remote),
        )
    }

    // ---------- 跳过规则 ----------

    @Test
    fun `skip reasons follow the shared order`() {
        assertEquals(
            SkipReason.NotStudent,
            codec.skipReason(remote(base().copy(student = false))),
        )
        assertEquals(
            SkipReason.InvalidId,
            codec.skipReason(remote(base().copy(id = 0))),
        )
        assertEquals(
            SkipReason.BlankTitle,
            codec.skipReason(remote(base().copy(title = "  "))),
        )
        assertEquals(
            SkipReason.NoDeadline,
            codec.skipReason(remote(base().copy(endTime = null))),
        )
        assertEquals(
            SkipReason.DeadlineUnparseable,
            codec.skipReason(remote(base().copy(endTime = "not-a-timestamp"))),
        )
        assertEquals(
            SkipReason.Locked,
            codec.skipReason(remote(base().copy(locked = true))),
        )
        assertEquals(
            SkipReason.Overdue,
            codec.skipReason(remote(base().copy(endTime = "2026-10-01T00:00:00Z"))),
        )
        assertNull(codec.skipReason(remote(base())))
    }

    @Test
    fun `unsafe key values are rejected as invalid id`() {
        val unsafeId = remote(base().copy(id = 1)).let { obj ->
            // 直接把 id 改成带路径分隔符的字符串（正常渲染路径不会产生，防御远端响应）
            JsonObject(obj.toMutableMap().apply { put("id", kotlinx.serialization.json.JsonPrimitive("1/2")) })
        }
        assertEquals(SkipReason.InvalidId, codec.skipReason(unsafeId))
    }

    @Test
    fun `missing key field is rejected as invalid id`() {
        val missingCourse = JsonObject(
            mapOf(
                "id" to kotlinx.serialization.json.JsonPrimitive(1),
                "title" to kotlinx.serialization.json.JsonPrimitive("题"),
                "type" to kotlinx.serialization.json.JsonPrimitive("homework"),
                "end_time" to kotlinx.serialization.json.JsonPrimitive("2026-11-05T15:59:00Z"),
            ),
        )
        assertEquals(SkipReason.InvalidId, codec.skipReason(missingCourse))
    }

    // ---------- 映射 ----------

    @Test
    fun `todo mapping converts ISO utc to declared zone and builds note`() {
        val todo = codec.toTodo(
            remote(
                DemoRemoteTodo(
                    id = 925512,
                    courseId = 106160,
                    courseName = "高等数学",
                    title = "第一次作业",
                    type = "homework",
                    endTime = "2026-11-04T10:30:00Z",
                ),
            ),
            fetchedAt = nowMillis,
        )
        assertNotNull(todo)
        assertEquals("demo-106160-925512", todo!!.id)
        assertEquals("第一次作业", todo.name)
        assertEquals(IsoDate.of(2026, 11, 4), todo.deadlineDate)
        assertEquals(18 * 60 + 30, todo.deadlineMinute)
        assertEquals("高等数学", todo.sourceDetail)
        assertTrue(todo.note.contains("https://lms.demo.edu.cn/course/106160/learning-activity#/925512"))
        assertTrue(todo.countdown)
    }

    @Test
    fun `pattern time parses in declared zone and rejects trailing text`() {
        val declaration = demoDeclaration().let { demo ->
            demo.copy(
                todo = demo.todo.copy(
                    endTime = DeclarationEndTime(
                        format = DeclarationTodo.FORMAT_PATTERN,
                        pattern = "yyyy-MM-dd HH:mm",
                        zone = "Asia/Shanghai",
                    ),
                ),
            )
        }
        val patternCodec = DeclarativeSyncCodec(declaration) { nowMillis }
        val ok = patternCodec.toTodo(
            remote(
                DemoRemoteTodo(
                    id = 1,
                    courseId = 2,
                    courseName = "课",
                    title = "题",
                    endTime = "2026-11-04 18:30",
                ),
            ),
            fetchedAt = nowMillis,
        )
        assertEquals(IsoDate.of(2026, 11, 4), ok?.deadlineDate)
        assertEquals(18 * 60 + 30, ok?.deadlineMinute)

        val trailing = patternCodec.toTodo(
            remote(
                DemoRemoteTodo(
                    id = 1,
                    courseId = 2,
                    courseName = "课",
                    title = "题",
                    endTime = "2026-11-04 18:30 garbage",
                ),
            ),
            fetchedAt = nowMillis,
        )
        assertNull(trailing)
    }

    @Test
    fun `auto group maps declared types case and space insensitively`() {
        assertEquals("demo:homework", codec.autoGroupKey(remote(base().copy(type = " Homework "))))
        assertEquals("demo:quiz", codec.autoGroupKey(remote(base().copy(type = "QUIZ"))))
        assertNull(codec.autoGroupKey(remote(base().copy(type = "exam"))))
    }

    @Test
    fun `ownership covers source and local id prefix`() {
        assertTrue(codec.isOwned(Todo(id = "demo-1-2", name = "a", source = "demo")))
        assertTrue(codec.isOwned(Todo(id = "demo-9", name = "a", source = null)))
        assertFalse(codec.isOwned(Todo(id = "xmu-1", name = "a", source = "xmu")))
    }

    // ---------- 工具 ----------

    private fun base(): DemoRemoteTodo = DemoRemoteTodo(
        id = 925512,
        courseId = 106160,
        courseName = "高等数学",
        title = "第一次作业",
        type = "homework",
        endTime = "2026-11-04T10:30:00Z",
    )

    private fun remote(todo: DemoRemoteTodo): JsonObject =
        codec.parseTodos(demoTodosJson(listOf(todo)))?.single()
            ?: error("fixture did not parse: $todo")
}
