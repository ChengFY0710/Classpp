package com.fangyi.classpp.data.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 截图版与网页版课表提示词的连堂换算公式必须一致：偶数节起始且跨对边界时
 * `ceil(oldSpan / 2)` 会少算一节，两份提示词都只能用 newEnd 反推 span。
 */
class AiSchedulePromptTest {

    @Test
    fun `both prompts share the same slot merge formula`() {
        listOf(
            "screenshot" to AiScheduleImportPrompt.SYSTEM,
            "web" to AiScheduleWebImportPrompt.SYSTEM,
        ).forEach { (label, prompt) ->
            assertTrue(
                "$label 提示词应包含 newEnd 换算公式",
                prompt.contains("floor((oldStart + oldSpan) / 2)"),
            )
            assertFalse(
                "$label 提示词不应把 ceil(oldSpan / 2) 当作换算公式",
                prompt.contains("- `newSpan = ceil(oldSpan / 2)`"),
            )
        }
    }
}
