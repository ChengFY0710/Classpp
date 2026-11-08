package com.fangyi.classpp.data.xmu

import com.fangyi.classpp.data.TodoRepository
import com.fangyi.classpp.data.sync.SyncInvariantContractTest
import com.fangyi.classpp.data.sync.SyncService
import com.fangyi.classpp.schools.xmu.XmuSyncService

/**
 * 不变量契约测试的厦大实例：既有编排（经归一化包装）与通用引擎跑同一组用例。
 * 断言全部在基类；这里只把远端夹具序列化成厦大接口的 JSON 形态。
 */
class XmuSyncInvariantContractTest : SyncInvariantContractTest() {

    private var profile: RemoteProfile = RemoteProfile()
    private var remotes: List<RemoteTodo> = emptyList()

    private val api = object : XmuTodoApi {
        override suspend fun fetchSession(): XmuFetchResult =
            XmuFetchResult.Ok(profileJson(profile), todosJson(remotes))
    }

    override suspend fun createService(todoRepository: TodoRepository): SyncService =
        XmuSyncService(
            XmuSyncRepository(
                store = XmuSyncStore(folder.root),
                todoRepository = todoRepository,
                api = api,
                nowMillis = { nowMillis },
            ),
        )

    override fun setRemote(profile: RemoteProfile, todos: List<RemoteTodo>) {
        this.profile = profile
        this.remotes = todos
    }

    override fun expectedLocalId(courseId: Long, remoteId: Long): String = "xmu-$courseId-$remoteId"

    override val homeworkGroupKey: String = "xmu:homework"

    override val quizGroupKey: String = "xmu:quiz"

    private fun profileJson(profile: RemoteProfile): String = buildString {
        append("{\"id\":").append(profile.uid.toLong()).append(',')
        append("\"name\":").append(quote(profile.name)).append('}')
    }

    private fun todosJson(todos: List<RemoteTodo>): String = buildString {
        append("{\"todo_list\":[")
        todos.forEachIndexed { index, todo ->
            if (index > 0) append(',')
            append('{')
            append("\"id\":").append(todo.id).append(',')
            append("\"title\":").append(quote(todo.title)).append(',')
            append("\"type\":").append(quote(todo.type)).append(',')
            append("\"course_id\":").append(todo.courseId).append(',')
            append("\"course_name\":").append(quote(todo.courseName)).append(',')
            todo.endTime?.let { append("\"end_time\":").append(quote(it)).append(',') }
            append("\"is_locked\":").append(todo.locked).append(',')
            append("\"is_student\":").append(todo.student)
            append('}')
        }
        append("]}")
    }

    private fun quote(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
