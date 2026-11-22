package com.fangyi.classpp.ui.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ai.AiChatMessage
import com.fangyi.classpp.data.ai.AiChatRole
import com.fangyi.classpp.data.ai.AiError
import com.fangyi.classpp.data.ai.AiImageStore
import com.fangyi.classpp.data.ai.AiTodoChange
import com.fangyi.classpp.data.ai.AiTodoChangeFailure
import com.fangyi.classpp.data.ai.AiTodoChangeKind
import com.fangyi.classpp.data.ai.AiTodoChangeStatus
import com.fangyi.classpp.data.ai.AiTodoCompletion
import com.fangyi.classpp.data.ai.AiTodoCompletionStatus
import com.fangyi.classpp.data.ai.AiTodoDraftCodec
import com.fangyi.classpp.data.ai.AiTodoLifecycle
import com.fangyi.classpp.data.ai.AiTodoLifecycleAction
import com.fangyi.classpp.data.ai.AiTodoLifecycleStatus
import com.fangyi.classpp.data.ai.AiTodoMapper
import com.fangyi.classpp.data.ai.AiTodoPatch
import com.fangyi.classpp.data.ai.AiToolRound
import com.fangyi.classpp.data.checkInPercent
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoUrgency
import com.fangyi.classpp.ui.components.EmptyState
import com.fangyi.classpp.ui.components.EmptyStateActionButton
import com.fangyi.classpp.ui.components.NoteCard
import com.fangyi.classpp.ui.components.NoteCardSwipe
import com.fangyi.classpp.ui.components.SheetInfoBanner
import com.fangyi.classpp.ui.components.SwipeAction
import com.fangyi.classpp.ui.components.SwipeCoordinator
import com.fangyi.classpp.ui.components.labelText
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.PressShade
import com.fangyi.classpp.ui.motion.containedRubberBandVerticalScroll
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.note.cardTimeText
import com.fangyi.classpp.ui.note.deadlineCardText
import com.fangyi.classpp.ui.note.deadlineColor
import com.fangyi.classpp.ui.note.deadlineDaysLeft
import com.fangyi.classpp.ui.note.repeatCardText
import com.fangyi.classpp.ui.theme.CardSectionSpacing
import com.fangyi.classpp.ui.theme.ChatCardShape
import com.fangyi.classpp.ui.theme.ChatImageShape
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.RowCardPaddingHorizontal
import com.fangyi.classpp.ui.theme.RowCardPaddingVertical
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.isDarkTheme

/**
 * AI 对话浮层的子组件（气泡 / 已创建待办卡 / 错误条 / 空态）。
 * 布局全部无状态——状态（输入、浮层目标）由 [AiChatOverlay] 持有，这里只吃参数。
 */

// ---------- 消息气泡 ----------

/**
 * 一条消息气泡：user 右对齐（surface 底 / primary 字）/ assistant 左对齐（surface 底 / onSurface 字）。
 * 正文统一 16sp；assistant 内容按 GFM Markdown 渲染（见 [ChatMarkdown]），user 内容为纯文本，
 * 用 [ClassppTextTokens.chatUserBody]（0.3sp 正字距，与 Chat 模式 Markdown 正文一致）。
 * assistant 气泡在页边距内撑满宽度，user 气泡按内容自适应（上限 320dp）。
 * 两种气泡的正文都支持长按选择与复制：每个气泡一个独立的 [SelectionContainer]，
 * 选区不跨气泡；选中高亮随气泡底材取色（见函数内的 [TextSelectionColors]）。
 *
 * 推理模型的思考过程（[AiChatMessage.reasoning] 非空）渲染在气泡**上方**的可折叠块里；
 * [thinkingInProgress] = true（思考阶段、正文未开始）时默认展开，正文开始/结束后自动收起，
 * 用户手动点过标题行后不再自动切换（见 [AiChatThinkingBlock]）。思考正文超出高度上限后
 * 在卡内滚动，流式期间粘底跟随最新内容，用户上滑阅读时暂停、回到底部恢复。
 *
 * 本条回复已创建待办、执行了完成/取消完成，或生成了待确认的修改/删除时
 * （[AiChatMessage.todoIds] 非空、落库失败、[AiChatMessage.todoChanges] 非空、
 * 有动作被丢弃或 [AiChatMessage.todoCompletions] 非空），原始 JSON 围栏块
 * 不再展示——信息已由下方卡片承接；正文若因此为空，气泡本身不渲染
 * （若回复带思考过程，上方的折叠块仍保留）。
 *
 * user 消息携带图片时（[AiChatMessage.images] 非空），缩略图渲染在正文上方（单张 160dp、
 * 多张 96dp 换行网格），点缩略图经 [onImageClick] 请求宿主拉全屏预览；纯图无正文的
 * 消息不渲染「正在思考…」占位（那是流式首字未到的 assistant 占位语义）。
 *
 * assistant 正文为空且被中断/失败（[AiChatMessage.interrupted]）时，占位文案原地从
 * 「正在思考…」变为「回答已中断」，中断语义由气泡本身承接，消息下方不再单独提示。
 */
@Composable
internal fun AiChatMessageBubble(
    message: AiChatMessage,
    thinkingInProgress: Boolean = false,
    /** 正在流式增长的尾部消息：关闭尺寸 tween，避免每个合并帧重设动画目标 */
    streamingTail: Boolean = false,
    modifier: Modifier = Modifier,
    // 图片附件库（仓库出借）：null = 无仓库（预览态），图片不渲染
    imageStore: AiImageStore? = null,
    onImageClick: (String) -> Unit = {},
    /** 截断提示里的重试入口：null = 不显示（非尾部消息没有可重试的上下文） */
    onRetry: (() -> Unit)? = null,
) {
    val isUser = message.role == AiChatRole.User
    val hasTodos = !isUser && (
        message.todoIds.isNotEmpty() ||
            message.todoSaveFailed ||
            message.todoChanges.isNotEmpty() ||
            message.todoChangeDropped ||
            message.todoCompletions.isNotEmpty() ||
            message.todoLifecycles.isNotEmpty()
        )
    val hasTools = !isUser && message.toolRounds.isNotEmpty()
    // 每批流式更新都会重组气泡：围栏解析/分段只在 content 或卡片集合变化时重算
    val content = remember(message.content, hasTodos, hasTools) {
        if (hasTodos || hasTools) {
            AiTodoDraftCodec.stripJsonBlock(message.content)
        } else {
            message.content
        }
    }
    // 工具轮次与最终正文交错渲染：每轮「文本 → 工具卡」，最后是最终正文。
    // 分段依赖 content 与 toolRounds；用「轮数 + 最后一轮状态」做轻量键——
    // 工具结果 contentJson 可能很大，避免每批流式都做整表结构比较
    val roundsSignature = message.toolRounds.size to message.toolRounds.lastOrNull()?.status
    val segments = remember(content, roundsSignature) {
        if (hasTools) message.assistantSegments(content) else emptyList()
    }
    // 正文与思考都没有可展示的内容（待办卡片承接了全部信息）：整条消息不占位；
    // 有工具轮次时即使正文为空也要渲染工具卡
    if (!hasTools && hasTodos && content.isEmpty() && message.reasoning.isEmpty()) return
    val showBubble = hasTools || !(hasTodos && content.isEmpty())
    val bubbleColor = MaterialTheme.colorScheme.surface
    val contentColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val shape = ChatCardShape
    // 选中高亮：user 与 assistant 的白底气泡统一用 primary 40%（项目「选中用 primary」口径）
    val selectionColors = TextSelectionColors(
        handleColor = MaterialTheme.colorScheme.primary,
        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = if (isUser) {
                Modifier.widthIn(max = 320.dp)
            } else {
                Modifier
                    .fillMaxWidth()
                    // 流式尾部按合并批次直接长高：不再每批重设 150ms 尺寸 tween
                    // （连续重设会让整列反复测量/布局，正是长回复掉帧的主因之一）
                    .then(
                        if (streamingTail) {
                            Modifier
                        } else {
                            Modifier.animateContentSize(
                                animationSpec = tween(
                                    durationMillis = Motion.ChatBubbleResizeMillis,
                                    easing = Motion.Standard,
                                ),
                            )
                        },
                    )
            },
            // 思考卡↔回复气泡：回复内卡片堆叠统一按全局「卡↔卡」节奏（10dp）
            verticalArrangement = Arrangement.spacedBy(CardSectionSpacing),
        ) {
            if (!isUser && message.reasoning.isNotEmpty()) {
                AiChatThinkingBlock(
                    reasoning = message.reasoning,
                    thinkingInProgress = thinkingInProgress,
                    streamingTail = streamingTail,
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
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        // 回复内的正文分段与工具卡同样按「卡↔卡」节奏；用户气泡的图片↔正文仍是 8dp
                        verticalArrangement = Arrangement.spacedBy(
                            if (hasTools) CardSectionSpacing else 8.dp,
                        ),
                    ) {
                        if (message.images.isNotEmpty() && imageStore != null) {
                            AiChatMessageImages(
                                names = message.images,
                                imageStore = imageStore,
                                onImageClick = onImageClick,
                            )
                        }
                        when {
                            isUser -> when {
                                content.isEmpty() && message.images.isEmpty() -> {
                                    // 占位阶段（流式首字未到 / 只有思考还没有正文）：明确提示正在思考
                                    Text(
                                        text = stringResource(R.string.ai_thinking_in_progress),
                                        style = ClassppTextTokens.chatBody,
                                    )
                                }
                                content.isEmpty() -> {
                                    // 纯图无正文：缩略图即全部内容
                                }
                                else -> CompositionLocalProvider(
                                    LocalTextSelectionColors provides selectionColors,
                                ) {
                                    SelectionContainer {
                                        Text(
                                            text = content,
                                            // 用户气泡正文比其他正文稍松，读起来更透气
                                            style = ClassppTextTokens.chatUserBody,
                                        )
                                    }
                                }
                            }
                            hasTools -> {
                                segments.forEach { segment ->
                                    if (segment.text.isNotBlank()) {
                                        CompositionLocalProvider(
                                            LocalTextSelectionColors provides selectionColors,
                                        ) {
                                            SelectionContainer {
                                                ChatMarkdown(content = segment.text)
                                            }
                                        }
                                    }
                                    segment.round?.let { round ->
                                        AiChatToolRoundBlock(round = round)
                                    }
                                }
                                if (message.toolLoopCapped) {
                                    AiChatToolLoopCappedHint()
                                }
                            }
                            content.isEmpty() && message.images.isEmpty() -> {
                                // 占位阶段：流式中提示正在思考；中断/失败后原地改为中断提示，
                                // 中断语义并入气泡，不再在消息下方单独占一行
                                Text(
                                    text = stringResource(
                                        if (message.interrupted) R.string.ai_interrupted
                                        else R.string.ai_thinking_in_progress,
                                    ),
                                    style = ClassppTextTokens.chatBody,
                                )
                            }
                            content.isEmpty() -> {
                                // 纯图无正文：缩略图即全部内容
                            }
                            else -> CompositionLocalProvider(
                                LocalTextSelectionColors provides selectionColors,
                            ) {
                                SelectionContainer {
                                    ChatMarkdown(content = content)
                                }
                            }
                        }
                        // 输出被 token 上限截断：明确提示并提供原地重试（截断的 JSON 不会被
                        // 解析成待办，重试是唯一恢复路径）
                        if (!isUser && message.truncated) {
                            AiChatTruncatedHint(onRetry = onRetry)
                        }
                    }
                }
            }
        }
    }
}

/**
 * assistant 消息的分段展示：按 toolRounds 顺序切出「工具轮文本」，最后追加最终正文。
 *
 * content 是各轮文本的顺序拼接（设计稿 §8.1），工具轮文本长度是切分依据；持久化数据
 * 异常（长度不足等）时按剩余内容兜底，绝不越界。
 */
private data class AiAssistantSegment(
    val text: String,
    val round: AiToolRound?,
)

private fun AiChatMessage.assistantSegments(displayContent: String): List<AiAssistantSegment> {
    val segments = mutableListOf<AiAssistantSegment>()
    var offset = 0
    for (round in toolRounds) {
        val start = offset.coerceAtMost(displayContent.length)
        val end = (offset + round.text.length).coerceAtMost(displayContent.length)
        segments += AiAssistantSegment(displayContent.substring(start, end), round)
        offset += round.text.length
    }
    segments += AiAssistantSegment(displayContent.drop(offset.coerceAtMost(displayContent.length)), null)
    return segments
}

// ---------- 消息图片 ----------

/** 气泡内缩略图尺寸：单张放大（看得清内容），多张小格换行 */
private val SingleImageSize = 160.dp
private val GridImageSize = 96.dp
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
            .clip(ChatImageShape)
            .clickable { onImageClick(name) },
    )
}

// ---------- 思考过程 ----------

/**
 * 思考正文的可视高度上限：超出部分在卡内滚动查看。不设上限时长推理会把消息列表顶满，
 * 把下方回复挤出视口。上限只作用于正文，标题行常驻（随时可点收起）。
 */
private val ThinkingBlockMaxBodyHeight = 240.dp

/** 智能跟底：认定「在底部」的容差（与消息列流式跟底同口径） */
private val ThinkingFollowBottomSlop = 8.dp

/** 智能跟底：单帧最大步长（同消息列流式跟底） */
private val ThinkingFollowMaxStep = 24.dp

/** 智能跟底：每帧补齐剩余差值的比例，约 8~12 帧收敛到底 */
private const val ThinkingFollowFraction = 0.28f

/**
 * 推理模型的思考过程折叠块：弱化容器（surface 底，无描边）+ 标题行（sparkle 图标 /
 * 文案 / 折叠箭头），点标题行切换展开。
 *
 * 展开态由 [thinkingInProgress] 自动驱动：思考阶段（正文还没开始）默认展开，正文开始或
 * 回复结束后自动收起；用户手动点过之后 [userToggled] 置位，不再自动改变——避免正文开始时
 * 把用户主动展开的内容强行收走。历史消息（非流式）初始收起。
 *
 * 展开后正文最高 [ThinkingBlockMaxBodyHeight]，超出部分在卡内纵向滚动：滚到边继续拖或
 * 惯性甩到边时，由卡内自己的橡皮筋过滚动拉出（[containedRubberBandVerticalScroll]），
 * 剩余滚动量与甩动速度不交给消息列表，手感和整页过滚动一致。
 *
 * 正文是「粘底」的（Stick to Bottom，[streamingTail] 时默认开启）：流式思考内容增长时
 * 自动滚到最新一行；用户上滑读历史立即暂停跟随，自己滑回底部（或在底部松手）后恢复；
 * 历史消息手动展开时初始不跟底，从第一行开始读。
 */
@Composable
internal fun AiChatThinkingBlock(
    reasoning: String,
    thinkingInProgress: Boolean,
    /** 正在流式增长的尾部消息：思考正文默认粘底跟随 */
    streamingTail: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(thinkingInProgress) }
    var userToggled by rememberSaveable { mutableStateOf(false) }
    // 未手动切换时直接跟随思考阶段，不在 LaunchedEffect 里二次写状态：那会让正文首帧
    // 与自动收合差一帧（正文已出现、思考仍展开），气泡先冲高再缩回，读感是上下抖一下。
    val shownExpanded = if (userToggled) expanded else thinkingInProgress
    val tint = MaterialTheme.classppColors.secondaryText
    val scrollState = rememberScrollState()
    // 粘底状态：流式消息默认跟随；用户上滑置 false，回到底部（或松手停在底部）恢复
    var autoFollow by remember { mutableStateOf(streamingTail) }
    val density = LocalDensity.current
    val bottomSlopPx = with(density) { ThinkingFollowBottomSlop.roundToPx() }
    val maxStepPx = with(density) { ThinkingFollowMaxStep.roundToPx() }

    // 手势/惯性接管期间：离开底部即暂停跟随，处在本页内回到底部即恢复。
    // 程序化的跟底位移走 dispatchRawDelta（不置 isScrollInProgress），不会被误判成用户滚动
    LaunchedEffect(scrollState, shownExpanded) {
        if (!shownExpanded) return@LaunchedEffect
        snapshotFlow { scrollState.isScrollInProgress to (scrollState.maxValue - scrollState.value) }
            .collect { (scrolling, gap) ->
                if (scrolling) {
                    autoFollow = gap <= bottomSlopPx
                } else if (gap <= bottomSlopPx) {
                    autoFollow = true
                }
            }
    }

    // 正文变长（maxValue 上涨）后分帧补齐到底：一帧瞬移会把整段 chunk 读成一次猛跳，
    // 与消息列的流式跟底同款逐帧逼近；用户接管时立即让出控制权
    LaunchedEffect(scrollState, shownExpanded) {
        if (!shownExpanded) return@LaunchedEffect
        snapshotFlow { scrollState.maxValue }.collect {
            if (!autoFollow) return@collect
            // 等新内容完成布局，再开始逼近新的底部
            withFrameNanos { }
            while (autoFollow && !scrollState.isScrollInProgress) {
                val gap = scrollState.maxValue - scrollState.value
                if (gap <= 0) break
                val step = if (gap <= 1) {
                    gap
                } else {
                    minOf(maxStepPx, (gap * ThinkingFollowFraction).toInt().coerceAtLeast(1))
                }
                scrollState.dispatchRawDelta(step.toFloat())
                withFrameNanos { }
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = tint,
        shape = ChatCardShape,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        userToggled = true
                        expanded = !shownExpanded
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
                        if (shownExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
                    ),
                    contentDescription = stringResource(R.string.cd_ai_thinking_toggle),
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(visible = shownExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 高度上限 + 卡内滚动：到边的拖拽/甩动在卡内过滚动，不回传消息列表
                        .containedRubberBandVerticalScroll(
                            state = scrollState,
                            maxHeight = ThinkingBlockMaxBodyHeight,
                        )
                        .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                ) {
                    Text(
                        text = reasoning.trim(),
                        style = ClassppTextTokens.secondaryTextSmall,
                        color = tint,
                    )
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
        // 一次回复创建的多张待办卡：与回复内其他卡片同节奏（10dp）
        verticalArrangement = Arrangement.spacedBy(CardSectionSpacing),
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
    SheetInfoBanner(
        text = stringResource(R.string.ai_todo_save_failed),
        modifier = modifier,
        isError = true,
    )
}

// ---------- 待确认的待办变更 ----------

/** 一行变更摘要：字段文案资源 + 已本地化的值（值在组合期由 [aiTodoChangeLines] 生成） */
private data class AiTodoChangeLine(val labelRes: Int, val value: String)

/** 备注等长文本在确认卡里的展示上限：确认卡只做摘要，完整内容去详情页看 */
private const val NotePreviewMaxLength = 40

/**
 * AI 生成的修改/删除确认卡：确认前不写待办库，用户点「应用变更」整批执行，
 * 点「忽略」整批作废。每条变更展示目标名称、变更摘要与状态（已应用/失败/已忽略）。
 *
 * 规格：卡内距 [RowCardPaddingHorizontal] / [RowCardPaddingVertical]（16/14）；标题行前置
 * ic_checkmark_circle_regular（18dp，图标↔标题 6dp，同 AI 思考卡标题行），文字用
 * classppTextStyles.sectionTitle（分组小标题同款 14sp Medium / 20sp 行高 secondaryText，
 * 不带 CardSectionTitle 的 6dp 缩进，与变更行左对齐）；每条变更的名称走 fieldLabel
 * （16sp SemiBold onSurface），变更摘要与提示对齐 SettingsCard 描述
 * （settingsDescription 14sp / 20sp 行高 secondaryText），名称↔描述 3dp，多条摘要连排。
 *
 * 待确认时的底部动作条：内容与动作条之间用 onSurfaceCard 分割线（左右随卡内距内缩），
 * 下方两颗等宽文字动作各占半张卡宽，文字与 SettingsCard 行标题同款 fieldLabel
 * （16sp SemiBold），上下内距 [RowCardPaddingVertical]（14dp）；按压反馈用项目统一的
 * 整体放大 + 明暗，放大溢出随卡片圆角裁剪。
 *
 * [todosById] 用于判断目标是否仍存在、是否为学校同步待办；删除后目标从活跃流消失，
 * 卡片仍按落盘时的名称快照展示，不会凭空消失。
 */
@Composable
internal fun AiChatTodoChangeCard(
    changes: List<AiTodoChange>,
    todosById: Map<String, Todo>,
    applying: Boolean,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (changes.isEmpty()) return
    val tint = MaterialTheme.classppColors.secondaryText
    val pending = changes.any { it.status == AiTodoChangeStatus.Pending }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = ChatCardShape,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Column(
                modifier = Modifier.padding(
                    horizontal = RowCardPaddingHorizontal,
                    vertical = RowCardPaddingVertical,
                ),
                verticalArrangement = Arrangement.spacedBy(CardSectionSpacing),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // 图标↔标题 6dp：与 AI 思考卡标题行同款
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_checkmark_circle_regular),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.ai_todo_change_title),
                        // 与分组标题同款文字样式，但不带 CardSectionTitle 的 6dp 起始缩进：
                        // 卡内标题行与下方变更行左对齐
                        style = MaterialTheme.classppTextStyles.sectionTitle,
                    )
                }
                changes.forEach { change ->
                    AiChatTodoChangeRow(change = change, todosById = todosById)
                }
            }
            if (pending) {
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.classppColors.onSurfaceCard,
                    modifier = Modifier.padding(horizontal = RowCardPaddingHorizontal),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    AiChatTodoChangeAction(
                        label = stringResource(R.string.ai_todo_change_dismiss),
                        color = MaterialTheme.colorScheme.onSurface,
                        enabled = !applying,
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    AiChatTodoChangeAction(
                        label = stringResource(
                            if (applying) R.string.ai_todo_change_applying
                            else R.string.ai_todo_change_apply,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        enabled = !applying,
                        onClick = onApply,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * 确认卡底部动作单元：占半张卡宽，文字上下内距 [RowCardPaddingVertical]（14dp），
 * 样式与 SettingsCard 行标题同款（fieldLabel）。按压反馈走项目统一的 pressFeedback
 * （整体放大 + 明暗）：浅色模式压暗、深色模式提亮，放大溢出由卡片圆角裁剪。
 */
@Composable
private fun AiChatTodoChangeAction(
    label: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shade = if (MaterialTheme.isDarkTheme) PressShade.Brighten else PressShade.Darken
    Box(
        modifier = modifier
            .pressClickable(
                shape = RectangleShape,
                shade = shade,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = RowCardPaddingVertical),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.classppTextStyles.fieldLabel,
            color = color,
        )
    }
}

@Composable
private fun AiChatTodoChangeRow(
    change: AiTodoChange,
    todosById: Map<String, Todo>,
) {
    val tint = MaterialTheme.classppColors.secondaryText
    val error = MaterialTheme.colorScheme.error
    val isDelete = change.kind == AiTodoChangeKind.Delete
    val target = todosById[change.todoId]
    val pending = change.status == AiTodoChangeStatus.Pending
    // 描述统一走 SettingsCard 行内描述样式：14sp Normal / 20sp 行高 / secondaryText
    val descriptionStyle = ClassppTextTokens.settingsDescription.copy(color = tint)
    val lines = if (!isDelete && change.patch != null) {
        aiTodoChangeLines(change.patch)
    } else {
        emptyList()
    }
    val hasDescription = isDelete || change.patch != null || (pending && target == null)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(
                    if (isDelete) R.string.ai_todo_change_delete else R.string.ai_todo_change_update,
                ),
                style = ClassppTextTokens.sectionAction,
                color = if (isDelete) error else MaterialTheme.colorScheme.primary,
            )
            Text(
                text = change.todoName.ifBlank { "—" },
                style = MaterialTheme.classppTextStyles.fieldLabel,
                modifier = Modifier.weight(1f),
            )
            AiChatTodoChangeStatusLabel(change)
        }
        if (hasDescription) {
            // 摘要连排：每行自带 20sp 行高，不再在行间叠 3dp（与 SettingsCard 描述块一致）
            Column {
                if (!isDelete && change.patch != null) {
                    if (lines.isEmpty()) {
                        Text(
                            text = stringResource(R.string.ai_todo_change_updated),
                            style = descriptionStyle,
                        )
                    } else {
                        lines.forEach { line ->
                            Text(
                                text = stringResource(
                                    R.string.ai_todo_change_line,
                                    stringResource(line.labelRes),
                                    line.value,
                                ),
                                style = descriptionStyle,
                            )
                        }
                    }
                }
                if (isDelete) {
                    Text(
                        text = stringResource(R.string.ai_todo_change_delete_hint),
                        style = descriptionStyle,
                    )
                }
                if (pending && target == null) {
                    Text(
                        text = stringResource(R.string.ai_todo_change_not_found),
                        style = descriptionStyle.copy(color = error),
                    )
                }
                // 同步来源（xmu 等）的删除才提示「下次同步可能恢复」；AI 自己创建的不要吓用户
                if (pending && isDelete &&
                    target?.source?.let { it.isNotBlank() && it != AiTodoMapper.SOURCE_AI } == true
                ) {
                    Text(
                        text = stringResource(R.string.ai_todo_change_sync_hint),
                        style = descriptionStyle,
                    )
                }
            }
        }
    }
}

/** 状态标签：Pending 不占位；失败按分类给出具体原因 */
@Composable
private fun AiChatTodoChangeStatusLabel(change: AiTodoChange) {
    if (change.status == AiTodoChangeStatus.Pending) return
    val textRes = when (change.status) {
        AiTodoChangeStatus.Applied -> R.string.ai_todo_change_applied
        AiTodoChangeStatus.Dismissed -> R.string.ai_todo_change_dismissed
        AiTodoChangeStatus.Failed -> when (change.failure) {
            AiTodoChangeFailure.NotFound -> R.string.ai_todo_change_not_found
            AiTodoChangeFailure.InvalidPatch -> R.string.ai_todo_change_invalid
            AiTodoChangeFailure.InvalidDate -> R.string.ai_todo_completion_invalid_date
            AiTodoChangeFailure.WrongState -> R.string.ai_todo_lifecycle_wrong_state
            AiTodoChangeFailure.SaveFailed -> R.string.ai_todo_change_save_failed
            AiTodoChangeFailure.None -> R.string.ai_todo_change_failed
        }
        AiTodoChangeStatus.Pending -> return
    }
    Text(
        text = stringResource(textRes),
        style = ClassppTextTokens.secondaryTextSmall,
        color = if (change.status == AiTodoChangeStatus.Failed) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.classppColors.secondaryText
        },
    )
}

/** 补丁 → 展示行：只出现模型明确给出的字段；清空类字段显示「清除」 */
@Composable
private fun aiTodoChangeLines(patch: AiTodoPatch): List<AiTodoChangeLine> {
    val clear = stringResource(R.string.ai_todo_change_clear)
    val on = stringResource(R.string.ai_todo_change_on)
    val off = stringResource(R.string.ai_todo_change_off)
    val yes = stringResource(R.string.ai_todo_change_yes)
    val no = stringResource(R.string.ai_todo_change_no)
    val updated = stringResource(R.string.ai_todo_change_updated)
    val lines = mutableListOf<AiTodoChangeLine>()

    patch.name?.let {
        lines += AiTodoChangeLine(R.string.ai_todo_change_field_name, it)
    }
    patch.dates?.let { dates ->
        lines += AiTodoChangeLine(
            R.string.ai_todo_change_field_dates,
            if (dates.isEmpty()) clear else dates.joinToString("、"),
        )
    }
    if (patch.startTime != null && patch.endTime != null) {
        lines += AiTodoChangeLine(
            R.string.ai_todo_change_field_time,
            "${patch.startTime}-${patch.endTime}",
        )
    } else {
        patch.allDay?.let { allDay ->
            lines += AiTodoChangeLine(
                R.string.ai_todo_change_field_time,
                if (allDay) stringResource(R.string.ai_todo_change_all_day) else clear,
            )
        }
    }
    if (patch.deadlineDate != null || patch.deadlineTime != null) {
        val cleared = (patch.deadlineDate != null && patch.deadlineDate.isBlank()) ||
            (patch.deadlineTime != null && patch.deadlineTime.isBlank())
        val value = if (cleared) {
            clear
        } else {
            listOfNotNull(patch.deadlineDate, patch.deadlineTime)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }
        lines += AiTodoChangeLine(R.string.ai_todo_change_field_deadline, value)
    }
    patch.deadlineBeforeRepeat?.let {
        lines += AiTodoChangeLine(
            R.string.ai_todo_change_field_deadline_before_repeat,
            if (it) yes else no,
        )
    }
    patch.deadlineBeforeDays?.let {
        lines += AiTodoChangeLine(
            R.string.ai_todo_change_field_deadline_before_days,
            stringResource(R.string.ai_todo_change_days, it),
        )
    }
    patch.urgency?.let { raw ->
        val urgency = TodoUrgency.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
        lines += AiTodoChangeLine(
            R.string.ai_todo_change_field_urgency,
            urgency?.let { stringResource(it.labelRes()) } ?: raw,
        )
    }
    patch.tags?.let { tags ->
        lines += AiTodoChangeLine(
            R.string.ai_todo_change_field_tags,
            if (tags.isEmpty()) clear else tags.joinToString("、"),
        )
    }
    patch.group?.let { group ->
        lines += AiTodoChangeLine(
            R.string.ai_todo_change_field_group,
            group.ifBlank { clear },
        )
    }
    patch.note?.let { note ->
        lines += AiTodoChangeLine(
            R.string.ai_todo_change_field_note,
            if (note.isBlank()) clear else note.take(NotePreviewMaxLength),
        )
    }
    patch.steps?.let { steps ->
        lines += AiTodoChangeLine(
            R.string.ai_todo_change_field_steps,
            if (steps.isEmpty()) clear else steps.joinToString("、"),
        )
    }
    patch.location?.let { location ->
        lines += AiTodoChangeLine(
            R.string.ai_todo_change_field_location,
            location.ifBlank { clear },
        )
    }
    patch.repeat?.let { repeat ->
        val type = RepeatType.entries.firstOrNull {
            it.name.equals(repeat.type.trim(), ignoreCase = true)
        }
        val value = when {
            type == null -> updated
            type == RepeatType.None -> clear
            else -> type.labelText()
        }
        lines += AiTodoChangeLine(R.string.ai_todo_change_field_repeat, value)
    }
    patch.countdown?.let {
        lines += AiTodoChangeLine(
            R.string.ai_todo_change_field_countdown,
            if (it) on else off,
        )
    }
    patch.pinned?.let {
        lines += AiTodoChangeLine(
            R.string.ai_todo_change_field_pinned,
            if (it) yes else no,
        )
    }
    return lines
}

/** 紧急度 → 现有文案资源（与待办页同一份口径） */
private fun TodoUrgency.labelRes(): Int = when (this) {
    TodoUrgency.Critical -> R.string.urgency_critical
    TodoUrgency.High -> R.string.urgency_high
    TodoUrgency.Medium -> R.string.urgency_medium
    TodoUrgency.Low -> R.string.urgency_low
    TodoUrgency.None -> R.string.urgency_none
}

/** 有修改/删除动作被丢弃（目标不存在或补丁非法）时的提示 */
@Composable
internal fun AiChatTodoChangeDroppedHint(modifier: Modifier = Modifier) {
    SheetInfoBanner(
        text = stringResource(R.string.ai_todo_change_dropped),
        modifier = modifier,
        isError = true,
    )
}

/** 动作 JSON 解析/修复失败或空信封却声称已创建：不再静默无动作 */
@Composable
internal fun AiChatTodoParseFailedHint(modifier: Modifier = Modifier) {
    SheetInfoBanner(
        text = stringResource(R.string.ai_todo_parse_failed),
        modifier = modifier,
        isError = true,
    )
}

// ---------- 完成 / 取消完成（直接生效，可撤销） ----------

/**
 * 一条 assistant 回复里 AI 直接执行的完成/取消完成记录：紧凑行 + 撤销按钮。
 *
 * 重复待办的期次日期跟在名称后；失败/撤销失败在行尾按原因着 error 色。
 * [undoing] 为 true 时所有撤销按钮禁用（仓库一次只处理一条撤销）。
 */
@Composable
internal fun AiChatTodoCompletionList(
    completions: List<AiTodoCompletion>,
    undoing: Boolean,
    onUndo: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (completions.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        completions.forEachIndexed { index, completion ->
            AiChatTodoCompletionRow(
                completion = completion,
                undoing = undoing,
                onUndo = { onUndo(index) },
            )
        }
    }
}

@Composable
private fun AiChatTodoCompletionRow(
    completion: AiTodoCompletion,
    undoing: Boolean,
    onUndo: () -> Unit,
) {
    val date = completion.date?.toString()
    val action = when {
        completion.completed && date != null ->
            stringResource(R.string.ai_todo_completion_action_done_on, completion.todoName, date)
        completion.completed ->
            stringResource(R.string.ai_todo_completion_action_done, completion.todoName)
        date != null ->
            stringResource(R.string.ai_todo_completion_action_reopened_on, completion.todoName, date)
        else ->
            stringResource(R.string.ai_todo_completion_action_reopened, completion.todoName)
    }
    val applied = completion.status == AiTodoCompletionStatus.Applied
    val actionColor = when {
        applied && completion.completed -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.classppColors.secondaryText
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = action,
            style = ClassppTextTokens.secondaryTextSmall,
            color = actionColor,
            modifier = Modifier.weight(1f),
        )
        when (completion.status) {
            AiTodoCompletionStatus.Applied -> if (completion.changed) {
                Text(
                    text = stringResource(R.string.ai_todo_completion_undo),
                    style = ClassppTextTokens.sectionAction,
                    color = if (undoing) {
                        MaterialTheme.classppColors.secondaryText
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier
                        .clip(PillShape)
                        .clickable(enabled = !undoing, onClick = onUndo)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            } else {
                Text(
                    text = stringResource(R.string.ai_todo_completion_already),
                    style = ClassppTextTokens.secondaryTextSmall,
                    color = MaterialTheme.classppColors.secondaryText,
                )
            }
            AiTodoCompletionStatus.Undone -> Text(
                text = stringResource(R.string.ai_todo_completion_undone),
                style = ClassppTextTokens.secondaryTextSmall,
                color = MaterialTheme.classppColors.secondaryText,
            )
            AiTodoCompletionStatus.Failed -> Text(
                text = stringResource(todoActionFailureTextRes(completion.failure)),
                style = ClassppTextTokens.secondaryTextSmall,
                color = MaterialTheme.colorScheme.error,
            )
            AiTodoCompletionStatus.UndoFailed -> Text(
                text = stringResource(
                    R.string.ai_todo_completion_undo_failed,
                    stringResource(todoActionFailureTextRes(completion.failure)),
                ),
                style = ClassppTextTokens.secondaryTextSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 完成/撤销失败的分类文案（与修改/删除的失败分类分开，避免语义串台） */
private fun todoActionFailureTextRes(failure: AiTodoChangeFailure): Int = when (failure) {
    AiTodoChangeFailure.NotFound -> R.string.ai_todo_completion_not_found
    AiTodoChangeFailure.InvalidDate -> R.string.ai_todo_completion_invalid_date
    AiTodoChangeFailure.WrongState -> R.string.ai_todo_lifecycle_wrong_state
    AiTodoChangeFailure.SaveFailed -> R.string.ai_todo_completion_save_failed
    AiTodoChangeFailure.InvalidPatch,
    AiTodoChangeFailure.None,
    -> R.string.ai_todo_completion_failed
}

// ---------- 归档 / 取消归档 / 恢复（直接生效，可撤销） ----------

/** 一条 assistant 回复里 AI 直接执行的归档/取消归档/恢复记录：紧凑行 + 撤销按钮 */
@Composable
internal fun AiChatTodoLifecycleList(
    lifecycles: List<AiTodoLifecycle>,
    undoing: Boolean,
    onUndo: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lifecycles.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        lifecycles.forEachIndexed { index, lifecycle ->
            AiChatTodoLifecycleRow(
                lifecycle = lifecycle,
                undoing = undoing,
                onUndo = { onUndo(index) },
            )
        }
    }
}

@Composable
private fun AiChatTodoLifecycleRow(
    lifecycle: AiTodoLifecycle,
    undoing: Boolean,
    onUndo: () -> Unit,
) {
    val action = when (lifecycle.action) {
        AiTodoLifecycleAction.Archive ->
            stringResource(R.string.ai_todo_lifecycle_archive, lifecycle.todoName)
        AiTodoLifecycleAction.Unarchive ->
            stringResource(R.string.ai_todo_lifecycle_unarchive, lifecycle.todoName)
        AiTodoLifecycleAction.Restore ->
            stringResource(R.string.ai_todo_lifecycle_restore, lifecycle.todoName)
    }
    val applied = lifecycle.status == AiTodoLifecycleStatus.Applied
    val actionColor = when {
        applied && lifecycle.action != AiTodoLifecycleAction.Archive ->
            MaterialTheme.colorScheme.primary
        else -> MaterialTheme.classppColors.secondaryText
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = action,
            style = ClassppTextTokens.secondaryTextSmall,
            color = actionColor,
            modifier = Modifier.weight(1f),
        )
        when (lifecycle.status) {
            AiTodoLifecycleStatus.Applied -> if (lifecycle.changed) {
                Text(
                    text = stringResource(R.string.ai_todo_completion_undo),
                    style = ClassppTextTokens.sectionAction,
                    color = if (undoing) {
                        MaterialTheme.classppColors.secondaryText
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier
                        .clip(PillShape)
                        .clickable(enabled = !undoing, onClick = onUndo)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            } else {
                Text(
                    text = stringResource(R.string.ai_todo_completion_already),
                    style = ClassppTextTokens.secondaryTextSmall,
                    color = MaterialTheme.classppColors.secondaryText,
                )
            }
            AiTodoLifecycleStatus.Undone -> Text(
                text = stringResource(R.string.ai_todo_completion_undone),
                style = ClassppTextTokens.secondaryTextSmall,
                color = MaterialTheme.classppColors.secondaryText,
            )
            AiTodoLifecycleStatus.Failed -> Text(
                text = stringResource(todoActionFailureTextRes(lifecycle.failure)),
                style = ClassppTextTokens.secondaryTextSmall,
                color = MaterialTheme.colorScheme.error,
            )
            AiTodoLifecycleStatus.UndoFailed -> Text(
                text = stringResource(
                    R.string.ai_todo_completion_undo_failed,
                    stringResource(todoActionFailureTextRes(lifecycle.failure)),
                ),
                style = ClassppTextTokens.secondaryTextSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ---------- 截断提示 ----------

/**
 * 输出被 token 上限截断时的气泡内提示：尾部消息可原地重试；截断回复里的 JSON 不完整，
 * 不会被解析成待办，重试是恢复路径。
 */
@Composable
private fun AiChatTruncatedHint(onRetry: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.ai_reply_truncated),
            style = ClassppTextTokens.secondaryTextSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) {
            Text(
                text = stringResource(R.string.ai_retry),
                style = ClassppTextTokens.sectionAction,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(PillShape)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

// ---------- 错误条 ----------
/** 分类错误条：文案 + 重试 + 关闭。重试对最后一场失败的对话重新发起（不重复追加用户消息） */
@Composable
internal fun AiChatErrorBanner(
    error: AiError,
    onRetry: () -> Unit,
    onDismiss: (() -> Unit)? = null,
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
        AiError.EmptyResponse -> stringResource(R.string.ai_error_empty_response)
        AiError.InputTooLong -> stringResource(R.string.ai_error_input_too_long)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ChatCardShape)
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
        // 输入过长是确定性错误：重发同一段文本不会有不同结果，不提供重试入口
        if (error != AiError.InputTooLong) {
            Text(
                text = stringResource(R.string.ai_retry),
                style = ClassppTextTokens.sectionAction,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .clip(PillShape)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (onDismiss != null) {
            Icon(
                painter = painterResource(R.drawable.ic_dismiss_circle),
                contentDescription = stringResource(R.string.ai_error_dismiss),
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .size(20.dp)
                    .clip(PillShape)
                    .clickable(onClick = onDismiss),
            )
        }
    }
}

// ---------- 空态 ----------

/** 未配置引导态：说明 + 直达 AI 设置按钮 */
@Composable
internal fun AiChatNotConfigured(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        icon = R.drawable.ic_ai,
        title = stringResource(R.string.ai_not_configured_title),
        description = stringResource(R.string.ai_not_configured_hint),
        modifier = modifier,
    ) {
        EmptyStateActionButton(
            label = stringResource(R.string.ai_go_settings),
            onClick = onOpenSettings,
        )
    }
}

/** 已配置的空会话：与未配置引导态同一套居中排布（标题 + 说明文案） */
@Composable
internal fun AiChatEmptyState(modifier: Modifier = Modifier) {
    EmptyState(
        icon = R.drawable.ic_ai,
        title = stringResource(R.string.ai_empty_title),
        description = stringResource(R.string.ai_empty_hint),
        modifier = modifier,
    )
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
