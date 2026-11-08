package com.fangyi.classpp.data.sync.remote

import com.fangyi.classpp.R
import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.sync.Capability
import com.fangyi.classpp.data.sync.SchoolSpec
import com.fangyi.classpp.data.sync.SchoolText
import com.fangyi.classpp.data.sync.TimetableIntro
import com.fangyi.classpp.data.sync.WebViewLoginSpec
import com.fangyi.classpp.data.sync.webview.WebViewPolicy
import java.util.TimeZone
import kotlinx.serialization.Serializable

/** 远端声明 schema 的当前支持版本：高于它的声明按「需要更新 App」跳过（不注册） */
const val SCHOOL_DECLARATION_SCHEMA_VERSION = 1

/** 远端声明学校的登录域信任边界：所有 host 必须为中国高校教育域名（含子域） */
internal val EDU_CN_SUFFIX = ".edu.cn"

/**
 * 桌面 UA：lms 类平台按 UA 分流（移动 UA 会 302 到移动门户，白名单导航被吞），
 * 声明里 `userAgent = "desktop"` 时使用；也可以直接写任意自定义 UA 字符串。
 */
internal const val DECLARATION_DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

/** 远端仓库的清单文件：`schools` 里的每一项对应一个声明文件 */
@Serializable
data class SchoolRemoteIndex(
    val schemaVersion: Int = SCHOOL_DECLARATION_SCHEMA_VERSION,
    val schools: List<SchoolRemoteEntry> = emptyList(),
) {

    /** 清单合法性：版本、id 唯一、文件路径形态（单条坏条目由拉取层跳过） */
    fun validate(): List<String> = buildList {
        if (schemaVersion < 1 || schemaVersion > SCHOOL_DECLARATION_SCHEMA_VERSION) {
            add("unsupported schemaVersion $schemaVersion (supported ≤ $SCHOOL_DECLARATION_SCHEMA_VERSION)")
        }
        val seen = HashSet<String>()
        for (entry in schools) {
            if (!SchoolDeclaration.SCHOOL_ID_PATTERN.matches(entry.id)) {
                add("invalid school id '${entry.id}'")
            }
            if (!seen.add(entry.id)) add("duplicate school id '${entry.id}'")
            if (!isSafeRelativeFile(entry.file)) add("invalid file path '${entry.file}'")
        }
    }

    internal companion object {
        /** 声明文件路径只允许仓库内相对路径（防路径穿越/外链/非 JSON） */
        internal fun isSafeRelativeFile(file: String): Boolean {
            if (file.isBlank() || file.length > MAX_FILE_LENGTH) return false
            if (file.any { it.isISOControl() }) return false
            if (file.contains("://") || file.contains('\\') || file.startsWith('/')) return false
            if (file.split('/').any { it == ".." || it == "." || it.isBlank() }) return false
            return file.endsWith(".json")
        }

        private const val MAX_FILE_LENGTH = 200
    }
}

@Serializable
data class SchoolRemoteEntry(
    /** 学校 id（须匹配学校 id 规则；缓存文件名也按它派生，防路径注入） */
    val id: String,
    /** 相对 [com.fangyi.classpp.data.sync.remote.SchoolRemoteConfig.BASE_URL] 的声明文件路径 */
    val file: String,
)

/**
 * 一所远端声明学校的完整声明（Tier 1：WebView 登录 + JSON 待办，可选声明式教务课表导入）。
 *
 * 校验在 [validate]：registry 的 id 规则、edu.cn 信任边界、`WebViewPolicy.validate`
 * 加固声明、模板/路径形态——校验通过才允许装配；装配期 `SchoolRegistry` 还会做
 * capabilities/槽位一致性与跨校白名单重叠的二次硬校验。
 */
@Serializable
data class SchoolDeclaration(
    val schemaVersion: Int = SCHOOL_DECLARATION_SCHEMA_VERSION,
    val id: String,
    /**
     * 能力声明：Tier 1 登录能力恒为 `["WEBVIEW_LOGIN"]`。教务课表导入是**可选 timetable
     * 块**（[timetable]），不写进本列表：旧客户端会忽略该块、降级为仅同步待办，而不是
     * 因未知能力整校拒绝。新客户端在 [toSpec] 里由该块推导 `SCHEDULE_IMPORT`。
     */
    val capabilities: List<String> = listOf(CAPABILITY_WEBVIEW_LOGIN),
    /** 学校名（选择卡/入口卡行值） */
    val displayName: LocalizedText,
    /** WebView 登录与取数声明（Tier 1 唯一登录方式） */
    val webView: DeclarationWebView,
    /** 页面文案（header/intro/浮层标题/徽标/分组名/通知标题 + 可空槽位覆盖） */
    val copy: DeclarationCopy,
    /** /profile 探活响应的字段路径与头像声明 */
    val profile: DeclarationProfile,
    /** /todos 待办列表的映射声明（声明式 codec 的全部输入） */
    val todo: DeclarationTodo,
    /**
     * 可选：教务课表页的声明式接口与字段映射（见 [DeclarationTimetable]）。
     * null = 不提供教务导入；旧客户端读取带本块的声明时会忽略它。
     */
    val timetable: DeclarationTimetable? = null,
) {

    /** 声明合法性问题清单；空 = 可装配 */
    fun validate(): List<String> = buildList {
        if (schemaVersion < 1 || schemaVersion > SCHOOL_DECLARATION_SCHEMA_VERSION) {
            add("unsupported schemaVersion $schemaVersion (supported ≤ $SCHOOL_DECLARATION_SCHEMA_VERSION)")
        }
        if (!SCHOOL_ID_PATTERN.matches(id)) add("invalid id '$id'")
        if (capabilities.size != 1 || capabilities.singleOrNull() != CAPABILITY_WEBVIEW_LOGIN) {
            add("capabilities must be exactly [\"$CAPABILITY_WEBVIEW_LOGIN\"]")
        }
        displayName.en?.takeIf { it.isBlank() }?.let { add("displayName.en must not be blank") }
        webView.validate().forEach { add("webView.$it") }
        copy.validate().forEach { add("copy.$it") }
        profile.validate().forEach { add("profile.$it") }
        todo.validate(id).forEach { add("todo.$it") }
        timetable?.validate()?.forEach { add("timetable.$it") }
    }

    /** 转成注册表/UI 消费的通用 spec（文案全部 Inline，[SchoolSpec.remote] = true） */
    fun toSpec(): SchoolSpec {
        val issues = validate()
        require(issues.isEmpty()) { "invalid school declaration '$id': ${issues.joinToString("; ")}" }
        val timetable = timetable
        return SchoolSpec(
            id = id,
            displayName = displayName.toSchoolText(),
            capabilities = buildSet {
                add(Capability.WEBVIEW_LOGIN)
                if (timetable != null) add(Capability.SCHEDULE_IMPORT)
            },
            headerTitle = copy.headerTitle.toSchoolText(),
            headerDescription = copy.headerDescription.toSchoolText(),
            introTitle = copy.introTitle.toSchoolText(),
            introBody = copy.introBody.toSchoolText(),
            // 远端声明学校无原生登录：原生登录专属槽位填中性占位（永不渲染，仅满足契约）
            usernameLabel = SchoolText.Res(R.string.sync_login_username_label),
            usernameHint = SchoolText.Res(R.string.sync_login_username_hint),
            loginBlankHint = SchoolText.Res(R.string.sync_login_blank_hint),
            loginNotes = SchoolText.Res(R.string.sync_login_notes),
            loginSheetTitle = copy.loginSheetTitle.toSchoolText(),
            sourceBadge = copy.sourceBadge.toSchoolText(),
            groupHomework = copy.groupHomework.toSchoolText(),
            groupQuiz = copy.groupQuiz.toSchoolText(),
            // 有 timetable 块才声明课表导入；无则保持中性占位（永不渲染）
            defaultScheduleName = timetable?.defaultName?.toSchoolText()
                ?: SchoolText.Res(R.string.sync_import_default_name),
            syncNotifTitle = copy.syncNotifTitle.toSchoolText(),
            accountChanged = copy.accountChanged?.toSchoolText(),
            logoutMessage = copy.logoutMessage?.toSchoolText(),
            switchMessage = copy.switchMessage?.toSchoolText(),
            serviceBusy = copy.serviceBusy?.toSchoolText(),
            invalidCredentials = copy.invalidCredentials?.toSchoolText(),
            sessionNotEstablished = copy.sessionNotEstablished?.toSchoolText(),
            autoLoginDisableMessage = copy.autoLoginDisableMessage?.toSchoolText(),
            loginImages = emptyList(),
            timetableIntro = timetable?.let { block ->
                TimetableIntro(
                    title = block.introTitle.toSchoolText(),
                    body = block.introBody.toSchoolText(),
                )
            },
            remote = true,
        )
    }

    /** WebView 登录声明（registry 装配时经 `WebViewPolicy.validate` 二次硬校验） */
    fun toWebViewLoginSpec(): WebViewLoginSpec = WebViewLoginSpec(
        entryUrl = webView.entryUrl,
        allowedHosts = webView.allowedHosts.toSet(),
        dataHost = webView.dataHost,
        profilePath = webView.profilePath,
        todosPath = webView.todosPath,
        userAgent = when (val ua = webView.userAgent?.takeUnless { it.isBlank() }) {
            null -> null // 未声明 = 系统 UA
            UA_ALIAS_DESKTOP -> DECLARATION_DESKTOP_USER_AGENT
            else -> ua
        },
    )

    companion object {
        internal val SCHOOL_ID_PATTERN = Regex("^[a-z][a-z0-9_]{0,31}$")
        internal const val UA_ALIAS_DESKTOP = "desktop"
        internal const val CAPABILITY_WEBVIEW_LOGIN = "WEBVIEW_LOGIN"

        fun decode(text: String): SchoolDeclaration =
            ScheduleJson.decodeStorage<SchoolDeclaration>(text)
    }
}

/** zh 必备、en 可缺的内联文案（远端声明无编译期资源） */
@Serializable
data class LocalizedText(val zh: String, val en: String? = null) {

    init {
        require(zh.isNotBlank()) { "zh text must not be blank" }
    }

    internal fun toSchoolText(): SchoolText = SchoolText.Inline(zh, en)
}

@Serializable
data class DeclarationWebView(
    val entryUrl: String,
    val allowedHosts: List<String>,
    val dataHost: String,
    val profilePath: String,
    val todosPath: String,
    /** 自定义 UA 或 "desktop"（见 [DECLARATION_DESKTOP_USER_AGENT]）；空 = 系统 UA */
    val userAgent: String? = null,
) {

    internal fun validate(): List<String> = buildList {
        val spec = WebViewLoginSpec(
            entryUrl = entryUrl,
            allowedHosts = allowedHosts.toSet(),
            dataHost = dataHost,
            profilePath = profilePath,
            todosPath = todosPath,
            userAgent = userAgent?.takeUnless { it.isBlank() },
        )
        WebViewPolicy.validate(spec).forEach { add(it) }
        // 信任边界（Tier 1）：远端白名单不再来自编译期代码，全部锁教育域名
        val hosts = allowedHosts + dataHost
        hosts.filterNot { DeclarationWebView.isEduCnHost(it) }
            .forEach { add("host '$it' is outside $EDU_CN_SUFFIX") }
        if (dataHost !in allowedHosts) add("dataHost must be inside allowedHosts")
        if (userAgent != null && userAgent.length > MAX_UA_LENGTH) add("userAgent too long")
    }

    internal companion object {
        const val MAX_UA_LENGTH = 300

        /** host 合法（且）属于 edu.cn 域：小写域名 + `.edu.cn` 结尾（`edu.cn` 本身、伪装域 `x.edu.cn.evil.com` 均拒绝） */
        internal fun isEduCnHost(host: String): Boolean {
            val normalized = host.trim().lowercase()
            return normalized.length > EDU_CN_SUFFIX.length && normalized.endsWith(EDU_CN_SUFFIX)
        }
    }
}

@Serializable
data class DeclarationCopy(
    val headerTitle: LocalizedText,
    val headerDescription: LocalizedText,
    val introTitle: LocalizedText,
    val introBody: LocalizedText,
    val loginSheetTitle: LocalizedText,
    val sourceBadge: LocalizedText,
    val groupHomework: LocalizedText,
    val groupQuiz: LocalizedText,
    val syncNotifTitle: LocalizedText,
    /** 以下七项可省：null = 落到 `sync_*` 中性默认键（与编译期 spec 的可空槽位同语义） */
    val accountChanged: LocalizedText? = null,
    val logoutMessage: LocalizedText? = null,
    val switchMessage: LocalizedText? = null,
    val serviceBusy: LocalizedText? = null,
    val invalidCredentials: LocalizedText? = null,
    val sessionNotEstablished: LocalizedText? = null,
    val autoLoginDisableMessage: LocalizedText? = null,
) {

    internal fun validate(): List<String> = buildList {
        val fields = listOf(
            "headerTitle" to headerTitle,
            "headerDescription" to headerDescription,
            "introTitle" to introTitle,
            "introBody" to introBody,
            "loginSheetTitle" to loginSheetTitle,
            "sourceBadge" to sourceBadge,
            "groupHomework" to groupHomework,
            "groupQuiz" to groupQuiz,
            "syncNotifTitle" to syncNotifTitle,
            "accountChanged" to accountChanged,
            "logoutMessage" to logoutMessage,
            "switchMessage" to switchMessage,
            "serviceBusy" to serviceBusy,
            "invalidCredentials" to invalidCredentials,
            "sessionNotEstablished" to sessionNotEstablished,
            "autoLoginDisableMessage" to autoLoginDisableMessage,
        )
        fields.forEach { (name, text) ->
            text?.en?.takeIf { it.isBlank() }?.let { add("$name.en must not be blank") }
        }
    }
}

@Serializable
data class DeclarationProfile(
    /** 探活有效性口径：该路径的值非空即认为已登录（也是 WebView profileValidator） */
    val uidPath: String,
    val namePath: String,
    /** 头像可选：候选键按序取第一个非空（语义对齐既有 avatar 候选键探测） */
    val avatar: DeclarationAvatar? = null,
) {

    internal fun validate(): List<String> = buildList {
        if (!isValidPath(uidPath)) add("uidPath invalid")
        if (!isValidPath(namePath)) add("namePath invalid")
        avatar?.let {
            if (it.candidates.isEmpty()) add("avatar.candidates empty")
            it.candidates.filterNot { key -> key.matches(AVATAR_KEY) }
                .forEach { key -> add("avatar candidate '$key' invalid") }
            if (it.baseUrl != null && !it.baseUrl.startsWith("https://")) {
                add("avatar.baseUrl must be https")
            }
            it.cookieHosts.filterNot { host -> DeclarationWebView.isEduCnHost(host) }
                .forEach { host -> add("avatar cookieHost '$host' is outside $EDU_CN_SUFFIX") }
        }
    }

    internal companion object {
        /** dot-path 段约束：字母数字下划线，禁数组下标/`$`/空白（防注入 + 表达力够用） */
        internal fun isValidPath(path: String): Boolean =
            path.isNotBlank() && path.split('.').all { segment -> SEGMENT.matches(segment) }

        private val SEGMENT = Regex("[A-Za-z0-9_]+")
        private val AVATAR_KEY = Regex("[A-Za-z0-9_]+")
    }
}

@Serializable
data class DeclarationAvatar(
    /** 候选字段（顶层或容器键下的字符串值），按序取第一个非空 */
    val candidates: List<String>,
    /** 相对路径补全基准（如 `https://lms.xxx.edu.cn`） */
    val baseUrl: String? = null,
    /** 允许携带会话 cookie 的域（含子域）；空 = 头像请求一律匿名 */
    val cookieHosts: List<String> = emptyList(),
)

@Serializable
data class DeclarationTodo(
    /** 待办数组在 /todos 响应里的 dot-path（如 `todo_list`） */
    val listPath: String,
    /** 唯一键字段：渲染 remoteKey/localId 模板的占位来源（校内唯一由声明保证） */
    val keyFields: List<DeclarationKeyField>,
    /** 远端唯一键模板（如 `demo:{courseId}:{id}`） */
    val remoteKeyTemplate: String,
    /** 确定性本地 id 模板：必须以 `<id>-` 开头（跨校隔离红线） */
    val localIdTemplate: String,
    val fields: DeclarationFieldPaths,
    /** 截止时间解析；null = 同 ISO */
    val endTime: DeclarationEndTime? = null,
    /** 过滤规则（缺省 = 不过滤学生/锁定校验，仍跳过期） */
    val filters: DeclarationFilters = DeclarationFilters(),
    /** 备注模板；占位符可用 keyField 名 + `{courseName}`/`{type}`/`{activityUrl}` */
    val noteTemplate: String? = null,
    /** 详情跳转链接模板（占位符同上）；null = 备注不带链接 */
    val activityUrlTemplate: String? = null,
    /** 远端 type → 自动分组（组键 = `<id>:<group>`） */
    val autoGroups: List<DeclarationAutoGroup> = emptyList(),
) {

    internal fun validate(schoolId: String): List<String> = buildList {
        if (!DeclarationProfile.isValidPath(listPath)) add("listPath invalid")
        if (keyFields.isEmpty()) add("keyFields empty")
        keyFields.filterNot { it.name.matches(PLACEHOLDER_NAME) && DeclarationProfile.isValidPath(it.path) }
            .forEach { add("keyField '${it.name}' invalid") }
        if (keyFields.map { it.name }.distinct().size != keyFields.size) add("keyField names duplicated")
        if (!DeclarationProfile.isValidPath(fields.titlePath)) add("fields.titlePath invalid")
        if (!DeclarationProfile.isValidPath(fields.courseNamePath)) add("fields.courseNamePath invalid")
        fields.typePath.takeIf { it.isNotEmpty() }?.let {
            if (!DeclarationProfile.isValidPath(it)) add("fields.typePath invalid")
        }
        fields.endTimePath.takeIf { it.isNotEmpty() }?.let {
            if (!DeclarationProfile.isValidPath(it)) add("fields.endTimePath invalid")
        }
        if (!isSafeKeyTemplate(remoteKeyTemplate)) add("remoteKeyTemplate invalid")
        if (!isSafeKeyTemplate(localIdTemplate)) add("localIdTemplate invalid")
        if (!localIdTemplate.startsWith("$schoolId-")) {
            add("localIdTemplate must start with '$schoolId-'")
        }
        // 模板占位符必须是已声明的 keyField（防运行期渲染出半截键）
        val names = keyFields.map { it.name }.toSet()
        listOf(remoteKeyTemplate, localIdTemplate).forEachIndexed { index, template ->
            PLACEHOLDER.findAll(template).map { it.groupValues[1] }
                .filterNot { it in names }
                .forEach { add("template ${if (index == 0) "remoteKey" else "localId"} has unknown placeholder '$it'") }
        }
        val textPlaceholders = names + setOf("courseName", "type", "activityUrl")
        listOf(
            "noteTemplate" to noteTemplate,
            "activityUrlTemplate" to activityUrlTemplate,
        ).forEach { (field, template) ->
            template?.takeIf { it.isNotBlank() }?.let { text ->
                if (!isSafeTextTemplate(text)) add("$field invalid")
                PLACEHOLDER.findAll(text).map { it.groupValues[1] }
                    .filterNot { it in textPlaceholders }
                    .forEach { add("$field has unknown placeholder '$it'") }
            }
        }
        endTime?.let {
            if (it.format != FORMAT_ISO && it.format != FORMAT_PATTERN) add("endTime.format '${it.format}' invalid")
            if (it.format == FORMAT_PATTERN && it.pattern.isNullOrBlank()) add("endTime.pattern required for PATTERN")
            it.zone?.takeIf { zone -> zone.isNotBlank() }?.let { zone ->
                if (zone !in TimeZone.getAvailableIDs()) add("endTime.zone '$zone' unknown")
            }
        }
        filters.requireStudent?.let {
            if (!DeclarationProfile.isValidPath(it)) add("filters.requireStudent invalid")
        }
        filters.skipLocked?.let {
            if (!DeclarationProfile.isValidPath(it)) add("filters.skipLocked invalid")
        }
        autoGroups.filterNot { it.type.isNotBlank() && it.group.matches(PLACEHOLDER_NAME) }
            .forEach { add("autoGroup '${it.type}'/'${it.group}' invalid") }
    }

    internal companion object {
        const val FORMAT_ISO = "ISO"
        const val FORMAT_PATTERN = "PATTERN"
        internal val PLACEHOLDER = Regex("""\{([A-Za-z0-9_]+)\}""")
        private val PLACEHOLDER_NAME = Regex("[A-Za-z0-9_]+")
        private const val MAX_TEMPLATE_LENGTH = 1024

        /** 键模板（remoteKey/localId）：单行、无路径分隔符、占位符成对合法 */
        internal fun isSafeKeyTemplate(template: String): Boolean =
            isSafeTemplate(template, allowSlash = false, allowNewline = false)

        /** 文本模板（备注/链接）：允许换行与 URL 斜杠，仍禁控制字符与反斜杠 */
        internal fun isSafeTextTemplate(template: String): Boolean =
            isSafeTemplate(template, allowSlash = true, allowNewline = true)

        private fun isSafeTemplate(
            template: String,
            allowSlash: Boolean,
            allowNewline: Boolean,
        ): Boolean {
            if (template.isBlank() || template.length > MAX_TEMPLATE_LENGTH) return false
            if (template.any { it.isISOControl() && !(allowNewline && it == '\n') }) return false
            if (template.contains('\\')) return false
            if (!allowSlash && template.contains('/')) return false
            var index = 0
            while (true) {
                val open = template.indexOf('{', index)
                val close = template.indexOf('}', index)
                if (open < 0 && close < 0) return true
                if (open < 0 || close < open) return false
                if (!PLACEHOLDER_NAME.matches(template.substring(open + 1, close))) return false
                index = close + 1
            }
        }
    }
}

@Serializable
data class DeclarationKeyField(val name: String, val path: String)

@Serializable
data class DeclarationFieldPaths(
    val titlePath: String,
    val courseNamePath: String,
    /** 远端类型（自动分组用）；空 = 不启用自动分组 */
    val typePath: String = "",
    /** 截止时间原文；空 = 所有条目按 NoDeadline 跳过 */
    val endTimePath: String = "",
)

@Serializable
data class DeclarationEndTime(
    /** [DeclarationTodo.FORMAT_ISO]（UTC `Z` 结尾，固定时区换算）或 [DeclarationTodo.FORMAT_PATTERN] */
    val format: String = DeclarationTodo.FORMAT_ISO,
    val pattern: String? = null,
    /** 固定时区（如 Asia/Shanghai）；缺省 = Asia/Shanghai（与既有实现同口径，不依赖设备时区） */
    val zone: String? = null,
)

@Serializable
data class DeclarationFilters(
    /** 值为 false 则按 NotStudent 跳过（路径）；null = 不校验 */
    val requireStudent: String? = null,
    /** 值为 true 则按 Locked 跳过（路径）；null = 不校验 */
    val skipLocked: String? = null,
    /** 截止早于当前时刻按 Overdue 跳过 */
    val skipOverdue: Boolean = true,
)

@Serializable
data class DeclarationAutoGroup(
    /** 远端 type 的精确取值（trim + 小写后比对） */
    val type: String,
    /** 分组键后缀：`<id>:<group>` */
    val group: String,
)
