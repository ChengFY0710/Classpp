package com.fangyi.classpp.data.ai

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 一条拉取到的模型：id + 服务商附带或模型族推断的模型级配置（推理等级）。
 * [config] 为空配置 = 该模型不使用推理等级（接口未提供 effort 且模型族表未命中）。
 */
data class AiFetchedModel(
    val id: String,
    val config: AiModelConfig = AiModelConfig(),
)

/** 一页模型列表：去重后的模型 + 下一页页码（null = 没有下一页） */
internal data class AiModelListPage(
    val models: List<AiFetchedModel>,
    val nextPage: Int? = null,
)

/**
 * 服务商「可用模型列表」的请求构造与响应解析（AI 配置方案浮层 → 从服务商拉取模型）。
 *
 * 拉取方案跟随服务商预设（[AiProviderPreset.modelListApi]），协议只区分通用与 Anthropic：
 * - OpenAI 兼容（默认）：`GET {baseUrl}/models` + `Authorization: Bearer <key>`，
 *   响应 `{"data":[{"id":"..."}]}`，单页；
 * - Anthropic：`GET {base}/v1/models?limit=1000` + `x-api-key` / `anthropic-version`，
 *   路径归一与 [AnthropicStreamCodec] 的 messagesUrl 同策略（已带 `/v1/models` 原样、
 *   已带 `/v1` 补 `/models`、直接粘贴 `/v1/messages` 的换成 `/v1/models`），
 *   避免出现 `/v1/v1/models` 这类 404 路径；
 * - 阿里云百炼（预设 `bailian`，[AiModelListApi.DashScope]）：OpenAI 兼容路径不提供
 *   模型列表（`/compatible-mode/v1/models` 会 404），改用原生
 *   `GET {origin}/api/v1/models?page_no=&page_size=` + Bearer，响应
 *   `{"output":{"total":N,"page_no":1,"page_size":20,"models":[{"model":"..."}]}}`，
 *   按 `page_no` / `page_size` / `total` 分页。origin 取自当前 baseUrl，覆盖官方、
 *   国际站与 Workspace 域名（`https://{WorkspaceId}.{region}.maas.aliyuncs.com`）。
 *
 * 模型 id trim + 忽略大小写去重；通用形态优先读 `id`、缺失时回落同形 `model` 字段
 * （百炼原生反之），兼容少数网关的列表写法。条目配置优先级：
 * 1. `effort` 元数据（DeepSeek：`supported_levels` + `default_level`，任何服务商返回都优先）；
 *    DeepSeek 的元数据只列思考开启后的强度档，「关」由 `thinking.type` 独立控制，
 *    拉取时按控制形态补一个 off 占位首档（见 [withOffLevel]）；
 * 2. 内置模型族档位表 [AiReasoningProfiles]（GLM / MiMo / Kimi / 千问 / 百炼的列表不含
 *    档位，按模型 id 推断；Kimi 明确 `supports_reasoning=false` 时不推断）；
 * 3. 空配置（该模型不使用推理等级）。
 * 结构不合法按 [AiError.BadResponse] 处理。
 */
internal object AiModelCatalog {

    /** 拉取结果与草稿合并的结果：更新后的连接 + 仅含新模型的列表 + 覆盖数量 */
    data class FetchedMerge(
        val connection: AiConnection,
        val fresh: List<AiFetchedModel>,
        val updated: Int,
    )

    /**
     * 把拉取结果并入草稿：与已添加模型同名（忽略大小写）且拉取配置非空时直接覆盖该模型
     * 的推理等级配置；同名但拉取配置为空时保留已有配置（不让一次拉取清空用户手填档位）。
     * 只有全新的模型进入 [FetchedMerge.fresh]（按 id 排序），模型列表顺序与当前模型不变。
     */
    fun mergeFetchedModels(
        connection: AiConnection,
        fetched: List<AiFetchedModel>,
    ): FetchedMerge {
        val existing = connection.modelOptions
        var next = connection
        var updated = 0
        val fresh = ArrayList<AiFetchedModel>(fetched.size)
        fetched.forEach { model ->
            val isExisting = existing.any { it.equals(model.id, ignoreCase = true) }
            when {
                isExisting && !model.config.isEmpty -> {
                    next = next.withModelConfig(model.id, model.config)
                    updated++
                }
                !isExisting -> fresh += model
            }
        }
        return FetchedMerge(
            connection = next,
            fresh = fresh.sortedBy { it.id.lowercase() },
            updated = updated,
        )
    }

    fun buildRequest(connection: AiConnection, apiKey: String, page: Int = 1): AiHttpRequest {
        if (usesDashScope(connection)) {
            return AiHttpRequest(
                url = "${dashScopeModelsUrl(connection.baseUrl)}?page_no=${page.coerceAtLeast(1)}&page_size=$PAGE_SIZE",
                headers = mapOf(
                    "Authorization" to "Bearer $apiKey",
                    "Accept" to "application/json",
                ),
                method = AiHttpMethod.Get,
            )
        }
        return when (connection.protocol) {
            AiProtocol.OpenAiCompat -> AiHttpRequest(
                url = connection.baseUrl.trimEnd('/') + MODELS_PATH,
                headers = buildMap {
                    // 本地 Ollama 等无 Key 服务商不发认证头（空 Bearer 无意义）
                    if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
                    put("Accept", "application/json")
                },
                method = AiHttpMethod.Get,
            )
            AiProtocol.Anthropic -> AiHttpRequest(
                url = modelsUrl(connection.baseUrl),
                headers = mapOf(
                    "x-api-key" to apiKey,
                    "anthropic-version" to API_VERSION,
                    "Accept" to "application/json",
                ),
                method = AiHttpMethod.Get,
            )
        }
    }

    /** 解析一页响应；百炼原生形态带分页信息，其余协议单页返回 */
    fun parsePage(connection: AiConnection, body: String): AiModelListPage {
        val root = try {
            CATALOG_JSON.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            throw AiException(AiError.BadResponse, detail = e.message)
        } ?: throw AiException(AiError.BadResponse, detail = "root is not a json object")

        return if (usesDashScope(connection)) {
            parseDashScopePage(connection, root)
        } else {
            parseCompatPage(connection, root)
        }
    }

    /** OpenAI 兼容 / Anthropic 同形：`{"data":[{"id":"..."}]}`，单页 */
    private fun parseCompatPage(connection: AiConnection, root: JsonObject): AiModelListPage {
        val data = root["data"] as? JsonArray
            ?: throw AiException(AiError.BadResponse, detail = errorDetail(root) ?: "missing data array")
        // 少数兼容网关（如智谱）条目用 model 而不是 id：主字段缺失时回落同形字段
        return AiModelListPage(
            models = parseModels(connection, data, ID_FIELD, fallbackField = MODEL_FIELD),
        )
    }

    /** 百炼原生形态：`{"output":{"total":N,"page_no":p,"page_size":s,"models":[{"model":"..."}]}}` */
    private fun parseDashScopePage(connection: AiConnection, root: JsonObject): AiModelListPage {
        val output = root["output"] as? JsonObject
            ?: throw AiException(AiError.BadResponse, detail = errorDetail(root) ?: "missing output")
        val modelsArray = output["models"] as? JsonArray
        val models = modelsArray?.let {
            parseModels(connection, it, MODEL_FIELD, fallbackField = ID_FIELD)
        }.orEmpty()
        val pageNo = output.intOrNull("page_no") ?: 1
        // 服务端回显页大小；缺失或异常值按请求页大小兜底，避免除零/原地分页
        val pageSize = output.intOrNull("page_size")?.takeIf { it > 0 } ?: PAGE_SIZE
        val total = output.intOrNull("total")
        // 空页即终止；响应回显的 page_no/page_size 决定是否还有下一页（防止服务端截断页大小）
        val nextPage = if (models.isNotEmpty() && total != null && pageNo * pageSize < total) {
            pageNo + 1
        } else {
            null
        }
        return AiModelListPage(models = models, nextPage = nextPage)
    }

    /** 解析一页的模型条目：主字段（id / 百炼的 model）+ 同形字段回退 + 推理等级配置 */
    private fun parseModels(
        connection: AiConnection,
        array: JsonArray,
        field: String,
        fallbackField: String? = null,
    ): List<AiFetchedModel> {
        val models = ArrayList<AiFetchedModel>(array.size)
        array.forEach { element ->
            val entry = element as? JsonObject ?: return@forEach
            val id = (entry[field] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                .ifEmpty {
                    fallbackField
                        ?.let { (entry[it] as? JsonPrimitive)?.contentOrNull?.trim() }
                        .orEmpty()
                }
            if (id.isEmpty() || models.any { it.id.equals(id, ignoreCase = true) }) return@forEach
            models += AiFetchedModel(
                id = id,
                config = reasoningConfig(connection, entry, id),
            )
        }
        return models
    }

    /**
     * 条目配置：接口 `effort` 元数据优先（DeepSeek），否则按服务商模型族表推断
     * （GLM / MiMo / Kimi / 千问 / 百炼）；Kimi 的 `supports_reasoning=false` 表示
     * 该模型不支持推理，不再按模型族补档位。
     */
    private fun reasoningConfig(
        connection: AiConnection,
        entry: JsonObject,
        id: String,
    ): AiModelConfig {
        val fromMetadata = effortConfig(entry)
        if (fromMetadata.reasoningLevels.isNotEmpty()) {
            return withOffLevel(connection, id, fromMetadata)
        }
        return AiReasoningProfiles.configFor(
            providerId = connection.providerId,
            model = id,
            supportsReasoning = entry.booleanOrNull("supports_reasoning"),
        ) ?: AiModelConfig()
    }

    /**
     * 给 `effort` 档位补「关」首档：DeepSeek 的 `supported_levels` 只列思考开启后的强度档
     * （如 low / high / max），「关」由 `thinking.type` 独立控制，不会出现在列表里。应用按
     * 位置把首档当「关」（[AiModelConfig.isReasoningOff]），不补的话真实的最低强度
     * （low）会被误当成关。
     *
     * - 始终思考的模型族（[AiThinkingControl.ReasoningEffortOnly]）没有「关」，不补；
     * - 首档已经是 off / none 时不重复补（千问 / 百炼的元数据可能自带关闭档）；
     * - 补完会超过 [ReasoningLevelCountRange] 上限时不补：超出后应用不再识别首位为「关」，
     *   保持原档位至少不改变请求语义。
     */
    private fun withOffLevel(
        connection: AiConnection,
        model: String,
        config: AiModelConfig,
    ): AiModelConfig {
        val levels = config.reasoningLevels
        if (levels.isEmpty()) return config.normalized()
        val supportsOff = AiProviderPresets
            .thinkingControlFor(connection.providerId, model)
            .supportsOff
        // 必须在 normalized 之前补首档：缺 default_level 时 normalized 会把首档选中，
        // 补档后首档才是「关」而不是真实的最低强度
        if (!supportsOff || levels.size >= ReasoningLevelCountRange.last) {
            return config.normalized()
        }
        if (levels.first().isOffLike()) return config.normalized()
        return config.copy(reasoningLevels = listOf(OFF_LEVEL) + levels).normalized()
    }

    /** `effort` 首档是否已经表示关闭（各服务商写法：off / none） */
    private fun String.isOffLike(): Boolean =
        equals(OFF_LEVEL, ignoreCase = true) || equals(NONE_LEVEL, ignoreCase = true)

    /**
     * DeepSeek 的 `effort` 元数据（`supported_levels` + `default_level`）直接对应应用的
     * 推理等级配置；无 effort 字段时返回空配置，由 [reasoningConfig] 继续按模型族表推断。
     * 这里不做 normalized：缺 `default_level` 时首档回落必须发生在补「关」首档之后。
     */
    private fun effortConfig(entry: JsonObject): AiModelConfig {
        val effort = entry["effort"] as? JsonObject ?: return AiModelConfig()
        val levels = (effort["supported_levels"] as? JsonArray)?.let(::parseStrings).orEmpty()
        val default = (effort["default_level"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return AiModelConfig(reasoningLevels = levels, reasoningLevel = default)
    }

    private fun parseStrings(array: JsonArray): List<String> = array.mapNotNull { element ->
        (element as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 拉取方案由预设决定：Anthropic 协议优先按协议处理，其余看预设的 [AiModelListApi] */
    private fun usesDashScope(connection: AiConnection): Boolean =
        connection.protocol == AiProtocol.OpenAiCompat &&
            AiProviderPresets.byId(connection.providerId).modelListApi == AiModelListApi.DashScope

    /**
     * 百炼原生模型端点：取当前 baseUrl 的 scheme + authority 拼 `/api/v1/models`
     * （官方兼容路径 `/compatible-mode/v1`、国际站与 Workspace 域名都按 origin 归一）。
     */
    private fun dashScopeModelsUrl(baseUrl: String): String {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
            ?: throw AiException(AiError.BadResponse, detail = "invalid base url")
        val scheme = uri.scheme
        val authority = uri.rawAuthority
        if (scheme.isNullOrBlank() || authority.isNullOrBlank()) {
            throw AiException(AiError.BadResponse, detail = "invalid base url")
        }
        return "$scheme://$authority$DASHSCOPE_MODELS_PATH"
    }

    /** Anthropic 模型端点：路径归一与 messagesUrl 同款，并追加 `limit=1000`（官方默认只回 20 条） */
    private fun modelsUrl(baseUrl: String): String {
        val base = baseUrl.trimEnd('/')
        val path = when {
            base.endsWith(MODELS_ENDPOINT_SUFFIX) -> base
            base.endsWith(MESSAGES_ENDPOINT_SUFFIX) -> base.removeSuffix(MESSAGES_PATH) + MODELS_PATH
            base.endsWith(VERSION_PATH) -> base + MODELS_PATH
            else -> base + MODELS_ENDPOINT_SUFFIX
        }
        return path + MODEL_LIMIT_QUERY
    }

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    /** 布尔元数据（Kimi 的 supports_reasoning）：缺失 / 非布尔返回 null */
    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    /** 百炼错误响应常带 code/message（HTTP 可能仍是 200）：拼进排查信息 */
    private fun errorDetail(root: JsonObject): String? = listOfNotNull(
        (root["code"] as? JsonPrimitive)?.contentOrNull,
        (root["message"] as? JsonPrimitive)?.contentOrNull,
    ).joinToString(": ").takeIf { it.isNotBlank() }

    private val CATALOG_JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 百炼单页条数：官方默认 20，取 100 减少分页次数 */
    private const val PAGE_SIZE = 100
    private const val ID_FIELD = "id"
    private const val MODEL_FIELD = "model"
    /** 「关」占位档：只参与位置映射，选中时不会作为 `reasoning_effort` 发送 */
    private const val OFF_LEVEL = "off"
    /** 千问 / 百炼的元数据用 none 表示关闭 */
    private const val NONE_LEVEL = "none"
    private const val MODELS_PATH = "/models"
    private const val VERSION_PATH = "/v1"
    private const val MESSAGES_PATH = "/messages"
    private const val MODELS_ENDPOINT_SUFFIX = VERSION_PATH + MODELS_PATH
    private const val MESSAGES_ENDPOINT_SUFFIX = VERSION_PATH + MESSAGES_PATH
    private const val API_VERSION = "2023-06-01"
    private const val MODEL_LIMIT_QUERY = "?limit=1000"
    private const val DASHSCOPE_MODELS_PATH = "/api/v1/models"
}
