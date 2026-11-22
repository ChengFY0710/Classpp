package com.fangyi.classpp.data.ai

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 信封纯字节测试（同 XmuKeystoreEnvelope 风格）；Keystore 本体与 Android 环境相关，不在此覆盖 */
class AiKeystoreEnvelopeTest {

    @Test
    fun `pack unpack roundtrip preserves iv and ciphertext`() {
        val iv = byteArrayOf(0, 1, 2, 12, Byte.MAX_VALUE)
        val ciphertext = "密文测试".toByteArray(Charsets.UTF_8)

        val unpacked = AiKeystoreEnvelope.unpack(AiKeystoreEnvelope.pack(iv, ciphertext))

        assertEquals(iv.size, unpacked!!.iv.size)
        assertArrayEquals(iv, unpacked.iv)
        assertArrayEquals(ciphertext, unpacked.ciphertext)
    }

    @Test
    fun `wrong version is rejected`() {
        val packed = AiKeystoreEnvelope.pack(byteArrayOf(1, 2, 3), byteArrayOf(4))
        packed[0] = 2
        assertNull(AiKeystoreEnvelope.unpack(packed))
    }

    @Test
    fun `truncated or empty blobs are rejected`() {
        assertNull(AiKeystoreEnvelope.unpack(ByteArray(0)))
        assertNull(AiKeystoreEnvelope.unpack(ByteArray(2))) // 只有头
        assertNull(AiKeystoreEnvelope.unpack(byteArrayOf(1, 8, 1))) // ivLen=8 但只有 1 字节
        assertNull(AiKeystoreEnvelope.unpack(byteArrayOf(1, 0))) // ivLen=0 非法
    }
}

/** 活动凭据的一致解析：连接与 Key 必须同源，不能取到「A 端点 + B Key」 */
class AiActiveCredentialsTest {

    private fun profile(id: String, model: String) = AiProfile(
        id = id,
        name = id,
        connection = AiConnection(baseUrl = "https://$id.example", model = model),
    )

    @Test
    fun `active profile resolves its own connection and key`() {
        val a = profile("a", "model-a")
        val b = profile("b", "model-b")

        val credentials = resolveActiveCredentials(
            activeProfileId = "a",
            profiles = listOf(a, b),
            profileKey = { id -> "key-$id" },
            customConnection = { AiConnection(baseUrl = "https://custom", model = "custom") },
            customKey = { "custom-key" },
        )

        assertEquals(a.connection, credentials.connection)
        assertEquals("key-a", credentials.apiKey)
    }

    @Test
    fun `missing active profile falls back to custom slot`() {
        val credentials = resolveActiveCredentials(
            activeProfileId = "gone",
            profiles = emptyList(),
            profileKey = { id -> "key-$id" },
            customConnection = { AiConnection(baseUrl = "https://custom", model = "custom") },
            customKey = { "custom-key" },
        )

        assertEquals("https://custom", credentials.connection.baseUrl)
        assertEquals("custom-key", credentials.apiKey)
    }
}

/** 连接配置的 JSON 回环与语义（PrefsAiSettingsStore 的可测内核） */
class AiConnectionTest {

    @Test
    fun `connection json roundtrip`() {
        val original = AiConnection(
            providerId = "deepseek",
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
        )
        val decoded = AiJson.storage.decodeFromString(
            AiConnection.serializer(),
            AiJson.storage.encodeToString(AiConnection.serializer(), original),
        )
        assertEquals(original, decoded)
    }

    @Test
    fun `connection with unknown fields decodes with defaults`() {
        val decoded = AiJson.storage.decodeFromString<AiConnection>(
            AiConnection.serializer(),
            """{"providerId":"zhipu","baseUrl":"https://x","model":"glm-4-flash","futureField":1}""",
        )
        assertEquals("zhipu", decoded.providerId)
    }

    @Test
    fun `connection protocol roundtrip`() {
        val original = AiConnection(
            providerId = "anthropic",
            baseUrl = "https://api.anthropic.com/v1",
            model = "claude-sonnet-4-5",
            protocol = AiProtocol.Anthropic,
        )
        val decoded = AiJson.storage.decodeFromString(
            AiConnection.serializer(),
            AiJson.storage.encodeToString(AiConnection.serializer(), original),
        )
        assertEquals(original, decoded)
    }

    @Test
    fun `legacy connection json without protocol decodes to openai compat`() {
        // 旧版本落盘的配置无 protocol 字段：回落默认值，既有配置行为不变
        val legacy = AiJson.storage.decodeFromString<AiConnection>(
            AiConnection.serializer(),
            """{"providerId":"custom","baseUrl":"https://x/v1","model":"m"}""",
        )
        assertEquals(AiProtocol.OpenAiCompat, legacy.protocol)
    }

    @Test
    fun `isConfigured requires both baseUrl and model`() {
        assertEquals(false, AiConnection().isConfigured)
        assertEquals(false, AiConnection(baseUrl = "https://x").isConfigured)
        assertEquals(false, AiConnection(model = "m").isConfigured)
        assertEquals(true, AiConnection(baseUrl = "https://x", model = "m").isConfigured)
    }

    @Test
    fun `connection models json roundtrip`() {
        val original = AiConnection(
            providerId = "deepseek",
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-reasoner",
            models = listOf("deepseek-chat", "deepseek-reasoner"),
        )
        val decoded = AiJson.storage.decodeFromString(
            AiConnection.serializer(),
            AiJson.storage.encodeToString(AiConnection.serializer(), original),
        )
        assertEquals(original, decoded)
    }

    @Test
    fun `legacy connection json without models decodes to empty list`() {
        // 旧版本只有单个 model 字段：models 回落空列表，展示列表仍含当前模型
        val legacy = AiJson.storage.decodeFromString<AiConnection>(
            AiConnection.serializer(),
            """{"providerId":"custom","baseUrl":"https://x/v1","model":"m"}""",
        )
        assertEquals(emptyList<String>(), legacy.models)
        assertEquals(listOf("m"), legacy.modelOptions)
    }

    @Test
    fun `modelOptions trims and dedupes case insensitively`() {
        val connection = AiConnection(
            baseUrl = "https://x/v1",
            model = " deepseek-chat ",
            models = listOf("DeepSeek-Chat", " ", "deepseek-reasoner"),
        )
        assertEquals(listOf("DeepSeek-Chat", "deepseek-reasoner"), connection.modelOptions)
    }

    @Test
    fun `withNormalizedModels canonicalizes current and keeps order`() {
        val normalized = AiConnection(
            baseUrl = "https://x/v1",
            model = "deepseek-chat",
            models = listOf("DeepSeek-Chat", "deepseek-reasoner"),
        ).withNormalizedModels()

        assertEquals("DeepSeek-Chat", normalized.model)
        assertEquals(listOf("DeepSeek-Chat", "deepseek-reasoner"), normalized.models)
        // 幂等：落库前重复调用不会改变结果
        assertEquals(normalized, normalized.withNormalizedModels())
    }

    @Test
    fun `withNormalizedModels appends current when missing`() {
        val normalized = AiConnection(
            baseUrl = "https://x/v1",
            model = "deepseek-reasoner",
            models = listOf("deepseek-chat"),
        ).withNormalizedModels()

        assertEquals("deepseek-reasoner", normalized.model)
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), normalized.models)
    }

    @Test
    fun `model configs json roundtrip`() {
        val original = AiConnection(
            providerId = "deepseek",
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-reasoner",
            models = listOf("deepseek-chat", "deepseek-reasoner"),
            modelConfigs = mapOf(
                "deepseek-reasoner" to AiModelConfig(
                    reasoningLevels = listOf("low", "high"),
                    reasoningLevel = "high",
                ),
            ),
        )
        val decoded = AiJson.storage.decodeFromString(
            AiConnection.serializer(),
            AiJson.storage.encodeToString(AiConnection.serializer(), original),
        )
        assertEquals(original, decoded)
    }

    @Test
    fun `legacy connection json without model configs decodes to empty map`() {
        // 旧版本落盘无 modelConfigs：回落空 map，模型行为与改造前一致
        val legacy = AiJson.storage.decodeFromString<AiConnection>(
            AiConnection.serializer(),
            """{"providerId":"custom","baseUrl":"https://x/v1","model":"m","models":["m"]}""",
        )
        assertEquals(emptyMap<String, AiModelConfig>(), legacy.modelConfigs)
        assertEquals(AiModelConfig(), legacy.modelConfig("m"))
    }

    @Test
    fun `modelConfig matches id ignoring case`() {
        val connection = AiConnection(
            model = "DeepSeek-Reasoner",
            modelConfigs = mapOf(
                "deepseek-reasoner" to AiModelConfig(listOf("high"), "high"),
            ),
        )
        assertEquals("high", connection.modelConfig("DEEPSEEK-REASONER").reasoningLevel)
    }

    @Test
    fun `withModelConfig canonicalizes key and drops empty config`() {
        val connection = AiConnection(
            model = "m",
            models = listOf("m", "DeepSeek-Chat"),
        ).withModelConfig("deepseek-chat", AiModelConfig(listOf(" high "), "HIGH"))

        // key 统一成列表里的写法，等级 trim 并与选中项对齐
        assertEquals(setOf("DeepSeek-Chat"), connection.modelConfigs.keys)
        assertEquals(
            AiModelConfig(listOf("high"), "high"),
            connection.modelConfig("deepseek-chat"),
        )

        // 空配置 = 不使用推理等级：不落冗余项
        val cleared = connection.withModelConfig("DeepSeek-Chat", AiModelConfig())
        assertEquals(emptyMap<String, AiModelConfig>(), cleared.modelConfigs)
    }

    @Test
    fun `withModels prunes configs for removed models`() {
        val connection = AiConnection(
            model = "a",
            models = listOf("a", "b"),
            modelConfigs = mapOf(
                "a" to AiModelConfig(listOf("low"), "low"),
                "b" to AiModelConfig(listOf("high"), "high"),
            ),
        ).withModels(listOf("b"), "b")

        assertEquals(listOf("b"), connection.models)
        assertEquals("b", connection.model)
        assertEquals(setOf("b"), connection.modelConfigs.keys)
        assertEquals("high", connection.modelConfig("b").reasoningLevel)
    }

    @Test
    fun `renamed model keeps config when attached after withModels`() {
        // UI 改名路径：先取旧配置，再 withModels(newId) + withModelConfig(newId, config)
        val original = AiConnection(
            model = "old",
            models = listOf("old"),
            modelConfigs = mapOf("old" to AiModelConfig(listOf("low", "high"), "high")),
        )
        val config = original.modelConfig("old")
        val renamed = original.withModels(listOf("new"), "new").withModelConfig("new", config)

        assertEquals(listOf("new"), renamed.models)
        assertEquals(setOf("new"), renamed.modelConfigs.keys)
        assertEquals("high", renamed.modelConfig("new").reasoningLevel)
    }

    @Test
    fun `withNormalizedModels normalizes reasoning levels`() {
        val normalized = AiConnection(
            model = "m",
            models = listOf("m"),
            modelConfigs = mapOf(
                // key 与等级都带写法差异：统一成列表 / 首次出现的写法
                "M" to AiModelConfig(
                    reasoningLevels = listOf(" High ", "low", "high", ""),
                    reasoningLevel = "LOW",
                ),
            ),
        ).withNormalizedModels()

        val config = normalized.modelConfig("m")
        assertEquals(listOf("High", "low"), config.reasoningLevels)
        assertEquals("low", config.reasoningLevel)
    }

    @Test
    fun `model config selection falls back to first level`() {
        // 选中项缺失回落首个；空等级列表回落空串（该模型不使用推理等级）
        assertEquals("High", AiModelConfig(listOf("High", "low")).normalized().reasoningLevel)
        assertEquals("", AiModelConfig(reasoningLevel = "high").normalized().reasoningLevel)
    }

    @Test
    fun `withNormalizedModels prunes configs outside model list`() {
        val normalized = AiConnection(
            model = "a",
            models = listOf("a"),
            modelConfigs = mapOf(
                "a" to AiModelConfig(listOf("low")),
                "stale" to AiModelConfig(listOf("high")),
            ),
        ).withNormalizedModels()

        assertEquals(setOf("a"), normalized.modelConfigs.keys)
    }

    @Test
    fun `withModels empty list clears models and configs`() {
        // 「保存为新方案」迁走自定义槽位 / clearCustomModels 的核心语义
        val cleared = AiConnection(
            model = "a",
            models = listOf("a"),
            modelConfigs = mapOf("a" to AiModelConfig(listOf("low"), "low")),
        ).withModels(emptyList(), "")

        assertEquals(AiConnection(), cleared)
        assertEquals(emptyMap<String, AiModelConfig>(), cleared.modelConfigs)
    }
}

/** 预设表：未知 id 一律回落 custom，不抛异常 */
class AiProviderPresetsTest {

    @Test
    fun `known id resolves`() {
        assertEquals("https://api.deepseek.com", AiProviderPresets.byId("deepseek").baseUrl)
        assertEquals("deepseek-flash", AiProviderPresets.byId("deepseek").suggestedModel)
        assertEquals("glm-4.7-flash", AiProviderPresets.byId("zhipu").suggestedModel)
        assertEquals("qwen3.8-flash", AiProviderPresets.byId("qwen").suggestedModel)
        assertEquals("kimi-k3.1-pro", AiProviderPresets.byId("moonshot").suggestedModel)
        assertEquals("https://api.xiaomimimo.com/v1", AiProviderPresets.byId("mimo").baseUrl)
        assertEquals("mimo-v2.6-pro", AiProviderPresets.byId("mimo").suggestedModel)
        assertEquals("阿里云百炼", AiProviderPresets.byId("bailian").label)
        assertEquals(AiModelListApi.DashScope, AiProviderPresets.byId("bailian").modelListApi)
        assertEquals("qwen3:8b", AiProviderPresets.byId("ollama").suggestedModel)
        // 其他预设的拉取方案不变，仍走通用 OpenAI 兼容路径
        assertEquals(AiModelListApi.OpenAiCompat, AiProviderPresets.byId("qwen").modelListApi)
    }

    @Test
    fun `opencode go preset targets v1 endpoint and declares session header`() {
        val preset = AiProviderPresets.byId("opencode-go")
        assertEquals("OpenCode Go", preset.label)
        // 文档根地址不带 /v1 时对话路径会 404：预设必须带版本段
        assertEquals("https://opencode.ai/inference/go/v1", preset.baseUrl)
        assertEquals("deepseek-v4.1-flash", preset.suggestedModel)
        assertEquals(AiModelListApi.OpenAiCompat, preset.modelListApi)
        assertEquals("x-opencode-session", preset.sessionHeader)
        assertEquals("x-opencode-session", AiProviderPresets.sessionHeaderFor("opencode-go"))
        assertNull(AiProviderPresets.sessionHeaderFor("deepseek"))
    }

    @Test
    fun `unknown id falls back to custom`() {
        assertEquals(AiProviderPresets.CUSTOM_ID, AiProviderPresets.byId("nope").id)
        assertEquals(AiProviderPresets.custom, AiProviderPresets.byId("nope"))
    }

    @Test
    fun `only custom preset requires manual model`() {
        assertTrue(AiProviderPresets.byId("custom").requiresManualModel)
        assertTrue(
            AiProviderPresets.all
                .filter { it.id != AiProviderPresets.CUSTOM_ID }
                .none { it.requiresManualModel },
        )
    }

    @Test
    fun `ollama preset is local and keyless`() {
        val ollama = AiProviderPresets.byId(AiProviderPresets.OLLAMA_ID)
        assertEquals("http://127.0.0.1:11434/v1", ollama.baseUrl)
        assertFalse(ollama.requiresApiKey)
        assertTrue(AiLocalNetwork.isLocalHttpUrl(ollama.baseUrl))
        assertFalse(AiProviderPresets.requiresApiKey(AiProviderPresets.OLLAMA_ID))
        assertTrue(AiProviderPresets.requiresApiKey("deepseek"))
        // 未知 id 回落 custom：仍按需要 Key 处理
        assertTrue(AiProviderPresets.requiresApiKey("nope"))
    }

    @Test
    fun `removed presets fall back to custom`() {
        // 下架的旧预设不能让已存配置失效：providerId 回落 custom，端点与模型仍按原配置
        listOf("openai", "anthropic").forEach { id ->
            assertEquals(AiProviderPresets.custom, AiProviderPresets.byId(id))
        }
    }

    @Test
    fun `presets use openai compatible protocol`() {
        assertTrue(AiProviderPresets.all.all { it.protocol == AiProtocol.OpenAiCompat })
    }
}

/** 方案库 JSON：列表回环 + 未知字段兼容（与课程时间方案同策略，旧文件缺字段走默认值） */
class AiProfileTest {

    @Test
    fun `profile list roundtrip`() {
        val profiles = listOf(
            AiProfile(
                id = "a",
                name = "DeepSeek",
                connection = AiConnection(
                    providerId = "deepseek",
                    baseUrl = "https://api.deepseek.com/v1",
                    model = "deepseek-chat",
                    models = listOf("deepseek-chat", "deepseek-reasoner"),
                    modelConfigs = mapOf(
                        "deepseek-reasoner" to AiModelConfig(
                            reasoningLevels = listOf("low", "high"),
                            reasoningLevel = "high",
                        ),
                    ),
                ),
            ),
            AiProfile(
                id = "b",
                name = "Anthropic",
                connection = AiConnection(
                    providerId = "anthropic",
                    baseUrl = "https://api.anthropic.com/v1",
                    model = "claude-sonnet-4-5",
                    protocol = AiProtocol.Anthropic,
                ),
            ),
        )

        val decoded = AiJson.storage.decodeFromString<List<AiProfile>>(
            AiJson.storage.encodeToString(profiles),
        )

        assertEquals(profiles, decoded)
    }

    @Test
    fun `profile with unknown fields or missing defaults decodes`() {
        val decoded = AiJson.storage.decodeFromString<List<AiProfile>>(
            """[{"id":"a","name":"A","connection":{"baseUrl":"https://x/v1","model":"m"},"future":1}]""",
        )

        assertEquals("A", decoded.single().name)
        assertEquals("m", decoded.single().connection.model)
        assertEquals(emptyList<String>(), decoded.single().connection.models)
        assertEquals(AiProtocol.OpenAiCompat, decoded.single().connection.protocol)
        assertEquals(AiProviderPresets.CUSTOM_ID, decoded.single().connection.providerId)
    }
}
