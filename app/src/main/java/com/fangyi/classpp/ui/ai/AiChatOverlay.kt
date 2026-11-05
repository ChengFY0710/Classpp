package com.fangyi.classpp.ui.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.data.TagRepository
import com.fangyi.classpp.data.TodoOpResult
import com.fangyi.classpp.data.TodoRepository
import com.fangyi.classpp.data.ai.AiChatMessage
import com.fangyi.classpp.data.ai.AiChatRepository
import com.fangyi.classpp.data.ai.AiChatRole
import com.fangyi.classpp.data.ai.AiException
import com.fangyi.classpp.data.model.TagEntry
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoGroup
import com.fangyi.classpp.ui.components.AiChatInputBar
import com.fangyi.classpp.ui.components.LocalOverlayBottomGlassBackdrop
import com.fangyi.classpp.ui.components.LocalOverlayBottomHazeState
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.components.SwipeCoordinator
import com.fangyi.classpp.ui.note.NewTodoSheet
import com.fangyi.classpp.ui.note.TodoDetailSheet
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.classppColors
import kotlinx.coroutines.launch

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
 * - AI 回复里的待办由仓库**直接落库**（无需确认）：本层按消息里的 todo id 从
 *   TodoRepository 现取，用 NoteCard 卡片展示；点卡片拉起 [TodoDetailSheet]（详情里的
 *   备注/步骤/倒计时/归档全部落库），侧滑呼出删除动作卡（满色松手即软删）；
 * - 「编辑」打开预填的 [NewTodoSheet]（existing 整体回填），对话浮层随之 covered 后退，
 *   两层动效由 OverlaySheet 的 covered 机制同帧衔接；
 * - 顶栏（同其它浮层的顶栏规格）：左上蓝「对话」= 新建对话入口的占位（功能尚未实现，
 *   点击暂无动作），居中「AI 助手」标题，右上白「关闭」收起浮层；
 * - AI 设置由设置页的「AI 设置」子页承载（设置 → AI 设置），浮层内不再有 ⚙ 入口：
 *   只有未配置引导卡经 [onOpenSettings] 请求宿主跳转（宿主先收起本浮层，再打开设置页
 *   并直达 AI 子页）。
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
    val saveFailedMessage = stringResource(R.string.todo_error_save_failed)
    val stepDeletedMessage = stringResource(R.string.toast_todo_step_deleted)
    val archivedMessage = stringResource(R.string.toast_todo_archived)

    val messages by aiChatRepository?.messages?.collectAsState()
        ?: remember { mutableStateOf(emptyList<AiChatMessage>()) }
    val sending by aiChatRepository?.sending?.collectAsState() ?: remember { mutableStateOf(false) }
    val lastError by aiChatRepository?.lastError?.collectAsState()
        ?: remember { mutableStateOf<AiException?>(null) }
    val configured by aiChatRepository?.configured?.collectAsState() ?: remember { mutableStateOf(false) }
    val groups by todoRepository?.groups?.collectAsState()
        ?: remember { mutableStateOf(emptyList<TodoGroup>()) }
    val todos by todoRepository?.todos?.collectAsState() ?: remember { mutableStateOf(emptyList<Todo>()) }
    val tags by tagRepository?.tags?.collectAsState()
        ?: remember { mutableStateOf(emptyList<TagEntry>()) }

    // 标签配色表（标签名 → 颜色）：用户标签取 TagEntry.colorArgb，未挑色回落主题 Primary；
    // 与待办页列表卡/详情浮层同一份口径（表里没有的名字由 NoteCard 自己兜底）
    val primaryColor = MaterialTheme.colorScheme.primary
    val tagColors = remember(tags, primaryColor) {
        tags.associate { it.name to (it.colorArgb?.let(::Color) ?: primaryColor) }
    }
    val todosById = remember(todos) { todos.associateBy { it.id } }

    var input by rememberSaveable { mutableStateOf("") }
    // 详情/编辑目标两态口径（同 TodoScreen 的 detail/edit）：id 是期望目标，mounted 是
    // 挂载快照（详情随仓库回流刷新，编辑表单只取一次初值）
    var detailTodoId by remember { mutableStateOf("") }
    var detailMounted by remember { mutableStateOf<Todo?>(null) }
    var editTodoId by remember { mutableStateOf("") }
    var editMounted by remember { mutableStateOf<Todo?>(null) }
    // 卡片侧滑互斥：同一时刻至多一张展开；返回键先收展开卡
    val swipeCoordinator = remember { SwipeCoordinator() }
    val chatScroll = remember { mutableStateOf<androidx.compose.foundation.ScrollState?>(null) }

    // 返回键：编辑器 → 详情 → 展开的侧滑卡 → 收起对话浮层（详情自身的返回拦截注册更晚，
    // 详情在场时由它先处理备注落盘，这里只兜底非详情场景）
    BackHandler(enabled = visible) {
        when {
            editTodoId != "" -> editTodoId = ""
            detailTodoId != "" -> detailTodoId = ""
            swipeCoordinator.activeKey != null -> swipeCoordinator.activeKey = null
            else -> onDismiss()
        }
    }

    // 主浮层由宿主外部收起（跳设置页等）时清掉详情/编辑目标：重开对话不会凭空弹回
    // 上次的浮层（挂载快照由各自 onDismissed 在退场结束后清理）
    LaunchedEffect(visible) {
        if (!visible) {
            detailTodoId = ""
            editTodoId = ""
            swipeCoordinator.activeKey = null
        }
    }

    // 自动滚底：新消息（条数变化）、流式增量（尾部内容/思考长度变化）与待办卡落位都把
    // 滚动列压到底。等下一帧再滚：LaunchedEffect 在布局前执行，此时 maxValue 还是上一帧的
    // 值，最后一块较大的增量会差一截滚不到底。
    LaunchedEffect(
        messages.size,
        messages.lastOrNull()?.content?.length,
        messages.lastOrNull()?.reasoning?.length,
        messages.lastOrNull()?.todoIds?.size,
        visible,
    ) {
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

    // 分组整表写回（编辑器里选分组浮层的「新建」用），与 DayScreen 的 commitGroups 同规格
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

    // ——— 待办动作（与待办页同一套仓库 API） ———

    fun openTodo(todo: Todo) {
        detailMounted = todo
        detailTodoId = todo.id
    }

    fun deleteTodo(todo: Todo) {
        val repository = todoRepository ?: return
        scope.launch {
            if (repository.removeTodo(todo.id) is TodoOpResult.Err) {
                AppToasts.show(context, saveFailedMessage)
            }
        }
    }

    fun toggleCompleted(todo: Todo, completed: Boolean) {
        val repository = todoRepository ?: return
        scope.launch {
            if (repository.setCompleted(todo.id, completed) is TodoOpResult.Err) {
                AppToasts.show(context, saveFailedMessage)
            }
        }
    }

    OverlaySheet(
        title = stringResource(R.string.ai_sheet_title),
        // 左上「对话」= 新建对话入口的占位：功能尚未实现，onConfirm 暂为空
        confirmLabel = stringResource(R.string.ai_sheet_chat),
        confirmIcon = R.drawable.ic_add_circle,
        onConfirm = {},
        // 右上「关闭」：白胶囊深色 ✕，点击即收起浮层（与其它浮层顶栏关闭同一颗胶囊）
        rightAction = SheetTopAction(
            label = stringResource(R.string.ai_sheet_close),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = onDismiss,
        ),
        visible = visible,
        onDismiss = onDismiss,
        onDismissed = onDismissed,
        // 详情/编辑浮层叠上来时本层缩小后退
        covered = detailTodoId != "" || editTodoId != "",
        onScrollStateReady = { chatScroll.value = it },
        bottomScrim = false,
        bottomContent = {
            // 与顶栏同源的玻璃采样层（关/API<31 为 null，输入栏回退实心）与 Haze 采样状态
            //（恒为浮层当前 hazeState，供附件菜单跨 Popup 窗口做磨砂）
            val bottomGlassBackdrop = LocalOverlayBottomGlassBackdrop.current
            val bottomHazeState = LocalOverlayBottomHazeState.current
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
                    value = input,
                    onValueChange = { input = it },
                    placeholder = stringResource(R.string.ai_input_hint),
                    sendingPlaceholder = stringResource(R.string.ai_input_sending_hint),
                    sending = sending,
                    enabled = configured,
                    onSend = { sendMessage(input) },
                    onStop = ::stopSending,
                    onAddImage = {},
                    onAddFile = {},
                    hazeState = bottomHazeState,
                    backdrop = bottomGlassBackdrop,
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
                        AiChatMessageBubble(
                            message = message,
                            // 思考阶段 = 本场流仍在进行、这是尾部消息、正文还没开始：
                            // 折叠块默认展开，正文一到就自动收起
                            thinkingInProgress = sending &&
                                index == messages.lastIndex &&
                                message.role == AiChatRole.Assistant &&
                                message.content.isEmpty(),
                        )
                        if (message.todoSaveFailed) {
                            AiChatTodoSaveFailedHint()
                        }
                        // 已创建待办：按落盘的 id 顺序从活跃待办流反查（被删除/归档的自然消失）
                        if (message.todoIds.isNotEmpty()) {
                            val created = message.todoIds.mapNotNull(todosById::get)
                            AiChatCreatedTodoList(
                                todos = created,
                                tagColors = tagColors,
                                swipeCoordinator = swipeCoordinator,
                                onOpen = ::openTodo,
                                onDelete = ::deleteTodo,
                                onCheckedChange = ::toggleCompleted,
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

    // ——— 待办详情浮层：点击 AI 创建的卡片唤起，接线同待办页 ———
    val detailEntry = todos.firstOrNull { it.id == detailTodoId }
    if (detailEntry != null) detailMounted = detailEntry
    detailMounted?.let { detail ->
        val repository = todoRepository
        key(detail.id) {
            TodoDetailSheet(
                // 主浮层收起时详情一并退场（宿主外部关闭不会留下孤儿浮层）
                visible = visible && detailEntry != null,
                covered = editTodoId != "",
                todo = detail,
                tagColors = tagColors,
                occurrenceDate = null,
                onDismiss = { detailTodoId = "" },
                onDismissed = { detailMounted = null },
                onNoteSave = { note ->
                    val cleaned = note.trim()
                    if (repository != null && cleaned != detail.note) {
                        scope.launch {
                            if (repository.updateTodo(detail.copy(note = cleaned)) is TodoOpResult.Err) {
                                AppToasts.show(context, saveFailedMessage)
                            }
                        }
                    }
                },
                onEdit = { latest ->
                    editMounted = latest
                    editTodoId = latest.id
                },
                onStepAdd = { title ->
                    if (repository != null) {
                        scope.launch {
                            if (repository.addStep(detail.id, title) is TodoOpResult.Err) {
                                AppToasts.show(context, saveFailedMessage)
                            }
                        }
                    }
                },
                onStepToggle = { stepId, done ->
                    if (repository != null) {
                        scope.launch {
                            if (repository.setStepDone(detail.id, stepId, done) is TodoOpResult.Err) {
                                AppToasts.show(context, saveFailedMessage)
                            }
                        }
                    }
                },
                onStepDelete = { stepId ->
                    if (repository != null) {
                        scope.launch {
                            when (repository.removeStep(detail.id, stepId)) {
                                TodoOpResult.Ok -> AppToasts.show(context, stepDeletedMessage)
                                is TodoOpResult.Err -> AppToasts.show(context, saveFailedMessage)
                            }
                        }
                    }
                },
                onOccurrenceCompletedChange = { date, completed ->
                    if (repository != null) {
                        scope.launch {
                            if (repository.setOccurrenceCompleted(detail.id, date, completed) is TodoOpResult.Err) {
                                AppToasts.show(context, saveFailedMessage)
                            }
                        }
                    }
                },
                onCountdownToggle = { enabled ->
                    if (repository != null) {
                        scope.launch {
                            if (repository.setCountdown(detail.id, enabled) is TodoOpResult.Err) {
                                AppToasts.show(context, saveFailedMessage)
                            }
                        }
                    }
                },
                onArchive = {
                    if (repository != null) {
                        scope.launch {
                            when (repository.archiveTodo(detail.id)) {
                                TodoOpResult.Ok -> {
                                    detailTodoId = ""
                                    AppToasts.show(context, archivedMessage)
                                }
                                is TodoOpResult.Err -> AppToasts.show(context, saveFailedMessage)
                            }
                        }
                    }
                },
                // AI 浮层没有日历权限/账户选择编排上下文：菜单项置灰，不提供假入口
                calendarAvailable = false,
                onAddToCalendar = {},
            )
        }
    }

    // ——— 待办编辑浮层：预填的 NewTodoSheet，叠在详情/对话之上 ———
    val editEntry = todos.firstOrNull { it.id == editTodoId }
    editMounted?.let { initial ->
        key(initial.id) {
            NewTodoSheet(
                // 主浮层收起时编辑浮层一并退场（同详情浮层）
                visible = visible && editEntry != null,
                onDismissed = { editMounted = null },
                onDismiss = { editTodoId = "" },
                tagRepository = tagRepository,
                scheduleRepository = scheduleRepository,
                groups = groups,
                todos = todos,
                onGroupsChange = commitGroups,
                existing = initial,
                onConfirm = { todo ->
                    val repository = todoRepository
                    if (repository != null) {
                        scope.launch {
                            when (repository.updateTodo(todo)) {
                                TodoOpResult.Ok -> editTodoId = ""
                                is TodoOpResult.Err -> AppToasts.show(context, saveFailedMessage)
                            }
                        }
                    }
                },
                onDelete = {
                    val repository = todoRepository
                    if (repository != null) {
                        scope.launch {
                            when (repository.removeTodo(initial.id)) {
                                TodoOpResult.Ok -> {
                                    editTodoId = ""
                                    detailTodoId = ""
                                }
                                is TodoOpResult.Err -> AppToasts.show(context, saveFailedMessage)
                            }
                        }
                    }
                },
            )
        }
    }
}
