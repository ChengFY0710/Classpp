package com.fangyi.classpp.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ai.AiChatMessage
import com.fangyi.classpp.data.ai.AiChatRole
import com.fangyi.classpp.data.ai.AiError
import com.fangyi.classpp.data.ai.AiTodoDraftCodec
import com.fangyi.classpp.data.checkInPercent
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.ui.components.NoteCard
import com.fangyi.classpp.ui.components.NoteCardSwipe
import com.fangyi.classpp.ui.components.SwipeAction
import com.fangyi.classpp.ui.components.SwipeCoordinator
import com.fangyi.classpp.ui.note.cardTimeText
import com.fangyi.classpp.ui.note.deadlineCardText
import com.fangyi.classpp.ui.note.deadlineColor
import com.fangyi.classpp.ui.note.deadlineDaysLeft
import com.fangyi.classpp.ui.note.repeatCardText
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.PillShape

/**
 * AI 对话浮层的子组件（气泡 / 已创建待办卡 / 错误条 / 空态 / 输入栏）。
 * 布局全部无状态——状态（输入、浮层目标）由 [AiChatOverlay] 持有，这里只吃参数。
 */

/** 气泡圆角：大圆角 + 朝向说话方的小角，视觉区分双方 */
private val BubbleCorner = 18.dp
private val BubbleTailCorner = 4.dp
private val BubbleShape = RoundedCornerShape(BubbleCorner)

// ---------- 消息气泡 ----------

/**
 * 一条消息气泡：user 右对齐（primary 底 / onPrimary 字）/ assistant 左对齐（surface 底 / onSurface 字）。
 * 正文统一 16sp；assistant 内容按 GFM Markdown 渲染（见 [ChatMarkdown]），user 内容为纯文本。
 *
 * 本条回复已创建待办时（[AiChatMessage.todoIds] 非空或落库失败），原始 JSON 围栏块
 * 不再展示——信息已由下方的 NoteCard 卡片承接；正文若因此为空，整个气泡不渲染。
 */
@Composable
internal fun AiChatMessageBubble(
    message: AiChatMessage,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == AiChatRole.User
    val hasTodos = !isUser && (message.todoIds.isNotEmpty() || message.todoSaveFailed)
    val content = if (hasTodos) AiTodoDraftCodec.stripJsonBlock(message.content) else message.content
    if (hasTodos && content.isEmpty()) return
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
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
                when {
                    content.isEmpty() -> {
                        // 占位阶段（流式首字未到）：三点呼吸省略号
                        Text(text = "…", style = ClassppTextTokens.chatBody)
                    }
                    isUser -> Text(text = content, style = ClassppTextTokens.chatBody)
                    else -> ChatMarkdown(content = content)
                }
            }
        }
    }
}

// ---------- 已创建待办 ----------

/**
 * 一条 assistant 回复里 AI 直接创建的待办列表：与待办页同一 [NoteCard] 样式
 * （待办时间/截止取色/重复/标签/紧急旗/打卡进度全部接线），点击拉起详情浮层，
 * 侧滑呼出删除动作卡（满色松手即软删，无二次确认）。
 *
 * 卡片内容来自 TodoRepository 的活跃待办流（宿主按 [Todo] 传入选好的条目）：
 * 待办被删除/归档后自然从列表消失；重复系列不做逐期展开，勾选框停用
 * （逐期完成在详情浮层的完成情况里操作），打卡系列按 [checkInPercent] 显示进度。
 */
@Composable
internal fun AiChatCreatedTodoList(
    todos: List<Todo>,
    tagColors: Map<String, Color>,
    swipeCoordinator: SwipeCoordinator,
    onOpen: (Todo) -> Unit,
    onDelete: (Todo) -> Unit,
    onCheckedChange: (Todo, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (todos.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.ai_created_todos),
            style = ClassppTextTokens.sectionTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        for (todo in todos) {
            val daysLeft = todo.deadlineDaysLeft()
            NoteCard(
                title = todo.name,
                time = todo.cardTimeText(),
                deadline = todo.deadlineCardText(),
                deadlineColor = todo.deadlineColor(),
                repeat = todo.repeatCardText(),
                countdownDays = if (todo.countdown) daysLeft else null,
                checkInPercent = todo.checkInPercent(),
                tags = todo.tags,
                tagColors = tagColors,
                urgency = todo.urgency,
                completed = todo.completed,
                overdue = daysLeft != null && daysLeft < 0,
                onClick = { onOpen(todo) },
                // 重复待办没有整条完成态（逐期结算），卡片勾选框停用
                onCheckedChange = if (todo.repeat.type == RepeatType.None) {
                    { checked -> onCheckedChange(todo, checked) }
                } else {
                    null
                },
                swipe = NoteCardSwipe(
                    key = todo.id,
                    coordinator = swipeCoordinator,
                    delete = SwipeAction(
                        iconRes = R.drawable.ic_delete,
                        tint = MaterialTheme.colorScheme.error,
                        activeColor = MaterialTheme.colorScheme.error,
                        onActiveColor = MaterialTheme.colorScheme.onError,
                        contentDescription = stringResource(R.string.todo_swipe_delete),
                        onAction = { onDelete(todo) },
                    ),
                    pin = null,
                ),
            )
        }
    }
}

/** 草稿解析出来但落库失败的提示（正文与卡片都不丢，只提示重试） */
@Composable
internal fun AiChatTodoSaveFailedHint(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.ai_todo_save_failed),
        style = ClassppTextTokens.secondaryTextSmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
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
                AiChatMessage(role = AiChatRole.User, content = "帮我看看下周安排"),
            )
            AiChatMessageBubble(
                AiChatMessage(
                    role = AiChatRole.Assistant,
                    content = """
                        ## 下周安排
                        下周三有**数学课**，周五要交 `实验报告`。

                        - 周三 19:00 复习高数
                        - 周五 23:59 前交报告

                        | 日期 | 事项 |
                        | --- | --- |
                        | 周三 | 高等数学 |
                        | 周五 | 实验报告 |

                        [查看课表](https://example.com)
                    """.trimIndent(),
                ),
            )
            AiChatMessageBubble(
                AiChatMessage(
                    role = AiChatRole.Assistant,
                    content = "已为你创建待办。\n\n```json\n{\"todos\":[{\"name\":\"实验报告\"}]}\n```",
                    todoIds = listOf("t1"),
                ),
            )
        }
    }
}

@Preview(name = "已创建待办", showBackground = true, widthDp = 360)
@Composable
private fun CreatedTodosPreview() {
    MaterialTheme {
        val todos = listOf(
            Todo(
                id = "t1",
                name = "实验报告",
                dates = listOf(IsoDate.of(2026, 11, 6)),
                deadlineDate = IsoDate.of(2026, 11, 6),
                deadlineMinute = 23 * 60 + 59,
            ),
            Todo(
                id = "t2",
                name = "复习数学",
                dates = listOf(IsoDate.of(2026, 11, 3)),
                timeKind = com.fangyi.classpp.data.model.TodoTimeKind.Period,
                startMinute = 19 * 60,
                endMinute = 21 * 60,
            ),
        )
        AiChatCreatedTodoList(
            todos = todos,
            tagColors = emptyMap(),
            swipeCoordinator = remember { SwipeCoordinator() },
            onOpen = {},
            onDelete = {},
            onCheckedChange = { _, _ -> },
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
