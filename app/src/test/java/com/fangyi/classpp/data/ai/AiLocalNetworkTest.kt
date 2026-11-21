package com.fangyi.classpp.data.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本地端点判定：只认回环 / 私网 / .local，公网 http 仍走仅 TLS 传输 */
class AiLocalNetworkTest {

    @Test
    fun `loopback and private ipv4 are local`() {
        listOf(
            "http://127.0.0.1:11434/v1",
            "http://127.8.9.10/v1",
            "http://localhost:11434/v1",
            "http://app.localhost/v1",
            "http://10.0.0.5:11434/v1",
            "http://172.16.0.1/v1",
            "http://172.31.255.254/v1",
            "http://192.168.1.10:11434/v1",
            "http://169.254.1.2/v1",
            "http://100.64.0.1:11434/v1",
        ).forEach { url ->
            assertTrue(url, AiLocalNetwork.isLocalHttpUrl(url))
        }
    }

    @Test
    fun `public or non-http urls are not local-cleartext`() {
        listOf(
            "http://8.8.8.8/v1",
            "http://172.32.0.1/v1",
            "http://192.169.0.1/v1",
            "http://100.128.0.1/v1",
            "https://127.0.0.1:11434/v1",
            "https://192.168.1.10/v1",
            "http://api.example.com/v1",
            "http://pc.example.com/v1",
            "not a url",
            "",
        ).forEach { url ->
            assertFalse(url, AiLocalNetwork.isLocalHttpUrl(url))
        }
    }

    @Test
    fun `mdns and ipv6 private hosts are local`() {
        listOf(
            "http://my-pc.local:11434/v1",
            "http://[::1]:11434/v1",
            "http://[fd00::1]:11434/v1",
            "http://[fe80::1]:11434/v1",
            "http://[::ffff:192.168.1.2]:11434/v1",
        ).forEach { url ->
            assertTrue(url, AiLocalNetwork.isLocalHttpUrl(url))
        }
    }

    @Test
    fun `loopback host is separate from other private hosts`() {
        assertTrue(AiLocalNetwork.isLoopbackHost("localhost"))
        assertTrue(AiLocalNetwork.isLoopbackHost("127.0.0.1"))
        assertTrue(AiLocalNetwork.isLoopbackHost("127.10.0.2"))
        assertTrue(AiLocalNetwork.isLoopbackHost("::1"))
        assertTrue(AiLocalNetwork.isLoopbackHost("[::1]"))
        assertFalse(AiLocalNetwork.isLoopbackHost("192.168.1.10"))
        assertFalse(AiLocalNetwork.isLoopbackHost("fe80::1"))
        assertFalse(AiLocalNetwork.isLoopbackHost("my-pc.local"))
    }

    @Test
    fun `keyless preset readiness does not require api key`() {
        val ollama = AiConnection(
            providerId = AiProviderPresets.OLLAMA_ID,
            baseUrl = "http://192.168.1.10:11434/v1",
            model = "qwen3:8b",
        )
        assertTrue(ollama.isReady(apiKey = null))
        assertTrue(ollama.isReady(apiKey = ""))

        val cloud = ollama.copy(providerId = "deepseek", baseUrl = "https://api.deepseek.com")
        assertFalse(cloud.isReady(apiKey = null))
        assertFalse(cloud.isReady(apiKey = ""))
        assertTrue(cloud.isReady(apiKey = "sk-test"))
        assertFalse(cloud.copy(model = "").isReady(apiKey = "sk-test"))
    }
}
