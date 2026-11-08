package com.fangyi.classpp.data.sync.remote

import com.fangyi.classpp.data.TodoRepository
import com.fangyi.classpp.data.sync.SyncInvariantContractTest
import com.fangyi.classpp.data.sync.SyncService
import com.fangyi.classpp.data.sync.engine.SchoolAutoGroupNames
import com.fangyi.classpp.data.sync.engine.SchoolSyncEngine
import com.fangyi.classpp.data.sync.engine.SchoolSyncStore

/**
 * 不变量契约测试的「声明式学校」实例：demo 声明 + 按声明路径产出的 JSON 夹具，
 * 跑与厦大/假学校完全相同的用例——证明 Tier 1 的声明式 codec 与通用引擎组合后
 * 满足全部同步不变量（基类零改动）。
 */
class RemoteSyncInvariantContractTest : SyncInvariantContractTest() {

    private val api = ScriptedJsonSessionApi()

    override suspend fun createService(todoRepository: TodoRepository): SyncService =
        SchoolSyncEngine(
            spec = demoDeclaration().toSpec(),
            codec = DeclarativeSyncCodec(demoDeclaration()) { nowMillis },
            store = SchoolSyncStore(folder.root, "demo"),
            todoRepository = todoRepository,
            api = api,
            groupNames = SchoolAutoGroupNames(source = "demo"),
            clock = { nowMillis },
        )

    override fun setRemote(profile: RemoteProfile, todos: List<RemoteTodo>) {
        api.respond(
            uid = profile.uid,
            name = profile.name,
            todos = todos.map { todo ->
                DemoRemoteTodo(
                    id = todo.id,
                    courseId = todo.courseId,
                    courseName = todo.courseName,
                    title = todo.title,
                    type = todo.type,
                    endTime = todo.endTime,
                    locked = todo.locked,
                    student = todo.student,
                )
            },
        )
    }

    override fun expectedLocalId(courseId: Long, remoteId: Long): String =
        "demo-$courseId-$remoteId"

    override val homeworkGroupKey: String = "demo:homework"

    override val quizGroupKey: String = "demo:quiz"
}
