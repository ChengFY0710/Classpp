package com.fangyi.classpp.data.ai

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
    fun `removed presets fall back to custom`() {
        // 下架的旧预设不能让已存配置失效：providerId 回落 custom，端点与模型仍按原配置
        listOf("openai", "anthropic", "ollama").forEach { id ->
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
