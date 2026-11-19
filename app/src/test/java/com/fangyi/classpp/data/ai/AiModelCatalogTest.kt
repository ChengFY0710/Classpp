package com.fangyi.classpp.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型列表请求构造与解析：预设拉取方案（通用 / 百炼原生）、Anthropic 路径归一、
 * 响应容错（同 AiStreamCodecTest 的纯 JVM 风格）。
 */
class AiModelCatalogTest {

    private fun connection(
        baseUrl: String,
        protocol: AiProtocol = AiProtocol.OpenAiCompat,
        providerId: String = "custom",
    ) = AiConnection(
        providerId = providerId,
        baseUrl = baseUrl,
        model = "test-model",
        protocol = protocol,
    )

    // ---------- 通用 OpenAI 兼容 / Anthropic ----------

    @Test
    fun `openai request is a GET to models endpoint with bearer auth`() {
        val request = AiModelCatalog.buildRequest(
            connection("https://api.example.com/v1/"),
            "sk-secret",
        )
        assertEquals(AiHttpMethod.Get, request.method)
        assertEquals("https://api.example.com/v1/models", request.url)
        assertEquals("Bearer sk-secret", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Accept"])
        assertTrue(request.jsonBody.isEmpty())
    }

    @Test
    fun `anthropic request targets v1 models with api key header`() {
        val request = AiModelCatalog.buildRequest(
            connection("https://api.anthropic.com", AiProtocol.Anthropic),
            "sk-ant",
        )
        assertEquals(AiHttpMethod.Get, request.method)
        assertEquals("https://api.anthropic.com/v1/models?limit=1000", request.url)
        assertEquals("sk-ant", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        assertTrue(request.jsonBody.isEmpty())
    }

    @Test
    fun `anthropic base url variants normalize without duplicate version segment`() {
        val cases = listOf(
            "https://api.example.com/v1",
            "https://api.example.com/v1/",
            "https://api.example.com/v1/messages",
            "https://api.example.com/v1/models",
        )
        cases.forEach { baseUrl ->
            val request = AiModelCatalog.buildRequest(
                connection(baseUrl, AiProtocol.Anthropic),
                "k",
            )
            assertEquals(
                "https://api.example.com/v1/models?limit=1000",
                request.url,
            )
        }
    }

    // ---------- 预设拉取方案 ----------

    @Test
    fun `bailian preset uses native dashscope endpoint with pagination`() {
        val request = AiModelCatalog.buildRequest(
            connection(
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                providerId = "bailian",
            ),
            "sk-bailian",
        )
        assertEquals(AiHttpMethod.Get, request.method)
        assertEquals(
            "https://dashscope.aliyuncs.com/api/v1/models?page_no=1&page_size=100",
            request.url,
        )
        assertEquals("Bearer sk-bailian", request.headers["Authorization"])
    }

    @Test
    fun `bailian workspace base url keeps origin and requests the given page`() {
        val request = AiModelCatalog.buildRequest(
            connection(
                baseUrl = "https://ws-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
                providerId = "bailian",
            ),
            "k",
            page = 3,
        )
        assertEquals(
            "https://ws-123.cn-beijing.maas.aliyuncs.com/api/v1/models?page_no=3&page_size=100",
            request.url,
        )
    }

    @Test
    fun `other presets keep the generic models endpoint`() {
        // 先不改其他预设的拉取：qwen 与自定义即便地址相同也仍走兼容路径
        val qwen = AiModelCatalog.buildRequest(
            connection(
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                providerId = "qwen",
            ),
            "k",
        )
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/models", qwen.url)

        val custom = AiModelCatalog.buildRequest(
            connection(
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                providerId = "custom",
            ),
            "k",
        )
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/models", custom.url)
    }

    // ---------- 响应解析 ----------

    @Test
    fun `parse extracts trimmed deduped ids and ignores unknown fields`() {
        val body = """
            {"object":"list","data":[
              {"id":" deepseek-chat ","object":"model","owned_by":"deepseek"},
              {"id":"deepseek-reasoner"},
              {"id":"DeepSeek-Chat"},
              {"id":""},
              {"object":"model"}
            ]}
        """.trimIndent()
        assertEquals(
            listOf("deepseek-chat", "deepseek-reasoner"),
            AiModelCatalog.parsePage(connection("https://api.example.com/v1"), body)
                .models.map { it.id },
        )
    }

    @Test
    fun `parse deepseek effort metadata into reasoning config`() {
        val body = """
            {"object":"list","data":[
              {"id":"deepseek-reasoner","effort":{"supported_levels":["low","high","max"],"default_level":"high"}},
              {"id":"deepseek-flash","effort":{"supported_levels":["low","high"]}},
              {"id":"deepseek-chat"}
            ]}
        """.trimIndent()
        val models = AiModelCatalog.parsePage(
            connection("https://api.deepseek.com"),
            body,
        ).models
        assertEquals(
            listOf("deepseek-reasoner", "deepseek-flash", "deepseek-chat"),
            models.map { it.id },
        )
        // 默认等级命中 supported_levels
        assertEquals(listOf("low", "high", "max"), models[0].config.reasoningLevels)
        assertEquals("high", models[0].config.reasoningLevel)
        // 缺 default_level 回落首个等级
        assertEquals(listOf("low", "high"), models[1].config.reasoningLevels)
        assertEquals("low", models[1].config.reasoningLevel)
        // 无 effort = 空配置（该模型不使用推理等级）
        assertTrue(models[2].config.isEmpty)
    }

    @Test
    fun `parse accepts empty data list`() {
        val page = AiModelCatalog.parsePage(
            connection("https://api.example.com/v1"),
            """{"data":[]}""",
        )
        assertEquals(emptyList<AiFetchedModel>(), page.models)
        assertEquals(null, page.nextPage)
    }

    @Test
    fun `parse dashscope page reports the next page until total is reached`() {
        val bailian = connection(
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            providerId = "bailian",
        )
        val first = AiModelCatalog.parsePage(
            bailian,
            """
            {"success":true,"output":{"total":3,"page_no":1,"page_size":2,
             "models":[{"model":"qwen-max"},{"model":"qwen-plus"}]}}
            """.trimIndent(),
        )
        assertEquals(listOf("qwen-max", "qwen-plus"), first.models.map { it.id })
        assertEquals(2, first.nextPage)

        val last = AiModelCatalog.parsePage(
            bailian,
            """
            {"success":true,"output":{"total":3,"page_no":2,"page_size":2,
             "models":[{"model":"qwen-turbo"}]}}
            """.trimIndent(),
        )
        assertEquals(listOf("qwen-turbo"), last.models.map { it.id })
        assertEquals(null, last.nextPage)
    }

    @Test
    fun `parse rejects malformed body`() {
        val error = runCatching {
            AiModelCatalog.parsePage(connection("https://api.example.com/v1"), "{broken")
        }.exceptionOrNull()
        assertTrue(error is AiException)
        assertEquals(AiError.BadResponse, (error as AiException).error)
    }

    @Test
    fun `parse rejects body without data array`() {
        val error = runCatching {
            AiModelCatalog.parsePage(
                connection("https://api.example.com/v1"),
                """{"object":"list"}""",
            )
        }.exceptionOrNull()
        assertTrue(error is AiException)
        assertEquals(AiError.BadResponse, (error as AiException).error)
    }

    @Test
    fun `parse dashscope error body carries service message in detail`() {
        val error = runCatching {
            AiModelCatalog.parsePage(
                connection(
                    baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    providerId = "bailian",
                ),
                """{"code":"InvalidApiKey","message":"Invalid API key"}""",
            )
        }.exceptionOrNull() as AiException
        assertEquals(AiError.BadResponse, error.error)
        assertTrue(error.detail.orEmpty().contains("InvalidApiKey"))
    }
}
