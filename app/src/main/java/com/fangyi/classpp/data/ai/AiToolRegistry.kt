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
 * P0 只落「契约」，P1 接入三个只读工具，P2 接入全部写工具。写工具的权限语义与现有
 * 动作一致——新建/完成/归档可撤销、修改/删除只生成确认卡；注册表只声明权限，
 * 真正执行由仓库注入的 [AiToolWriteGateway] 完成，不改变这条边界。
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

    // 首版（P1）只读查询工具；写工具在 P2 起同样进入原生 tools（见 writeSpecs）
    const val GET_DAY_PLAN = "get_day_plan"
    const val SEARCH_TODOS = "search_todos"
    const val GET_WEEK_SCHEDULE = "get_week_schedule"

    // ---------- 枚举值（schema 与回退契约共用的唯一来源） ----------

    private val URGENCY_VALUES = listOf("Critical", "High", "Medium", "Low")
    private val URGENCY_VALUES_WITH_NONE = URGENCY_VALUES + "None"
    private val REPEAT_TYPE_VALUES = listOf("None", "Daily", "Weekly", "Monthly", "Yearly", "Custom")
    private val REPEAT_UNIT_VALUES = listOf("Day", "Week", "Month", "Year")
    private val REPEAT_END_TYPE_VALUES = listOf("Never", "Date", "Count")

    // ---------- Schema（wire 的单一来源） ----------

    private val repeatParameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            enumProperty("type", "重复类型", REPEAT_TYPE_VALUES)
            enumProperty("unit", "Custom 的频率单位", REPEAT_UNIT_VALUES)
            integerProperty("interval", "Custom 的间隔（每 N 个单位）")
            integerArrayProperty("marks", "Custom 的标记：周几 1..7 / 几号 1..31 / 几月 1..12")
            enumProperty("endType", "结束条件", REPEAT_END_TYPE_VALUES)
            stringProperty("endDate", "结束日期 yyyy-MM-dd（不含当天）")
            integerProperty("endCount", "期数或打卡次数 1..999")
            booleanProperty("merge", "是否在列表中合并为一条滚动卡")
            booleanProperty("checkIn", "打卡模式（仅 Count 有效）")
        }
    }

    private fun todoDraftParameters(urgencyValues: List<String>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            stringProperty("name", "待办名称")
            stringArrayProperty("dates", "日期列表（yyyy-MM-dd）")
            booleanProperty("allDay", "是否全天；全天待办不要给时段")
            stringProperty("startTime", "开始时间 HH:mm，与 endTime 成对")
            stringProperty("endTime", "结束时间 HH:mm，必须晚于 startTime")
            stringProperty("deadlineDate", "截止日期 yyyy-MM-dd，与 deadlineTime 成对")
            stringProperty("deadlineTime", "截止时刻 HH:mm")
            booleanProperty("deadlineBeforeRepeat", "截止随重复自动取下一次重复开始前")
            integerProperty("deadlineBeforeDays", "截止提前天数")
            enumProperty("urgency", "紧急度", urgencyValues)
            stringArrayProperty("tags", "标签列表")
            stringProperty("group", "分组名")
            stringProperty("note", "备注")
            stringArrayProperty("steps", "步骤标题列表")
            stringProperty("location", "地点")
            booleanProperty("countdown", "卡面倒计时开关，仅在有截止时有效")
            booleanProperty("pinned", "是否置顶")
            put("repeat", repeatParameters)
        }
    }

    private val todoUpdateItemParameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            stringProperty("id", "待办 id，必须原样取自上下文")
            put("patch", todoDraftParameters(URGENCY_VALUES_WITH_NONE))
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
            arrayOfObjectsProperty("todos", "要创建的待办列表", todoDraftParameters(URGENCY_VALUES))
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
     * 首版只读工具：带 [AiToolSpec.execute]，因此进入原生 tools 的 wire 数组。
     * 描述限定 1~3 句，避免每次请求都付大额 schema token。
     */
    private val readSpecs: List<AiToolSpec> = listOf(
        AiToolSpec(
            name = GET_DAY_PLAN,
            description = "查询某一天的课程、待办与空闲时段。用户问“某天有没有空/那天有什么课”时调用；" +
                "省略 date = 今天。",
            parameters = AiReadTools.dayPlanParameters,
            permission = AiToolPermission.Read,
            execute = AiReadTools::executeDayPlan,
        ),
        AiToolSpec(
            name = SEARCH_TODOS,
            description = "按关键词、状态、分组、标签或日期范围查询待办。上下文里的待办列表可能被截断，" +
                "需要完整/精确结果时调用。",
            parameters = AiReadTools.searchTodosParameters,
            permission = AiToolPermission.Read,
            execute = AiReadTools::executeSearchTodos,
        ),
        AiToolSpec(
            name = GET_WEEK_SCHEDULE,
            description = "查询某一周的课表（按天分组）。用户问“下周怎么安排/这周有什么课”时调用；" +
                "weekOffset：-1=上周，0=本周，1=下周。",
            parameters = AiReadTools.weekScheduleParameters,
            permission = AiToolPermission.Read,
            execute = AiReadTools::executeWeekSchedule,
        ),
    )

    /** 写工具（P2）：与 JSON 回退契约一一对应，执行由仓库注入的 [AiToolWriteGateway] 完成 */
    private val writeSpecs: List<AiToolSpec> = listOf(
        AiToolSpec(
            name = CREATE_TODOS,
            description = "新建待办。用户要求记录要做的事（可带日期、截止、重复）时调用；" +
                "创建立即生效，可在消息下方撤销。",
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
    )

    /** 全部工具定义：只读在前、写工具在后（回退契约与查找共用） */
    val specs: List<AiToolSpec> = readSpecs + writeSpecs

    /** 可原生执行的工具（P2 起 = 全部：3 个只读 + 5 个写）；wire 只发送这些 */
    val nativeSpecs: List<AiToolSpec> = specs.filter { it.execute != null }

    /** 按名称查找工具；未注册返回 null（未知工具名由执行层转成 `unknown_tool` 结果） */
    fun find(name: String): AiToolSpec? = specs.firstOrNull { it.name == name }

    /** 写工具（会改待办库或生成确认卡）：重试时据此判断上一轮是否已经执行过写操作 */
    fun isWriteTool(name: String): Boolean = writeSpecs.any { it.name == name }

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
