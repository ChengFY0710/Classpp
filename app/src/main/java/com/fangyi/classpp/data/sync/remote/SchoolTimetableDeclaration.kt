package com.fangyi.classpp.data.sync.remote

import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.data.sync.WebViewTimetableSpec
import com.fangyi.classpp.data.sync.webview.WebViewPolicy
import java.util.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 远端声明里的可选「教务课表」块：声明式接口 + 字段路径，**不接收远端 JS**。
 *
 * App 用固定脚本在已登录的课表页内按 [requests] 同源取数（页面学期 → 可选学生信息 →
 * 排课结果/作息/当前周），把原始数组与学期信息回传给通用映射器；字段路径只在 App 侧
 * 解释，远端无法注入代码。所有 host 仍受 `.edu.cn` 信任边界与 `WebViewPolicy` 加固约束。
 *
 * 旧客户端会因 `ignoreUnknownKeys` 忽略本块，继续只同步待办；新客户端由本块推导
 * `Capability.SCHEDULE_IMPORT`（JSON 的 `capabilities` 仍只写 `WEBVIEW_LOGIN`）。
 */
@Serializable
data class DeclarationTimetable(
    val entryUrl: String,
    val allowedHosts: List<String>,
    /** null/空 = 系统 UA；`desktop` = 内置桌面 UA */
    val userAgent: String? = null,
    /** 账号页「教务课表导入」介绍卡 */
    val introTitle: LocalizedText,
    val introBody: LocalizedText,
    /** 接口未给学期名时的兜底课表名 */
    val defaultName: LocalizedText,
    /** 接口基准路径，如 `/gsapp/sys/wdkbapp`；与 [baseMarker] 二选一 */
    val basePath: String? = null,
    /** 从页面 URL 推导 context path 的标记，如 `/sys/wdkbapp/`；与 [basePath] 二选一 */
    val baseMarker: String? = null,
    val semester: DeclarationTimetableSemester,
    val requests: DeclarationTimetableRequests,
    /** 作息接口映射；与 [fixedSlots] 二选一 */
    val slots: DeclarationSlotMapping? = null,
    /** 固定作息（课程用位置引用节次）；与 [slots] 二选一 */
    val fixedSlots: List<DeclarationFixedSlot> = emptyList(),
    val courses: DeclarationCourseMapping,
    val term: DeclarationTerm = DeclarationTerm(),
) {

    /** 声明合法性问题清单；空 = 可装配。前缀由 [SchoolDeclaration.validate] 添加 */
    internal fun validate(): List<String> = buildList {
        localizeIssues("introTitle", introTitle)
        localizeIssues("introBody", introBody)
        localizeIssues("defaultName", defaultName)
        userAgent?.takeIf { it.isNotBlank() }?.let {
            if (it.length > DeclarationWebView.MAX_UA_LENGTH) add("userAgent too long")
        }
        WebViewPolicy.validateTimetable(
            WebViewTimetableSpec(
                entryUrl = entryUrl,
                allowedHosts = allowedHosts.toSet(),
                userAgent = resolveUserAgent(),
            ),
        ).forEach { add(it) }
        allowedHosts.filterNot { DeclarationWebView.isEduCnHost(it) }
            .forEach { add("host '$it' is outside $EDU_CN_SUFFIX") }

        if (basePath != null && baseMarker != null) {
            add("basePath and baseMarker are mutually exclusive")
        }
        basePath?.let { if (!isSafeBasePath(it)) add("basePath invalid") }
        baseMarker?.let { if (!isSafeBaseMarker(it)) add("baseMarker invalid") }

        validateSemester()
        validateRequests()
        validateSlots()
        validateCourses()
        validateTerm()
    }

    private fun MutableList<String>.validateSemester() {
        val semester = semester
        if (semester.selector.isNullOrBlank() && semester.global.isNullOrBlank()) {
            add("semester requires selector or global")
        }
        semester.selector?.let {
            if (!isSafeSelector(it)) add("semester.selector invalid")
        }
        semester.global?.let {
            if (!GLOBAL_PATTERN.matches(it)) add("semester.global invalid")
            if (semester.codePath.isNullOrBlank()) add("semester.codePath required with global")
        }
        semester.codePath?.takeIf { it.isNotBlank() }?.let {
            if (!DeclarationPath.isValid(it)) add("semester.codePath invalid")
        }
        semester.labelPath?.takeIf { it.isNotBlank() }?.let {
            if (!DeclarationPath.isValid(it)) add("semester.labelPath invalid")
        }
    }

    private fun MutableList<String>.validateRequests() {
        val requests = requests
        requests.student?.let { request ->
            validateRequest("student", request)
            val idPath = request.idPath
            if (idPath.isNullOrBlank()) {
                add("requests.student.idPath required")
            } else if (!DeclarationPath.isValid(idPath)) {
                add("requests.student.idPath invalid")
            }
        }
        validateRequest("schedule", requests.schedule)
        requests.slots?.let { validateRequest("slots", it) }
        requests.week?.let { validateRequest("week", it) }
    }

    private fun MutableList<String>.validateRequest(
        id: String,
        request: DeclarationTimetableRequest,
    ) {
        if (!isSafeRelativePath(request.path)) add("requests.$id.path invalid")
        if (request.method.uppercase() !in setOf("GET", "POST")) {
            add("requests.$id.method must be GET or POST")
        }
        for ((name, value) in request.fields) {
            if (!FIELD_NAME.matches(name)) add("requests.$id.fields['$name'] invalid")
            if (!isSafeFieldValue(value)) add("requests.$id.fields['$name'] invalid value")
            if (value.contains("{studentId}") && requests.student == null) {
                add("requests.$id.fields['$name'] uses {studentId} without a student request")
            }
        }
        request.idPath?.takeIf { id != "student" && it.isNotBlank() }?.let {
            if (!DeclarationPath.isValid(it)) add("requests.$id.idPath invalid")
        }
    }

    private fun MutableList<String>.validateSlots() {
        val slots = slots
        if (slots == null && fixedSlots.isEmpty()) {
            add("slots or fixedSlots is required")
        }
        if (slots != null && fixedSlots.isNotEmpty()) {
            add("slots and fixedSlots are mutually exclusive")
        }
        slots?.let { mapping ->
            if (requests.byId(mapping.request) == null) {
                add("slots.request '${mapping.request}' is not declared")
            }
            if (!DeclarationPath.isValid(mapping.listPath)) add("slots.listPath invalid")
            if (mapping.fields.code.isBlank() || !DeclarationPath.isValid(mapping.fields.code)) {
                add("slots.fields.code invalid")
            }
            if (!DeclarationPath.isValid(mapping.fields.start)) add("slots.fields.start invalid")
            if (!DeclarationPath.isValid(mapping.fields.end)) add("slots.fields.end invalid")
            mapping.fields.name?.let {
                if (!DeclarationPath.isValid(it)) add("slots.fields.name invalid")
            }
            mapping.fields.order?.let {
                if (!DeclarationPath.isValid(it)) add("slots.fields.order invalid")
            }
            if (mapping.timeFormat !in TIME_FORMATS) add("slots.timeFormat invalid")
            mapping.excludeNameContains?.let {
                if (!isSafePlainText(it)) add("slots.excludeNameContains invalid")
            }
        }
        if (fixedSlots.isNotEmpty()) {
            if (fixedSlots.size > MAX_SLOTS) add("fixedSlots too many")
            var previousEnd = 0
            for ((index, slot) in fixedSlots.withIndex()) {
                val start = TimeText.normalize(slot.start)?.let(TimeText::parseMinutes)
                val end = TimeText.normalize(slot.end)?.let(TimeText::parseMinutes)
                if (start == null) add("fixedSlots[$index].start invalid")
                if (end == null) add("fixedSlots[$index].end invalid")
                if (start != null && end != null) {
                    if (start >= end) add("fixedSlots[$index] start must be before end")
                    if (previousEnd > start) add("fixedSlots[$index] overlaps previous slot")
                    previousEnd = end
                }
            }
        }
    }

    private fun MutableList<String>.validateCourses() {
        val courses = courses
        if (requests.byId(courses.request) == null) {
            add("courses.request '${courses.request}' is not declared")
        }
        if (!DeclarationPath.isValid(courses.listPath)) add("courses.listPath invalid")
        val fields = courses.fields
        listOf(
            "name" to fields.name,
            "day" to fields.day,
            "startSlot" to fields.startSlot,
            "endSlot" to fields.endSlot,
            "weeks" to fields.weeks,
        ).forEach { (name, path) ->
            if (path.isBlank() || !DeclarationPath.isValid(path)) {
                add("courses.fields.$name invalid")
            }
        }
        listOf(
            "teacher" to fields.teacher,
            "location" to fields.location,
            "note" to fields.note,
            "code" to fields.code,
        ).forEach { (name, path) ->
            path?.takeIf { it.isNotBlank() }?.let {
                if (!DeclarationPath.isValid(it)) add("courses.fields.$name invalid")
            }
        }
        if (courses.weeksFormat != WEEKS_BITMAP) add("courses.weeksFormat must be $WEEKS_BITMAP")
        if (courses.slotRef !in SLOT_REFS) add("courses.slotRef invalid")
        if (fixedSlots.isNotEmpty() && courses.slotRef != SLOT_INDEX) {
            add("courses.slotRef must be $SLOT_INDEX when fixedSlots is used")
        }
    }

    private fun MutableList<String>.validateTerm() {
        val term = term
        when (term.source) {
            TERM_CURRENT_WEEK -> {
                if (term.request.isNullOrBlank()) add("term.request required for $TERM_CURRENT_WEEK")
                if (term.path.isNullOrBlank() || !DeclarationPath.isValid(term.path)) {
                    add("term.path invalid")
                }
            }
            TERM_DATE -> {
                if (term.request.isNullOrBlank()) add("term.request required for $TERM_DATE")
                if (term.path.isNullOrBlank() || !DeclarationPath.isValid(term.path)) {
                    add("term.path invalid")
                }
                if (term.pattern.isNullOrBlank()) add("term.pattern required for $TERM_DATE")
                term.zone?.takeIf { it.isNotBlank() }?.let {
                    if (it !in TimeZone.getAvailableIDs()) add("term.zone '$it' unknown")
                }
            }
            else -> add("term.source invalid")
        }
        term.request?.takeIf { it.isNotBlank() }?.let {
            if (requests.byId(it) == null) add("term.request '$it' is not declared")
        }
        term.pattern?.takeIf { it.isNotBlank() }?.let {
            if (it.length > MAX_PATTERN_LENGTH || it.any(Char::isISOControl)) {
                add("term.pattern invalid")
            }
        }
    }

    private fun MutableList<String>.localizeIssues(field: String, text: LocalizedText) {
        text.en?.takeIf { it.isBlank() }?.let { add("$field.en must not be blank") }
    }

    /** 解析 "desktop" 别名；空串/null = 系统 UA */
    internal fun resolveUserAgent(): String? =
        when (val ua = userAgent?.takeUnless { it.isBlank() }) {
            null -> null
            SchoolDeclaration.UA_ALIAS_DESKTOP -> DECLARATION_DESKTOP_USER_AGENT
            else -> ua
        }

    /** 转成通用 WebView 页声明（脚本由 App 生成） */
    fun toWebViewTimetableSpec(script: String): WebViewTimetableSpec = WebViewTimetableSpec(
        entryUrl = entryUrl,
        allowedHosts = allowedHosts.toSet(),
        userAgent = resolveUserAgent(),
        importScript = script,
    )

    internal companion object {
        const val TIME_HHMM = "HHMM"
        const val TIME_HMM = "H_MM"
        const val TIME_MINUTES = "MINUTES"
        const val WEEKS_BITMAP = "BITMAP"
        const val SLOT_CODE = "CODE"
        const val SLOT_INDEX = "INDEX"
        const val TERM_CURRENT_WEEK = "CURRENT_WEEK"
        const val TERM_DATE = "DATE"

        internal val TIME_FORMATS = setOf(TIME_HHMM, TIME_HMM, TIME_MINUTES)
        internal val SLOT_REFS = setOf(SLOT_CODE, SLOT_INDEX)

        private const val MAX_SLOTS = 30
        private const val MAX_PATTERN_LENGTH = 100
        private const val MAX_PATH_LENGTH = 500
        private const val MAX_SELECTOR_LENGTH = 200
        private const val MAX_TEXT_LENGTH = 500
        private val FIELD_NAME = Regex("[A-Za-z0-9_]+")
        private val GLOBAL_PATTERN = Regex("[A-Za-z0-9_$]+")

        private fun isSafeRelativePath(path: String): Boolean =
            path.isNotBlank() && path.length <= MAX_PATH_LENGTH &&
                path.startsWith("/") && !path.contains("://") && !path.contains("..") &&
                !path.contains('\\') && path.none(Char::isISOControl)

        private fun isSafeBasePath(path: String): Boolean =
            path.isNotBlank() && path.length <= MAX_PATH_LENGTH &&
                path.startsWith("/") && !path.contains("..") && !path.contains('\\') &&
                path.none(Char::isISOControl) && !path.contains("://")

        private fun isSafeBaseMarker(marker: String): Boolean =
            marker.length <= MAX_PATH_LENGTH && marker.startsWith("/") &&
                marker.endsWith("/") && !marker.contains("..") && !marker.contains('\\') &&
                marker.none(Char::isISOControl) && !marker.contains("://")

        private fun isSafeSelector(selector: String): Boolean =
            selector.isNotBlank() && selector.length <= MAX_SELECTOR_LENGTH &&
                !selector.contains('\\') && selector.none(Char::isISOControl)

        private fun isSafePlainText(text: String): Boolean =
            text.length <= MAX_TEXT_LENGTH && !text.contains('\\') && text.none(Char::isISOControl)

        /** 请求字段值：允许三种模板占位符，禁控制字符/反斜杠/未闭合或未知占位符 */
        private fun isSafeFieldValue(value: String): Boolean {
            if (value.length > MAX_TEXT_LENGTH || value.any(Char::isISOControl)) return false
            if (value.contains('\\')) return false
            var index = 0
            while (true) {
                val open = value.indexOf('{', index)
                val close = value.indexOf('}', index)
                if (open < 0 && close < 0) return true
                if (open < 0 || close < open) return false
                val name = value.substring(open + 1, close)
                if (name !in TEMPLATE_PLACEHOLDERS) return false
                index = close + 1
            }
        }

        internal val TEMPLATE_PLACEHOLDERS = setOf("semesterCode", "semesterLabel", "studentId")
    }
}

@Serializable
data class DeclarationTimetableSemester(
    /** CSS 选择器；读取选中值与该 option 的文本 */
    val selector: String? = null,
    /** 页面全局变量名；selector 取不到时兜底 */
    val global: String? = null,
    /** global 对象里的学期代码路径（如 `XNXQDM`） */
    val codePath: String? = null,
    /** global 对象里的学期展示名路径（如 `XNXQDM_DISPLAY`） */
    val labelPath: String? = null,
)

@Serializable
data class DeclarationTimetableRequests(
    /** 可选：从响应取学号，供后续请求的 `{studentId}` 使用 */
    val student: DeclarationTimetableRequest? = null,
    /** 必需：排课结果接口 */
    val schedule: DeclarationTimetableRequest,
    /** 可选：作息接口 */
    val slots: DeclarationTimetableRequest? = null,
    /** 可选：当前周次接口（term=CURRENT_WEEK 时必需） */
    val week: DeclarationTimetableRequest? = null,
) {
    internal fun byId(id: String): DeclarationTimetableRequest? = when (id) {
        "student" -> student
        "schedule" -> schedule
        "slots" -> slots
        "week" -> week
        else -> null
    }
}

@Serializable
data class DeclarationTimetableRequest(
    /** 同源相对路径（`/` 开头，禁 `://`/`..`/反斜杠） */
    val path: String,
    /** GET 或 POST（大小写不敏感） */
    val method: String = "POST",
    /** 表单字段；值可用 `{semesterCode}` / `{semesterLabel}` / `{studentId}` */
    val fields: Map<String, String> = emptyMap(),
    /** 仅 student 请求使用：从响应用 array-aware dot-path 取学号 */
    val idPath: String? = null,
)

@Serializable
data class DeclarationSlotMapping(
    /** 使用哪个已声明请求（`schedule` / `slots` / `week` / `student`） */
    val request: String,
    /** 作息数组在响应里的 array-aware dot-path */
    val listPath: String,
    val fields: DeclarationSlotFields,
    /** HHMM（800/1430）| H_MM（8:00）| MINUTES（480） */
    val timeFormat: String = DeclarationTimetable.TIME_HHMM,
    /** 名称包含该文本的行剔除（如「中午」）；null = 不剔除 */
    val excludeNameContains: String? = null,
)

@Serializable
data class DeclarationSlotFields(
    val code: String,
    val name: String? = null,
    val order: String? = null,
    val start: String,
    val end: String,
)

@Serializable
data class DeclarationFixedSlot(val start: String, val end: String)

@Serializable
data class DeclarationCourseMapping(
    /** 使用哪个已声明请求（通常 `schedule`） */
    val request: String,
    /** 排课数组在响应里的 array-aware dot-path */
    val listPath: String,
    val fields: DeclarationCourseFields,
    /** 首版只支持位图 */
    val weeksFormat: String = DeclarationTimetable.WEEKS_BITMAP,
    /** CODE = 起止节次字段是节次代码（配合 slots 接口）；INDEX = 直接是 1-based 位置 */
    val slotRef: String = DeclarationTimetable.SLOT_CODE,
    /** 同课按「代码/名称/星期/起止/周次」拆行合并教师与教室 */
    val mergeSameSessions: Boolean = true,
)

@Serializable
data class DeclarationCourseFields(
    val name: String,
    val teacher: String? = null,
    val location: String? = null,
    val day: String,
    val startSlot: String,
    val endSlot: String,
    val weeks: String,
    val note: String? = null,
    /** 可选：参与同课拆行合并分组的课程代码 */
    val code: String? = null,
)

@Serializable
data class DeclarationTerm(
    /** CURRENT_WEEK = 当前周 + 今天反推；DATE = 从响应字段按 pattern 解析 */
    val source: String = DeclarationTimetable.TERM_CURRENT_WEEK,
    /** 读取当前周/开学日的请求 id */
    val request: String? = null,
    /** 读取当前周/开学日的 array-aware dot-path */
    val path: String? = null,
    /** DATE 时的 SimpleDateFormat 模式，必须整串匹配 */
    val pattern: String? = null,
    /** DATE 时的固定时区，缺省 Asia/Shanghai */
    val zone: String? = null,
)

/**
 * 声明里的 array-aware dot-path：对象逐层下探，数字段可索引数组。
 * 仅支持 `[A-Za-z0-9_]` 段，足够表达教务 JSON，且不引入任意表达式。
 */
internal object DeclarationPath {

    private val SEGMENT = Regex("[A-Za-z0-9_]+")
    private const val MAX_LENGTH = 200

    fun isValid(path: String): Boolean =
        path.isNotBlank() && path.length <= MAX_LENGTH &&
            path.split('.').all { SEGMENT.matches(it) }

    fun at(root: JsonElement?, path: String?): JsonElement? {
        if (root == null || path.isNullOrBlank()) return null
        var current: JsonElement = root
        for (segment in path.split('.')) {
            current = when (val node = current) {
                is JsonObject -> node[segment] ?: return null
                is JsonArray -> {
                    val index = segment.toIntOrNull() ?: return null
                    if (index !in 0 until node.size) return null
                    node[index]
                }
                else -> return null
            }
        }
        return current
    }

    fun stringAt(root: JsonElement?, path: String?): String? =
        (at(root, path) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    fun intAt(root: JsonElement?, path: String?): Int? =
        stringAt(root, path)?.trim()?.toIntOrNull()

    fun arrayAt(root: JsonElement?, path: String?): JsonArray? =
        at(root, path) as? JsonArray
}
