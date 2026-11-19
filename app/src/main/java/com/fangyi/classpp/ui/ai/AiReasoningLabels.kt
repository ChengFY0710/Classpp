package com.fangyi.classpp.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.fangyi.classpp.R

/**
 * 推理等级数量 → 对话浮层的友好文案（按配置顺序从低到高做位置映射，第一个为「关」）：
 * 2 个：关、开启；3 个：关、高、最高；4 个：关、低、高、最高；
 * 5 个：关、低、高、最高、极致。
 */
private val ReasoningIntensityLabels: Map<Int, List<Int>> = mapOf(
    2 to listOf(
        R.string.ai_reasoning_level_off,
        R.string.ai_reasoning_level_on,
    ),
    3 to listOf(
        R.string.ai_reasoning_level_off,
        R.string.ai_reasoning_level_high,
        R.string.ai_reasoning_level_highest,
    ),
    4 to listOf(
        R.string.ai_reasoning_level_off,
        R.string.ai_reasoning_level_low,
        R.string.ai_reasoning_level_high,
        R.string.ai_reasoning_level_highest,
    ),
    5 to listOf(
        R.string.ai_reasoning_level_off,
        R.string.ai_reasoning_level_low,
        R.string.ai_reasoning_level_high,
        R.string.ai_reasoning_level_highest,
        R.string.ai_reasoning_level_extreme,
    ),
)

/**
 * 等级在对话浮层里的展示名：2~5 个等级按位置映射为「关/开启/低/高/最高/极致」，
 * 其它数量（1 / >5）回退为等级原文（映射表只覆盖约定档位）。「关」即不开启思考，
 * 语义见 [com.fangyi.classpp.data.ai.AiModelConfig.isReasoningOff]。
 */
@Composable
internal fun reasoningIntensityLabel(levels: List<String>, level: String): String {
    val index = levels.indexOfFirst { it.equals(level, ignoreCase = true) }
    val labels = ReasoningIntensityLabels[levels.size]
    return if (index >= 0 && labels != null) {
        stringResource(labels[index])
    } else {
        level
    }
}
