package com.fangyi.classpp.ui.ai

/** 一条消息最多携带的图片数：视觉模型识别足够、单条请求体积可控 */
internal const val MaxImagesPerMessage = 4

/**
 * 系统分享投递到 AI 输入栏的草稿：图片已由宿主经 [com.fangyi.classpp.data.ai.AiImageStore]
 * 导入副本目录，[id] 只用于「同一份草稿只消费一次」的去重。
 *
 * 只预填输入栏与附件，发送仍由用户在浮层里确认——截图可能含敏感内容，不自动发出。
 */
data class AiChatDraft(
    val id: String,
    val prompt: String = "",
    val images: List<String> = emptyList(),
)
