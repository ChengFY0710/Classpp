package com.fangyi.classpp.ui.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ai.AiChatMessage
import com.fangyi.classpp.data.ai.AiChatRole
import com.fangyi.classpp.data.ai.AiError
import com.fangyi.classpp.data.ai.AiImageStore
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
import com.fangyi.classpp.ui.theme.ChatBubbleShape
import com.fangyi.classpp.ui.theme.ChatErrorBannerShape
import com.fangyi.classpp.ui.theme.ChatThinkingShape
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.PillShape

/**
 * AI 对话浮层的子组件（气泡 / 已创建待办卡 / 错误条 / 空态）。
 * 布局全部无状态——状态（输入、浮层目标）由 [AiChatOverlay] 持有，这里只吃参数。
 */

// ---------- 消息气泡 ----------

/**
 * 一条消息气泡：user 右对齐（primary 底 / onPrimary 字）/ assistant 左对齐（surface 底 / onSurface 字）。
 * 正文统一 16sp；assistant 内容按 GFM Markdown 渲染（见 [ChatMarkdown]），user 内容为纯文本。
 * assistant 气泡在页边距内撑满宽度，user 气泡按内容自适应（上限 320dp）。
 * 两种气泡的正文都支持长按选择与复制：每个气泡一个独立的 [SelectionContainer]，
 * 选区不跨气泡；选中高亮随气泡底材取色（见函数内的 [TextSelectionColors]）。
 *
 * 推理模型的思考过程（[AiChatMessage.reasoning] 非空）渲染在气泡**上方**的可折叠块里；
 * [thinkingInProgress] = true（思考阶段、正文未开始）时默认展开，正文开始/结束后自动收起，
 * 用户手动点过标题行后不再自动切换（见 [AiChatThinkingBlock]）。
 *
 * 本条回复已创建待办时（[AiChatMessage.todoIds] 非空或落库失败），原始 JSON 围栏块
 * 不再展示——信息已由下方的 NoteCard 卡片承接；正文若因此为空，气泡本身不渲染
 * （若回复带思考过程，上方的折叠块仍保留）。
 *
 * user 消息携带图片时（[AiChatMessage.images] 非空），缩略图渲染在正文上方（单张 160dp、
 * 多张 96dp 换行网格），点缩略图经 [onImageClick] 请求宿主拉全屏预览；纯图无正文的
 * 消息不渲染「…」占位（那是流式首字未到的 assistant 占位语义）。
 */
@Composable
internal fun AiChatMessageBubble(
    message: AiChatMessage,
    thinkingInProgress: Boolean = false,
    modifier: Modifier = Modifier,
    // 图片附件库（仓库出借）：null = 无仓库（预览态），图片不渲染
    imageStore: AiImageStore? = null,
    onImageClick: (String) -> Unit = {},
) {
    val isUser = message.role == AiChatRole.User
    val hasTodos = !isUser && (message.todoIds.isNotEmpty() || message.todoSaveFailed)
    val content = if (hasTodos) AiTodoDraftCodec.stripJsonBlock(message.content) else message.content
    // 正文与思考都没有可展示的内容：整条消息不占位
    if (hasTodos && content.isEmpty() && message.reasoning.isEmpty()) return
    val showBubble = !(hasTodos && content.isEmpty())
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
    val shape = ChatBubbleShape
    // 选中高亮：assistant 白底用 primary 40%（项目「选中用 primary」口径）；
    // user 蓝底上 primary 40% 与底色同色、几乎看不出，改用 onPrimary 30% 的白晕
    val selectionColors = if (isUser) {
        TextSelectionColors(
            handleColor = contentColor,
            backgroundColor = contentColor.copy(alpha = 0.3f),
        )
    } else {
        TextSelectionColors(
            handleColor = MaterialTheme.colorScheme.primary,
            backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
        )
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = if (isUser) Modifier.widthIn(max = 320.dp) else Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!isUser && message.reasoning.isNotEmpty()) {
                AiChatThinkingBlock(
                    reasoning = message.reasoning,
                    thinkingInProgress = thinkingInProgress,
                )
            }
            if (showBubble) {
                Surface(
                    color = bubbleColor,
                    contentColor = contentColor,
                    shape = shape,
                    // assistant 在页边距内撑满（Column 已有 fillMaxWidth），user 仍按内容自适应
                    modifier = if (isUser) Modifier else Modifier.fillMaxWidth(),
                ) {
                    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
                        SelectionContainer {
                            Column(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (message.images.isNotEmpty() && imageStore != null) {
                                    AiChatMessageImages(
                                        names = message.images,
                                        imageStore = imageStore,
                                        onImageClick = onImageClick,
                                    )
                                }
                                when {
                                    content.isEmpty() && message.images.isEmpty() -> {
                                        // 占位阶段（流式首字未到 / 只有思考还没有正文）：三点呼吸省略号
                                        Text(text = "…", style = ClassppTextTokens.chatBody)
                                    }
                                    content.isEmpty() -> {
                                        // 纯图无正文：缩略图即全部内容
                                    }
                                    isUser -> Text(text = content, style = ClassppTextTokens.chatBody)
                                    else -> ChatMarkdown(content = content)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- 消息图片 ----------

/** 气泡内缩略图尺寸：单张放大（看得清内容），多张小格换行 */
private val SingleImageSize = 160.dp
private val GridImageSize = 96.dp
private val ImageCorner = 10.dp
private val ImageSpacing = 6.dp

/**
 * user 消息携带的图片缩略图：单张 160dp 方图，多张 96dp 换行网格。
 * 点击经 [onImageClick] 把文件名抛给宿主（全屏预览 Dialog）。
 */
@Composable
private fun AiChatMessageImages(
    names: List<String>,
    imageStore: AiImageStore,
    onImageClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (names.size == 1) {
        AiChatImageThumb(
            name = names.first(),
            imageStore = imageStore,
            size = SingleImageSize,
            onImageClick = onImageClick,
            modifier = modifier,
        )
    } else {
        FlowRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(ImageSpacing),
            verticalArrangement = Arrangement.spacedBy(ImageSpacing),
        ) {
            names.forEach { name ->
                AiChatImageThumb(
                    name = name,
                    imageStore = imageStore,
                    size = GridImageSize,
                    onImageClick = onImageClick,
                )
            }
        }
    }
}

/** 单张缩略图：Crop 裁进圆角方框，点击拉全屏预览 */
@Composable
private fun AiChatImageThumb(
    name: String,
    imageStore: AiImageStore,
    size: Dp,
    onImageClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = imageStore.file(name),
        contentDescription = stringResource(R.string.cd_ai_message_image),
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(ImageCorner))
            .clickable { onImageClick(name) },
    )
}

// ---------- 思考过程 ----------

/**
 * 推理模型的思考过程折叠块：弱化容器（surface 底 + outline 描边）+ 标题行（sparkle 图标 /
 * 文案 / 折叠箭头），点标题行切换展开。
 *
 * 展开态由 [thinkingInProgress] 自动驱动：思考阶段（正文还没开始）默认展开，正文开始或
 * 回复结束后自动收起；用户手动点过之后 [userToggled] 置位，不再自动改变——避免正文开始时
 * 把用户主动展开的内容强行收走。历史消息（非流式）初始收起。
 */
@Composable
internal fun AiChatThinkingBlock(
    reasoning: String,
    thinkingInProgress: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(thinkingInProgress) }
    var userToggled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(thinkingInProgress) {
        if (!userToggled) expanded = thinkingInProgress
    }
    val tint = MaterialTheme.classppColors.secondaryText
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = tint,
        shape = ChatThinkingShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        userToggled = true
                        expanded = !expanded
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_ai),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(
                        if (thinkingInProgress) R.string.ai_thinking_in_progress else R.string.ai_thinking_title,
                    ),
                    style = ClassppTextTokens.sectionTitle,
                    color = tint,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painter = painterResource(
                        if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
                    ),
                    contentDescription = stringResource(R.string.cd_ai_thinking_toggle),
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = reasoning.trim(),
                    style = ClassppTextTokens.secondaryTextSmall,
                    color = tint,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                )
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
            .clip(ChatErrorBannerShape)
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
            painter = painterResource(R.drawable.ic_ai),
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
                    reasoning = "先把这周的课表和待办过一遍：周三晚上高数课，周五是实验报告的截止日，" +
                        "再确认一下有没有和复习冲突的安排。",
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

@Preview(name = "思考过程", showBackground = true, widthDp = 360)
@Composable
private fun ThinkingBlockPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AiChatThinkingBlock(
                reasoning = "先确认今天的日期，再看课表里有没有课；如果没有冲突，就把复习安排到晚上。",
                thinkingInProgress = true,
            )
            AiChatThinkingBlock(
                reasoning = "先确认今天的日期，再看课表里有没有课；如果没有冲突，就把复习安排到晚上。",
                thinkingInProgress = false,
            )
        }
    }
}
