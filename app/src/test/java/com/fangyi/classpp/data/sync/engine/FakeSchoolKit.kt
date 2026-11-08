package com.fangyi.classpp.data.sync.engine

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.sync.Capability
import com.fangyi.classpp.data.sync.LoginRaw
import com.fangyi.classpp.data.sync.SchoolSpec
import com.fangyi.classpp.data.sync.SchoolText
import java.security.MessageDigest
import java.util.GregorianCalendar
import java.util.TimeZone

/** 假学校远端条目（测试工具）：字段与真实学校的 DTO 同构，只是协议换成行式文本 */
internal data class FakeRemoteTodo(
    val id: Long,
    val courseId: Long,
    val courseName: String,
    val title: String,
    val type: String = "homework",
    val endTime: String? = "2026-11-05T15:59:00Z",
    val locked: Boolean = false,
    val student: Boolean = true,
)

/** 脚本化取数接口：记录调用次数，按当前 [outcome] 返回 */
internal class FakeSessionApi : SessionApi {
    var outcome: FetchOutcome = FetchOutcome.Failed(FetchError.SessionExpired)
    var calls = 0
        private set

    fun respond(
        uid: String = "9001",
        name: String = "张三",
        avatarUrl: String? = null,
        todos: List<FakeRemoteTodo>,
    ) {
        outcome = FetchOutcome.Ok(
            LoginRaw(profileRaw(uid, name, avatarUrl), todosRaw(todos)),
        )
    }

    override suspend fun fetchSession(): FetchOutcome {
        calls++
        return outcome
    }
}

internal fun profileRaw(uid: String, name: String, avatarUrl: String? = null): String =
    listOfNotNull(uid, name, avatarUrl).joinToString("\t")

internal fun todosRaw(todos: List<FakeRemoteTodo>): String = todos.joinToString("\n") { todo ->
    listOf(
        todo.id.toString(),
        todo.courseId.toString(),
        todo.title,
        todo.courseName,
        todo.type,
        todo.endTime.orEmpty(),
        if (todo.locked) "1" else "0",
        if (todo.student) "1" else "0",
    ).joinToString("\t")
}

/**
 * 假学校编码器：`<id>-<courseId>-<remoteId>` 确定性本地 id、行式远端协议、
 * `<id>:homework/quiz` 自动分组——足以覆盖 [SchoolSyncEngine] 的全部不变量。
 */
internal class FakeSyncCodec(
    override val source: String = "fakeu",
    private val clock: () -> Long = System::currentTimeMillis,
) : SyncCodec<FakeRemoteTodo> {

    override fun localId(remoteKey: String): String {
        val parts = remoteKey.split(':')
        return "$source-${parts[1]}-${parts[2]}"
    }

    override fun matchesLocalId(id: String): Boolean = id.startsWith("$source-")

    override fun parseProfile(raw: String): RemoteProfile? {
        val parts = raw.split('\t')
        if (parts.size !in 2..3 || parts[0].isBlank()) return null
        return object : RemoteProfile {
            override val uid: String = parts[0]
            override val accountName: String = parts[1]
            override val avatarUrl: String? = parts.getOrNull(2)?.ifEmpty { null }
        }
    }

    override fun parseTodos(raw: String): List<FakeRemoteTodo>? {
        if (raw.isBlank()) return emptyList()
        return try {
            raw.lineSequence().filter { it.isNotBlank() }.map { line ->
                val f = line.split('\t')
                FakeRemoteTodo(
                    id = f[0].toLong(),
                    courseId = f[1].toLong(),
                    title = f[2],
                    courseName = f[3],
                    type = f[4],
                    endTime = f[5].ifEmpty { null },
                    locked = f[6] == "1",
                    student = f[7] == "1",
                )
            }.toList()
        } catch (_: Exception) {
            null
        }
    }

    override fun remoteKey(remote: FakeRemoteTodo): String =
        "$source:${remote.courseId}:${remote.id}"

    override fun remoteHash(remote: FakeRemoteTodo): String = sha256(
        listOf(remote.title, remote.endTime ?: "", remote.courseName, remote.type)
            .joinToString("|"),
    )

    override fun toTodo(remote: FakeRemoteTodo, fetchedAt: Long): Todo? {
        val deadline = deadline(remote.endTime) ?: return null
        return Todo(
            id = localId(remoteKey(remote)),
            name = remote.title.trim(),
            timeKind = TodoTimeKind.None,
            deadlineDate = deadline.date,
            deadlineMinute = deadline.minute,
            countdown = true,
            note = "课程：${remote.courseName}\n类型：${remote.type}",
            source = source,
            sourceDetail = remote.courseName.trim().ifEmpty { null },
        )
    }

    override fun skipReason(remote: FakeRemoteTodo): SkipReason? {
        if (!remote.student) return SkipReason.NotStudent
        if (remote.id <= 0L) return SkipReason.InvalidId
        if (remote.title.trim().isEmpty()) return SkipReason.BlankTitle
        val deadline = deadline(remote.endTime)
            ?: return if (remote.endTime.isNullOrBlank()) {
                SkipReason.NoDeadline
            } else {
                SkipReason.DeadlineUnparseable
            }
        if (remote.locked) return SkipReason.Locked
        if (deadline.millis <= clock()) return SkipReason.Overdue
        return null
    }

    override fun autoGroupKey(remote: FakeRemoteTodo): String? =
        when (remote.type.trim().lowercase()) {
            "homework" -> "$source:homework"
            "quiz" -> "$source:quiz"
            else -> null
        }

    override fun isOwned(todo: Todo): Boolean =
        todo.source == source || matchesLocalId(todo.id)

    private fun deadline(text: String?): Deadline? {
        val m = text?.let(DATE_TIME_UTC::matchEntire) ?: return null
        val (y, mo, d, h, mi) = m.destructured
        return try {
            val utc = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
                isLenient = false
                clear()
                set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), 0)
            }
            val sh = GregorianCalendar(TimeZone.getTimeZone("Asia/Shanghai")).apply {
                timeInMillis = utc.timeInMillis
            }
            Deadline(
                date = IsoDate.of(
                    sh.get(GregorianCalendar.YEAR),
                    sh.get(GregorianCalendar.MONTH) + 1,
                    sh.get(GregorianCalendar.DAY_OF_MONTH),
                ),
                minute = sh.get(GregorianCalendar.HOUR_OF_DAY) * 60 +
                    sh.get(GregorianCalendar.MINUTE),
                millis = utc.timeInMillis,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private data class Deadline(val date: IsoDate, val minute: Int, val millis: Long)

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val DATE_TIME_UTC = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})Z""")
    }
}

/** 最小学校声明（资源 id 全 0；引擎/注册表配置测试不解析资源） */
internal fun fakeSchoolSpec(
    id: String = "fakeu",
    capabilities: Set<Capability> = setOf(Capability.NATIVE_LOGIN),
): SchoolSpec = SchoolSpec(
    id = id,
    displayName = SchoolText.Res(0),
    capabilities = capabilities,
    headerTitle = SchoolText.Res(0),
    headerDescription = SchoolText.Res(0),
    introTitle = SchoolText.Res(0),
    introBody = SchoolText.Res(0),
    usernameLabel = SchoolText.Res(0),
    usernameHint = SchoolText.Res(0),
    loginBlankHint = SchoolText.Res(0),
    loginNotes = SchoolText.Res(0),
    loginSheetTitle = SchoolText.Res(0),
    sourceBadge = SchoolText.Res(0),
    groupHomework = SchoolText.Res(0),
    groupQuiz = SchoolText.Res(0),
    defaultScheduleName = SchoolText.Res(0),
    syncNotifTitle = SchoolText.Res(0),
)
