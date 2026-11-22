package com.fangyi.classpp.data.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI 服务地址格式校验：必须是有 host 的 http/https URL */
class AiEndpointUrlTest {

    @Test
    fun `accepts http and https urls with host`() {
        assertTrue(isValidAiBaseUrl("https://api.deepseek.com"))
        assertTrue(isValidAiBaseUrl("https://api.deepseek.com/v1"))
        assertTrue(isValidAiBaseUrl("http://127.0.0.1:11434"))
        assertTrue(isValidAiBaseUrl("  https://example.com  "))
    }

    @Test
    fun `rejects malformed or non http urls`() {
        assertFalse(isValidAiBaseUrl(""))
        assertFalse(isValidAiBaseUrl("http"))
        assertFalse(isValidAiBaseUrl("httpfoo"))
        assertFalse(isValidAiBaseUrl("ftp://example.com"))
        assertFalse(isValidAiBaseUrl("https://"))
    }
}
