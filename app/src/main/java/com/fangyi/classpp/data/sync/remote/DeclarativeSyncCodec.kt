package com.fangyi.classpp.data.sync.remote

import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.sync.engine.RemoteProfile
import com.fangyi.classpp.data.sync.engine.SkipReason
import com.fangyi.classpp.data.sync.engine.SyncCodec
import java.security.MessageDigest
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 声明式同步编码器：把 [SchoolDeclaration.todo] 的映射规则接到 [SyncCodec] 契约上，
 * 使「WebView 登录 + JSON 待办接口」的学校零代码接入（数据即适配）。
 *
 * 与编译期编码器（如厦大）遵守同一组不变量与同一条哈希配方
 * （`title|endTime|courseName|type` 的 SHA-256，契约测试锁定）：
 * - 本地 id 口径 = [SchoolDeclaration.localIdTemplate] 渲染（声明校验强制 `<id>-` 开头）；
 * - 远端唯一键 = [SchoolDeclaration.remoteKeyTemplate] 渲染（键值来自声明 keyFields）；
 * - 跳过规则：NotStudent/InvalidId/BlankTitle/NoDeadline/DeadlineUnparseable/Locked/Overdue，
 *   顺序与既有实现一致；`skipReason` 与 `toTodo` 共用 [evaluate] 单一逻辑路径；
 * - 截止时间换算**不依赖设备时区**：固定 [SchoolDeclaration.EndTimeSpec zone]
 *   （缺省 Asia/Shanghai，与既有实现同口径），项目惯例规避 java.time（minSdk 24）。
 */
internal class DeclarativeSyncCodec(
    private val declaration: SchoolDeclaration,
    private val clock: () -> Long,
) : SyncCodec<JsonObject> {

    override val source: String = declaration.id

    private val todo = declaration.todo
    private val zone: TimeZone = DeclarationTime.zoneOf(todo.endTime)

    private val remoteKeyRegex = templateRegex(todo.remoteKeyTemplate)
    private val remoteKeyPlaceholders = placeholderNames(todo.remoteKeyTemplate)
    private val keyPathsByName = todo.keyFields.associate { it.name to it.path }

    /** 键值安全形态：无模板分隔符（`:`）、无路径分隔符、无控制字符，长度有界 */
    private val safeKeyValue = Regex("^[A-Za-z0-9_.-]{1,128}$")

    // ---------- 契约 ----------

    override fun localId(remoteKey: String): String {
        val values = remoteKeyRegex.matchEntire(remoteKey)
            ?.let { match -> remoteKeyPlaceholders.zip(match.groupValues.drop(1)).toMap() }
            // 防御：键必由本类渲染，非模板形态按哈希兜底（绝不让远端原文进本地 id）
            ?: return source + "-" + sha256(remoteKey).take(32)
        return render(todo.localIdTemplate, values)
    }

    override fun matchesLocalId(id: String): Boolean = id.startsWith("$source-")

    override fun parseProfile(raw: String): RemoteProfile? {
        val root = parseObject(raw) ?: return null
        val uid = root.stringAt(declaration.profile.uidPath)?.takeIf { it.isNotBlank() } ?: return null
        return object : RemoteProfile {
            override val uid: String = uid
            override val accountName: String =
                root.stringAt(declaration.profile.namePath).orEmpty()
            override val avatarUrl: String? =
                declaration.profile.avatar?.let { extractAvatar(root, it) }
        }
    }

    /** 登录态确认口径（WebView 驱动/浮层共用）：uid 路径的值非空即已登录 */
    fun profileValidator(): (String) -> Boolean = { raw ->
        runCatching {
            val root = parseObject(raw)
            root != null && !root.stringAt(declaration.profile.uidPath).isNullOrBlank()
        }.getOrDefault(false)
    }

    override fun parseTodos(raw: String): List<JsonObject>? {
        val root = parseObject(raw) ?: return null
        val list = root.at(todo.listPath) as? JsonArray ?: return null
        return list.mapNotNull { it as? JsonObject }
    }

    override fun remoteKey(remote: JsonObject): String =
        render(todo.remoteKeyTemplate, keyValues(remote))

    override fun remoteHash(remote: JsonObject): String = sha256(
        listOf(
            remote.stringAt(todo.fields.titlePath).orEmpty().trim(),
            remote.stringAt(todo.fields.endTimePath).orEmpty(),
            remote.stringAt(todo.fields.courseNamePath).orEmpty(),
            remote.stringAt(todo.fields.typePath).orEmpty(),
        ).joinToString("|"),
    )

    override fun skipReason(remote: JsonObject): SkipReason? =
        when (val outcome = evaluate(remote, clock())) {
            is Eval.Skip -> outcome.reason
            is Eval.Ok -> null
        }

    override fun toTodo(remote: JsonObject, fetchedAt: Long): Todo? =
        when (val outcome = evaluate(remote, fetchedAt)) {
            is Eval.Skip -> null
            is Eval.Ok -> outcome.todo
        }

    override fun autoGroupKey(remote: JsonObject): String? {
        val type = remote.stringAt(todo.fields.typePath)?.trim()?.lowercase() ?: return null
        val group = todo.autoGroups
            .firstOrNull { it.type.trim().lowercase() == type }
            ?.group
            ?: return null
        return "$source:$group"
    }

    override fun isOwned(todo: Todo): Boolean = todo.source == source || matchesLocalId(todo.id)

    // ---------- 映射（skipReason / toTodo 共用单一逻辑路径） ----------

    private sealed interface Eval {
        data class Skip(val reason: SkipReason) : Eval
        data class Ok(val todo: Todo) : Eval
    }

    private fun evaluate(remote: JsonObject, nowMillis: Long): Eval {
        val filters = todo.filters
        filters.requireStudent?.let { path ->
            if (remote.boolAt(path) == false) return Eval.Skip(SkipReason.NotStudent)
        }
        // 键值必须齐全且形态安全（进 remoteKey/localId 与落盘映射）：缺失/含分隔符/
        // 超长一律按无效条目跳过，防止远端响应把本地 id 撑坏
        val keyValues = keyValues(remote)
        if (keyValues.size != keyPathsByName.size || keyValues.values.any { !isSafeKeyValue(it) }) {
            return Eval.Skip(SkipReason.InvalidId)
        }
        // id 键值非正整数/缺失 = 无效条目（与既有实现 `id <= 0` 同口径）；未声明 id 键则不校验
        keyPathsByName["id"]?.let { path ->
            val value = remote.stringAt(path)?.toLongOrNull() ?: return Eval.Skip(SkipReason.InvalidId)
            if (value <= 0) return Eval.Skip(SkipReason.InvalidId)
        }
        val title = remote.stringAt(todo.fields.titlePath)?.trim().orEmpty()
        if (title.isEmpty()) return Eval.Skip(SkipReason.BlankTitle)
        val endText = todo.fields.endTimePath.takeIf { it.isNotEmpty() }
            ?.let { remote.stringAt(it) }
        val deadline = DeclarationTime.parseDeadline(endText, todo.endTime)
            ?: return Eval.Skip(
                if (endText.isNullOrBlank()) SkipReason.NoDeadline else SkipReason.DeadlineUnparseable,
            )
        filters.skipLocked?.let { path ->
            if (remote.boolAt(path) == true) return Eval.Skip(SkipReason.Locked)
        }
        if (filters.skipOverdue && DeclarationTime.isOverdue(deadline, nowMillis, zone)) {
            return Eval.Skip(SkipReason.Overdue)
        }
        val courseName = remote.stringAt(todo.fields.courseNamePath).orEmpty()
        return Eval.Ok(
            Todo(
                id = localId(remoteKey(remote)),
                name = title,
                timeKind = TodoTimeKind.None,
                deadlineDate = deadline.date,
                deadlineMinute = deadline.minute,
                countdown = true,
                note = renderNote(remote),
                source = source,
                sourceDetail = courseName.trim().ifEmpty { null },
            ),
        )
    }

    // ---------- 模板 / 路径 ----------

    private fun keyValues(remote: JsonObject): Map<String, String> =
        keyPathsByName.mapNotNull { (name, path) -> remote.stringAt(path)?.let { name to it } }.toMap()

    private fun isSafeKeyValue(value: String): Boolean = safeKeyValue.matches(value)

    /** `{name}` 占位渲染：未命中的占位符渲染为空串（键完整性由声明校验保证） */
    private fun render(template: String, values: Map<String, String>): String =
        DeclarationTodo.PLACEHOLDER.replace(template) { match -> values[match.groupValues[1]].orEmpty() }

    private fun placeholderNames(template: String): List<String> =
        DeclarationTodo.PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toList()

    private fun templateRegex(template: String): Regex = buildString {
        var index = 0
        for (match in DeclarationTodo.PLACEHOLDER.findAll(template)) {
            append(Regex.escape(template.substring(index, match.range.first)))
            append("(.+)")
            index = match.range.last + 1
        }
        append(Regex.escape(template.substring(index)))
    }.let(::Regex)

    private fun renderNote(remote: JsonObject): String {
        val url = todo.activityUrlTemplate?.takeIf { it.isNotBlank() }?.let { render(it, keyValues(remote)) }
        val values = keyValues(remote) + mapOf(
            "courseName" to remote.stringAt(todo.fields.courseNamePath).orEmpty(),
            "type" to remote.stringAt(todo.fields.typePath).orEmpty(),
            "activityUrl" to url.orEmpty(),
        )
        val template = todo.noteTemplate ?: if (url.isNullOrBlank()) {
            "课程：{courseName}\n类型：{type}"
        } else {
            "课程：{courseName}\n类型：{type}\n{activityUrl}"
        }
        return render(template, values)
    }

    // ---------- JSON 读取 ----------

    private fun parseObject(raw: String): JsonObject? =
        runCatching { ScheduleJson.storage.parseToJsonElement(raw) as? JsonObject }.getOrNull()

    /** dot-path 逐层下探：中途遇非对象/缺键返回 null（由调用方按形态分类） */
    private fun JsonElement.at(path: String): JsonElement? {
        var current = this
        for (segment in path.split('.')) {
            val obj = current as? JsonObject ?: return null
            current = obj[segment] ?: return null
        }
        return current
    }

    private fun JsonElement.stringAt(path: String): String? =
        (at(path) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonElement.boolAt(path: String): Boolean? =
        (at(path) as? JsonPrimitive)?.booleanOrNull

    /** 头像地址：候选键顶层 → 容器键；相对路径按声明基准补全，仅接受 http(s) */
    private fun extractAvatar(root: JsonObject, avatar: DeclarationAvatar): String? {
        val pick: (JsonObject) -> String? = { obj ->
            avatar.candidates.firstNotNullOfOrNull { key ->
                (obj[key] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
        }
        val raw = pick(root)
            ?: listOf("user", "profile", "data").firstNotNullOfOrNull { container ->
                (root[container] as? JsonObject)?.let(pick)
            }
            ?: return null
        return when {
            raw.startsWith("https://", true) || raw.startsWith("http://", true) -> raw
            raw.startsWith("/") -> avatar.baseUrl?.let { base -> base.trimEnd('/') + raw }
            else -> null
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * 声明式截止时间换算：[DeclarationEndTime] 的固定时区口径（缺省 Asia/Shanghai），
 * **不依赖设备时区**（与既有实现一致——设备不在 +8 时截止日整体偏移不可接受）。
 * ISO 形态 = UTC `Z` 结尾（既有实现同款正则）；[SimpleDateFormat] 模式按声明时区直读。
 */
internal object DeclarationTime {

    /** 截止时刻：声明时区日期 + 当天分钟数（0..1439） */
    data class Deadline(val date: IsoDate, val minute: Int)

    internal const val DEFAULT_ZONE = "Asia/Shanghai"

    private val UTC = TimeZone.getTimeZone("UTC")
    private val ISO_UTC = Regex(
        """^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?Z$""",
    )

    fun zoneOf(spec: DeclarationEndTime?): TimeZone = TimeZone.getTimeZone(spec?.zone ?: DEFAULT_ZONE)

    /** 空串/格式不符/非法日期返回 null（映射层按原因分类跳过）；秒截断到分钟 */
    fun parseDeadline(text: String?, spec: DeclarationEndTime?): Deadline? {
        if (text.isNullOrBlank()) return null
        val zone = zoneOf(spec)
        val millis: Long = when {
            spec == null || spec.format == DeclarationTodo.FORMAT_ISO -> {
                val match = ISO_UTC.matchEntire(text.trim()) ?: return null
                val (y, mo, d, h, mi, s) = match.destructured
                try {
                    // 非法日期（如 2 月 30 日）在取 timeInMillis 时才校验，必须包在 try 内
                    GregorianCalendar(UTC).apply {
                        isLenient = false
                        clear()
                        set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), s.toIntOrNull() ?: 0)
                    }.timeInMillis
                } catch (_: IllegalArgumentException) {
                    return null
                }
            }
            else -> {
                val pattern = spec.pattern ?: return null
                try {
                    val format = SimpleDateFormat(pattern, Locale.US).apply {
                        isLenient = false
                        timeZone = zone
                    }
                    val trimmed = text.trim()
                    val position = ParsePosition(0)
                    val instant = format.parse(trimmed, position) ?: return null
                    // 必须整串消费：SimpleDateFormat.parse 默认忽略尾部残留（防止半截时间被误读）
                    if (position.index != trimmed.length) return null
                    instant.time
                } catch (_: Exception) {
                    return null
                }
            }
        }
        val shifted = GregorianCalendar(zone).apply { timeInMillis = millis }
        return Deadline(
            date = IsoDate.of(
                shifted.get(GregorianCalendar.YEAR),
                shifted.get(GregorianCalendar.MONTH) + 1,
                shifted.get(GregorianCalendar.DAY_OF_MONTH),
            ),
            minute = shifted.get(GregorianCalendar.HOUR_OF_DAY) * 60 +
                shifted.get(GregorianCalendar.MINUTE),
        )
    }

    /** 截止是否已过（同一声明时区口径、分钟粒度）；不用 IsoDate.today（设备时区） */
    fun isOverdue(deadline: Deadline, nowMillis: Long, zone: TimeZone): Boolean {
        val cal = GregorianCalendar(zone).apply { timeInMillis = nowMillis }
        val today = IsoDate.of(
            cal.get(GregorianCalendar.YEAR),
            cal.get(GregorianCalendar.MONTH) + 1,
            cal.get(GregorianCalendar.DAY_OF_MONTH),
        )
        val nowMinute = cal.get(GregorianCalendar.HOUR_OF_DAY) * 60 +
            cal.get(GregorianCalendar.MINUTE)
        return deadline.date.epochDay < today.epochDay ||
            (deadline.date.epochDay == today.epochDay && deadline.minute < nowMinute)
    }
}
