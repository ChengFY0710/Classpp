package com.fangyi.classpp.ui.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.data.TagRepository
import com.fangyi.classpp.data.TodoOpResult
import com.fangyi.classpp.data.TodoReadResult
import com.fangyi.classpp.data.TodoRepository
import com.fangyi.classpp.data.ai.AiChatRepository
import com.fangyi.classpp.data.ai.AiChatRole
import com.fangyi.classpp.data.ai.AiDraftStatus
import com.fangyi.classpp.data.ai.AiMessageDrafts
import com.fangyi.classpp.data.ai.AiTodoDraftMapping
import com.fangyi.classpp.data.ai.AiTodoMapper
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoGroup
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.note.NewTodoSheet
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import kotlinx.coroutines.launch

/** 草稿编辑的目标：消息下标 + 草稿下标 + 映射出的 Todo（编辑器初值快照） */
private data class AiDraftEditEntry(
    val messageIndex: Int,
    val draftIndex: Int,
    val todo: Todo,
)

/**
 * AI 对话浮层（日程页顶栏右侧按钮唤起，M1 唯一 AI 入口）。
 *
 * 基于 [OverlaySheet]：遮罩/进出场/下拉关闭/键盘让位/顶栏玻璃全部复用组件内置规格；
 * 消息列表走浮层自带滚动列（onScrollStateReady 接管 ScrollState 做自动滚底），
 * 输入栏钉底（bottomScrim = false，渐变与导航栏留白自绘并随键盘上移）。
 *
 * - 发送中输入禁用、发送键变停止键：发送 Job 由 [AiChatRepository] 自己的 scope 驱动，
 *   收起浮层/旋转屏幕不会中断流；停止 = [AiChatRepository.cancelSending]，半截回复由
 *   仓库标记 interrupted 保留（重试走 [AiChatRepository.retryInBackground]）；
 * - 草稿卡「编辑」打开预填的 [NewTodoSheet]（existing 整体回填），对话浮层随之
 *   covered 后退，两层动效由 OverlaySheet 的 covered 机制同帧衔接；
 * - AI 设置由设置页的「AI 设置」子页承载（设置 → AI 设置），本浮层不再内嵌设置页：
 *   右上角 ⚙ 与未配置引导卡都只经 [onOpenSettings] 请求宿主跳转（宿主先收起本浮层，
 *   再打开设置页并直达 AI 子页）。
 *
 * 未配置（[AiChatRepository.configured] = false）时渲染引导态，输入栏禁用。
 */
@Composable
fun AiChatOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    aiChatRepository: AiChatRepository?,
    todoRepository: TodoRepository?,
    tagRepository: TagRepository?,
    scheduleRepository: ScheduleRepository?,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val saveFailedMessage = stringResource(R.string.ai_save_failed)
    val nameRequiredMessage = stringResource(R.string.ai_draft_name_required)

    val messages by aiChatRepository?.messages?.collectAsState()
        ?: remember { mutableStateOf(emptyList<com.fangyi.classpp.data.ai.AiChatMessage>()) }
    val sending by aiChatRepository?.sending?.collectAsState() ?: remember { mutableStateOf(false) }
    val lastError by aiChatRepository?.lastError?.collectAsState()
        ?: remember { mutableStateOf<com.fangyi.classpp.data.ai.AiException?>(null) }
    val configured by aiChatRepository?.configured?.collectAsState() ?: remember { mutableStateOf(false) }
    val groups by todoRepository?.groups?.collectAsState()
        ?: remember { mutableStateOf(emptyList<TodoGroup>()) }
    val todos by todoRepository?.todos?.collectAsState() ?: remember { mutableStateOf(emptyList<Todo>()) }
    val tags by tagRepository?.tags?.collectAsState()
        ?: remember { mutableStateOf(emptyList<com.fangyi.classpp.data.model.TagEntry>()) }

    var input by rememberSaveable { mutableStateOf("") }
    // 草稿编辑两态口径（同 DayScreen 的 todoEdit）：entry 是期望目标，mounted 是挂载快照
    var draftEditEntry by remember { mutableStateOf<AiDraftEditEntry?>(null) }
    var draftEditMounted by remember { mutableStateOf<Todo?>(null) }
    val chatScroll = remember { mutableStateOf<androidx.compose.foundation.ScrollState?>(null) }

    // 返回键：编辑器开着先关编辑器，再轮到收起对话浮层
    BackHandler(enabled = visible) {
        if (draftEditEntry != null) {
            draftEditEntry = null
        } else {
            onDismiss()
        }
    }

    // 自动滚底：新消息（条数变化）与流式增量（尾部内容长度变化）都把滚动列压到底。
    // 等下一帧再滚：LaunchedEffect 在布局前执行，此时 maxValue 还是上一帧的值，
    // 最后一块较大的增量会差一截滚不到底。
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length, visible) {
        if (!visible) return@LaunchedEffect
        withFrameNanos { }
        chatScroll.value?.let { it.scrollTo(it.maxValue) }
    }

    // ——— 动作集合 ———

    fun sendMessage(text: String) {
        val repository = aiChatRepository ?: return
        if (text.isBlank() || sending) return
        input = ""
        keyboard?.hide()
        // Job 归仓库所有：收起浮层/旋转不中断；停止走 cancelSending
        repository.sendInBackground(text)
    }

    fun stopSending() {
        aiChatRepository?.cancelSending()
    }

    fun retryLast() {
        aiChatRepository?.retryInBackground()
    }

    // 分组整表写回（草稿编辑器里选分组浮层的「新建」用），与 DayScreen 的 commitGroups 同规格
    val commitGroups: (List<TodoGroup>, Map<String, String>) -> Unit = { newGroups, renames ->
        val repository = todoRepository
        if (repository != null) {
            scope.launch {
                if (repository.setGroups(newGroups, renames) is TodoOpResult.Err) {
                    AppToasts.show(context, saveFailedMessage)
                }
            }
        }
    }

    fun saveSelected(
        messageIndex: Int,
        selected: Set<Int>,
        mappings: List<AiTodoDraftMapping>,
    ) {
        val repository = todoRepository ?: return
        val chatRepository = aiChatRepository ?: return
        if (selected.isEmpty()) {
            // 默认勾选已排除缺名称行：整卡都不可保存时按钮不应像失灵，给出明确原因
            if (mappings.any { !it.canSave }) AppToasts.show(context, nameRequiredMessage)
            return
        }
        scope.launch {
            // 缺名称的草稿会让 addTodos 全量校验整体失败（零落盘），先摘出去
            val savable = selected.filter { index -> mappings.getOrNull(index)?.canSave == true }
            if (savable.isEmpty()) {
                AppToasts.show(context, nameRequiredMessage)
                return@launch
            }
            val todosToSave = savable.mapNotNull { index -> mappings.getOrNull(index)?.todo }
            when (val result = repository.addTodos(todosToSave)) {
                is TodoReadResult.Ok -> {
                    // 批量回写：一次内存更新 + 一次落盘（N 条不再重写 N 次整个文件）
                    chatRepository.updateDraftStatuses(
                        messageIndex,
                        savable.associateWith { AiDraftStatus.Saved },
                    )
                }
                is TodoReadResult.Err -> AppToasts.show(context, saveFailedMessage)
            }
        }
    }

    OverlaySheet(
        title = stringResource(R.string.ai_sheet_title),
        // 无确认语义：左胶囊不渲染，右上角是 AI 设置入口
        confirmLabel = null,
        rightAction = SheetTopAction(
            label = stringResource(R.string.cd_ai_settings),
            icon = R.drawable.ic_settings,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            contentColorGlass = MaterialTheme.classppColors.onPrimaryGlass,
            onClick = onOpenSettings,
        ),
        visible = visible,
        onDismiss = onDismiss,
        onDismissed = onDismissed,
        covered = draftEditEntry != null,
        onScrollStateReady = { chatScroll.value = it },
        bottomScrim = false,
        bottomContent = {
            // 钉底输入栏：自绘渐变兜底 + 导航栏留白 + 键盘上移（insets 消费顺序：
            // navigationBarsPadding 先吃掉导航栏，imePadding 补键盘高出导航栏的部分）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                            ),
                        ),
                    )
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(top = 12.dp, bottom = 24.dp)
                    .padding(horizontal = PageHorizontalSpacing),
            ) {
                AiChatInputBar(
                    text = input,
                    onTextChange = { input = it },
                    enabled = configured,
                    sending = sending,
                    onSend = { sendMessage(input) },
                    onStop = ::stopSending,
                )
            }
        },
    ) {
        when {
            !configured -> {
                AiChatNotConfigured(onOpenSettings = onOpenSettings)
            }
            messages.isEmpty() -> {
                AiChatEmptyState(
                    examples = listOf(
                        stringResource(R.string.ai_example_1),
                        stringResource(R.string.ai_example_2),
                    ),
                    onExample = { sendMessage(it) },
                )
            }
            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    messages.forEachIndexed { index, message ->
                        AiChatMessageBubble(message = message)
                        message.drafts?.let { draftsState ->
                            val knownGroups = groups.map { it.name }
                            val knownTags = tags.map { it.name }
                            val mappings = remember(index, draftsState, knownGroups, knownTags) {
                                AiTodoMapper.map(draftsState.drafts, knownGroups, knownTags)
                            }
                            // 勾选集：Pending 且可直接保存（缺名称会拖垮整批 addTodos）的行全选。
                            // 只在首次组合初始化；状态回写后仅剔除已处置行——否则用户手工取消
                            // 的勾选会被「全部 Pending 重选」覆盖。
                            var selected by remember(index) {
                                mutableStateOf(
                                    draftsState.statuses
                                        .withIndex()
                                        .filter { it.value == AiDraftStatus.Pending }
                                        .filter { mappings.getOrNull(it.index)?.canSave == true }
                                        .map { it.index }
                                        .toSet(),
                                )
                            }
                            LaunchedEffect(draftsState) {
                                selected = selected.filter { draftIndex ->
                                    draftsState.statuses.getOrElse(draftIndex) {
                                        AiDraftStatus.Pending
                                    } == AiDraftStatus.Pending
                                }.toSet()
                            }
                            Spacer(Modifier.padding(2.dp))
                            AiChatDraftCard(
                                drafts = draftsState,
                                mappings = mappings,
                                selected = selected,
                                onToggle = { toggleIndex ->
                                    selected = if (toggleIndex in selected) {
                                        selected - toggleIndex
                                    } else {
                                        selected + toggleIndex
                                    }
                                },
                                onEdit = { editIndex ->
                                    mappings.getOrNull(editIndex)?.let { mapping ->
                                        draftEditEntry = AiDraftEditEntry(index, editIndex, mapping.todo)
                                        draftEditMounted = mapping.todo
                                    }
                                },
                                onSaveSelected = {
                                    saveSelected(index, selected, mappings)
                                },
                                onDismissDraft = { dismissIndex ->
                                    scope.launch {
                                        aiChatRepository?.updateDraftStatus(
                                            index,
                                            dismissIndex,
                                            AiDraftStatus.Dismissed,
                                        )
                                    }
                                },
                            )
                        }
                        if (message.interrupted && message.role == AiChatRole.Assistant) {
                            Text(
                                text = stringResource(R.string.ai_interrupted),
                                style = ClassppTextTokens.secondaryTextSmall,
                                color = MaterialTheme.classppColors.secondaryText,
                            )
                        }
                    }
                    lastError?.let { error ->
                        AiChatErrorBanner(
                            error = error.error,
                            onRetry = ::retryLast,
                            onDismiss = { aiChatRepository?.dismissError() },
                        )
                    }
                }
            }
        }
    }

    // ——— 草稿编辑器：预填的新建待办浮层，叠在对话浮层之上 ———
    draftEditMounted?.let { initial ->
        NewTodoSheet(
            visible = draftEditEntry != null,
            onDismissed = { draftEditMounted = null },
            onDismiss = { draftEditEntry = null },
            tagRepository = tagRepository,
            scheduleRepository = scheduleRepository,
            groups = groups,
            todos = todos,
            onGroupsChange = commitGroups,
            existing = initial,
            onConfirm = { todo ->
                val repository = todoRepository
                val chatRepository = aiChatRepository
                val entry = draftEditEntry
                if (repository != null && chatRepository != null) {
                    scope.launch {
                        when (val result = repository.addTodo(todo)) {
                            is TodoReadResult.Ok -> {
                                entry?.let { e ->
                                    chatRepository.updateDraftStatus(e.messageIndex, e.draftIndex, AiDraftStatus.Saved)
                                }
                                draftEditEntry = null
                            }
                            is TodoReadResult.Err -> AppToasts.show(context, saveFailedMessage)
                        }
                    }
                }
            },
            onDelete = null,
        )
    }
}
