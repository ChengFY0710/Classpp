package com.fangyi.classpp.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ai.AiChatMessage
import com.fangyi.classpp.data.ai.AiDraftStatus
import com.fangyi.classpp.data.ai.AiDraftField
import com.fangyi.classpp.data.ai.AiError
import com.fangyi.classpp.data.ai.AiMessageDrafts
import com.fangyi.classpp.data.ai.AiTodoDraft
import com.fangyi.classpp.data.ai.AiTodoDraftMapping
import com.fangyi.classpp.data.ai.AiTodoMapper
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.PillShape

/**
 * AI 对话浮层的子组件（气泡 / 错误条 / 空态 / 草稿卡 / 输入栏）。
 * 布局全部无状态——状态（输入、勾选）由 [AiChatOverlay] 持有，这里只吃参数。
 */

/** 气泡最大宽度（占卡片内容区比例）：长回复不横穿整卡，留出对话节奏 */
private val BubbleMaxWidthFraction = 0.86f

/** 气泡圆角：大圆角 + 朝向说话方的小角，视觉区分双方 */
private val BubbleCorner = 18.dp
private val BubbleTailCorner = 4.dp
private val BubbleShape = RoundedCornerShape(BubbleCorner)

// ---------- 消息气泡 ----------

/**
 * 一条消息气泡：user 右对齐（主色底）/ assistant 左对齐（表面底）。
 * 内容按 ``` 围栏切分——围栏段等宽小块渲染，正文段按换行保留的纯文本渲染。
 */
@Composable
internal fun AiChatMessageBubble(
    message: AiChatMessage,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == com.fangyi.classpp.data.ai.AiChatRole.User
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.classppColors.secondaryText
    }
    val shape = if (isUser) {
        RoundedCornerShape(
            topStart = BubbleCorner,
            topEnd = BubbleCorner,
            bottomEnd = BubbleTailCorner,
            bottomStart = BubbleCorner,
        )
    } else {
        RoundedCornerShape(
            topStart = BubbleCorner,
            topEnd = BubbleCorner,
            bottomEnd = BubbleCorner,
            bottomStart = BubbleTailCorner,
        )
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = bubbleColor,
            contentColor = contentColor,
            shape = shape,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((isFence, segment) in splitFences(message.content)) {
                    if (segment.isEmpty()) continue
                    if (isFence) {
                        Text(
                            text = segment,
                            style = MonospaceSegmentStyle,
                            color = contentColor.copy(alpha = 0.85f),
                        )
                    } else {
                        Text(text = segment, style = ClassppTextTokens.secondaryText)
                    }
                }
                if (message.content.isEmpty()) {
                    // 占位阶段（流式首字未到）：三点呼吸省略号
                    Text(text = "…", style = ClassppTextTokens.secondaryText)
                }
            }
        }
    }
}

/** 等宽代码块文字（围栏段）：13sp 等宽家族，行高与正文同节奏 */
private val MonospaceSegmentStyle = ClassppTextTokens.secondaryTextSmall

/**
 * 围栏切分：```…``` 段标记为 true（等宽渲染），其余为 false；相邻段直接拼接渲染。
 * 未闭合的围栏（流式打到一半）把剩余内容整体按围栏段处理，增量期间不跳样式。
 */
internal fun splitFences(content: String): List<Pair<Boolean, String>> {
    if (!content.contains("```")) return listOf(false to content)
    val result = mutableListOf<Pair<Boolean, String>>()
    var inFence = false
    for (part in content.split("```")) {
        // 切分后首语言行（如 "json"）单独吞掉
        val text = if (inFence && part.lineSequence().firstOrNull()?.matches(Regex("[A-Za-z0-9_-]*")) == true) {
            part.lineSequence().drop(1).joinToString("\n")
        } else {
            part
        }
        if (text.isNotEmpty()) result += inFence to text
        inFence = !inFence
    }
    // 结尾仍开着的围栏：最后一段已按 inFence 标记，符合流式中间态
    return result
}

// ---------- 错误条 ----------

/** 分类错误条：文案 + 重试 + 关闭。重试对最后一场失败的对话重新发起（不重复追加用户消息） */
@Composable
internal fun AiChatErrorBanner(
    error: AiError,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val messageText = when (error) {
        AiError.Auth -> stringResource(R.string.ai_error_auth)
        AiError.Quota -> stringResource(R.string.ai_error_quota)
        AiError.NotFound -> stringResource(R.string.ai_error_not_found)
        AiError.RateLimited -> stringResource(R.string.ai_error_rate_limited)
        AiError.Server -> stringResource(R.string.ai_error_server)
        AiError.Network -> stringResource(R.string.ai_error_network)
        AiError.BadResponse -> stringResource(R.string.ai_error_bad_response)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_alert),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = messageText,
            style = ClassppTextTokens.secondaryTextSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.ai_retry),
            style = ClassppTextTokens.sectionAction,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onRetry)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Icon(
            painter = painterResource(R.drawable.ic_dismiss),
            contentDescription = stringResource(R.string.ai_dismiss_error),
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .clickable(onClick = onDismiss)
                .padding(2.dp),
        )
    }
}

// ---------- 空态 ----------

/** 未配置引导态：说明 + 直达 AI 设置按钮 */
@Composable
internal fun AiChatNotConfigured(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_chat_multiple),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp),
        )
        Text(
            text = stringResource(R.string.ai_not_configured_title),
            style = ClassppTextTokens.emptyTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.ai_not_configured_hint),
            style = ClassppTextTokens.emptyHint,
            color = MaterialTheme.classppColors.secondaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Box(
            modifier = Modifier
                .clip(PillShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onOpenSettings)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text(
                text = stringResource(R.string.ai_go_settings),
                style = ClassppTextTokens.buttonText,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/** 已配置的空会话：示例提问 chips，点按即发送 */
@Composable
internal fun AiChatEmptyState(
    examples: List<String>,
    onExample: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_chat_multiple),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp),
        )
        for (example in examples) {
            Box(
                modifier = Modifier
                    .clip(PillShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = PillShape,
                    )
                    .clickable { onExample(example) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = example,
                    style = ClassppTextTokens.secondaryTextSmall,
                    color = MaterialTheme.classppColors.secondaryText,
                )
            }
        }
    }
}

// ---------- 草稿卡 ----------

/** 草稿行摘要的第二行素材：日期 / 时段 / 截止 / 紧急度 / 分组 / 标签（有则显示） */
@Composable
private fun AiTodoDraftRowMeta(mapping: AiTodoDraftMapping): String {
    val todo = mapping.todo
    val parts = mutableListOf<String>()
    if (todo.dates.isNotEmpty()) {
        parts += todo.dates.joinToString("、") { it.toString() }
    }
    if (todo.timeKind == com.fangyi.classpp.data.model.TodoTimeKind.Period) {
        val start = todo.startMinute
        val end = todo.endMinute
        if (start != null && end != null) {
            parts += "${com.fangyi.classpp.data.model.TimeText.format(start)}-" +
                com.fangyi.classpp.data.model.TimeText.format(end)
        }
    }
    todo.deadlineDate?.let { date ->
        val minute = todo.deadlineMinute
        parts += if (minute != null) {
            "截止 ${date} ${com.fangyi.classpp.data.model.TimeText.format(minute)}"
        } else {
            "截止 $date"
        }
    }
    return parts.joinToString("　")
}

/** 单条草稿的「待人工修正」chip 文案 */
@Composable
private fun invalidFieldLabel(field: AiDraftField): String = when (field) {
    AiDraftField.Dates -> stringResource(R.string.ai_draft_invalid_dates)
    AiDraftField.PeriodTime -> stringResource(R.string.ai_draft_invalid_period)
    AiDraftField.Deadline -> stringResource(R.string.ai_draft_invalid_deadline)
}

/**
 * 待办草稿确认卡（红线：AI 不静默写库——保存动作全在这里发生，且用户可先逐条编辑）。
 *
 * 行为：Pending 行默认全选可勾选，「保存所选」把勾选行整批落库（走 TodoValidator，
 * 与手建同路径）；单行「编辑」打开预填的新建待办浮层；Saved/Dismissed 行只读展示状态。
 */
@Composable
internal fun AiChatDraftCard(
    drafts: AiMessageDrafts,
    mappings: List<AiTodoDraftMapping>,
    selected: Set<Int>,
    onToggle: (index: Int) -> Unit,
    onEdit: (index: Int) -> Unit,
    onSaveSelected: () -> Unit,
    onDismissDraft: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasPending = drafts.statuses.any { it == AiDraftStatus.Pending }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.ai_draft_card_title),
            style = ClassppTextTokens.sectionTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        for (index in drafts.drafts.indices) {
            val mapping = mappings.getOrNull(index) ?: continue
            val status = drafts.statuses.getOrElse(index) { AiDraftStatus.Pending }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 状态位：Pending = 可勾选框；Saved/Dismissed = 状态文字
                when (status) {
                    AiDraftStatus.Pending -> {
                        val checked = index in selected
                        Icon(
                            painter = painterResource(
                                if (checked) R.drawable.ic_checkbox_checked else R.drawable.ic_checkbox_unchecked,
                            ),
                            contentDescription = mapping.todo.name,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onToggle(index) }
                                .padding(1.dp),
                        )
                    }
                    AiDraftStatus.Saved -> StatusChip(
                        text = stringResource(R.string.ai_draft_saved),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    AiDraftStatus.Dismissed -> StatusChip(
                        text = stringResource(R.string.ai_draft_dismissed),
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = mapping.todo.name.ifBlank { "（未命名）" },
                        style = ClassppTextTokens.listRowTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val meta = AiTodoDraftRowMeta(mapping)
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta,
                            style = ClassppTextTokens.secondaryTextSmall,
                            color = MaterialTheme.classppColors.secondaryText,
                        )
                    }
                    if (mapping.invalidFields.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (field in mapping.invalidFields) {
                                Text(
                                    text = invalidFieldLabel(field),
                                    style = ClassppTextTokens.tagText,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
                if (status == AiDraftStatus.Pending) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = stringResource(R.string.cd_ai_draft_edit),
                        tint = MaterialTheme.classppColors.secondaryText,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .clickable { onEdit(index) }
                            .padding(2.dp),
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_dismiss),
                        contentDescription = stringResource(R.string.ai_draft_dismissed),
                        tint = MaterialTheme.classppColors.secondaryText,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .clickable { onDismissDraft(index) }
                            .padding(2.dp),
                    )
                }
            }
        }
        if (hasPending) {
            Box(
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(PillShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onSaveSelected)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.ai_draft_save_selected),
                    style = ClassppTextTokens.sectionAction,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = ClassppTextTokens.tagText,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(width = 1.dp, color = color, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ---------- 输入栏 ----------

/** 底部输入栏：圆角输入框 + 发送/停止切换。sending 时按钮变停止（取消当前流） */
@Composable
internal fun AiChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    enabled: Boolean,
    sending: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val focusColor = MaterialTheme.colorScheme.primary
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            enabled = enabled && !sending,
            singleLine = true,
            textStyle = ClassppTextTokens.secondaryText.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(focusColor),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.ai_input_hint),
                            style = ClassppTextTokens.secondaryText,
                            color = MaterialTheme.classppColors.secondaryText.copy(alpha = 0.6f),
                        )
                    }
                    inner()
                }
            },
        )
        val actionEnabled = if (sending) true else text.isNotBlank() && enabled
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (actionEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                )
                .clickable(enabled = actionEnabled) { if (sending) onStop() else onSend() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(if (sending) R.drawable.ic_stop else R.drawable.ic_send),
                contentDescription = stringResource(if (sending) R.string.cd_ai_stop else R.string.cd_ai_send),
                tint = if (actionEnabled) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.classppColors.secondaryText.copy(alpha = 0.6f)
                },
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

// ---------- 预览 ----------

@Preview(name = "气泡", showBackground = true)
@Composable
private fun BubblePreview() {
    MaterialTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AiChatMessageBubble(
                AiChatMessage(role = com.fangyi.classpp.data.ai.AiChatRole.User, content = "帮我看看下周安排"),
            )
            AiChatMessageBubble(
                AiChatMessage(
                    role = com.fangyi.classpp.data.ai.AiChatRole.Assistant,
                    content = "下周三有数学课，周五要交实验报告。\n\n```json\n{\"todos\":[{\"name\":\"实验报告\"}]}\n```",
                ),
            )
        }
    }
}

@Preview(name = "草稿卡", showBackground = true, widthDp = 360)
@Composable
private fun DraftCardPreview() {
    MaterialTheme {
        val drafts = AiMessageDrafts(
            drafts = listOf(
                AiTodoDraft(name = "实验报告", dates = listOf("2026-11-06"), deadlineDate = "2026-11-06", deadlineTime = "23:59"),
                AiTodoDraft(name = "复习数学", startTime = "19:00", endTime = "21:00", dates = listOf("2026-11-03")),
            ),
            statuses = listOf(AiDraftStatus.Pending, AiDraftStatus.Saved),
        )
        val mappings = AiTodoMapper.map(drafts.drafts)
        AiChatDraftCard(
            drafts = drafts,
            mappings = mappings,
            selected = setOf(0),
            onToggle = {},
            onEdit = {},
            onSaveSelected = {},
            onDismissDraft = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "输入栏", showBackground = true)
@Composable
private fun InputBarPreview() {
    MaterialTheme {
        AiChatInputBar(
            text = "添加：周五交实验报告",
            onTextChange = {},
            enabled = true,
            sending = false,
            onSend = {},
            onStop = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
