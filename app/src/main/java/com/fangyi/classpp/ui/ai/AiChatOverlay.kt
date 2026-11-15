package com.fangyi.classpp.ui.ai

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import com.fangyi.classpp.data.ai.AiImageStore
import com.fangyi.classpp.data.model.TagEntry
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoGroup
import com.fangyi.classpp.ui.components.AiChatInputBar
import com.fangyi.classpp.ui.components.LocalOverlayBottomHazeState
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.components.SwipeCoordinator
import com.fangyi.classpp.ui.components.rememberHazeRetreat
import com.fangyi.classpp.ui.components.rememberImeVisible
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.rememberSystemAnimationsEnabled
import com.fangyi.classpp.ui.note.NewTodoSheet
import com.fangyi.classpp.ui.note.TodoDetailSheet
import com.fangyi.classpp.ui.note.rememberAddToAlarm
import com.fangyi.classpp.ui.theme.CardSectionSpacing
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.isGlassStyleEnabled
import com.fangyi.classpp.ui.theme.rememberGlassBackdrop
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import dev.chrisbanes.haze.HazeState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * 基于 [OverlaySheet]：遮罩/进出场/下拉关闭/顶栏磨砂复用组件内置规格；键盘让位不走
 * 内容列的滚动追踪——输入栏在钉底槽里用 `imePadding()` 自行让位，消息列表的跟随由本层
 * 的 IME 补偿实现（见下方「键盘弹起时消息列表跟随」）。
 * 消息列即采样源；「切换对话内容塌缩 / 流式逐字重写」曾触发 Adreno Vulkan 驱动崩溃
 * 而整链退让，根因是 HazeSourceSelection.All 无视 z 序采样，改 Behind（f958dbb）后
 * 重新挂回，真机验证不再崩溃；
 * 消息列表走浮层自带滚动列（onScrollStateReady 接管 ScrollState 做自动滚底），
 * 输入栏钉底（bottomScrim = false，底部光场/渐隐/导航栏留白自绘并随键盘上移）——
 * 光场由 [AiThinkingAurora] 绘制，只在 AI 思考 / 回答（sending）期间自输入栏后方上浮
 * 出现并缓慢游动，结束即下沉淡出、空闲完全不绘制；输入栏玻璃采样
 * 光场层（见底部槽的 layerBackdrop），玻璃关闭/API<31 时回退实心底。
 * 无消息空态（含未配置引导）里内容列没有输入框，浮层切 [SheetImeBehavior.IgnoreIme]：
 * 键盘弹起时内容不追加 IME 余量、空态不避让键盘；滚动容器与橡皮筋过滚动保留。
 * 有消息时恢复 [SheetImeBehavior.ContentScroll] 只为尾部余量：「输入框随内容列滚到键盘
 * 上方/收起回滚」在这里不适用——输入栏在钉底槽、不参与内容列 ImeScrollTracker，收起时
 * 也不会触发该 tracker 的回滚（P2-4）。
 *
 * - 发送中输入禁用、发送键变停止键（产品取舍：为回答让出屏幕；发送即收键盘，2026-11-10
 *   确认保留）：发送 Job 由 [AiChatRepository] 自己的 scope 驱动，
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

    // 收集为 State 实例而非直接解包：根组合只读 derived 后的 hasMessages/scrollSignal，
    // 流式增量（每 33ms 一批）不再让整个浮层根函数重组
    val messagesState: State<List<AiChatMessage>> =
        aiChatRepository?.messages?.collectAsState()
            ?: remember { mutableStateOf(emptyList<AiChatMessage>()) }
    val hasMessages by remember(messagesState) {
        derivedStateOf { messagesState.value.isNotEmpty() }
    }
    // 只在这些信号变化时做离散滚底：流式正文增长（size/卡计数不变）不触发
    val scrollSignal by remember(messagesState) {
        derivedStateOf {
            val list = messagesState.value
            val last = list.lastOrNull()
            ChatScrollSignal(
                size = list.size,
                todoIds = last?.todoIds?.size ?: 0,
                changes = last?.todoChanges?.size ?: 0,
                completions = last?.todoCompletions?.size ?: 0,
                lifecycles = last?.todoLifecycles?.size ?: 0,
            )
        }
    }
    val sending by aiChatRepository?.sending?.collectAsState() ?: remember { mutableStateOf(false) }
    val lastError by aiChatRepository?.lastError?.collectAsState()
        ?: remember { mutableStateOf<AiException?>(null) }
    val configured by aiChatRepository?.configured?.collectAsState() ?: remember { mutableStateOf(false) }
    val conversationsState: State<List<AiConversation>> =
        aiChatRepository?.conversations?.collectAsState()
            ?: remember { mutableStateOf(emptyList<AiConversation>()) }
    val hasConversations by remember(conversationsState) {
        derivedStateOf { conversationsState.value.isNotEmpty() }
    }
    val activeConversationId by aiChatRepository?.activeConversationId?.collectAsState()
        ?: remember { mutableStateOf<String?>(null) }
    // 正在应用确认卡的消息 id：null = 空闲。只影响对应消息的按钮态，不阻塞输入
    val changesApplyingMessageId by aiChatRepository?.todoChangesApplyingMessageId?.collectAsState()
        ?: remember { mutableStateOf<String?>(null) }
    // 正在撤销完成记录的消息 id：同上，只禁用撤销按钮
    val completionUndoingMessageId by aiChatRepository?.todoCompletionUndoingMessageId?.collectAsState()
        ?: remember { mutableStateOf<String?>(null) }
    // 正在撤销归档/恢复记录的消息 id
    val lifecycleUndoingMessageId by aiChatRepository?.todoLifecycleUndoingMessageId?.collectAsState()
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
    // 键盘可见性：消息列表跟随键盘的触发源（输入栏在钉底槽，不参与内容列的滚动追踪）
    val imeVisible = rememberImeVisible()

    // 标签配色表（标签名 → 颜色）：用户标签取 TagEntry.colorArgb，未挑色回落主题 Primary；
    // 与待办页列表卡/详情浮层同一份口径（表里没有的名字由 NoteCard 自己兜底）
    val primaryColor = MaterialTheme.colorScheme.primary
    val tagColors = remember(tags, primaryColor) {
        tags.associate { it.name to (it.colorArgb?.let(::Color) ?: primaryColor) }
    }
    val todosById = remember(todos) { todos.associateBy { it.id } }

    val inputState = rememberSaveable { mutableStateOf("") }
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
    // 系统是否允许动画：关闭时气泡直接呈现、列表直接到底（不做位移与缓动）
    val animationsEnabled = rememberSystemAnimationsEnabled()
    // 发送落位请求：只在「用户主动发送」这一条路径上存在，播完即清。
    // 钉底与滚动都要靠它——布局先于 LaunchedEffect 执行，若不在布局阶段读它，
    // 新消息首帧就会被「发送中逐帧钉底」直接钉到底，滚动接住整段看不见。
    var sendEntrance by remember { mutableStateOf<SendEntrance?>(null) }
    // 上一次已响应的消息 id 序列：滚底 effect 也会被切对话/打开浮层/待办卡落位触发，
    // 只有「原列表原样延长」才算发送追加（判定见 isPureAppend）。初值留空，
    // 由滚底 effect 首跑时写入——避免根组合读 messagesState（会订阅每批流式发射）
    var lastMessageIds by remember { mutableStateOf<List<String>>(emptyList()) }

    // ——— 图片附件：相册选图 / 拍照（Photo Picker 无需权限；TakePicture 唤系统相机） ———

    val imageStore = aiChatRepository?.imageStore
    // 待发送图片附件（已导入的压缩副本文件名）：发送时随消息入流；浮层退场仍未发送则删副本
    val pendingImagesState = rememberSaveable { mutableStateOf(listOf<String>()) }
    // 拍照临时文件（TakePicture 的输出目标）：结果回来后导入压缩，无论成败即删
    var captureFile by remember { mutableStateOf<File?>(null) }
    // 全屏预览的已发送图片文件名（气泡缩略图点开）；null = 无预览
    var previewImageName by remember { mutableStateOf<String?>(null) }

    /** 相册多选导入：Photo Picker 的 URI 是临时授权，先导入压缩副本（IO），超额度截断 */
    fun importImages(uris: List<Uri>) {
        val store = imageStore ?: return
        val remaining = MaxImagesPerMessage - pendingImagesState.value.size
        if (remaining <= 0 || uris.isEmpty()) return
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                uris.take(remaining).mapNotNull { store.import(context, it) }
            }
            if (imported.isNotEmpty()) {
                pendingImagesState.value =
                    (pendingImagesState.value + imported).take(MaxImagesPerMessage)
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
                if (pendingImagesState.value.size < MaxImagesPerMessage) {
                    pendingImagesState.value =
                        (pendingImagesState.value + imported).take(MaxImagesPerMessage)
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
        if (pendingImagesState.value.size >= MaxImagesPerMessage) return
        galleryLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    /** 拍照入口：预建 cacheDir 临时文件，经 FileProvider 授权给系统相机写入 */
    fun startCapture() {
        if (imageStore == null) return
        if (pendingImagesState.value.size >= MaxImagesPerMessage) return
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
        pendingImagesState.value = pendingImagesState.value - file.name
        scope.launch(Dispatchers.IO) { store.delete(file.name) }
    }

    /** 浮层退场收尾：未发送的待发送图片没有持有方了，副本一并删除 */
    fun clearPendingImages() {
        val stale = pendingImagesState.value
        if (stale.isEmpty()) return
        pendingImagesState.value = emptyList()
        val store = imageStore ?: return
        scope.launch(Dispatchers.IO) {
            stale.forEach { store.delete(it) }
        }
    }

    // 返回键：编辑器 → 详情 → 展开的侧滑卡 → 先收输入键盘 → 收起对话浮层
    //（详情自身的返回拦截注册更晚，详情在场时由它先处理备注落盘，这里只兜底非详情场景）
    BackHandler(enabled = visible) {
        when {
            editTodoId != "" -> editTodoId = ""
            detailTodoId != "" -> detailTodoId = ""
            swipeCoordinator.activeKey != null -> swipeCoordinator.activeKey = null
            imeVisible -> keyboard?.hide()
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
    // key 取「是否为空」而非列表本身：流式增量刷新 conversations 不该反复重启本协程
    LaunchedEffect(hasConversations) {
        if (!hasConversations) conversationsVisible = false
    }

    // 删除对话的落库回流落定：重写帧仍在 deleteHazeRetreat 的退让窗口内，此处只推迟挂回。
    // snapshotFlow 只观察对话 id 序列：流式增量只改消息内容，不该每 33ms 重启一次
    LaunchedEffect(deleteHazeRetreat, conversationsState) {
        snapshotFlow { conversationsState.value.map { it.id } }
            .distinctUntilChanged()
            .collect { deleteHazeRetreat.onReflow() }
    }

    // 离散事件滚底：新消息入列 / 待办卡落位 / 切换对话 / 浮层可见——等下一帧布局完成后滚一次。
    // 用户主动发送那一次改走短缓动「接住」新气泡（Motion.ChatScrollCatchMillis + Overlay）；
    // 其余路径（打开浮层、切对话、待办卡落位）保持原来的立即到底——它们没有「我刚发了一条」
    // 这个因果，缓动只会让页面看起来在自作主张地动。
    // 流式增量不走这里（见下方「流式钉底」），那一层不能动画化。
    LaunchedEffect(scrollSignal, activeConversationId, visible) {
        if (!visible) return@LaunchedEffect
        withFrameNanos { }
        val scroll = chatScroll.value
        val request = sendEntrance
        // 流式增量只改内容不改条数，这里天然不会被触发；切对话 / 删除是整体换写，追加判定为假
        val ids = messagesState.value.map { it.id }
        val appended = isPureAppend(lastMessageIds, ids)
        lastMessageIds = ids
        try {
            // 用户手势 / 惯性接管期间不滚：scrollTo 是 Default 优先级的挂起滚动，拖动期间
            // 会排队、等松手后再把列表拉到底——读感是「刚滑上去又弹回底部」。这次离散滚底
            // 直接作废即可：停到底部后由流式跟底或下一次离散事件恢复。
            if (scroll != null && !scroll.isScrollInProgress) {
                val catchUp = request != null && appended && request.atBottom && animationsEnabled &&
                    scroll.maxValue - scroll.value > with(density) { ScrollCatchMinSlop.roundToPx() }
                if (catchUp) {
                    scroll.animateScrollTo(
                        value = scroll.maxValue,
                        animationSpec = tween(
                            durationMillis = Motion.ChatScrollCatchMillis,
                            easing = Motion.Overlay,
                        ),
                    )
                    // 动画目标固定在启动时的 maxValue；动画期间内容若继续增长，剩余差值
                    // 交给流式平滑跟底逐帧补齐——这里不能再硬 scrollTo，否则会把「回复正在
                    // 快速出字」的那段积压在一帧内拉完，读感就是卡片突然变长后猛地闪现上移
                } else {
                    scroll.scrollTo(scroll.maxValue)
                }
            }
        } finally {
            // 只在「确实发生了追加」这一趟消费请求：新对话的首条消息会先建对话、把消息列
            // 清空一次，那次状态更新可能单独占一帧，此时清掉请求会把紧随其后的真追加掐掉。
            // 没被消费的请求由超时兜底清。
            if (appended) sendEntrance = null
        }
    }

    // 发送请求的兜底清理：收起浮层即失效（重开时列表可能整块换写，不能留旧请求）；
    // 超时兜底另管「迟迟没有消息入列」的路径——正常路径由滚底 effect 消费后清掉。
    // 不按 activeConversationId 清：新对话的首条消息会先建对话再追加消息，两次状态更新
    // 可能落在两帧里，按 id 清会把这次发送的落位半路掐掉。
    LaunchedEffect(visible) {
        if (!visible) sendEntrance = null
    }
    LaunchedEffect(sendEntrance) {
        if (sendEntrance != null) {
            delay(SendEntranceTimeoutMillis)
            sendEntrance = null
        }
    }
    // ——— 键盘弹起时消息列表跟随 ———
    // 输入栏在钉底槽里，不在内容列 ImeScrollTracker 的作用域内：键盘弹起时尾部余量逐帧
    // 增长，但 ScrollState 不会自己动，末条消息因此滑进键盘区。这里只在「弹起前就在底部」
    // 时跟随——正在翻历史的用户不该被拽走。
    val chatScrollState = chatScroll.value
    var chatAtBottomBeforeIme by remember { mutableStateOf(true) }
    // 键盘未弹起期间持续记录底部状态，供弹起那一帧取用（弹起后冻结，避免被自身补偿改写）
    LaunchedEffect(chatScrollState, imeVisible, visible) {
        val scroll = chatScrollState ?: return@LaunchedEffect
        if (!visible || imeVisible) return@LaunchedEffect
        val slopPx = with(density) { SendAtBottomSlop.roundToPx() }
        snapshotFlow { scroll.maxValue - scroll.value }.collect { gap ->
            chatAtBottomBeforeIme = gap <= slopPx
        }
    }
    // 弹起动画期间尾部余量逐帧增长：逐帧钉到新的 maxValue，内容随输入栏一起上移。
    // 跟随窗口取 IME 动画量级（600ms）而不是「连续 N 帧不变」：动画起止段可能连续几帧
    // 高度相同，按稳定帧收手会提前退出；窗口内用户一开始拖动就把控制权交还。
    // 收起不需要另做动画：尾部余量收缩把 maxValue 压小，停在底部的滚动值会被
    // ScrollState 自动钳制，内容随输入栏一起回落。
    LaunchedEffect(chatScrollState, imeVisible, visible) {
        val scroll = chatScrollState ?: return@LaunchedEffect
        if (!visible || !imeVisible || !chatAtBottomBeforeIme) return@LaunchedEffect
        val startNanos = withFrameNanos { it }
        while (withFrameNanos { it } - startNanos < ImeFollowWindowMillis * 1_000_000L) {
            if (scroll.isScrollInProgress) return@LaunchedEffect
            val max = scroll.maxValue
            if (max != scroll.value) scroll.scrollTo(max)
        }
    }

    // 流式钉底（平滑跟随）：内容高度在布局阶段写回 ScrollState.maxValue，这里每帧把滚动
    // 位置向 maxValue 逼近，而不是一帧内瞬移。瞬移会把「一个 chunk 带来的整段高度」直接
    // 读成一次上跳——回复/思考很快时，卡片先突然变长、再猛地闪上去了。按帧逼近后同样的
    // 高度增量会被摊成几帧的平滑上移；流结束或中途停顿时也能在几帧内收敛到底。
    // 用户手势/惯性接管（用户上滑读历史）即停止跟随，回到底部后自动恢复；发送接住动画
    // 期间让位，不抢它的目标值。
    val currentSendEntrance by rememberUpdatedState(sendEntrance)
    val streamFollow = remember { StreamFollowState() }
    LaunchedEffect(chatScrollState, visible, sending) {
        val scroll = chatScrollState ?: return@LaunchedEffect
        if (!visible) {
            // 收起浮层期间不跟；重开时由 visible 的离散滚底直接到底
            streamFollow.settleNeeded = false
            return@LaunchedEffect
        }
        val slopPx = with(density) { SendAtBottomSlop.roundToPx() }
        val maxStepPx = with(density) { StreamFollowMaxStep.roundToPx() }
        fun easeStep() {
            val gap = scroll.maxValue - scroll.value
            if (gap <= 0) return
            val step = if (gap <= 1) {
                gap
            } else {
                minOf(maxStepPx, (gap * StreamFollowFraction).toInt().coerceAtLeast(1))
            }
            scroll.dispatchRawDelta(step.toFloat())
        }

        if (!sending) {
            // 回复收尾：最后一批内容的尺寸 tween 可能还有一小段尾巴（maxValue 还在长），
            // 继续跟几帧到底；用户已上滑读历史时不打扰
            if (!streamFollow.settleNeeded || !streamFollow.following ||
                scroll.isScrollInProgress
            ) {
                streamFollow.settleNeeded = false
                return@LaunchedEffect
            }
            val start = withFrameNanos { it }
            while (withFrameNanos { it } - start < StreamFollowSettleMillis * 1_000_000L) {
                if (scroll.isScrollInProgress) break
                val gap = scroll.maxValue - scroll.value
                if (gap <= 0) break
                easeStep()
            }
            streamFollow.settleNeeded = false
            return@LaunchedEffect
        }

        streamFollow.settleNeeded = true
        streamFollow.following = true
        var userTookOver = false
        var scrollingFrames = 0
        while (true) {
            withFrameNanos { }
            val sendCatchUp = currentSendEntrance?.atBottom == true
            if (scroll.isScrollInProgress) {
                // 发送接住动画也是「滚动进行中」，但由程序发起，不算用户接管。
                // 非动画的 scrollTo 只占同一帧，连续两帧仍在滚动才认成手势/惯性。
                if (!sendCatchUp && ++scrollingFrames >= 2) userTookOver = true
                continue
            }
            scrollingFrames = 0
            if (userTookOver) {
                // 手势/惯性结束：停在底部才继续跟随，否则把控制权交还用户
                streamFollow.following = scroll.maxValue - scroll.value <= slopPx
                userTookOver = false
            }
            if (!streamFollow.following) {
                // 用户自己回到底部后恢复跟随
                if (scroll.maxValue - scroll.value <= slopPx) {
                    streamFollow.following = true
                } else {
                    continue
                }
            }
            if (sendCatchUp) continue
            easeStep()
        }
    }

    // ——— 动作集合 ———

    fun sendMessage(text: String) {
        val repository = aiChatRepository ?: return
        if (sending) return
        if (text.isBlank() && pendingImagesState.value.isEmpty()) return
        val images = pendingImagesState.value
        // 落位请求必须在清空输入、仓库追加之前定：atBottom 读的是「用户按下发送那一刻」
        // 的滚动位置，消息入列之后再读已经不是同一件事了
        val scroll = chatScroll.value
        sendEntrance = SendEntrance(
            atBottom = scroll != null &&
                scroll.maxValue - scroll.value <= with(density) { SendAtBottomSlop.roundToPx() },
            sentAtMillis = System.currentTimeMillis(),
        )
        inputState.value = ""
        // 图片随消息发出：文件名已由仓库随 user 消息持有，预览条清空但**不删副本**
        pendingImagesState.value = emptyList()
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
        if (!hasConversations) return
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
        // 空态（未配置 / 无消息）里内容列没有输入框：不参与键盘让位——键盘弹起时尾部
        // 不再撑出 IME 余量（否则空态可被拖进一大片空白）。滚动容器与橡皮筋过滚动仍由
        // OverlaySheet 正常提供，不受本开关影响。有消息时恢复 ContentScroll 的输入框让位。
        imeBehavior = if (!hasMessages) {
            SheetImeBehavior.IgnoreIme
        } else {
            SheetImeBehavior.ContentScroll
        },
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
                    // 输入状态由本叶子读取：键入只重组输入栏，不失效整个对话浮层
                    AiChatComposer(
                        inputState = inputState,
                        pendingImagesState = pendingImagesState,
                        imageStore = imageStore,
                        sending = sending,
                        enabled = configured,
                        onSend = ::sendMessage,
                        onStop = ::stopSending,
                        onTakePhoto = ::startCapture,
                        onAddImage = ::launchGallery,
                        onRemoveImage = ::removePendingImage,
                        hazeState = bottomHazeState,
                        backdrop = auroraBackdrop,
                    )
                }
            }
        },
    ) {
        when {
            aiChatRepository == null -> AiChatLoadingState()
            !configured -> {
                AiChatNotConfigured(onOpenSettings = onOpenSettings)
            }
            !hasMessages -> {
                AiChatEmptyState()
            }
            else -> AiChatMessageList(
                messagesState = messagesState,
                sending = sending,
                sendEntrance = sendEntrance,
                lastError = lastError,
                todosById = todosById,
                tagColors = tagColors,
                imageStore = imageStore,
                swipeCoordinator = swipeCoordinator,
                changesApplyingMessageId = changesApplyingMessageId,
                completionUndoingMessageId = completionUndoingMessageId,
                lifecycleUndoingMessageId = lifecycleUndoingMessageId,
                onImageClick = { name -> previewImageName = name },
                onOpenTodo = ::openTodo,
                onDeleteTodo = ::deleteTodo,
                onToggleCompleted = ::toggleCompleted,
                onApplyChanges = { id -> aiChatRepository.applyTodoChangesInBackground(id) },
                onDismissChanges = { id -> aiChatRepository.dismissTodoChanges(id) },
                onUndoCompletion = { id, index ->
                    aiChatRepository.undoTodoCompletionInBackground(id, index)
                },
                onUndoLifecycle = { id, index ->
                    aiChatRepository.undoTodoLifecycleInBackground(id, index)
                },
                onRetry = ::retryLast,
            )
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
            conversationsState = conversationsState,
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

/**
 * AI 消息列：自己读 [messagesState]，流式增量只重组本列，不失效 AiChatOverlay 根作用域。
 * 非懒加载 Column（OverlaySheet 的 verticalScroll 契约与本文件 Haze 采样约束见上方注释）：
 * 每批合并只有尾部一条消息换了实例，其余消息按下标槽位跳过重组——**前提是卡片段的
 * 入参也保持稳定**，所以待办卡的反查列表与撤销/应用回调都在各自 `if` 里用 remember 固定
 * （见下方注释）。列本身仍会随每批重组（它要读新内容），这是「批发布」的固有成本。
 */
@Composable
private fun AiChatMessageList(
    messagesState: State<List<AiChatMessage>>,
    sending: Boolean,
    sendEntrance: SendEntrance?,
    lastError: AiException?,
    todosById: Map<String, Todo>,
    tagColors: Map<String, Color>,
    imageStore: AiImageStore?,
    swipeCoordinator: SwipeCoordinator,
    changesApplyingMessageId: String?,
    completionUndoingMessageId: String?,
    lifecycleUndoingMessageId: String?,
    onImageClick: (String) -> Unit,
    onOpenTodo: (Todo) -> Unit,
    onDeleteTodo: (Todo) -> Unit,
    onToggleCompleted: (Todo, Boolean) -> Unit,
    onApplyChanges: (String) -> Unit,
    onDismissChanges: (String) -> Unit,
    onUndoCompletion: (String, Int) -> Unit,
    onUndoLifecycle: (String, Int) -> Unit,
    onRetry: () -> Unit,
) {
    val messages = messagesState.value
    // 消息之间（含回复气泡与下方待办卡）统一走全局「卡↔卡」节奏（10dp）
    Column(verticalArrangement = Arrangement.spacedBy(CardSectionSpacing)) {
        // 尾部 user 消息的 id：气泡落位只认这一条（发送后追加的第一条用户消息）
        val lastUserId = messages.lastOrNull { it.role == AiChatRole.User }?.id
        messages.forEachIndexed { index, message ->
            // key 让入场状态跟着消息 id 走：切对话 / 删除导致槽位复用时，上一条的
            // 「正在入场」不会串到别的气泡上（key 不产生布局节点，spacedBy 不变）
            key(message.id) {
                // 入场判定只在首次组合时求一次：之后清 sendEntrance 既不撤图层、也不重播。
                // 只给 user 条入场——assistant 占位与它同帧追加，两条一起抬读不出因果
                val entering = remember {
                    val request = sendEntrance
                    request != null &&
                        message.role == AiChatRole.User &&
                        message.id == lastUserId &&
                        message.createdAtMillis >= request.sentAtMillis
                }
                // 不在底部（用户正在翻历史）时位移减半：这次滚动仍是硬跳，
                // 大幅抬升会和它叠成两段方向相反的运动
                val riseDp = remember {
                    if (sendEntrance?.atBottom == false) {
                        BubbleRiseNearBottom
                    } else {
                        BubbleRiseAtBottom
                    }
                }
                val isTail = index == messages.lastIndex
                AiChatMessageBubble(
                    message = message,
                    imageStore = imageStore,
                    onImageClick = onImageClick,
                    // 思考阶段 = 本场流仍在进行、这是尾部消息、正文还没开始：
                    // 折叠块默认展开，正文一到就自动收起
                    thinkingInProgress = sending && isTail &&
                        message.role == AiChatRole.Assistant &&
                        message.content.isEmpty(),
                    // 流式尾部不挂尺寸 tween：合并批次本身就是平滑的
                    streamingTail = sending && isTail && message.role == AiChatRole.Assistant,
                    modifier = if (entering) {
                        Modifier.chatBubbleEntrance(message.id, riseDp)
                    } else {
                        Modifier
                    },
                )
                if (message.todoSaveFailed) {
                    AiChatTodoSaveFailedHint()
                }
                // 已创建待办：按落盘的 id 顺序从活跃待办流反查（被删除/归档的自然消失）。
                // 反查结果与回调都用 remember 固定：否则每批流式都会生成新 List / 新 lambda，
                // 让这张卡（以及带卡的尾部消息）在正文增长期间白重组
                if (message.todoIds.isNotEmpty()) {
                    val created = remember(message.todoIds, todosById) {
                        message.todoIds.mapNotNull(todosById::get)
                    }
                    AiChatCreatedTodoList(
                        todos = created,
                        tagColors = tagColors,
                        swipeCoordinator = swipeCoordinator,
                        onOpen = onOpenTodo,
                        onDelete = onDeleteTodo,
                        onCheckedChange = onToggleCompleted,
                    )
                }
                // 已完成/取消完成：直接生效，行尾可撤销（先于待确认项展示）
                if (message.todoCompletions.isNotEmpty()) {
                    val onUndo = remember(onUndoCompletion) {
                        { index: Int -> onUndoCompletion(message.id, index) }
                    }
                    AiChatTodoCompletionList(
                        completions = message.todoCompletions,
                        undoing = completionUndoingMessageId == message.id,
                        onUndo = onUndo,
                    )
                }
                // 归档/取消归档/恢复：直接生效，行尾可撤销
                if (message.todoLifecycles.isNotEmpty()) {
                    val onUndo = remember(onUndoLifecycle) {
                        { index: Int -> onUndoLifecycle(message.id, index) }
                    }
                    AiChatTodoLifecycleList(
                        lifecycles = message.todoLifecycles,
                        undoing = lifecycleUndoingMessageId == message.id,
                        onUndo = onUndo,
                    )
                }
                // 待确认的修改/删除：确认前不写库；目标已消失时卡片仍按名称快照展示
                if (message.todoChanges.isNotEmpty()) {
                    val onApply = remember(onApplyChanges) { { onApplyChanges(message.id) } }
                    val onDismiss = remember(onDismissChanges) { { onDismissChanges(message.id) } }
                    AiChatTodoChangeCard(
                        changes = message.todoChanges,
                        todosById = todosById,
                        applying = changesApplyingMessageId == message.id,
                        onApply = onApply,
                        onDismiss = onDismiss,
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
        }
        lastError?.let { error ->
            AiChatErrorBanner(
                error = error.error,
                onRetry = onRetry,
            )
        }
    }
}

/**
 * 输入栏叶子：键入只读 [inputState]，不把根 Composable 拉进重组；
 * 待发送图片同理交给本叶子读取，发送时根回调再取当前值。
 */
@Composable
private fun AiChatComposer(
    inputState: MutableState<String>,
    pendingImagesState: State<List<String>>,
    imageStore: AiImageStore?,
    sending: Boolean,
    enabled: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onTakePhoto: () -> Unit,
    onAddImage: () -> Unit,
    onRemoveImage: (File) -> Unit,
    hazeState: HazeState?,
    backdrop: Backdrop?,
) {
    AiChatInputBar(
        value = inputState.value,
        onValueChange = { inputState.value = it },
        placeholder = stringResource(R.string.ai_input_hint),
        sendingPlaceholder = stringResource(R.string.ai_input_sending_hint),
        sending = sending,
        enabled = enabled,
        onSend = { onSend(inputState.value) },
        onStop = onStop,
        onTakePhoto = onTakePhoto,
        onAddImage = onAddImage,
        pendingImages = imageStore
            ?.let { store -> pendingImagesState.value.map(store::file) }
            ?: emptyList(),
        onRemoveImage = onRemoveImage,
        hazeState = hazeState,
        backdrop = backdrop,
    )
}

/** AI 仓库尚未完成后台预热：既不显示“未配置”引导，也不允许发送 */
@Composable
private fun AiChatLoadingState() {
    // 内容列在 verticalScroll 内（高度约束无限）：只铺宽 + 固定上留白居中，
    // 不用 fillMaxSize（无限高时不会真正撑满）
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 120.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

/** 离散滚底信号：只包含会改变列表结构/待办卡数量的维度，流式正文增长不在此列 */
private data class ChatScrollSignal(
    val size: Int,
    val todoIds: Int,
    val changes: Int,
    val completions: Int,
    val lifecycles: Int,
)

/**
 * 一次发送的「落位请求」：只在用户主动发送这一条路径上存在，滚底 effect 消费后即清。
 *
 * [atBottom] 在按下发送那一刻读定——为 true 时列表用 [Motion.ChatScrollCatchMillis] 缓动
 * 接住新气泡、气泡抬升满幅；为 false（用户正在翻历史）时沿用原来的立即到底、抬升减半。
 * [sentAtMillis] 用来认领「这一条」用户消息：只有创建时刻不早于本次发送的消息才算刚落位，
 * 请求在窗口内没被消费就切了对话时，新对话尾部那条旧消息不会误播入场。
 */
private class SendEntrance(val atBottom: Boolean, val sentAtMillis: Long)

/**
 * 流式跟底状态：跨 `sending` 翻转存活。
 * - [following]：用户是否仍在跟底（上滑读过历史则为 false），收尾阶段据此决定
 *   要不要把尺寸 tween 的尾巴补完；
 * - [settleNeeded]：本次是否刚从流式回复结束，避免打开历史对话时误做收尾滚动。
 */
private class StreamFollowState {
    var following = true
    var settleNeeded = false
}

/** 气泡抬升幅度（列表在底部）：末尾 [AuroraHeadroom] 空带足够容纳，不会压到上一条消息。 */
private val BubbleRiseAtBottom = 10.dp

/** 不在底部时的抬升幅度：这次滚动仍是硬跳，幅度减半，免得两段运动叠在一起读成「抖」。 */
private val BubbleRiseNearBottom = 5.dp

/** 判定「在底部」的容差：与列表末尾差这么多以内都算在底部，照常缓动接住。 */
private val SendAtBottomSlop = 8.dp

/** 缓动接住的最小滚动距离：比这更近时直接到底——位移看不出来，动画白跑一趟。 */
private val ScrollCatchMinSlop = 24.dp

/** 流式跟底的单帧最大步长：一个 chunk 带来一大段高度时，摊成多帧补，避免一帧闪上去。 */
private val StreamFollowMaxStep = 24.dp

/** 流式跟底每帧补齐剩余差值的比例：约 8~12 帧收敛到底。 */
private const val StreamFollowFraction = 0.28f

/** 回复结束后的收尾跟随窗口：把气泡高度过渡的最后一段尾巴补到底。 */
private const val StreamFollowSettleMillis = 300L

/**
 * 落位请求的存活上限：正常路径由滚底 effect 消费后立即清掉，这里只兜「仓库迟迟没追加」
 * （凭据读取被阻塞等）的情况——超过这个时长就当这次发送没有落位可言，不再等它。
 * 不能太长：残留的请求会被下一次列表变化误认成发送。
 */
private const val SendEntranceTimeoutMillis = 1200L

/**
 * 键盘弹起后消息列表跟随的最大窗口：覆盖系统 IME 动画时长即可。窗口内逐帧把滚动钉到
 * `maxValue`（尾部余量随 IME 增长），用户拖动会提前退出。取固定窗口而不是「连续 N 帧
 * 不变」：IME 动画起止段可能连续几帧高度相同，按稳定帧判断会提前收手。
 */
private const val ImeFollowWindowMillis = 600L
