package com.fangyi.classpp.ui.ai

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.data.TagRepository
import com.fangyi.classpp.data.TodoOpResult
import com.fangyi.classpp.data.TodoRepository
import com.fangyi.classpp.data.ai.AiChatMessage
import com.fangyi.classpp.data.ai.AiChatRepository
import com.fangyi.classpp.data.ai.AiChatRole
import com.fangyi.classpp.data.ai.AiConversation
import com.fangyi.classpp.data.ai.AiException
import com.fangyi.classpp.data.model.TagEntry
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoGroup
import com.fangyi.classpp.ui.components.AiChatInputBar
import com.fangyi.classpp.ui.components.LocalOverlayBottomHazeState
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.components.SwipeCoordinator
import com.fangyi.classpp.ui.components.rememberHazeRetreat
import com.fangyi.classpp.ui.note.NewTodoSheet
import com.fangyi.classpp.ui.note.TodoDetailSheet
import com.fangyi.classpp.ui.note.rememberAddToAlarm
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.isGlassStyleEnabled
import com.fangyi.classpp.ui.theme.rememberGlassBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 光场在输入栏上方额外留出的可见高度：与输入栏、导航栏一起构成约 240dp 的光场带。
 * 空闲时光场完全隐藏，但这条带子（含内容末尾的同高留白）高度恒定——若随光场出现
 * 收放，思考开始 / 结束时消息列表会整体跳动。
 */
private val AuroraHeadroom = 120.dp

/** 一条消息最多携带的图片数：视觉模型识别足够、单条请求体积可控 */
private const val MaxImagesPerMessage = 4

/** 拍照临时文件目录名（cacheDir 下；FileProvider 只对这一段授权给系统相机写入） */
private const val CaptureDirName = "ai_capture"

/**
 * AI 对话浮层（日程页顶栏右侧按钮唤起，M1 唯一 AI 入口）。
 *
 * 基于 [OverlaySheet]：遮罩/进出场/下拉关闭/键盘让位/顶栏磨砂全部复用组件内置规格
 * （消息列即采样源；「切换对话内容塌缩 / 流式逐字重写」曾触发 Adreno Vulkan 驱动崩溃
 * 而整链退让，根因是 HazeSourceSelection.All 无视 z 序采样，改 Behind（f958dbb）后
 * 重新挂回，真机验证不再崩溃）；
 * 消息列表走浮层自带滚动列（onScrollStateReady 接管 ScrollState 做自动滚底），
 * 输入栏钉底（bottomScrim = false，底部光场/渐隐/导航栏留白自绘并随键盘上移）——
 * 光场由 [AiThinkingAurora] 绘制，只在 AI 思考 / 回答（sending）期间自输入栏后方上浮
 * 出现并缓慢游动，结束即下沉淡出、空闲完全不绘制；输入栏玻璃采样
 * 光场层（见底部槽的 layerBackdrop），玻璃关闭/API<31 时回退实心底。
 *
 * - 发送中输入禁用、发送键变停止键：发送 Job 由 [AiChatRepository] 自己的 scope 驱动，
 *   收起浮层/旋转屏幕不会中断流；停止 = [AiChatRepository.cancelSending]，半截回复由
 *   仓库标记 interrupted 保留（重试走 [AiChatRepository.retryInBackground]）；
 * - AI 回复里的待办由仓库**直接落库**（无需确认）：本层按消息里的 todo id 从
 *   TodoRepository 现取，用 NoteCard 卡片展示；点卡片拉起 [TodoDetailSheet]（详情里的
 *   备注/步骤/倒计时/归档全部落库），侧滑呼出删除动作卡（满色松手即软删）；
 * - 「编辑」打开预填的 [NewTodoSheet]（existing 整体回填），对话浮层随之 covered 后退，
 *   两层动效由 OverlaySheet 的 covered 机制同帧衔接；
 * - 顶栏（同其它浮层的顶栏规格）：左上蓝「对话」= 打开「所有对话」管理浮层
 *   （[AiConversationsSheet]，叠在本浮层之上、covered 后退分层动效）：新建 / 切换 /
 *   删除对话，新建/切换即时落库（管理浮层同步收起，退场动画照播——播完时内容已切
 *   到位、高亮已移到新对话）；没有对话时按钮保持可点但点击无反应（列表空无从管理，发消息会隐式
 *   建对话），不显示居中标题，右上白「关闭」收起浮层；
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
    // 「添加到闹钟」（待办详情菜单项）：目标时刻映射与结果 Toast 收口在 AddToAlarmFlow；
    // 与日历不同，闹钟不需要权限/账户编排上下文，AI 浮层同样可用
    val addToAlarm = rememberAddToAlarm()

    val messages by aiChatRepository?.messages?.collectAsState()
        ?: remember { mutableStateOf(emptyList<AiChatMessage>()) }
    val sending by aiChatRepository?.sending?.collectAsState() ?: remember { mutableStateOf(false) }
    val lastError by aiChatRepository?.lastError?.collectAsState()
        ?: remember { mutableStateOf<AiException?>(null) }
    val configured by aiChatRepository?.configured?.collectAsState() ?: remember { mutableStateOf(false) }
    val conversations by aiChatRepository?.conversations?.collectAsState()
        ?: remember { mutableStateOf(emptyList<AiConversation>()) }
    val activeConversationId by aiChatRepository?.activeConversationId?.collectAsState()
        ?: remember { mutableStateOf<String?>(null) }
    // 正在应用确认卡的消息 id：null = 空闲。只影响对应消息的按钮态，不阻塞输入
    val changesApplyingMessageId by aiChatRepository?.todoChangesApplyingMessageId?.collectAsState()
        ?: remember { mutableStateOf<String?>(null) }
    // 正在撤销完成记录的消息 id：同上，只禁用撤销按钮
    val completionUndoingMessageId by aiChatRepository?.todoCompletionUndoingMessageId?.collectAsState()
        ?: remember { mutableStateOf<String?>(null) }
    val groups by todoRepository?.groups?.collectAsState()
        ?: remember { mutableStateOf(emptyList<TodoGroup>()) }
    val todos by todoRepository?.todos?.collectAsState() ?: remember { mutableStateOf(emptyList<Todo>()) }
    val tags by tagRepository?.tags?.collectAsState()
        ?: remember { mutableStateOf(emptyList<TagEntry>()) }

    // 底部光场的玻璃采样层：光场节点挂 layerBackdrop，输入栏在该节点之外采样，
    // 不会出现读写同一层的反馈环。玻璃关闭 / API<31 时为 null，输入栏回退现有实心底。
    val glassEnabled = isGlassStyleEnabled()
    val auroraBackdrop = rememberGlassBackdrop(MaterialTheme.colorScheme.background)
        .takeIf { glassEnabled }
    val density = LocalDensity.current
    // 键盘高出导航栏的预留：光场把可见底边锚在键盘上沿，渐隐带不被 IME 预留撑长
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val navBottomPx = WindowInsets.navigationBars.getBottom(density)
    val imeReservePx = (imeBottomPx - navBottomPx).coerceAtLeast(0)

    // 标签配色表（标签名 → 颜色）：用户标签取 TagEntry.colorArgb，未挑色回落主题 Primary；
    // 与待办页列表卡/详情浮层同一份口径（表里没有的名字由 NoteCard 自己兜底）
    val primaryColor = MaterialTheme.colorScheme.primary
    val tagColors = remember(tags, primaryColor) {
        tags.associate { it.name to (it.colorArgb?.let(::Color) ?: primaryColor) }
    }
    val todosById = remember(todos) { todos.associateBy { it.id } }

    var input by rememberSaveable { mutableStateOf("") }
    // 「所有对话」管理浮层两态口径（同 ScheduleScreen 的 switcherVisible/mounted）：
    // visible 请求进出场，mounted 是挂载快照（出场动画播完才卸载）
    var conversationsVisible by remember { mutableStateOf(false) }
    var conversationsMounted by remember { mutableStateOf(false) }
    // 切换对话期间顶栏 Haze 的退让开关（见 applyConversationChange 的崩溃史注释）：
    // true 时整条 Haze 链卸载、顶栏走静态底材；落库并绘制完成后再挂回
    var conversationHazeSuspended by remember { mutableStateOf(false) }
    // 在途的对话切换协程（单飞闸门）：退场途中连点两张卡只认第一张，避免两场协程
    // 交错把 Haze 提前挂回源子树重写窗口
    var conversationChangeJob by remember { mutableStateOf<Job?>(null) }
    // 删除对话的落库回流窗口：删除是唯一留在管理浮层内落库的对话操作——列表行消失
    // （删光为 N→0）与活动对话被删时的消息列整块换写都发生在窗口内，两层顶栏磨砂
    // 一起退让（崩溃史同 applyConversationChange / tombstone_05；机制见 HazeRetreatState）
    val deleteHazeRetreat = rememberHazeRetreat()
    // 详情/编辑目标两态口径（同 TodoScreen 的 detail/edit）：id 是期望目标，mounted 是
    // 挂载快照（详情随仓库回流刷新，编辑表单只取一次初值）
    var detailTodoId by remember { mutableStateOf("") }
    var detailMounted by remember { mutableStateOf<Todo?>(null) }
    var editTodoId by remember { mutableStateOf("") }
    var editMounted by remember { mutableStateOf<Todo?>(null) }
    // 卡片侧滑互斥：同一时刻至多一张展开；返回键先收展开卡
    val swipeCoordinator = remember { SwipeCoordinator() }
    val chatScroll = remember { mutableStateOf<androidx.compose.foundation.ScrollState?>(null) }

    // ——— 图片附件：相册选图 / 拍照（Photo Picker 无需权限；TakePicture 唤系统相机） ———

    val imageStore = aiChatRepository?.imageStore
    // 待发送图片附件（已导入的压缩副本文件名）：发送时随消息入流；浮层退场仍未发送则删副本
    var pendingImageNames by rememberSaveable { mutableStateOf(listOf<String>()) }
    // 拍照临时文件（TakePicture 的输出目标）：结果回来后导入压缩，无论成败即删
    var captureFile by remember { mutableStateOf<File?>(null) }
    // 全屏预览的已发送图片文件名（气泡缩略图点开）；null = 无预览
    var previewImageName by remember { mutableStateOf<String?>(null) }

    /** 相册多选导入：Photo Picker 的 URI 是临时授权，先导入压缩副本（IO），超额度截断 */
    fun importImages(uris: List<Uri>) {
        val store = imageStore ?: return
        val remaining = MaxImagesPerMessage - pendingImageNames.size
        if (remaining <= 0 || uris.isEmpty()) return
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                uris.take(remaining).mapNotNull { store.import(context, it) }
            }
            if (imported.isNotEmpty()) {
                pendingImageNames = (pendingImageNames + imported).take(MaxImagesPerMessage)
            }
        }
    }

    /** 拍照结果导入：临时文件先压缩进副本库，导入完成即删（成败都删） */
    fun importCapturedImage(temp: File) {
        val store = imageStore ?: run {
            temp.delete()
            return
        }
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                store.import(context, Uri.fromFile(temp)).also { temp.delete() }
            }
            if (imported != null) {
                if (pendingImageNames.size < MaxImagesPerMessage) {
                    pendingImageNames = (pendingImageNames + imported).take(MaxImagesPerMessage)
                } else {
                    // 额度满（理论上入口已拦住）：不留孤儿副本
                    withContext(Dispatchers.IO) { store.delete(imported) }
                }
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MaxImagesPerMessage),
    ) { uris -> importImages(uris) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { captured ->
        val temp = captureFile
        captureFile = null
        if (temp != null) {
            if (captured) importCapturedImage(temp) else temp.delete()
        }
    }

    /** 相册选图入口：额度满不再拉起选择器 */
    fun launchGallery() {
        if (imageStore == null) return
        if (pendingImageNames.size >= MaxImagesPerMessage) return
        galleryLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    /** 拍照入口：预建 cacheDir 临时文件，经 FileProvider 授权给系统相机写入 */
    fun startCapture() {
        if (imageStore == null) return
        if (pendingImageNames.size >= MaxImagesPerMessage) return
        val dir = File(context.cacheDir, CaptureDirName).apply { mkdirs() }
        // 上一次拍照没带回结果（拍照中途收浮层，结果回调不再执行）的残留临时文件顺手清掉
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        captureFile = file
        cameraLauncher.launch(
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file),
        )
    }

    /** 移除一张待发送图片：引用与副本文件一起删（还没随消息落库，没有别的持有方） */
    fun removePendingImage(file: File) {
        val store = imageStore ?: return
        pendingImageNames = pendingImageNames - file.name
        scope.launch(Dispatchers.IO) { store.delete(file.name) }
    }

    /** 浮层退场收尾：未发送的待发送图片没有持有方了，副本一并删除 */
    fun clearPendingImages() {
        val stale = pendingImageNames
        if (stale.isEmpty()) return
        pendingImageNames = emptyList()
        val store = imageStore ?: return
        scope.launch(Dispatchers.IO) {
            stale.forEach { store.delete(it) }
        }
    }

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

    // 主浮层由宿主外部收起（跳设置页等）时清掉管理浮层与详情/编辑目标：重开对话不会
    // 凭空弹回上次的浮层（挂载快照由各自 onDismissed 在退场结束后清理）
    LaunchedEffect(visible) {
        if (!visible) {
            conversationsVisible = false
            detailTodoId = ""
            editTodoId = ""
            swipeCoordinator.activeKey = null
        }
    }

    // 删光对话时自动收起管理浮层：列表空了无从管理，回到无对话态（入口点击不再有反应）。
    // key 取「是否为空」而非列表本身：流式增量逐字刷新 conversations，不该反复重启本协程
    LaunchedEffect(conversations.isEmpty()) {
        if (conversations.isEmpty()) conversationsVisible = false
    }

    // 删除对话的落库回流落定：重写帧仍在 deleteHazeRetreat 的退让窗口内，此处只推迟挂回
    // （key 必须取列表本身：删除非活动对话时 isEmpty 不变，只有列表内容回流可作落定信号）
    LaunchedEffect(conversations) {
        deleteHazeRetreat.onReflow()
    }

    // 离散事件滚底：新消息入列 / 待办卡落位 / 切换对话 / 浮层可见——等下一帧布局完成后滚一次。
    LaunchedEffect(
        messages.size,
        messages.lastOrNull()?.todoIds?.size,
        messages.lastOrNull()?.todoChanges?.size,
        messages.lastOrNull()?.todoCompletions?.size,
        activeConversationId,
        visible,
    ) {
        if (!visible) return@LaunchedEffect
        withFrameNanos { }
        chatScroll.value?.let { it.scrollTo(it.maxValue) }
    }
    // 流式跟随：在消息列高度变化的同一帧布局内直接修正滚动位置（见消息列 onSizeChanged）。
    // 不用 snapshotFlow / LaunchedEffect：它们在布局结束后才回调，scrollTo 要到下一帧才生效，
    // 思考内容（同步 Text）每 chunk 高度立变而滚动晚一帧，内容先沉后拉回，逐 chunk 抖动。

    // ——— 动作集合 ———

    fun sendMessage(text: String) {
        val repository = aiChatRepository ?: return
        if (sending) return
        if (text.isBlank() && pendingImageNames.isEmpty()) return
        val images = pendingImageNames
        input = ""
        // 图片随消息发出：文件名已由仓库随 user 消息持有，预览条清空但**不删副本**
        pendingImageNames = emptyList()
        keyboard?.hide()
        // Job 归仓库所有：收起浮层/旋转不中断；停止走 cancelSending
        repository.sendInBackground(text, images)
    }

    fun stopSending() {
        aiChatRepository?.cancelSending()
    }

    fun retryLast() {
        aiChatRepository?.retryInBackground()
    }

    // ——— 对话管理（「所有对话」浮层的回调，执行在仓库、浮层随回流关闭） ———

    /** 打开管理浮层：没有对话时不响应（按钮可点但无反应，见顶栏 KDoc） */
    fun openConversations() {
        if (conversations.isEmpty()) return
        keyboard?.hide()
        conversationsVisible = true
    }

    /**
     * 对话切换的落库收口：**即时落仓库**（管理浮层同步收起、退场动画照播，切换不再
     * 等退场播完）。Haze 退让窗口收窄为「源子树重写的前后各两帧」：
     *
     * 崩溃史（tombstone_05，2026-11-07 03:39）：消息列是 Haze 采样源，切换对话时内容
     * 从长列表塌缩成空态——源子树在同一帧被整块重写，Haze 2 的 source fallback 会构建
     * 自引用 RenderNode，RenderThread 在 prepareTreeImpl 无限递归、栈溢出（tombstone 的
     * 512 帧全是 libhwui 同一段递归）。消费方（顶栏磨砂——「浮层顶栏模糊」开关开启时）
     * 与消息列没有祖先 hazeSource，Behind 采样修复覆盖不到这条路径，故在此显式退让：
     * 落库前先等两帧，让整条 Haze 链（hazeEnabled=false：内容列不挂采样源、钉底槽不下发
     * 状态；顶栏磨砂随之退回渐变遮罩）卸载并完整绘制一帧，源子树重写才落在零
     * Haze 节点的帧上；落库后再等两帧——重组
     * 与绘制都走完——才挂回。稳态磨砂与其余浮层不受影响。
     *
     * 单飞：上一场切换在途时忽略再次点选（浮层已在退场，连点只认第一张），避免两场
     * 协程交错在前一场挂回 Haze 时后一场还在重写源子树。
     */
    fun applyConversationChange(block: suspend () -> Unit) {
        if (conversationChangeJob?.isActive == true) return
        conversationHazeSuspended = true
        conversationsVisible = false
        conversationChangeJob = scope.launch {
            try {
                // 前两帧：Haze 链卸载并完整绘制一帧，源子树重写与卸载不同帧
                withFrameNanos { }
                withFrameNanos { }
                block()
                // 崩溃发生在 RenderThread 准备新内容那一帧渲染树的阶段：源节点要在
                // 落库后的重组与绘制都走完之后才允许挂回
                withFrameNanos { }
                withFrameNanos { }
            } finally {
                conversationHazeSuspended = false
            }
        }
    }

    /**
     * 新建对话：即时落仓库（管理浮层同步收起、退场动画照播）——新对话为空态，
     * 发首条消息时由仓库自动命名
     */
    fun createConversation() {
        val repository = aiChatRepository ?: return
        applyConversationChange { repository.createConversation() }
    }

    /** 切换对话：即时落仓库（管理浮层同步收起），退场期间高亮已切到新对话 */
    fun switchConversation(id: String) {
        val repository = aiChatRepository ?: return
        applyConversationChange { repository.switchConversation(id) }
    }

    /**
     * 删除对话：留在管理浮层，列表随仓库回流更新（删光由 LaunchedEffect 收起浮层）。
     * 删除活动对话时消息列也会整块换写（tombstone_05 同型源重写），落库窗口内两层顶栏
     * Haze 退让（机制见 HazeRetreatState），回流落定后再等两帧自动挂回。
     */
    fun deleteConversation(id: String) {
        val repository = aiChatRepository ?: return
        deleteHazeRetreat.forCommit {
            scope.launch {
                repository.deleteConversation(id)
            }
        }
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
        // 标题置空：对话浮层不显示顶栏标题，只保留左右两颗胶囊
        title = "",
        // 左上「对话」= 打开「所有对话」管理浮层；没有对话时按钮保持可点、点击无反应
        confirmLabel = stringResource(R.string.ai_sheet_chat),
        confirmIcon = R.drawable.ic_add_circle,
        onConfirm = ::openConversations,
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
        onDismissed = {
            // 退场播完才清：未发送的待发送图片副本删掉，不留孤儿文件
            clearPendingImages()
            onDismissed()
        },
        // 管理浮层/详情/编辑浮层叠上来时本层缩小后退
        covered = conversationsVisible || detailTodoId != "" || editTodoId != "",
        onScrollStateReady = { chatScroll.value = it },
        bottomScrim = false,
        // 切换 / 删除对话的落库与绘制窗口内整条 Haze 链退让（崩溃史见 applyConversationChange
        // 与 deleteConversation）；平时保持挂载，稳态磨砂不变
        hazeEnabled = !conversationHazeSuspended && !deleteHazeRetreat.suspended,
        // 崩溃与修复史（ec9fa71 退让 → f958dbb 改 Behind → 本次再复现）：消息列是采样源，
        // 切换对话时内容从长列表塌缩成空态、流式期间逐字重写——「内容结构频繁重写采样源」
        // 曾触发 RenderThread SIGSEGV（切换到空对话必崩）。All→Behind 修复了效果位于源子树
        // 内的路径，但消费方（顶栏磨砂、跨 Popup 窗口的钉底槽弹出菜单）与消息列没有祖先
        // hazeSource，切换对话那一帧源子树整块重写仍可能构建自引用 RenderNode
        // （tombstone_05，2026-11-07 03:39，prepareTreeImpl 512 帧递归栈溢出）。折中方案：
        // 切换落库/绘制窗口内由 conversationHazeSuspended 让整条链退让（零采样/效果节点），
        // 落定后挂回——稳态磨砂保留；退让期间顶栏磨砂退回渐变遮罩。
        // 光场与输入栏玻璃走 kyant backdrop（独立体系），不受影响。
        bottomContent = {
            // 与顶栏同源的 Haze 采样状态（供附件菜单跨 Popup 窗口做磨砂）
            val bottomHazeState = LocalOverlayBottomHazeState.current
            // 钉底光场 + 输入栏：光场画在 scrim 之上、输入栏之下；输入栏玻璃采样光场层。
            // inset 消费顺序：navigationBarsPadding 先吃掉导航栏，imePadding 补键盘高出
            // 导航栏的部分。光场自身不参与 inset，随整个槽一起被键盘顶起。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 消息从光场下方淡出：底段到不透明，保证文字不透过彩色光场
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                                MaterialTheme.colorScheme.background,
                            ),
                        ),
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .then(
                            if (auroraBackdrop != null) {
                                Modifier.layerBackdrop(auroraBackdrop)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    AiThinkingAurora(
                        // 只在思考 / 回答期间出现：进场自输入栏后方向上浮入，结束后下沉淡出
                        // 并停掉帧循环；收起浮层同样触发退场
                        active = visible && sending,
                        imeReservePx = imeReservePx,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(top = 12.dp, bottom = 24.dp)
                        .padding(horizontal = PageHorizontalSpacing),
                ) {
                    // 光场在输入栏上方再留出一段可见高度，整体接近设计稿的底部光场带
                    Spacer(Modifier.height(AuroraHeadroom))
                    AiChatInputBar(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = stringResource(R.string.ai_input_hint),
                        sendingPlaceholder = stringResource(R.string.ai_input_sending_hint),
                        sending = sending,
                        enabled = configured,
                        onSend = { sendMessage(input) },
                        onStop = ::stopSending,
                        onTakePhoto = ::startCapture,
                        onAddImage = ::launchGallery,
                        onAddFile = {},
                        pendingImages = imageStore
                            ?.let { store -> pendingImageNames.map(store::file) }
                            ?: emptyList(),
                        onRemoveImage = ::removePendingImage,
                        hazeState = bottomHazeState,
                        backdrop = auroraBackdrop,
                    )
                }
            }
        },
    ) {
        when {
            !configured -> {
                AiChatNotConfigured(onOpenSettings = onOpenSettings)
            }
            messages.isEmpty() -> {
                AiChatEmptyState()
            }
            else -> {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    // 流式滚底：高度变化的同一帧布局内直接钉底，不走帧后回调（消除一帧延迟抖动）
                    modifier = Modifier.onSizeChanged {
                        if (sending) chatScroll.value?.let { s ->
                            val delta = (s.maxValue - s.value).toFloat()
                            if (delta != 0f) s.dispatchRawDelta(delta)
                        }
                    },
                ) {
                    messages.forEachIndexed { index, message ->
                        AiChatMessageBubble(
                            message = message,
                            imageStore = imageStore,
                            onImageClick = { name -> previewImageName = name },
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
                        // 已完成/取消完成：直接生效，行尾可撤销（先于待确认项展示）
                        if (message.todoCompletions.isNotEmpty()) {
                            AiChatTodoCompletionList(
                                completions = message.todoCompletions,
                                undoing = completionUndoingMessageId == message.id,
                                onUndo = { index ->
                                    aiChatRepository?.undoTodoCompletionInBackground(message.id, index)
                                },
                            )
                        }
                        // 待确认的修改/删除：确认前不写库；目标已消失时卡片仍按名称快照展示
                        if (message.todoChanges.isNotEmpty()) {
                            AiChatTodoChangeCard(
                                changes = message.todoChanges,
                                todosById = todosById,
                                applying = changesApplyingMessageId == message.id,
                                onApply = {
                                    aiChatRepository?.applyTodoChangesInBackground(message.id)
                                },
                                onDismiss = {
                                    aiChatRepository?.dismissTodoChanges(message.id)
                                },
                            )
                        }
                        if (message.todoChangeDropped) {
                            AiChatTodoChangeDroppedHint()
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
                        )
                    }
                }
            }
        }
        // 底部光场带比原输入栏高出一截：滚动内容末尾补同样高度，
        // 自动滚底时最后一条消息停在光场上方，不被 scrim 盖住
        Spacer(Modifier.height(AuroraHeadroom))
    }

    // ——— 已发送图片的全屏预览：气泡缩略图点开，点任意处关闭 ———
    previewImageName?.takeIf { imageStore != null }?.let { name ->
        Dialog(
            onDismissRequest = { previewImageName = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { previewImageName = null },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = imageStore?.file(name),
                    contentDescription = stringResource(R.string.cd_ai_image_preview),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // ——— 「所有对话」管理浮层：左上「对话」胶囊唤起，交互同切换课表浮层 ———
    // 挂载在主浮层之后：画到其上、BackHandler 后注册先收到（返回键先收管理层）。
    // visible 叠加宿主 visible：主浮层收起时管理浮层一并退场，不留孤儿浮层
    if (conversationsVisible) conversationsMounted = true
    if (conversationsMounted) {
        AiConversationsSheet(
            visible = visible && conversationsVisible,
            onDismissed = { conversationsMounted = false },
            conversations = conversations,
            activeConversationId = activeConversationId,
            onCreate = ::createConversation,
            onDismiss = { conversationsVisible = false },
            onSwitch = ::switchConversation,
            onDelete = ::deleteConversation,
            // 删除落库窗口内管理浮层同样退让：它自己的列表也是采样源（行消失 / 删光 N→0）
            hazeEnabled = !deleteHazeRetreat.suspended,
            // 详情/编辑浮层盖上来时缩小后退（与管理浮层互斥的路径，防御性接线同主浮层）
            covered = detailTodoId != "" || editTodoId != "",
        )
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
                // 「添加到闹钟」只拉起系统时钟（无需权限/编排上下文），AI 浮层照常可用
                onAddToAlarm = { addToAlarm(detail) },
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
