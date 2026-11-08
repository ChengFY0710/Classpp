package com.fangyi.classpp.data.sync.engine

import com.fangyi.classpp.data.TodoRepository
import com.fangyi.classpp.data.sync.SyncInvariantContractTest
import com.fangyi.classpp.data.sync.SyncService

/**
 * 不变量契约测试的通用引擎实例：用一套「假学校」编码器与假取数接口跑与厦大完全相同的
 * 用例，证明适配路径（[SyncCodec] + [SchoolSyncEngine]）不依赖任何真实学校也能走通。
 */
class EngineSyncInvariantContractTest : SyncInvariantContractTest() {

    private val api = FakeSessionApi()

    override suspend fun createService(todoRepository: TodoRepository): SyncService =
        SchoolSyncEngine(
            spec = fakeSchoolSpec(),
            codec = FakeSyncCodec(source = "fakeu") { nowMillis },
            store = SchoolSyncStore(folder.root, "fakeu"),
            todoRepository = todoRepository,
            api = api,
            groupNames = SchoolAutoGroupNames(source = "fakeu"),
            clock = { nowMillis },
        )

    override fun setRemote(profile: RemoteProfile, todos: List<RemoteTodo>) {
        api.respond(
            uid = profile.uid,
            name = profile.name,
            todos = todos.map { todo ->
                FakeRemoteTodo(
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
        "fakeu-$courseId-$remoteId"

    override val homeworkGroupKey: String = "fakeu:homework"

    override val quizGroupKey: String = "fakeu:quiz"
}
