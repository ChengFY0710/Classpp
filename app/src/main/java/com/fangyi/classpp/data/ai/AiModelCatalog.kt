package com.fangyi.classpp.data.ai

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 一页模型列表：去重后的模型 id + 下一页页码（null = 没有下一页） */
internal data class AiModelListPage(
    val models: List<String>,
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
 * 模型 id trim + 忽略大小写去重；结构不合法按 [AiError.BadResponse] 处理。
 */
internal object AiModelCatalog {

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
                headers = mapOf(
                    "Authorization" to "Bearer $apiKey",
                    "Accept" to "application/json",
                ),
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

        return if (usesDashScope(connection)) parseDashScopePage(root) else parseCompatPage(root)
    }

    /** OpenAI 兼容 / Anthropic 同形：`{"data":[{"id":"..."}]}`，单页 */
    private fun parseCompatPage(root: JsonObject): AiModelListPage {
        val data = root["data"] as? JsonArray
            ?: throw AiException(AiError.BadResponse, detail = errorDetail(root) ?: "missing data array")
        return AiModelListPage(models = parseIds(data, ID_FIELD))
    }

    /** 百炼原生形态：`{"output":{"total":N,"page_no":p,"page_size":s,"models":[{"model":"..."}]}}` */
    private fun parseDashScopePage(root: JsonObject): AiModelListPage {
        val output = root["output"] as? JsonObject
            ?: throw AiException(AiError.BadResponse, detail = errorDetail(root) ?: "missing output")
        val modelsArray = output["models"] as? JsonArray
        val models = modelsArray?.let { parseIds(it, MODEL_FIELD) }.orEmpty()
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

    private fun parseIds(array: JsonArray, field: String): List<String> {
        val ids = ArrayList<String>(array.size)
        array.forEach { element ->
            val id = ((element as? JsonObject)?.get(field) as? JsonPrimitive)
                ?.contentOrNull
                ?.trim()
                .orEmpty()
            if (id.isNotEmpty() && ids.none { it.equals(id, ignoreCase = true) }) {
                ids += id
            }
        }
        return ids
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
    private const val MODELS_PATH = "/models"
    private const val VERSION_PATH = "/v1"
    private const val MESSAGES_PATH = "/messages"
    private const val MODELS_ENDPOINT_SUFFIX = VERSION_PATH + MODELS_PATH
    private const val MESSAGES_ENDPOINT_SUFFIX = VERSION_PATH + MESSAGES_PATH
    private const val API_VERSION = "2023-06-01"
    private const val MODEL_LIMIT_QUERY = "?limit=1000"
    private const val DASHSCOPE_MODELS_PATH = "/api/v1/models"
}
