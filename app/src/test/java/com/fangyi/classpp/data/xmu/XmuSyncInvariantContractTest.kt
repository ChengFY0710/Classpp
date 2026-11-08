package com.fangyi.classpp.data.xmu

import com.fangyi.classpp.data.TodoRepository
import com.fangyi.classpp.data.sync.LoginRaw
import com.fangyi.classpp.data.sync.SyncInvariantContractTest
import com.fangyi.classpp.data.sync.SyncService
import com.fangyi.classpp.data.sync.engine.FetchOutcome
import com.fangyi.classpp.data.sync.engine.SchoolAutoGroupNames
import com.fangyi.classpp.data.sync.engine.SchoolSyncEngine
import com.fangyi.classpp.data.sync.engine.SchoolSyncStore
import com.fangyi.classpp.data.sync.engine.SessionApi
import com.fangyi.classpp.schools.xmu.XmuSyncCodec
import com.fangyi.classpp.schools.xmu.XMU_SCHOOL_SPEC

/**
 * 不变量契约测试的厦大实例：厦大真实协议（[XmuSyncCodec] 委托冻结 [XmuTodoMapper]）
 * 跑在通用 [SchoolSyncEngine] 上，与基类的全部断言对齐。
 * 断言全部在基类；这里只把远端夹具序列化成厦大接口的 JSON 形态。
 */
class XmuSyncInvariantContractTest : SyncInvariantContractTest() {

    private var profile: RemoteProfile = RemoteProfile()
    private var remotes: List<RemoteTodo> = emptyList()

    private val api = SessionApi {
        FetchOutcome.Ok(LoginRaw(profileRaw = profileJson(profile), todosRaw = todosJson(remotes)))
    }

    override suspend fun createService(todoRepository: TodoRepository): SyncService =
        SchoolSyncEngine(
            spec = XMU_SCHOOL_SPEC,
            codec = XmuSyncCodec(clock = { nowMillis }),
            store = SchoolSyncStore(folder.root, XMU_SCHOOL_SPEC.id),
            todoRepository = todoRepository,
            api = api,
            groupNames = SchoolAutoGroupNames(
                source = XMU_SCHOOL_SPEC.id,
                homework = "作业",
                quiz = "测验",
            ),
            clock = { nowMillis },
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
