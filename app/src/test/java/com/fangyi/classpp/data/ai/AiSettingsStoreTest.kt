package com.fangyi.classpp.data.ai

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
    fun `isConfigured requires both baseUrl and model`() {
        assertEquals(false, AiConnection().isConfigured)
        assertEquals(false, AiConnection(baseUrl = "https://x").isConfigured)
        assertEquals(false, AiConnection(model = "m").isConfigured)
        assertEquals(true, AiConnection(baseUrl = "https://x", model = "m").isConfigured)
    }
}

/** 预设表：未知 id 一律回落 custom，不抛异常 */
class AiProviderPresetsTest {

    @Test
    fun `known id resolves`() {
        assertEquals("https://api.deepseek.com/v1", AiProviderPresets.byId("deepseek").baseUrl)
        assertEquals("glm-4-flash", AiProviderPresets.byId("zhipu").suggestedModel)
    }

    @Test
    fun `unknown id falls back to custom`() {
        assertEquals(AiProviderPresets.CUSTOM_ID, AiProviderPresets.byId("nope").id)
        assertEquals(AiProviderPresets.custom, AiProviderPresets.byId("nope"))
    }

    @Test
    fun `manual model flag on local and custom presets`() {
        assertTrue(AiProviderPresets.byId("ollama").requiresManualModel)
        assertTrue(AiProviderPresets.byId("custom").requiresManualModel)
        assertTrue(AiProviderPresets.all.none { it.id == "deepseek" && it.requiresManualModel })
    }
}
