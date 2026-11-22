package com.fangyi.classpp.data.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 工具注册表：协议中性的单一 schema 源（设计稿 §4）。
 *
 * 三种 wire 形态都由 [specs] 生成：
 * - OpenAI 兼容 `tools` 数组（[openAiTools]）；
 * - Anthropic `tools` 数组（[anthropicTools]）；
 * - JSON 回退模式的提示词契约文本（[fallbackContracts] / [fallbackShape]）。
 *
 * P0 只落「契约」，P1 接入三个只读工具，P2 接入全部写工具，P3 补 `get_todo` 并让上下文
 * 只留今日摘要（细节由工具按需取）。写工具的权限语义与现有动作一致——新建/完成/归档
 * 可撤销、修改/删除只生成确认卡；注册表只声明权限，真正执行由仓库注入的
 * [AiToolWriteGateway] 完成，不改变这条边界。
 *
 * **注意**：不要在这里保存任何流式累积状态（codec 是 object 单例，累积器由 AiClient
 * 每次调用新建）。
 */
object AiToolRegistry {

    const val CREATE_TODOS = "create_todos"
    const val UPDATE_TODOS = "update_todos"
    const val DELETE_TODOS = "delete_todos"
    const val COMPLETE_TODOS = "complete_todos"
    const val ARCHIVE_TODOS = "archive_todos"

    // 分组/标签新建工具（立即落库、同名幂等）：仅原生 tools，不进入 JSON 回退契约
    const val CREATE_TODO_GROUPS = "create_todo_groups"
    const val CREATE_TODO_TAGS = "create_todo_tags"

    // 只读查询工具（P1 三个 + P3 的 get_todo）；写工具在 P2 起同样进入原生 tools（见 writeSpecs）
    const val GET_CURRENT_TIME = "get_current_time"
    const val GET_DAY_PLAN = "get_day_plan"
    const val SEARCH_TODOS = "search_todos"
    const val GET_WEEK_SCHEDULE = "get_week_schedule"
    const val GET_TODO = "get_todo"

    // ---------- 枚举值（schema 与回退契约共用的唯一来源） ----------

    private val URGENCY_VALUES = listOf("Critical", "High", "Medium", "Low")
    private val URGENCY_VALUES_WITH_NONE = URGENCY_VALUES + "None"
    private val REPEAT_TYPE_VALUES = listOf("None", "Daily", "Weekly", "Monthly", "Yearly", "Custom")
    private val REPEAT_UNIT_VALUES = listOf("Day", "Week", "Month", "Year")
    private val REPEAT_END_TYPE_VALUES = listOf("Never", "Date", "Count")

    // ---------- Schema（wire 的单一来源） ----------

    /**
     * 重复规则 schema：create_todos 用完整描述；update_todos 的 patch 传 terse=true，
     * 字段含义与 create 侧一致，描述精简为交叉引用——wire 里两份 schema 每次请求都要发。
     */
    private fun repeatParameters(terse: Boolean): JsonObject = buildJsonObject {
        put("type", "object")
        if (terse) put("description", "重复规则（None=取消重复；字段含义同 create_todos 的 repeat）")
        putJsonObject("properties") {
            enumProperty("type", "重复类型", REPEAT_TYPE_VALUES)
            enumProperty("unit", if (terse) "Custom 的单位" else "Custom 的频率单位", REPEAT_UNIT_VALUES)
            integerProperty("interval", if (terse) "Custom 的间隔" else "Custom 的间隔（每 N 个单位）")
            integerArrayProperty(
                "marks",
                if (terse) "Custom 的标记" else "Custom 的标记：周几 1..7 / 几号 1..31 / 几月 1..12",
            )
            enumProperty("endType", "结束条件", REPEAT_END_TYPE_VALUES)
            stringProperty("endDate", if (terse) "结束日期（不含当天）" else "结束日期 yyyy-MM-dd（不含当天）")
            integerProperty("endCount", if (terse) "期数或打卡次数" else "期数或打卡次数 1..999")
            booleanProperty("merge", if (terse) "合并为一条滚动卡" else "是否在列表中合并为一条滚动卡")
            booleanProperty("checkIn", if (terse) "打卡（仅 Count）" else "打卡模式（仅 Count 有效）")
        }
    }

    /**
     * 待办草稿 schema：
     * - create_todos 的元素传 [nameRequired]：name 无默认值可省，无名草稿会被静默丢弃，
     *   schema 层标必填让模型在生成时就避开；
     * - update_todos 的 patch 传 [terse]：patch 是部分更新语义（未写=不变），name 可不写，
     *   描述精简为交叉引用，避免与 create 侧逐字重复的双份长描述。
     */
    private fun todoDraftParameters(
        urgencyValues: List<String>,
        nameRequired: Boolean = false,
        terse: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("type", "object")
        if (terse) {
            put(
                "description",
                "只写要改的字段：未写=保持不变，空串/空数组=清空（name 不能为空）。" +
                    "字段含义同 create_todos 的 todos 元素。",
            )
        }
        if (nameRequired) putJsonArray("required") { add("name") }
        putJsonObject("properties") {
            stringProperty(
                "name",
                when {
                    nameRequired -> "待办名称（必填）"
                    terse -> "新名称（不能为空）"
                    else -> "待办名称"
                },
            )
            stringArrayProperty(
                "dates",
                if (terse) {
                    "新日期列表 yyyy-MM-dd（整体替换原日期）"
                } else {
                    "日期列表（yyyy-MM-dd）；时段或截止所在的那天也要列入"
                },
            )
            booleanProperty("allDay", if (terse) "是否全天" else "是否全天；全天待办不要给时段")
            stringProperty(
                "startTime",
                if (terse) {
                    "开始 HH:mm（与 endTime 成对）"
                } else {
                    "开始时间 HH:mm，与 endTime 成对；用户给时间段时填时段"
                },
            )
            stringProperty("endTime", if (terse) "结束 HH:mm（须晚于 startTime）" else "结束时间 HH:mm，必须晚于 startTime")
            stringProperty(
                "deadlineDate",
                if (terse) {
                    "截止日期（与 deadlineTime 成对）"
                } else {
                    "截止日期 yyyy-MM-dd，与 deadlineTime 成对；用户只给一个时间点时作截止"
                },
            )
            stringProperty(
                "deadlineTime",
                if (terse) "截止时刻 HH:mm" else "截止时刻 HH:mm；用户只给一个时间点时作截止",
            )
            booleanProperty("deadlineBeforeRepeat", "截止随重复自动取下一次重复开始前")
            integerProperty("deadlineBeforeDays", "截止提前天数")
            enumProperty("urgency", if (terse) "紧急度（None=清除）" else "紧急度", urgencyValues)
            stringArrayProperty(
                "tags",
                if (terse) "新标签列表（整体替换）" else "标签列表；库内不存在的标签可先用 create_todo_tags 新建",
            )
            stringProperty("group", if (terse) "新分组名" else "分组名；须已存在，可先用 create_todo_groups 新建")
            stringProperty("note", if (terse) "新备注" else "备注")
            stringArrayProperty("steps", if (terse) "新步骤标题列表（整体替换）" else "步骤标题列表")
            stringProperty("location", if (terse) "新地点" else "地点")
            booleanProperty("countdown", if (terse) "倒计时开关（需有截止）" else "卡面倒计时开关，仅在有截止时有效")
            booleanProperty("pinned", "是否置顶")
            put("repeat", repeatParameters(terse))
        }
    }

    private val todoUpdateItemParameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            stringProperty("id", "待办 id，必须原样取自上下文")
            put("patch", todoDraftParameters(URGENCY_VALUES_WITH_NONE, terse = true))
        }
        putJsonArray("required") {
            add("id")
            add("patch")
        }
    }

    private val completionItemParameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            stringProperty("id", "待办 id")
            stringProperty("date", "重复待办的期次日期 yyyy-MM-dd；不重复的待办省略")
        }
        putJsonArray("required") { add("id") }
    }

    private val idItemParameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            stringProperty("id", "待办 id")
        }
        putJsonArray("required") { add("id") }
    }

    private val createTodosParameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            arrayOfObjectsProperty("todos", "要创建的待办列表", todoDraftParameters(URGENCY_VALUES, nameRequired = true))
        }
        putJsonArray("required") { add("todos") }
    }

    private val updateTodosParameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            arrayOfObjectsProperty("updates", "待修改的待办列表", todoUpdateItemParameters)
        }
        putJsonArray("required") { add("updates") }
    }

    private val deleteTodosParameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            arrayOfObjectsProperty("deletes", "要删除（移入回收站）的待办列表", idItemParameters)
        }
        putJsonArray("required") { add("deletes") }
    }

    private val completeTodosParameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            arrayOfObjectsProperty("completes", "要完成的待办列表", completionItemParameters)
            arrayOfObjectsProperty("reopens", "要取消完成的待办列表", completionItemParameters)
        }
    }

    private val archiveTodosParameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            arrayOfObjectsProperty("archives", "要归档的活跃待办", idItemParameters)
            arrayOfObjectsProperty("unarchives", "要取消归档的已归档待办", idItemParameters)
            arrayOfObjectsProperty("restores", "要从回收站恢复的待办", idItemParameters)
        }
    }

    private val createTodoGroupsParameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            stringArrayProperty("groups", "要新建的分组名列表；同名分组已存在时不会重复创建")
        }
        putJsonArray("required") { add("groups") }
    }

    private val createTodoTagsParameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            stringArrayProperty("tags", "要新建的标签名列表；同名标签已存在时不会重复创建")
        }
        putJsonArray("required") { add("tags") }
    }

    // ---------- 回退信封示例（提示词契约的单一来源） ----------

    private fun repeatExample(): JsonObject = buildJsonObject {
        put("type", REPEAT_TYPE_VALUES.joinToString("|"))
        put("unit", REPEAT_UNIT_VALUES.joinToString("|"))
        put("interval", 1)
        putJsonArray("marks") { add(1) }
        put("endType", REPEAT_END_TYPE_VALUES.joinToString("|"))
        put("endDate", "yyyy-MM-dd")
        put("endCount", 5)
        put("merge", true)
        put("checkIn", false)
    }

    private fun todoDraftExample(allDay: Boolean, urgencyValues: List<String>): JsonObject = buildJsonObject {
        put("name", "...")
        putJsonArray("dates") { add("yyyy-MM-dd") }
        put("allDay", allDay)
        put("startTime", "HH:mm")
        put("endTime", "HH:mm")
        put("deadlineDate", "yyyy-MM-dd")
        put("deadlineTime", "HH:mm")
        put("deadlineBeforeRepeat", false)
        put("deadlineBeforeDays", 0)
        put("urgency", urgencyValues.joinToString("|"))
        putJsonArray("tags") { add("...") }
        put("group", "...")
        put("note", "...")
        putJsonArray("steps") { add("...") }
        put("location", "...")
        put("countdown", false)
        put("pinned", false)
        put("repeat", repeatExample())
    }

    private fun idItemExample(): JsonObject = buildJsonObject { put("id", "<待办 id>") }

    private val todosEnvelopeExample = buildJsonArray {
        add(todoDraftExample(allDay = false, urgencyValues = URGENCY_VALUES))
    }

    private val updatesEnvelopeExample = buildJsonArray {
        add(
            buildJsonObject {
                put("id", "<待办 id>")
                put("patch", todoDraftExample(allDay = true, urgencyValues = URGENCY_VALUES_WITH_NONE))
            },
        )
    }

    private val deletesEnvelopeExample = buildJsonArray { add(idItemExample()) }

    private val completesEnvelopeExample = buildJsonArray {
        add(
            buildJsonObject {
                put("id", "<待办 id>")
                put("date", "yyyy-MM-dd")
            },
        )
    }

    private val reopensEnvelopeExample = buildJsonArray {
        add(
            buildJsonObject {
                put("id", "<待办 id>")
                put("date", "yyyy-MM-dd")
            },
        )
    }

    private val archivesEnvelopeExample = buildJsonArray { add(idItemExample()) }

    private val unarchivesEnvelopeExample = buildJsonArray {
        add(buildJsonObject { put("id", "<已归档 id>") })
    }

    private val restoresEnvelopeExample = buildJsonArray {
        add(buildJsonObject { put("id", "<回收站 id>") })
    }

    // ---------- 工具定义（必须在 schema / 示例之后初始化） ----------

    /**
     * 只读工具：带 [AiToolSpec.execute]，因此进入原生 tools 的 wire 数组。
     * 描述限定 1~3 句，避免每次请求都付大额 schema token。
     */
    private val readSpecs: List<AiToolSpec> = listOf(
        AiToolSpec(
            name = GET_CURRENT_TIME,
            description = "查询当前日期、星期与时刻（本地时区），无参数。调用时机见系统规则。",
            parameters = AiReadTools.currentTimeParameters,
            permission = AiToolPermission.Read,
            execute = AiReadTools::executeCurrentTime,
        ),
        AiToolSpec(
            name = GET_DAY_PLAN,
            description = "查询一天或多天的课程、待办与空闲时段。date 查单日；from/to 查连续多天" +
                "（含首尾）；dates 查不连续的多天（如 11月4号、12月14号、2月3号）。" +
                "三种方式任选其一，最多 ${AiReadTools.MAX_DAY_PLAN_DAYS} 天；" +
                "多天必须一次查完，不要逐天调用。",
            parameters = AiReadTools.dayPlanParameters,
            permission = AiToolPermission.Read,
            maxResultChars = AiReadTools.MAX_DAY_PLAN_RESULT_CHARS,
            execute = AiReadTools::executeDayPlan,
        ),
        AiToolSpec(
            name = SEARCH_TODOS,
            description = "按关键词、状态、分组、标签或日期范围查待办，默认只查活跃待办（不含归档与回收站）。" +
                "用户要找、列或统计待办时调用；返回协议与分页规则见系统规则。" +
                "结果可能很大，必要时用 limit/offset 分页或缩小查询范围。",
            parameters = AiReadTools.searchTodosParameters,
            permission = AiToolPermission.Read,
            maxResultChars = AiReadTools.MAX_SEARCH_RESULT_CHARS,
            execute = AiReadTools::executeSearchTodos,
        ),
        AiToolSpec(
            name = GET_WEEK_SCHEDULE,
            description = "查询某一周的课表（按天分组），课表全部周都可查；overview=true 只返回课表概况" +
                "（总周数、当前周、学期起止）。用户问“下周怎么安排/这周有什么课/第几周上什么/一共几周”" +
                "时调用；week 传绝对周次（1 起），weekOffset 相对本周（-1=上周，0=本周，1=下周），" +
                "都省略 = 本周。结果中 truncated=true 表示 days 不完整，按 missingDates 用 get_day_plan 补齐。",
            parameters = AiReadTools.weekScheduleParameters,
            permission = AiToolPermission.Read,
            maxResultChars = AiReadTools.MAX_WEEK_RESULT_CHARS,
            execute = AiReadTools::executeWeekSchedule,
        ),
        AiToolSpec(
            name = GET_TODO,
            description = "查询单个待办的完整详情（备注、步骤、重复规则与期次、所在状态）。" +
                "拿到 id 后要改内容/改期限/完成某一期时先用它看清现状。",
            parameters = AiReadTools.getTodoParameters,
            permission = AiToolPermission.Read,
            execute = AiReadTools::executeGetTodo,
        ),
    )

    /** 写工具（P2）：与 JSON 回退契约一一对应，执行由仓库注入的 [AiToolWriteGateway] 完成 */
    private val writeSpecs: List<AiToolSpec> = listOf(
        AiToolSpec(
            name = CREATE_TODOS,
            description = "新建待办。用户要求记录要做的事（可带日期、截止、重复）时调用；" +
                "多条待办一次全部放进 todos 数组提交，不要拆成多次调用；" +
                "用户只给一个时间点默认作截止，给时间段默认作待办时段；创建立即生效，可在消息下方撤销。",
            parameters = createTodosParameters,
            permission = AiToolPermission.WriteReversible,
            envelope = listOf(AiToolEnvelopeSection("todos", todosEnvelopeExample)),
            execute = AiWriteTools::executeCreateTodos,
        ),
        AiToolSpec(
            name = UPDATE_TODOS,
            description = "修改已有待办。只提交要改的字段；不会立即写入，应用会生成确认卡，" +
                "用户点击「应用变更」后才生效。",
            parameters = updateTodosParameters,
            permission = AiToolPermission.WriteConfirm,
            envelope = listOf(AiToolEnvelopeSection("updates", updatesEnvelopeExample)),
            execute = AiWriteTools::executeUpdateTodos,
        ),
        AiToolSpec(
            name = DELETE_TODOS,
            description = "删除已有待办（移入回收站，可恢复）。不会立即写入，应用会生成确认卡。",
            parameters = deleteTodosParameters,
            permission = AiToolPermission.WriteConfirm,
            envelope = listOf(AiToolEnvelopeSection("deletes", deletesEnvelopeExample)),
            execute = AiWriteTools::executeDeleteTodos,
        ),
        AiToolSpec(
            name = COMPLETE_TODOS,
            description = "完成或取消完成待办。立即生效，可撤销；重复待办必须给出期次日期。",
            parameters = completeTodosParameters,
            permission = AiToolPermission.WriteReversible,
            envelope = listOf(
                AiToolEnvelopeSection("completes", completesEnvelopeExample),
                AiToolEnvelopeSection("reopens", reopensEnvelopeExample),
            ),
            execute = AiWriteTools::executeCompleteTodos,
        ),
        AiToolSpec(
            name = ARCHIVE_TODOS,
            description = "归档、取消归档或从回收站恢复待办。立即生效，可撤销。",
            parameters = archiveTodosParameters,
            permission = AiToolPermission.WriteReversible,
            envelope = listOf(
                AiToolEnvelopeSection("archives", archivesEnvelopeExample),
                AiToolEnvelopeSection("unarchives", unarchivesEnvelopeExample),
                AiToolEnvelopeSection("restores", restoresEnvelopeExample),
            ),
            execute = AiWriteTools::executeArchiveTodos,
        ),
        AiToolSpec(
            name = CREATE_TODO_GROUPS,
            description = "新建一个或多个待办分组。立即生效；同名分组已存在时不会重复创建。" +
                "用户要新建分组，或要把待办放进一个尚不存在的分组时，先调用它，再在 " +
                "create_todos / update_todos 的 group 里引用。",
            parameters = createTodoGroupsParameters,
            permission = AiToolPermission.WriteReversible,
            execute = AiWriteTools::executeCreateTodoGroups,
        ),
        AiToolSpec(
            name = CREATE_TODO_TAGS,
            description = "新建一个或多个待办标签（进入用户标签库）。立即生效；同名标签已存在时不会重复创建。" +
                "用户要新建标签，或要给待办加一个库内尚不存在的标签时，先调用它，再在 " +
                "create_todos / update_todos 的 tags 里引用。",
            parameters = createTodoTagsParameters,
            permission = AiToolPermission.WriteReversible,
            execute = AiWriteTools::executeCreateTodoTags,
        ),
    )

    /** 全部工具定义：只读在前、写工具在后（回退契约与查找共用） */
    val specs: List<AiToolSpec> = readSpecs + writeSpecs

    /** 可原生执行的工具（P3 起 = 全部：4 个只读 + 5 个写）；wire 只发送这些 */
    val nativeSpecs: List<AiToolSpec> = specs.filter { it.execute != null }

    /** 原生 tools 的 JSON schema 粗估 token（上下文预算裁剪用，避免每次请求重算） */
    val nativeSpecsTokenEstimate: Int by lazy {
        AiDebugTrace.estimateTokens(openAiTools(nativeSpecs).toString())
    }

    /** 按名称查找工具；未注册返回 null（未知工具名由执行层转成 `unknown_tool` 结果） */
    fun find(name: String): AiToolSpec? = specs.firstOrNull { it.name == name }

    /** 写工具（会改待办库或生成确认卡）：重试时据此判断上一轮是否已经执行过写操作 */
    fun isWriteTool(name: String): Boolean = writeSpecs.any { it.name == name }

    /**
     * 已落库的写工具：排除只生成确认卡的 update/delete。重试提示只对真正改变数据的
     * 工具成立——把待确认的修改当成「已生效」，重试时会让模型放弃重新生成确认卡。
     */
    fun isPersistedWriteTool(name: String): Boolean = when (name) {
        CREATE_TODOS,
        COMPLETE_TODOS,
        ARCHIVE_TODOS,
        CREATE_TODO_GROUPS,
        CREATE_TODO_TAGS,
        -> true
        else -> false
    }

    // ---------- JSON 回退契约 ----------

    /**
     * 四段 JSON 回退契约文本：system prompt 与仓库的草稿修复重试**共用这一份生成结果**
     * （避免两处手写漂移）。
     */
    val fallbackContracts: AiFallbackContracts by lazy {
        AiFallbackContracts(
            createTodos = fallbackShape("todos"),
            updateAndDelete = fallbackShape("updates", "deletes"),
            complete = fallbackShape("completes", "reopens"),
            lifecycle = fallbackShape("archives", "unarchives", "restores"),
        )
    }

    /** 修复重试的 system 消息（文本与重构前逐字节一致） */
    val repairSystemPrompt: String by lazy {
        "待办动作必须是可解析的 json 代码块。\n" +
            "新建（立即落库）格式：\n" + fallbackContracts.createTodos + "\n" +
            "修改/删除（等待用户确认）格式：\n" + fallbackContracts.updateAndDelete + "\n" +
            "完成/取消完成（立即生效）格式：\n" + fallbackContracts.complete + "\n" +
            "归档/取消归档/恢复（立即生效）格式：\n" + fallbackContracts.lifecycle
    }

    /**
     * 按给定顺序把注册表里的回退段拼成一个 JSON 对象文本。
     *
     * 只接受已注册的段键：[sectionKeys] 必须都在 [AiToolSpec.envelope] 里出现过，
     * 拼错（或段被删除）时立即失败而不是悄悄生成残缺契约。
     */
    fun fallbackShape(vararg sectionKeys: String): String {
        val sections = fallbackSections
        val picked = sectionKeys.map { key ->
            val section = sections.firstOrNull { it.key == key }
                ?: throw IllegalArgumentException("unknown tool envelope section: $key")
            key to section.example
        }
        return buildJsonObject {
            picked.forEach { (key, example) -> put(key, example) }
        }.toString()
    }

    private val fallbackSections: List<AiToolEnvelopeSection>
        get() = specs.flatMap { it.envelope }

    // ---------- Wire 形态 ----------

    /** OpenAI 兼容 `tools` 数组：`{type:"function",function:{name,description,parameters}}` */
    fun openAiTools(specs: List<AiToolSpec> = nativeSpecs): JsonArray = buildJsonArray {
        specs.forEach { spec ->
            add(
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", spec.name)
                        put("description", spec.description)
                        put("parameters", spec.parameters)
                    }
                },
            )
        }
    }

    /** Anthropic `tools` 数组：`{name,description,input_schema}` */
    fun anthropicTools(specs: List<AiToolSpec> = nativeSpecs): JsonArray = buildJsonArray {
        specs.forEach { spec ->
            add(
                buildJsonObject {
                    put("name", spec.name)
                    put("description", spec.description)
                    put("input_schema", spec.parameters)
                },
            )
        }
    }

    /** 按协议生成 `tools` 字段；空列表的语义由调用方决定（不发送 tools 字段 = 回退） */
    fun wireTools(
        protocol: AiProtocol,
        specs: List<AiToolSpec> = nativeSpecs,
    ): JsonArray = when (protocol) {
        AiProtocol.OpenAiCompat -> openAiTools(specs)
        AiProtocol.Anthropic -> anthropicTools(specs)
    }

    // ---------- Schema 构件 ----------

    private fun JsonObjectBuilder.stringProperty(name: String, description: String) {
        putJsonObject(name) {
            put("type", "string")
            put("description", description)
        }
    }

    private fun JsonObjectBuilder.booleanProperty(name: String, description: String) {
        putJsonObject(name) {
            put("type", "boolean")
            put("description", description)
        }
    }

    private fun JsonObjectBuilder.integerProperty(name: String, description: String) {
        putJsonObject(name) {
            put("type", "integer")
            put("description", description)
        }
    }

    private fun JsonObjectBuilder.enumProperty(name: String, description: String, values: List<String>) {
        putJsonObject(name) {
            put("type", "string")
            put("description", description)
            putJsonArray("enum") { values.forEach { add(it) } }
        }
    }

    private fun JsonObjectBuilder.stringArrayProperty(name: String, description: String) {
        putJsonObject(name) {
            put("type", "array")
            put("description", description)
            putJsonObject("items") { put("type", "string") }
        }
    }

    private fun JsonObjectBuilder.integerArrayProperty(name: String, description: String) {
        putJsonObject(name) {
            put("type", "array")
            put("description", description)
            putJsonObject("items") { put("type", "integer") }
        }
    }

    private fun JsonObjectBuilder.arrayOfObjectsProperty(
        name: String,
        description: String,
        itemParameters: JsonObject,
    ) {
        putJsonObject(name) {
            put("type", "array")
            put("description", description)
            put("items", itemParameters)
        }
    }
}

/**
 * JSON 回退模式下提示词里的四段契约（由 [AiToolRegistry] 生成）。
 *
 * [AiPromptBuilder] 的 system prompt 与仓库的修复重试引用同一实例，保证「修复请求里
 * 说严格遵循格式」时真的有同一份声明可循。
 */
data class AiFallbackContracts(
    val createTodos: String,
    val updateAndDelete: String,
    val complete: String,
    val lifecycle: String,
)
