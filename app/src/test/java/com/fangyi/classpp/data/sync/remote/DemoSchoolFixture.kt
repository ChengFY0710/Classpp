package com.fangyi.classpp.data.sync.remote

import com.fangyi.classpp.data.sync.LoginRaw
import com.fangyi.classpp.data.sync.engine.FetchOutcome
import com.fangyi.classpp.data.sync.engine.SessionApi
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 测试夹具：与 `tools/schoolsync/templates/demo.json` 同构的声明 + 按声明路径产出的
 * JSON 响应。只在测试里使用——demo 学校**不**进入 `index.json`，生产不可见。
 */
internal fun demoDeclaration(): SchoolDeclaration = SchoolDeclaration(
    schemaVersion = 1,
    id = "demo",
    capabilities = listOf("WEBVIEW_LOGIN"),
    displayName = LocalizedText("示例大学", "Demo University"),
    webView = DeclarationWebView(
        entryUrl = "https://lms.demo.edu.cn/user/index?version=v1",
        allowedHosts = listOf("lms.demo.edu.cn", "ids.demo.edu.cn"),
        dataHost = "lms.demo.edu.cn",
        profilePath = "/api/profile",
        todosPath = "/api/todos",
        userAgent = "desktop",
    ),
    copy = DeclarationCopy(
        headerTitle = LocalizedText("示例大学课程中心", "Demo University LMS"),
        headerDescription = LocalizedText("登录后同步作业与测验到待办"),
        introTitle = LocalizedText("同步可以做什么"),
        introBody = LocalizedText("网页登录成功后读取待办列表"),
        loginSheetTitle = LocalizedText("登录示例大学课程中心"),
        sourceBadge = LocalizedText("示例大学"),
        groupHomework = LocalizedText("示例大学作业"),
        groupQuiz = LocalizedText("示例大学测验"),
        syncNotifTitle = LocalizedText("示例大学自动同步完成"),
        accountChanged = LocalizedText("账号与上次同步的不一致"),
        logoutMessage = LocalizedText("退出后将清除同步记录"),
        switchMessage = LocalizedText("继续同步需要先清空旧记录"),
        serviceBusy = LocalizedText("示例大学服务繁忙"),
        invalidCredentials = LocalizedText("账号或密码不正确"),
        sessionNotEstablished = LocalizedText("登录成功但无法读取数据"),
        autoLoginDisableMessage = LocalizedText("关闭后不再自动续期"),
    ),
    profile = DeclarationProfile(
        uidPath = "user.uid",
        namePath = "user.name",
        avatar = DeclarationAvatar(
            candidates = listOf("avatar_big_url", "avatar_small_url", "avatar_url"),
            baseUrl = "https://lms.demo.edu.cn",
            cookieHosts = listOf("lms.demo.edu.cn"),
        ),
    ),
    todo = DeclarationTodo(
        listPath = "todo_list",
        keyFields = listOf(
            DeclarationKeyField(name = "courseId", path = "course.id"),
            DeclarationKeyField(name = "id", path = "id"),
        ),
        remoteKeyTemplate = "demo:{courseId}:{id}",
        localIdTemplate = "demo-{courseId}-{id}",
        fields = DeclarationFieldPaths(
            titlePath = "title",
            courseNamePath = "course.name",
            typePath = "type",
            endTimePath = "end_time",
        ),
        endTime = DeclarationEndTime(format = DeclarationTodo.FORMAT_ISO, zone = "Asia/Shanghai"),
        filters = DeclarationFilters(
            requireStudent = "is_student",
            skipLocked = "locked",
            skipOverdue = true,
        ),
        noteTemplate = "课程：{courseName}\n类型：{type}\n{activityUrl}",
        activityUrlTemplate = "https://lms.demo.edu.cn/course/{courseId}/learning-activity#/{id}",
        autoGroups = listOf(
            DeclarationAutoGroup(type = "homework", group = "homework"),
            DeclarationAutoGroup(type = "quiz", group = "quiz"),
        ),
    ),
)

/** 与契约测试基类的远端条目同构的 demo 条目 */
internal data class DemoRemoteTodo(
    val id: Long,
    val courseId: Long,
    val courseName: String,
    val title: String,
    val type: String = "homework",
    val endTime: String? = "2026-11-05T15:59:00Z",
    val locked: Boolean = false,
    val student: Boolean = true,
)

internal fun demoProfileJson(
    uid: String = "9001",
    name: String = "张三",
    avatarUrl: String? = null,
): String = buildJsonObject {
    putJsonObject("user") {
        put("uid", uid)
        put("name", name)
        avatarUrl?.let { put("avatar_big_url", it) }
    }
}.toString()

internal fun demoTodosJson(todos: List<DemoRemoteTodo>): String = buildJsonObject {
    putJsonArray("todo_list") {
        todos.forEach { todo ->
            addJsonObject {
                put("id", todo.id)
                put("title", todo.title)
                put("type", todo.type)
                if (todo.endTime != null) {
                    put("end_time", todo.endTime)
                } else {
                    put("end_time", JsonNull)
                }
                put("locked", todo.locked)
                put("is_student", todo.student)
                putJsonObject("course") {
                    put("id", todo.courseId)
                    put("name", todo.courseName)
                }
            }
        }
    }
}.toString()

/** 脚本化 JSON 取数接口：与 `FakeSessionApi` 同职责，返回 demo 声明能解析的响应 */
internal class ScriptedJsonSessionApi : SessionApi {

    var outcome: FetchOutcome = FetchOutcome.Failed(
        com.fangyi.classpp.data.sync.engine.FetchError.SessionExpired,
    )
    var calls = 0
        private set

    fun respond(
        uid: String = "9001",
        name: String = "张三",
        avatarUrl: String? = null,
        todos: List<DemoRemoteTodo>,
    ) {
        outcome = FetchOutcome.Ok(
            LoginRaw(demoProfileJson(uid, name, avatarUrl), demoTodosJson(todos)),
        )
    }

    override suspend fun fetchSession(): FetchOutcome {
        calls++
        return outcome
    }
}
