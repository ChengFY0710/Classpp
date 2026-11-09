package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.Clock
import com.fangyi.classpp.data.LoadState
import com.fangyi.classpp.data.ReadResult
import com.fangyi.classpp.data.TodoValidator
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.occurrencesBetween
import com.fangyi.classpp.data.store.LoadIssue
import com.fangyi.classpp.data.store.LoadOutcome
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 一次对话调用的上下文快照：由 contextProvider 从各仓库 StateFlow 现取（只读，不落盘） */
data class AiChatContext(
    val schedule: Schedule? = null,
    val currentWeek: Int? = null,
    val todos: List<Todo> = emptyList(),
    val groups: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

/**
 * 待办落库回调：AI 回复解析出的待办由仓库直接写入 TodoRepository（无需用户确认）。
 * 抽成接口是为了让 AiChatRepository 不反向依赖具体仓库、单测可注入 fake。
 * 返回 true = 整批落库成功（`TodoRepository.addTodos` 是全量校验、零落盘的批量语义）。
 */
fun interface AiTodoPersister {
    suspend fun addAll(todos: List<Todo>): Boolean
}

/**
 * AI 修改/删除待办的写入口（用户确认后才会调用）。
 *
 * 与 [AiTodoPersister] 分开：新建仍是「立即落库」语义，修改/删除走确认卡，
 * 两者的失败处理与测试替身都不同。实现侧接 `TodoRepository.updateTodo`（归一化 +
 * 校验 + 单次提交）与 `removeTodos`（批量软删除、单次提交）。
 */
interface AiTodoChangeWriter {
    /** 写入一条修改后的待办；false = 归一化/校验/落盘失败 */
    suspend fun update(todo: Todo): Boolean

    /** 批量软删除（移入回收站）；false = 落盘失败 */
    suspend fun deleteAll(ids: List<String>): Boolean

    /** 普通待办的完成/取消完成；false = 落盘失败 */
    suspend fun setCompleted(id: String, completed: Boolean): Boolean

    /** 重复待办某一期的完成/取消完成；false = 落盘失败 */
    suspend fun setOccurrenceCompleted(id: String, date: IsoDate, completed: Boolean): Boolean
}

/** 保存 AI 配置的结果：配置本身是否落盘 + 保存后是否具备可解密的 Key */
data class AiConfigSaveOutcome(
    val configSaved: Boolean,
    val keySaved: Boolean,
)

/**
 * AI 对话仓库：内存真源（StateFlow）+ 磁盘持久化（[AiChatStore]），与 TodoRepository
 * 同构；流式增量直接写入消息流（UI 逐字渲染）。
 *
 * **多对话模型**：真源是 [_conversations]（每场对话各自的消息），[_activeConversationId]
 * 指向当前活跃对话；公开的 [messages] 是活跃对话消息的同步镜像（见 [updateConversation]）。
 * 每场发送在锁内绑定一场对话（无活跃对话时隐式新建，未命名借首条用户消息命名），
 * 流式增量按绑定 id 定向落库——中途切换对话，回复仍完整落在发起时的对话里。
 *
 * **调用编排**（见 .mimocode/docs/AI功能计划.md §6.3）：
 * - [send] 先对历史窗口拍快照 → 追加 user 消息与空的 assistant 占位 → 拼 system
 *   prompt（上下文快照 + 历史快照 + 本轮 user）→ [AiClient.streamChat] 流式 →
 *   完成后解析草稿块 → **直接映射并落库** → 把创建成功的 todo id 挂到消息上 → 落盘；
 * - 中断/失败保留半截文本（interrupted 标记 + [lastError]），重试走 [retryLast]
 *   （不重复追加 user 消息）；半截回复不创建待办——只认完整结束的回复；
 * - 发送 Job 归仓库自有的 [scope]（[sendInBackground] / [retryInBackground]）：收起
 *   对话浮层、旋转屏幕都不会中断流；停止 = [cancelSending]，[send] 捕获
 *   CancellationException 后先落盘半截再原样传播——取消语义不吞；
 * - 流过长时按 [CHECKPOINT_INTERVAL_MILLIS] 节流做半截 checkpoint，进程被杀也能
 *   恢复已收到的部分。
 *
 * **待办落库**：草稿解析成功后由 [persister] 写入待办库，消息只落成功创建的 id；
 * UI 从 TodoRepository 现取内容渲染 NoteCard（详情/编辑/侧划删除都走真实待办）。
 * 缺名称的草稿会被映射器滤掉（否则整批校验失败），落库抛错则消息标记
 * [AiChatMessage.todoSaveFailed]。
 *
 * **修改/删除待办**：updates/deletes 不直接写库——解析后校验目标与补丁，挂到消息的
 * [AiChatMessage.todoChanges] 上等待用户确认；确认卡「应用变更」经 [changeWriter]
 * 逐条写入（删除为软删除），状态随消息落盘；「忽略」只把待确认标记为已忽略。
 * 只有完整结束的回复才会生成待确认变更（半截回复不产生）。
 *
 * **完成/取消完成**：completes/reopens 解析后**直接执行**（重复待办按注入的期次日期
 * 校验），结果挂到 [AiChatMessage.todoCompletions]；[undoTodoCompletionInBackground]
 * 可按记录反向写回（撤销失败只标记状态，不谎报原操作未发生）。
 *
 * 写：Mutex 串行化（同一次对话期间禁止并发 send），流程 = 内存变更 → `store.save`
 * → 成功与否只影响下次装载（对话历史可弃，失败不回滚内存）。对话管理操作
 * （[createConversation] / [switchConversation] / [deleteConversation]）刻意不取该
 * Mutex——否则「新建/切换/删除」会卡到在途流式结束；安全性由定向写入保证（见
 * [updateConversation]），落盘仍统一经 [persistMutex] 串行。
 */
class AiChatRepository internal constructor(
    private val store: AiChatStore,
    private val settings: AiSettingsStore,
    private val client: AiClient,
    /** 上下文快照提供方：AppContainer 装配时读各仓库 StateFlow.value（同步、无 I/O） */
    private val contextProvider: () -> AiChatContext,
    /** AI 创建待办的落库入口（AppContainer 接 TodoRepository.addTodos） */
    private val persister: AiTodoPersister,
    /** AI 修改/删除待办的写入口（用户确认后调用；AppContainer 接 updateTodo/removeTodos） */
    private val changeWriter: AiTodoChangeWriter,
    /**
     * 图片附件库（AppContainer 接 filesDir/ai_chat_images）：UI 借它导入相册/拍照图片，
     * 发送时在锁内把消息引用的文件编码进请求。公开给 UI 是浮层直连仓库架构下的
     * 服务定位（同 messages/sending 的取用方式）。
     */
    val imageStore: AiImageStore,
    private val clock: Clock = Clock.system,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /** 落盘互斥：checkpoint 与最终落盘并发时，快照必须在锁内取，防旧快照覆盖新文件 */
    private val persistMutex = Mutex()

    /** 仓库自有协程域：发送 Job 不随浮层卸载/Activity 重建取消（AppContainer 进程级单例） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 发送闸门：[_sending] 是异步翻转的，靠它挡住两次快速点击导致的并发 send */
    private val sendGate = AtomicBoolean(false)
    private val activeSend = AtomicReference<Job?>(null)

    /** 在途发送绑定的对话 id（send/retry 锁内绑定）：删除对话时据此决定是否掐断在途流 */
    private val activeSendConversationId = AtomicReference<String?>(null)

    /** 上次 checkpoint 时刻（[nowMillis] 口径）；只由流式回调线程写，读侧加 @Volatile */
    @Volatile
    private var lastCheckpointAt = 0L

    private val _conversations = MutableStateFlow<List<AiConversation>>(emptyList())

    /** 全部对话（含各自消息，按创建顺序）；「所有对话」浮层据此渲染列表 */
    val conversations: StateFlow<List<AiConversation>> = _conversations.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)

    /** 当前活跃对话 id；null = 无对话（对话浮层走空态，「所有对话」入口点击无反应） */
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    /**
     * 活跃对话的消息镜像（对话浮层渲染源）。真源是 [_conversations] 里活跃对话的
     * messages，由 [updateConversation] 在每次定向变更后同步回填——保持同步赋值
     * （而非派生流）让「发送 → UI 可见」在同帧内可见，也保证既有读侧语义不变。
     */
    private val _messages = MutableStateFlow<List<AiChatMessage>>(emptyList())
    val messages: StateFlow<List<AiChatMessage>> = _messages.asStateFlow()

    private val _loadState = MutableStateFlow(LoadState.Ready)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    /** 发送中（发送键变停止键、输入禁用）；一次只允许一场对话 */
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    /** 正在应用待确认变更的消息 id（null = 空闲）；UI 据此显示进度并禁用按钮 */
    private val _todoChangesApplyingMessageId = MutableStateFlow<String?>(null)
    val todoChangesApplyingMessageId: StateFlow<String?> =
        _todoChangesApplyingMessageId.asStateFlow()

    /** 应用闸门：确认落库一次只允许一批（连点「应用变更」只认第一批） */
    private val applyChangesGate = AtomicBoolean(false)

    /** 正在撤销完成操作的消息 id（null = 空闲）；UI 据此禁用撤销按钮 */
    private val _todoCompletionUndoingMessageId = MutableStateFlow<String?>(null)
    val todoCompletionUndoingMessageId: StateFlow<String?> =
        _todoCompletionUndoingMessageId.asStateFlow()

    /** 撤销闸门：一次只处理一条撤销（连点只认第一次） */
    private val undoCompletionGate = AtomicBoolean(false)

    /**
     * 最近一次失败的分类错误（UI 错误条 + 重试）。错误**归属对话**：真源是
     * [AiConversation.lastError]，随对话落盘——退出浮层、切走再切回、进程重启后错误条
     * 都还在原位。公开流是活跃对话的镜像：归属对话正在查看时才对外可见（在途流失败时
     * 用户可能已切到别的对话，错误条不能串场）。新发送 / 用户关闭时清空。
     */
    private val _lastError = MutableStateFlow<AiException?>(null)
    val lastError: StateFlow<AiException?> = _lastError.asStateFlow()

    /** 记录归属对话的失败（定向写入对话，[mirrorLastError] 决定是否对外可见） */
    private fun storeLastError(error: AiException, conversationId: String?) {
        val id = conversationId ?: return
        updateConversation(id) { it.copy(lastError = error.error) }
        mirrorLastError()
    }

    /** 重镜像 [lastError]：切换/新建/删除对话后，错误条随归属对话显隐 */
    private fun mirrorLastError() {
        _lastError.value = activeConversation()?.lastError?.let { AiException(it) }
    }

    /** 清空活跃对话的错误（新一轮发送开始 / 用户关闭 / 清空历史） */
    private fun clearLastError() {
        val active = activeConversation()
        if (active != null && active.lastError != null) {
            updateConversation(active.id) { it.copy(lastError = null) }
        }
        mirrorLastError()
    }

    /** 配置是否就绪（连接 + Key）；bootstrap 与设置页保存后刷新，UI 据此走引导态 */
    private val _configured = MutableStateFlow(false)
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    /** AI 配置方案库（不含「自定义」槽位）；方案写操作与 bootstrap 后刷新 */
    private val _profiles = MutableStateFlow<List<AiProfile>>(emptyList())
    val profiles: StateFlow<List<AiProfile>> = _profiles.asStateFlow()

    /** 当前激活方案 id；null = 使用「自定义」槽位 */
    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    /** 当前生效连接（活动方案或自定义槽位），设置页入口行据此显示摘要 */
    private val _activeConnection = MutableStateFlow(AiConnection())
    val activeConnection: StateFlow<AiConnection> = _activeConnection.asStateFlow()

    /**
     * Keystore 是否可用（响应式）：读取/保存 Key 后同步——那些操作正是可用性可能
     * 翻转的时机，UI 据此展示不可用横幅与保存失败原因。
     */
    private val _secureAvailable = MutableStateFlow(settings.secureAvailable)
    val secureAvailable: StateFlow<Boolean> = _secureAvailable.asStateFlow()

    // ---------- 生命周期 ----------

    /** 从磁盘装载（阻塞 I/O，经 Dispatchers.IO）；对话历史可弃，损坏降级零负担 */
    suspend fun bootstrap() {
        val outcome = withContext(Dispatchers.IO) { store.load() }
        _loadState.value = when (outcome.issue) {
            LoadIssue.None -> LoadState.Ready
            LoadIssue.RestoredFromBackup -> LoadState.RestoredFromBackup
            LoadIssue.ResetAfterCorruption -> LoadState.ResetAfterCorruption
        }
        val file = when (outcome) {
            is LoadOutcome.Loaded<AiChatFile> -> outcome.file
            is LoadOutcome.Fresh<AiChatFile> -> outcome.file
        }
        // v1 顶层 messages 包装成一场对话；活跃指针悬空（手工改档等）回落「最近更新」
        val (normalized, migrated) = migrateFile(file)
        _conversations.value = normalized.conversations
        _activeConversationId.value = normalized.activeConversationId
            ?.takeIf { id -> normalized.conversations.any { it.id == id } }
            ?: normalized.conversations.maxByOrNull { it.updatedAtMillis }?.id
        _messages.value = activeConversation()?.messages ?: emptyList()
        // 错误随对话落盘：重新装载后按活跃对话镜像恢复（退出浮层 / 重启后错误条仍在）
        mirrorLastError()
        // 孤儿图片清理：persist 截最旧/装载降级后，历史已不引用的图片副本不再留着；
        // 宽限期豁免「已导入未发送」的待发送图片（它们还没有消息引用）
        withContext(Dispatchers.IO) {
            val referenced = _conversations.value
                .flatMap { conversation -> conversation.messages.flatMap { it.images } }
            imageStore.deleteExcept(referenced.toHashSet(), minAgeMillis = ORPHAN_IMAGE_GRACE_MILLIS)
        }
        if (migrated) persist()
        refreshConfigured()
    }

    /**
     * v1 → v2 迁移：旧信封的顶层 messages 包成一场对话（标题按命名规则取首条用户
     * 消息截断），activeConversationId 指向它。v2 信封与空库原样返回（不动）。
     * 迁移是幂等的：重写落盘失败时下次装载会再次走到这里。
     */
    private fun migrateFile(file: AiChatFile): Pair<AiChatFile, Boolean> {
        if (file.conversations.isNotEmpty() || file.messages.isEmpty()) return file to false
        val conversation = AiConversation(
            id = java.util.UUID.randomUUID().toString(),
            title = deriveTitle(
                file.messages.firstOrNull { it.role == AiChatRole.User }?.content.orEmpty(),
            ).orEmpty(),
            createdAtMillis = file.messages.firstOrNull()?.createdAtMillis ?: 0L,
            updatedAtMillis = file.messages.lastOrNull()?.createdAtMillis ?: 0L,
            messages = file.messages,
        )
        return AiChatFile(
            formatVersion = AI_CHAT_FORMAT_VERSION,
            conversations = listOf(conversation),
            activeConversationId = conversation.id,
        ) to true
    }

    /**
     * 读凭据：SharedPreferences 读与 Keystore 解密都是阻塞 I/O，调用方可能在 Main
     * （对话浮层直接触发 send）——统一经 Dispatchers.IO，且一次读齐两项。
     */
    private suspend fun loadCredentials(): Pair<AiConnection, String?> =
        withContext(Dispatchers.IO) {
            val credentials = settings.loadConnection() to settings.loadApiKey()
            _secureAvailable.value = settings.secureAvailable
            credentials
        }

    /** 设置页保存/清除配置后调用；发送前也兜底刷新一次 */
    suspend fun refreshConfigured() {
        val snapshot = withContext(Dispatchers.IO) {
            CredentialsSnapshot(
                profiles = settings.loadProfiles(),
                activeProfileId = settings.loadActiveProfileId(),
                connection = settings.loadConnection(),
                apiKey = settings.loadApiKey(),
            ).also { _secureAvailable.value = settings.secureAvailable }
        }
        _profiles.value = snapshot.profiles
        _activeProfileId.value = snapshot.activeProfileId
        _activeConnection.value = snapshot.connection
        _configured.value = snapshot.connection.isConfigured && snapshot.apiKey != null
    }

    private class CredentialsSnapshot(
        val profiles: List<AiProfile>,
        val activeProfileId: String?,
        val connection: AiConnection,
        val apiKey: String?,
    )

    // ---------- 对话 ----------

    /**
     * UI 发送入口：Job 归仓库所有（不随浮层卸载/旋转取消），返回 null 表示已有
     * 发送在进行。异常不外抛——分类错误经 [lastError] 发布，停止走 [cancelSending]。
     *
     * @param images 随消息发送的图片附件（[AiImageStore] 目录内的文件名，UI 已导入完毕）
     */
    fun sendInBackground(text: String, images: List<String> = emptyList()): Job? =
        launchSend { send(text, images) }

    /** 同 [sendInBackground]，重试最后一场失败的对话 */
    fun retryInBackground(): Job? = launchSend { retryLast() }

    /** 停止生成：取消后台 Job；半截文本由 [send] 的取消路径标记并落盘 */
    fun cancelSending() {
        activeSend.get()?.cancel()
    }

    /**
     * 后台发送的统一收口：Job 的异常绝不能漏到 [scope]（SupervisorJob 下会进全局
     * 异常处理器，Android 上表现为崩溃）；发送自身的分类错误已在内部发布。
     */
    private fun launchSend(block: suspend () -> Unit): Job? {
        if (!sendGate.compareAndSet(false, true)) return null
        val job = scope.launch {
            try {
                block()
            } catch (_: CancellationException) {
                // 停止：半截已由 send/retryLast 标记 interrupted 并（NonCancellable）落盘
            } catch (e: Exception) {
                // 非分类的意外异常：归到本次发送绑定的对话（尚未绑定则不挂到任何对话）
                storeLastError(AiException(AiError.BadResponse, detail = e.message), activeSendConversationId.get())
                // 错误随对话落盘：退出浮层 / 重启后仍能看到失败提示
                persist()
            }
        }
        activeSend.set(job)
        // 闸门只在完成回调里放行：任务「开跑前就被取消」时协程体不会执行，
        // 放在 finally 会把闸门永久卡死；完成回调对取消同样会触发
        job.invokeOnCompletion {
            activeSend.compareAndSet(job, null)
            activeSendConversationId.set(null)
            sendGate.set(false)
        }
        return job
    }

    /**
     * 发送一条用户消息并流式接收回复。
     *
     * 未配置（连接或 Key 缺失）时刷新 [configured] 后静默返回：UI 据此渲染引导态。
     * 流式过程中的分类错误发布到 [lastError]，不抛出。
     *
     * 本轮发送在锁内绑定一场对话：无活跃对话（删光后首次发言）时隐式新建，未命名
     * 对话借首条用户消息自动命名；流式增量按绑定的 id 定向落库——即使用户中途切换
     * 对话，回复仍完整落在发起时的对话里，不打扰当前显示。
     *
     * 允许「有图无文本」（图片即提问）：正文与图片至少其一非空；纯图消息的对话名
     * 借不到文本，保持未命名（UI 回落「新对话」）。
     *
     * @param images 随消息发送的图片附件（[AiImageStore] 目录内的文件名，UI 已导入完毕）
     * @throws CancellationException 调用方取消（半截文本已标记 interrupted 并落盘）
     */
    suspend fun send(text: String, images: List<String> = emptyList()) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && images.isEmpty()) return
        mutex.withLock {
            val (connection, apiKey) = loadCredentials()
            _configured.value = connection.isConfigured && apiKey != null
            if (!connection.isConfigured || apiKey == null) return

            val conversationId = ensureConversationLocked(trimmed)
            activeSendConversationId.set(conversationId)

            clearLastError()
            _sending.value = true
            try {
                val userMessage = AiChatMessage(
                    role = AiChatRole.User,
                    content = trimmed,
                    createdAtMillis = nowMillis(),
                    images = images,
                    id = newMessageId(),
                )
                // 占位 assistant 消息先入流，onDelta 按位更新它的 content
                val placeholder = AiChatMessage(
                    role = AiChatRole.Assistant,
                    content = "",
                    createdAtMillis = nowMillis(),
                    id = newMessageId(),
                )
                // 历史快照必须在本轮 user/占位入流之前拍：否则请求里会重复本轮 user，
                // 并夹带一条 content="" 的 assistant（部分网关直接回 400）
                val history = boundMessages(conversationId).takeLast(HISTORY_WINDOW)
                appendMessages(conversationId) { it + userMessage + placeholder }
                lastCheckpointAt = nowMillis()

                val full = try {
                    client.streamChat(
                        connection = connection,
                        apiKey = apiKey,
                        turns = buildTurns(history, trimmed, images),
                        onDelta = { chunk ->
                            appendToLast(conversationId, chunk)
                        },
                        onReasoning = { chunk ->
                            appendReasoningToLast(conversationId, chunk)
                        },
                    )
                } catch (e: CancellationException) {
                    // 停止/退出：半截文本保留，标记 interrupted；取消的协程里 withContext
                    // 会直接抛出，落盘必须包 NonCancellable 才能执行完
                    markLastInterrupted(conversationId)
                    withContext(NonCancellable) { persist() }
                    throw e
                } catch (e: AiException) {
                    markLastInterrupted(conversationId)
                    storeLastError(e, conversationId)
                    persist()
                    return
                }

                try {
                    finalizeLast(conversationId, full, connection, apiKey)
                    persist()
                } catch (e: CancellationException) {
                    // 正文已在 finalizeLast 第一步写全：草稿解析（可能含修复请求）阶段被取消
                    // 时，落盘要抢在取消传播前完成，否则重启后整段回复消失
                    withContext(NonCancellable) { persist() }
                    throw e
                }
            } finally {
                _sending.value = false
            }
        }
    }

    /**
     * 重试最后一场失败的对话：移除尾部 interrupted 的 assistant 消息后按原 user
     * 消息重新流式（不重复追加 user 消息）。无可重试状态（没有 interrupted 尾巴）
     * 时静默返回。绑定当前活跃对话——重试入口只存在于正在查看的对话里。
     */
    suspend fun retryLast() {
        mutex.withLock {
            val conversationId = _activeConversationId.value ?: return
            val bound = boundMessages(conversationId)
            if (bound.lastOrNull()?.takeIf { it.role == AiChatRole.Assistant }?.interrupted != true) {
                return
            }
            // 尾部 user 消息整体重发（正文 + 图片附件；图片文件可能已被清理，编码侧跳过）
            val lastUser = bound.lastOrNull { it.role == AiChatRole.User } ?: return
            // 先确认凭据再动消息：未配置时直接返回，不留下「尾巴被摘掉」的坏状态
            val (connection, apiKey) = loadCredentials()
            _configured.value = connection.isConfigured && apiKey != null
            if (!connection.isConfigured || apiKey == null) return
            activeSendConversationId.set(conversationId)
            updateConversation(conversationId) { conversation ->
                conversation.copy(messages = conversation.messages.dropLastWhile { it.role == AiChatRole.Assistant })
            }
            // 尾部那条 user 就是本次要重发的：留在 history 里会与下面的重发重复
            val history = boundMessages(conversationId).dropLast(1).takeLast(HISTORY_WINDOW)
            clearLastError()
            _sending.value = true
            try {
                val placeholder = AiChatMessage(
                    role = AiChatRole.Assistant,
                    content = "",
                    createdAtMillis = nowMillis(),
                    id = newMessageId(),
                )
                appendMessages(conversationId) { it + placeholder }
                lastCheckpointAt = nowMillis()
                val full = try {
                    client.streamChat(
                        connection = connection,
                        apiKey = apiKey,
                        turns = buildTurns(history, lastUser.content, lastUser.images),
                        onDelta = { chunk -> appendToLast(conversationId, chunk) },
                        onReasoning = { chunk -> appendReasoningToLast(conversationId, chunk) },
                    )
                } catch (e: CancellationException) {
                    // 取消路径的落盘同样包 NonCancellable（见 send 的同款注释）
                    markLastInterrupted(conversationId)
                    withContext(NonCancellable) { persist() }
                    throw e
                } catch (e: AiException) {
                    markLastInterrupted(conversationId)
                    storeLastError(e, conversationId)
                    persist()
                    return
                }
                try {
                    finalizeLast(conversationId, full, connection, apiKey)
                    persist()
                } catch (e: CancellationException) {
                    // 同 send：正文已完整时落盘不能被取消吞掉
                    withContext(NonCancellable) { persist() }
                    throw e
                }
            } finally {
                _sending.value = false
            }
        }
    }

    /**
     * AI 识别导入：把 [imageName]（[imageStore] 里的临时图片）交给当前配置的模型，
     * 按 [AiScheduleImportPrompt] 的契约解析成可导入课表。
     *
     * 独立于对话：不读写 [messages] / [conversations]、不解析待办、不落盘；取消原样抛出
     * [CancellationException]（调用方取消协程即中断请求）。通道失败 / 无 JSON / 空课表 /
     * 校验失败分别用 [AiScheduleRecognition] 的分支表达，UI 据此显示错误条。
     *
     * 图片必须先经 [imageStore] 的 `import` 落盘（UI 层做），这里只按文件名编码。
     */
    suspend fun recognizeSchedule(imageName: String): AiScheduleRecognition {
        val (connection, apiKey) = loadCredentials()
        _configured.value = connection.isConfigured && apiKey != null
        if (!connection.isConfigured || apiKey == null) {
            return AiScheduleRecognition.NotConfigured
        }

        val image = withContext(Dispatchers.IO) { imageStore.encode(imageName) }
            ?: return AiScheduleRecognition.ImageUnavailable

        val reply = try {
            client.streamChat(
                connection = connection,
                apiKey = apiKey,
                turns = listOf(
                    AiChatTurn(role = "system", content = AiScheduleImportPrompt.SYSTEM),
                    AiChatTurn(
                        role = "user",
                        content = AiScheduleImportPrompt.USER,
                        images = listOf(image),
                    ),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            return AiScheduleRecognition.AiFailed(e.error)
        }

        return when (val parsed = AiScheduleImportCodec.parse(reply)) {
            null -> AiScheduleRecognition.NoJson
            is ReadResult.Ok -> if (parsed.value.courses.isEmpty()) {
                AiScheduleRecognition.NoCourses
            } else {
                AiScheduleRecognition.Ok(parsed.value)
            }
            is ReadResult.Err -> AiScheduleRecognition.Invalid(parsed.error)
        }
    }

    /**
     * 清空对话历史（设置页「清空对话历史」）：删除全部对话（多对话模型下回到无对话
     * 状态，活跃指针置空）；落盘失败不回滚内存（历史可弃）。
     * 走 [persist] 而非直接 store.save：所有落盘在 [persistMutex] 上串行——在途
     * checkpoint 的旧快照不会晚于清空落盘，把刚清空的旧历史写回磁盘。
     */
    suspend fun clearHistory() {
        mutex.withLock {
            val staleImages = _conversations.value
                .flatMap { conversation -> conversation.messages.flatMap { it.images } }
            _conversations.value = emptyList()
            _activeConversationId.value = null
            _messages.value = emptyList()
            clearLastError()
            // 用户已两次确认：落盘不能被浮层关闭取消，否则重启后旧历史回归
            withContext(NonCancellable) { persist() }
            withContext(Dispatchers.IO) {
                staleImages.forEach { imageStore.delete(it) }
            }
        }
    }

    // ---------- 对话管理（「所有对话」浮层经此读写） ----------

    /**
     * 新建空对话并置为活跃（「所有对话」浮层的「新建」）：不命名，发首条消息时自动
     * 命名。返回新对话 id；活跃对话已是空对话时直接复用（连点「新建」不堆积空对话）。
     *
     * 刻意不取 [mutex]：与在途发送互斥会让「新建」卡到流式结束。安全性由定向写入
     * 保证——在途流按发起时绑定的对话 id 落消息（见 [updateConversation]），新建只是
     * 追加一个成员并移动活跃指针，互不干扰。
     */
    suspend fun createConversation(): String {
        activeConversation()?.takeIf { it.messages.isEmpty() }?.let { return it.id }
        val conversation = AiConversation(
            id = java.util.UUID.randomUUID().toString(),
            createdAtMillis = nowMillis(),
            updatedAtMillis = nowMillis(),
        )
        _conversations.update { it + conversation }
        _activeConversationId.value = conversation.id
        _messages.value = emptyList()
        mirrorLastError()
        persist()
        return conversation.id
    }

    /**
     * 切换活跃对话（「所有对话」浮层点卡片）：镜像切换 [messages]，错误条随归属
     * 对话显隐（[mirrorLastError]）。id 不存在或已是活跃对话时静默返回。并发安全与
     * [createConversation] 同理——不取 [mutex]，在途流按绑定 id 落库，切换只影响
     * 镜像与活跃指针。
     */
    suspend fun switchConversation(id: String) {
        if (id == _activeConversationId.value) return
        val target = _conversations.value.firstOrNull { it.id == id } ?: return
        _activeConversationId.value = target.id
        _messages.value = target.messages
        mirrorLastError()
        persist()
    }

    /**
     * 删除对话（「所有对话」浮层删除确认后）；id 不存在时静默返回。消息引用的图片
     * 副本一并删除（I/O 失败留下孤儿文件，bootstrap 装载时兜底清理）。
     * 在途发送绑定该对话时先 [cancelSending]（对话已删，半截回复不再有意义，流式
     * 增量随定向写入的目标消失而自然丢弃）；删除活跃对话时回落「最近更新」的剩余
     * 对话，删光后活跃置空——对话浮层走空态，入口按钮点击无反应，再次发言隐式新建。
     * 并发安全与 [createConversation] 同理。
     */
    suspend fun deleteConversation(id: String) {
        if (_conversations.value.none { it.id == id }) return
        if (activeSendConversationId.get() == id) cancelSending()
        val staleImages = _conversations.value
            .firstOrNull { it.id == id }
            ?.messages
            ?.flatMap { it.images }
            .orEmpty()
        _conversations.update { list -> list.filterNot { it.id == id } }
        if (_activeConversationId.value == id) {
            _activeConversationId.value = _conversations.value
                .maxByOrNull { it.updatedAtMillis }?.id
            _messages.value = activeConversation()?.messages ?: emptyList()
        }
        mirrorLastError()
        persist()
        // 图片副本的删除不回滚内存：I/O 失败留下孤儿文件，bootstrap 装载时兜底清理
        withContext(Dispatchers.IO) {
            staleImages.forEach { imageStore.delete(it) }
        }
    }

    /** 关闭错误条（UI 的「知道了」）：清空并落盘，退出/重启后不再出现；下次失败会重新发布 */
    fun dismissError() {
        clearLastError()
        scope.launch { persist() }
    }

    // ---------- 配置方案（AI 设置页与方案浮层经此读写，store 不直接出包） ----------

    /** 「自定义」槽位的连接与 Key 存在性（打开方案浮层时读取） */
    suspend fun loadCustomConfig(): Pair<AiConnection, Boolean> = withContext(Dispatchers.IO) {
        val connection = settings.loadCustomConnection()
        val hasKey = settings.loadCustomKey() != null
        _secureAvailable.value = settings.secureAvailable
        connection to hasKey
    }

    /** 指定方案是否已有可解密的 Key（解密失败与未配置同形返回 false） */
    suspend fun loadProfileKeyPresent(id: String): Boolean = withContext(Dispatchers.IO) {
        val present = settings.loadProfileKey(id) != null
        _secureAvailable.value = settings.secureAvailable
        present
    }

    /**
     * 保存为新方案：只入库，**不激活**（当前生效配置不变，需在浮层「确认」后才切换）。
     * [apiKey] 非空时一并加密保存；名称为空返回 null。
     */
    suspend fun createProfile(
        name: String,
        connection: AiConnection,
        apiKey: String?,
    ): AiProfile? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val profile = AiProfile(
            id = java.util.UUID.randomUUID().toString(),
            name = trimmed,
            connection = connection,
        )
        val saved = withContext(Dispatchers.IO) {
            val profileSaved = settings.saveProfile(profile, activate = false)
            // Key 落盘失败不回滚方案：方案仍在库中，补填 Key 即可，失败原因由 secureAvailable 呈现
            if (profileSaved && apiKey != null) {
                settings.saveProfileKey(profile.id, apiKey)
            }
            _secureAvailable.value = settings.secureAvailable
            profileSaved
        }
        if (saved) refreshConfigured()
        return profile.takeIf { saved }
    }

    /** 保存并激活已存方案：覆盖连接、按需覆盖 Key；Key 缺失按未配置返回 */
    suspend fun updateProfile(
        id: String,
        connection: AiConnection,
        apiKey: String?,
    ): AiConfigSaveOutcome {
        val target = withContext(Dispatchers.IO) {
            settings.loadProfiles().firstOrNull { it.id == id }
        } ?: return AiConfigSaveOutcome(configSaved = false, keySaved = false)
        val outcome = withContext(Dispatchers.IO) {
            val configSaved = settings.saveProfile(
                target.copy(connection = connection),
                activate = true,
            )
            val keySaved = if (apiKey != null) {
                settings.saveProfileKey(id, apiKey)
            } else {
                settings.loadProfileKey(id) != null
            }
            _secureAvailable.value = settings.secureAvailable
            AiConfigSaveOutcome(configSaved = configSaved, keySaved = keySaved)
        }
        if (outcome.configSaved) refreshConfigured()
        return outcome
    }

    /** 保存并激活「自定义」槽位（活动指针置空）；Key 缺失按未配置返回 */
    suspend fun saveCustom(
        connection: AiConnection,
        apiKey: String?,
    ): AiConfigSaveOutcome {
        val outcome = withContext(Dispatchers.IO) {
            val configSaved = settings.saveCustomConnection(connection)
            val keySaved = if (apiKey != null) {
                settings.saveCustomKey(apiKey)
            } else {
                settings.loadCustomKey() != null
            }
            _secureAvailable.value = settings.secureAvailable
            AiConfigSaveOutcome(configSaved = configSaved, keySaved = keySaved)
        }
        if (outcome.configSaved) refreshConfigured()
        return outcome
    }

    /** 只改方案名（即时落库，不影响活动配置） */
    suspend fun renameProfile(id: String, name: String): Boolean {
        val renamed = withContext(Dispatchers.IO) { settings.renameProfile(id, name) }
        if (renamed) refreshConfigured()
        return renamed
    }

    /** 删除方案及其 Key；删的是活动方案时活动配置回落「自定义」 */
    suspend fun deleteProfile(id: String): Boolean {
        val deleted = withContext(Dispatchers.IO) { settings.deleteProfile(id) }
        if (deleted) refreshConfigured()
        return deleted
    }

    // ---------- 待确认的修改/删除 ----------

    /**
     * UI「应用变更」入口：Job 归仓库自有 scope（收起浮层/旋转不中断），同一时刻只处理
     * 一批。待办写入在**应用时**基于最新状态重新套补丁——确认前用户可能在别处改过
     * 同一待办，未涉及字段不会被旧快照覆盖。
     */
    fun applyTodoChangesInBackground(messageId: String) {
        if (messageId.isBlank()) return
        if (!applyChangesGate.compareAndSet(false, true)) return
        _todoChangesApplyingMessageId.value = messageId
        scope.launch {
            try {
                applyTodoChanges(messageId)
            } catch (_: CancellationException) {
                // 仓库 scope 不主动取消；防御 apply 内部的可取消挂起点
            } catch (_: Exception) {
                // 意外异常不能让后台 Job 崩掉进程；已写入的条目保留状态，未写入的仍为 Pending
            } finally {
                _todoChangesApplyingMessageId.value = null
                applyChangesGate.set(false)
            }
        }
    }

    /** UI「忽略」入口：只把待确认标记为已忽略（不触碰待办库），并落盘 */
    fun dismissTodoChanges(messageId: String) {
        val located = findMessage(messageId) ?: return
        val (conversationId, message) = located
        if (message.todoChanges.none { it.status == AiTodoChangeStatus.Pending }) return
        updateMessageById(conversationId, messageId) { current ->
            current.copy(
                todoChanges = current.todoChanges.map { change ->
                    if (change.status == AiTodoChangeStatus.Pending) {
                        change.copy(status = AiTodoChangeStatus.Dismissed)
                    } else {
                        change
                    }
                },
            )
        }
        scope.launch {
            try {
                persist()
            } catch (_: Exception) {
                // 忽略是纯 UI 状态：落盘失败只影响下次装载，不打扰用户
            }
        }
    }

    /**
     * 应用一批待确认变更：修改先于删除执行；逐条记录 Applied/Failed 并落盘。
     *
     * - 修改：按 id 取**当前**待办 → 重新套补丁 → 校验 → [changeWriter] 写入；
     * - 删除：目标已不存在的记 NotFound，其余一次性批量软删除；
     * - 消息可能属于非活跃对话（用户中途切走），按消息 id 定位后定向写回。
     */
    private suspend fun applyTodoChanges(messageId: String) {
        val (conversationId, message) = findMessage(messageId) ?: return
        val pending = message.todoChanges.filter { it.status == AiTodoChangeStatus.Pending }
        if (pending.isEmpty()) return

        val context = contextProvider()
        // 工作快照：同一待办可能有多条修改，逐条写入后把新值回填，后一条基于前一条的结果
        // 继续套补丁（否则第二条会拿旧快照覆盖第一条的字段）
        val workingTodos = context.todos.associateBy { it.id }.toMutableMap()
        val results = pending.toMutableList()

        // 修改先行：同一目标同时出现在 updates 与 deletes 时，后续删除仍按最新状态执行
        for (index in pending.indices) {
            val change = pending[index]
            if (change.kind == AiTodoChangeKind.Update) {
                results[index] = applyUpdate(change, workingTodos, context)
            }
        }

        // 删除：目标不存在的不送去批量删除（避免把「目标已消失」记成删除成功）
        val deleteIndices = pending.indices.filter {
            pending[it].kind == AiTodoChangeKind.Delete
        }
        val existingDeleteIndices = deleteIndices.filter {
            workingTodos.containsKey(pending[it].todoId)
        }
        for (index in deleteIndices - existingDeleteIndices.toSet()) {
            results[index] = pending[index].copy(
                status = AiTodoChangeStatus.Failed,
                failure = AiTodoChangeFailure.NotFound,
            )
        }
        val deleteIds = existingDeleteIndices.map { pending[it].todoId }.distinct()
        if (deleteIds.isNotEmpty()) {
            val ok = try {
                changeWriter.deleteAll(deleteIds)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            for (index in existingDeleteIndices) {
                results[index] = pending[index].copy(
                    status = if (ok) AiTodoChangeStatus.Applied else AiTodoChangeStatus.Failed,
                    failure = if (ok) AiTodoChangeFailure.None else AiTodoChangeFailure.SaveFailed,
                )
            }
        }

        // pending 是 todoChanges 里 Pending 的有序子集：按顺序把结果回填到原列表
        var cursor = 0
        val merged = message.todoChanges.map { change ->
            if (change.status == AiTodoChangeStatus.Pending) results[cursor++] else change
        }
        updateMessageById(conversationId, messageId) { it.copy(todoChanges = merged) }
        persist()
    }

    /** 单条修改：目标/补丁/校验任一不成立都标记失败，不写库；无实际变化视为已应用 */
    private suspend fun applyUpdate(
        change: AiTodoChange,
        todosById: MutableMap<String, Todo>,
        context: AiChatContext,
    ): AiTodoChange {
        val current = todosById[change.todoId]
            ?: return change.copy(status = AiTodoChangeStatus.Failed, failure = AiTodoChangeFailure.NotFound)
        val patch = change.patch
            ?: return change.copy(status = AiTodoChangeStatus.Failed, failure = AiTodoChangeFailure.InvalidPatch)
        val mapping = AiTodoPatchMapper.apply(current, patch, context.groups, context.tags)
        val next = mapping.todo
            ?: return change.copy(status = AiTodoChangeStatus.Failed, failure = AiTodoChangeFailure.InvalidPatch)
        if (!mapping.changed) return change.copy(status = AiTodoChangeStatus.Applied)
        val errors = TodoValidator.validate(next)
        if (errors.isNotEmpty()) {
            return change.copy(status = AiTodoChangeStatus.Failed, failure = AiTodoChangeFailure.InvalidPatch)
        }
        val ok = try {
            changeWriter.update(next)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        return if (ok) {
            // 写成功才回填：同一待办的下一条修改基于最新值继续
            todosById[change.todoId] = next
            change.copy(status = AiTodoChangeStatus.Applied)
        } else {
            change.copy(status = AiTodoChangeStatus.Failed, failure = AiTodoChangeFailure.SaveFailed)
        }
    }

    /** 按消息 id 在所有对话中定位（用户可能已切走）；返回对话 id + 当前消息快照 */
    private fun findMessage(messageId: String): Pair<String, AiChatMessage>? {
        if (messageId.isBlank()) return null
        for (conversation in _conversations.value) {
            val message = conversation.messages.firstOrNull { it.id == messageId }
            if (message != null) return conversation.id to message
        }
        return null
    }

    // ---------- 定向写入（多对话内核：一切消息变更按对话 id 落位） ----------

    /** 当前活跃对话（同步读取，配合 [_activeConversationId] 使用） */
    private fun activeConversation(): AiConversation? =
        _conversations.value.firstOrNull { it.id == _activeConversationId.value }

    /** 指定对话当前的消息快照（send/retry 的历史窗口读取用） */
    private fun boundMessages(conversationId: String): List<AiChatMessage> =
        _conversations.value.firstOrNull { it.id == conversationId }?.messages ?: emptyList()

    /**
     * 定向更新一场对话并同步 [messages] 镜像：目标是活跃对话时镜像随之刷新（发送中
     * 逐字可见），非活跃目标（后台在途流）只落库、不打扰当前显示；目标已被删除时
     * 整体静默跳过——「边发边删」的流式增量与收尾标记自然消失。
     *
     * 并发：`MutableStateFlow.update` 的 CAS 循环保证跨线程互不覆盖（流式回调线程与
     * 锁内协程都会走到这里，故管理操作不需要与 [mutex] 互斥）。
     */
    private fun updateConversation(id: String, update: (AiConversation) -> AiConversation) {
        _conversations.update { list -> list.map { if (it.id == id) update(it) else it } }
        if (id == _activeConversationId.value) {
            _messages.value = activeConversation()?.messages ?: emptyList()
        }
    }

    /** 向指定对话追加消息并刷新 [AiConversation.updatedAtMillis]（最近排序 / 回落依据） */
    private fun appendMessages(
        conversationId: String,
        transform: (List<AiChatMessage>) -> List<AiChatMessage>,
    ) {
        updateConversation(conversationId) { conversation ->
            conversation.copy(
                messages = transform(conversation.messages),
                updatedAtMillis = nowMillis(),
            )
        }
    }

    /** 替换指定对话的尾部 assistant 消息（流式增量 / finalize / interrupted 共用） */
    private fun updateLastMessage(conversationId: String, transform: (AiChatMessage) -> AiChatMessage) {
        updateConversation(conversationId) { conversation ->
            val list = conversation.messages
            val last = list.lastOrNull() ?: return@updateConversation conversation
            if (last.role != AiChatRole.Assistant) return@updateConversation conversation
            conversation.copy(
                messages = list.toMutableList().also { it[it.lastIndex] = transform(last) },
                updatedAtMillis = nowMillis(),
            )
        }
    }

    /**
     * 按消息 id 定向替换一条消息（确认卡状态回写用）：消息所在的对话可能已不是活跃
     * 对话，定向写保证不打扰当前显示。刻意**不刷新** [AiConversation.updatedAtMillis]
     * ——确认/忽略是一次历史消息上的操作，不该把旧对话顶到「最近」最前面。
     */
    private fun updateMessageById(
        conversationId: String,
        messageId: String,
        transform: (AiChatMessage) -> AiChatMessage,
    ) {
        updateConversation(conversationId) { conversation ->
            val index = conversation.messages.indexOfFirst { it.id == messageId }
            if (index < 0) return@updateConversation conversation
            conversation.copy(
                messages = conversation.messages.toMutableList().also {
                    it[index] = transform(it[index])
                },
            )
        }
    }

    /**
     * 锁内：确保存在活跃对话，返回其 id。无活跃对话（删光后首次发言）时隐式新建；
     * 未命名对话借本轮首条用户消息自动命名（迁移进来的历史对话已在 bootstrap 命名）。
     */
    private fun ensureConversationLocked(firstUserText: String): String {
        val existing = activeConversation()
        if (existing != null) {
            if (existing.title.isBlank()) {
                deriveTitle(firstUserText)?.let { title ->
                    updateConversation(existing.id) { it.copy(title = title) }
                }
            }
            return existing.id
        }
        val conversation = AiConversation(
            id = java.util.UUID.randomUUID().toString(),
            title = deriveTitle(firstUserText).orEmpty(),
            createdAtMillis = nowMillis(),
            updatedAtMillis = nowMillis(),
        )
        _conversations.update { it + conversation }
        _activeConversationId.value = conversation.id
        _messages.value = emptyList()
        return conversation.id
    }

    /** 首条用户消息 → 对话名：压平换行取开头截断；空白返回 null（保持未命名） */
    private fun deriveTitle(text: String): String? {
        val flattened = text.replace('\n', ' ').trim()
        if (flattened.isEmpty()) return null
        return flattened.take(TITLE_MAX_LENGTH)
    }

    /** 新消息标识：确认卡的应用/忽略按它定位消息（旧文件里的消息没有 id） */
    private fun newMessageId(): String = java.util.UUID.randomUUID().toString()

    // ---------- 待办落库 ----------

    /**
     * 本次请求的消息序列：system prompt（上下文快照）+ 历史窗口快照（不含本轮
     * user）+ 本轮 user 消息。历史里的 assistant 正文原样回放（含已创建待办的
     * JSON 块，给模型连续上下文）；空 assistant（取消/失败残留的占位）不发送——
     * 对任何服务商都是噪声，个别网关会因 `content=""` 直接回 400。
     *
     * 图片附件随消息原样回放（历史窗口内的 user 消息与本轮都带，追问时模型仍看得到图）；
     * 文件可能已被清理（删除对话/清空历史只清无引用文件，但极端时序下仍有竞争），
     * 编码失败的图静默跳过，不阻断整条请求。编码涉及磁盘读取，在 IO 线程执行。
     */
    private suspend fun buildTurns(
        history: List<AiChatMessage>,
        userText: String,
        userImages: List<String>,
    ): List<AiChatTurn> {
        val context = contextProvider()
        val system = AiPromptBuilder.build(
            now = clock.today(),
            schedule = context.schedule,
            currentWeek = context.currentWeek,
            todos = context.todos,
            groups = context.groups,
            tags = context.tags,
        )
        val turns = history
            .filterNot { it.role == AiChatRole.Assistant && it.content.isBlank() }
            .map { it.toTurn() }
        return listOf(AiChatTurn(role = "system", content = system)) + turns +
            AiChatTurn(role = "user", content = userText, images = encodeImages(userImages))
    }

    /** 持久化消息 → wire 消息：role 落小写串，图片文件在 IO 线程编码 */
    private suspend fun AiChatMessage.toTurn(): AiChatTurn = AiChatTurn(
        role = role.name.lowercase(),
        content = content,
        images = encodeImages(images),
    )

    /** 图片文件名 → base64 附件：文件缺失/读取失败跳过（见 [AiImageStore.encode]） */
    private suspend fun encodeImages(names: List<String>): List<AiTurnImage> =
        withContext(Dispatchers.IO) {
            names.mapNotNull { imageStore.encode(it) }
        }

    /** 流式增量追加到绑定对话的尾部 assistant 消息（UI 逐字渲染） */
    private fun appendToLast(conversationId: String, chunk: String) {
        updateLastMessage(conversationId) { it.copy(content = it.content + chunk) }
        maybeCheckpoint()
    }

    /**
     * 思考增量追加到绑定对话尾部 assistant 消息的 [AiChatMessage.reasoning]：与正文
     * 分列，UI 在气泡上方的折叠块里展示；同样参与 checkpoint 节流（长思考也要能恢复）。
     */
    private fun appendReasoningToLast(conversationId: String, chunk: String) {
        updateLastMessage(conversationId) { it.copy(reasoning = it.reasoning + chunk) }
        maybeCheckpoint()
    }

    /**
     * 长流中途落盘（节流到 [CHECKPOINT_INTERVAL_MILLIS]）：进程被杀时至少恢复已收到的
     * 部分。运行在流式回调线程，异步交给仓库 scope，不阻塞读循环。
     */
    private fun maybeCheckpoint() {
        val now = nowMillis()
        if (now - lastCheckpointAt < CHECKPOINT_INTERVAL_MILLIS) return
        lastCheckpointAt = now
        scope.launch {
            try {
                persist()
            } catch (_: CancellationException) {
                // 仓库 scope 不主动取消任务；这里只防御 persist 内部的可取消挂起点
            } catch (_: Exception) {
                // checkpoint 是尽力而为：失败由最终落盘兜底，不能让后台 Job 崩掉进程
            }
        }
    }

    /**
     * 流结束（成功）：先把完整正文写进消息（取消也不会丢），再解析动作块。
     * 新建草稿**直接落库**（现状不变）；完成/取消完成**直接执行**（UI 可撤销）；
     * 修改/删除方案只校验并挂成待确认变更，等用户在确认卡上点「应用变更」。草稿解析
     * 失败会发起一次修复重试（同连接复用契约），该阶段被取消时正文已成立。
     *
     * 落库顺序：映射 → 过滤缺名称行 → 批量 addTodos（单次提交）→ 成功创建的 id 写回
     * 消息。落库失败只标记 [AiChatMessage.todoSaveFailed]，不影响正文与历史。
     * 只有完整结束的回复才会生成待确认变更（半截回复不产生）。
     */
    private suspend fun finalizeLast(
        conversationId: String,
        full: String,
        connection: AiConnection,
        apiKey: String,
    ) {
        updateLastMessage(conversationId) { it.copy(content = full, interrupted = false) }
        val payload = extractActions(full, connection, apiKey) ?: return
        // 只有 updates/deletes/completes/reopens 时不能走 addTodos（空批次会误报创建失败）
        val created = if (payload.todos.isNotEmpty()) {
            createTodos(payload)
        } else {
            CreateTodosOutcome(emptyList(), failed = false)
        }
        // 完成/取消完成直接落库（可撤销）；修改/删除只生成待确认变更
        val completions = applyCompletions(payload)
        val (changes, dropped) = buildTodoChanges(payload)
        updateLastMessage(conversationId) { last ->
            last.copy(
                todoIds = created.ids,
                todoSaveFailed = created.failed,
                todoChanges = changes,
                todoChangeDropped = dropped,
                todoCompletions = completions,
            )
        }
    }

    /** 一次落库的结果：成功创建的 id + 是否失败（草稿非空却零落库 / 写库异常） */
    private data class CreateTodosOutcome(val ids: List<String>, val failed: Boolean)

    /**
     * 草稿 → Todo → 落库。缺名称的行在此被滤掉：`addTodos` 全量校验、任意一条非法即
     * 整批零落盘，与用户确认相比这里没有「改完再存」的机会，只能保证批次本身合法。
     * 取消原样抛出（调用方的落盘不能被跳过）；其余异常按落库失败处理。
     */
    private suspend fun createTodos(payload: AiTodoDraftPayload): CreateTodosOutcome {
        val context = contextProvider()
        val mappings = AiTodoMapper.map(payload.todos, context.groups, context.tags)
        val todos = mappings.filter { it.canSave }.map { it.todo }
        if (todos.isEmpty()) return CreateTodosOutcome(emptyList(), failed = true)
        val saved = try {
            persister.addAll(todos)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        return if (saved) {
            CreateTodosOutcome(todos.map { it.id }, failed = false)
        } else {
            CreateTodosOutcome(emptyList(), failed = true)
        }
    }

    /**
     * 校验 updates/deletes 并生成待确认变更。
     *
     * 目标必须存在于当前活跃待办（归档/回收站不可操作）；补丁必须能通过
     * [AiTodoPatchMapper] 与 [TodoValidator]；合法但无实际变化的补丁不生成确认项。
     * 任何一条不成立都丢弃并置 dropped——确认卡只展示真正可应用的变更，避免用户确认后
     * 才发现失败。删除按 id 去重。
     */
    private fun buildTodoChanges(payload: AiTodoDraftPayload): Pair<List<AiTodoChange>, Boolean> {
        val context = contextProvider()
        val todosById = context.todos.associateBy { it.id }
        val changes = mutableListOf<AiTodoChange>()
        var dropped = false

        for (draft in payload.updates) {
            val id = draft.id.trim()
            val existing = todosById[id]
            if (id.isEmpty() || existing == null) {
                dropped = true
                continue
            }
            val mapping = AiTodoPatchMapper.apply(existing, draft.patch, context.groups, context.tags)
            val next = mapping.todo
            if (next == null || !mapping.changed || TodoValidator.validate(next).isNotEmpty()) {
                dropped = true
                continue
            }
            changes += AiTodoChange(
                kind = AiTodoChangeKind.Update,
                todoId = id,
                todoName = existing.name,
                patch = draft.patch,
            )
        }

        val seenDeletes = mutableSetOf<String>()
        for (draft in payload.deletes) {
            val id = draft.id.trim()
            val existing = todosById[id]
            if (id.isEmpty() || existing == null) {
                dropped = true
                continue
            }
            if (!seenDeletes.add(id)) continue
            changes += AiTodoChange(
                kind = AiTodoChangeKind.Delete,
                todoId = id,
                todoName = existing.name,
            )
        }
        return changes to dropped
    }

    /**
     * 执行完成/取消完成（直接落库、不生成确认卡）。
     *
     * 重复待办必须带合法期次日期（按当前 repeat 规则展开校验），否则记
     * Failed(InvalidDate)；普通待办忽略 date。未知 id 记 Failed(NotFound)。
     * 同一 (id, date, completed) 去重，避免模型重复输出产生重复结果行。
     */
    private suspend fun applyCompletions(payload: AiTodoDraftPayload): List<AiTodoCompletion> {
        if (payload.completes.isEmpty() && payload.reopens.isEmpty()) return emptyList()
        val todosById = contextProvider().todos.associateBy { it.id }
        val results = mutableListOf<AiTodoCompletion>()
        val seen = mutableSetOf<Triple<String, String?, Boolean>>()
        for (draft in payload.completes) {
            val key = Triple(draft.id.trim(), draft.date?.trim()?.takeIf { it.isNotEmpty() }, true)
            if (seen.add(key)) results += executeCompletion(draft, completed = true, todosById)
        }
        for (draft in payload.reopens) {
            val key = Triple(draft.id.trim(), draft.date?.trim()?.takeIf { it.isNotEmpty() }, false)
            if (seen.add(key)) results += executeCompletion(draft, completed = false, todosById)
        }
        return results
    }

    private suspend fun executeCompletion(
        draft: AiTodoCompletionDraft,
        completed: Boolean,
        todosById: Map<String, Todo>,
    ): AiTodoCompletion {
        val id = draft.id.trim()
        val requestedDate = parseDate(draft.date)
        val todo = todosById[id]
            ?: return AiTodoCompletion(
                todoId = id,
                completed = completed,
                date = requestedDate,
                status = AiTodoCompletionStatus.Failed,
                failure = AiTodoChangeFailure.NotFound,
            )
        if (todo.repeat.type != RepeatType.None) {
            if (requestedDate == null || !isValidOccurrence(todo, requestedDate)) {
                return AiTodoCompletion(
                    todoId = id,
                    todoName = todo.name,
                    completed = completed,
                    date = requestedDate,
                    status = AiTodoCompletionStatus.Failed,
                    failure = AiTodoChangeFailure.InvalidDate,
                )
            }
            // 已经是目标状态：不写库，也不提供撤销（否则会把用户原有的完成态翻掉）
            if ((requestedDate in todo.completedOccurrences) == completed) {
                return completionResult(todo, completed, requestedDate, changed = false, ok = true)
            }
            val ok = safeWrite { changeWriter.setOccurrenceCompleted(id, requestedDate, completed) }
            return completionResult(todo, completed, requestedDate, changed = true, ok = ok)
        }
        if (todo.completed == completed) {
            return completionResult(todo, completed, date = null, changed = false, ok = true)
        }
        val ok = safeWrite { changeWriter.setCompleted(id, completed) }
        return completionResult(todo, completed, date = null, changed = true, ok = ok)
    }

    private fun completionResult(
        todo: Todo,
        completed: Boolean,
        date: IsoDate?,
        changed: Boolean,
        ok: Boolean,
    ): AiTodoCompletion = AiTodoCompletion(
        todoId = todo.id,
        todoName = todo.name,
        completed = completed,
        date = date,
        changed = changed,
        status = if (ok) AiTodoCompletionStatus.Applied else AiTodoCompletionStatus.Failed,
        failure = if (ok) AiTodoChangeFailure.None else AiTodoChangeFailure.SaveFailed,
    )

    /** 期次日期必须属于当前重复系列（与仓库同一套展开规则，日期档/次数档一并生效） */
    private fun isValidOccurrence(todo: Todo, date: IsoDate): Boolean {
        val anchor = todo.dates.minByOrNull { it.epochDay } ?: return false
        return todo.repeat.occurrencesBetween(anchor, date, date + 1).contains(date)
    }

    private fun parseDate(text: String?): IsoDate? =
        text?.trim()?.takeIf { it.isNotEmpty() }?.let { IsoDate.parseOrNull(it) }

    /** 写待办统一收口：取消原样抛出（落盘不能被跳过），其余异常按失败处理 */
    private suspend inline fun safeWrite(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /**
     * UI「撤销」入口：把一条已执行的完成/取消完成反向写回。Job 归仓库自有 scope。
     */
    fun undoTodoCompletionInBackground(messageId: String, completionIndex: Int) {
        if (messageId.isBlank()) return
        if (!undoCompletionGate.compareAndSet(false, true)) return
        _todoCompletionUndoingMessageId.value = messageId
        scope.launch {
            try {
                undoTodoCompletion(messageId, completionIndex)
            } catch (_: CancellationException) {
                // 仓库 scope 不主动取消；防御内部可取消挂起点
            } catch (_: Exception) {
                // 意外异常不能崩进程；原记录保持「已生效」状态
            } finally {
                _todoCompletionUndoingMessageId.value = null
                undoCompletionGate.set(false)
            }
        }
    }

    /**
     * 撤销一条完成记录：把 completed 取反写回（重复待办按记录里的期次日期）。
     * 目标不存在 → UndoFailed(NotFound)；期次失效 → UndoFailed(InvalidDate)；
     * 写库失败 → UndoFailed(SaveFailed)。原记录仍表示「操作已生效」。
     */
    private suspend fun undoTodoCompletion(messageId: String, completionIndex: Int) {
        val (conversationId, message) = findMessage(messageId) ?: return
        val completion = message.todoCompletions.getOrNull(completionIndex) ?: return
        if (completion.status != AiTodoCompletionStatus.Applied) return
        // 无实际变化的记录本来就没有可撤销的写入
        if (!completion.changed) return
        val todo = contextProvider().todos.firstOrNull { it.id == completion.todoId }
        val next = if (todo == null) {
            completion.copy(
                status = AiTodoCompletionStatus.UndoFailed,
                failure = AiTodoChangeFailure.NotFound,
            )
        } else if (todo.repeat.type != RepeatType.None) {
            val date = completion.date
            if (date == null || !isValidOccurrence(todo, date)) {
                completion.copy(
                    status = AiTodoCompletionStatus.UndoFailed,
                    failure = AiTodoChangeFailure.InvalidDate,
                )
            } else if ((date in todo.completedOccurrences) != !completion.completed) {
                // 已经是撤销后的目标状态（用户可能在别处手动改回）：不重复写库
                completion.copy(
                    status = AiTodoCompletionStatus.Undone,
                    failure = AiTodoChangeFailure.None,
                )
            } else {
                val ok = safeWrite {
                    changeWriter.setOccurrenceCompleted(todo.id, date, !completion.completed)
                }
                completion.copy(
                    status = if (ok) AiTodoCompletionStatus.Undone else AiTodoCompletionStatus.UndoFailed,
                    failure = if (ok) AiTodoChangeFailure.None else AiTodoChangeFailure.SaveFailed,
                )
            }
        } else {
            val target = !completion.completed
            if (todo.completed == target) {
                // 已经是撤销后的目标状态：不重复写库
                completion.copy(
                    status = AiTodoCompletionStatus.Undone,
                    failure = AiTodoChangeFailure.None,
                )
            } else {
                val ok = safeWrite {
                    changeWriter.setCompleted(todo.id, target)
                }
                completion.copy(
                    status = if (ok) AiTodoCompletionStatus.Undone else AiTodoCompletionStatus.UndoFailed,
                    failure = if (ok) AiTodoChangeFailure.None else AiTodoChangeFailure.SaveFailed,
                )
            }
        }
        val updated = message.todoCompletions.toMutableList().also {
            it[completionIndex] = next
        }
        updateMessageById(conversationId, messageId) { it.copy(todoCompletions = updated) }
        persist()
    }

    /**
     * 取消/失败：标记 interrupted。半截回复不创建待办——只认完整结束的回复，
     * 避免用户按下停止时把半截 JSON 写成真实待办。
     */
    private fun markLastInterrupted(conversationId: String) {
        updateLastMessage(conversationId) { it.copy(interrupted = true) }
    }

    /**
     * 从回复文本提取动作信封（无块或空动作 = null）；解析失败带错误自动重试一次
     * （把上次输出原样附回，请模型修正后重新输出），仍失败返回 null（视为纯对话）。
     *
     * 信封可同时包含新建（todos）、修改（updates）与删除（deletes）；后者只生成待确认
     * 变更，不会直接写库。凭据由本次发送一路传入（同一场对话内不会变），省掉每次回复的
     * 重复解密；取消必须原样抛出——否则调用方的落盘会被跳过。
     */
    private suspend fun extractActions(
        reply: String,
        connection: AiConnection,
        apiKey: String,
    ): AiTodoDraftPayload? {
        if (!connection.isConfigured) return null

        val json = AiTodoDraftCodec.extractJsonBlock(reply) ?: return null
        AiTodoDraftCodec.parseTodoDrafts(json)?.let { return it }

        // 修复重试：非流式轻量收口——复用流式通道但整块收，UI 无感（增量丢弃）。
        // system 同时带上新建与修改/删除的契约：修复请求里说「严格遵循格式」必须真的有声明可循。
        val repairPrompt = "你上一条回复里的 JSON 无法解析。请只输出一个修正后的 ```json 代码块，" +
            "严格遵循系统消息给出的格式，不要输出其他内容。"
        val repaired = try {
            client.streamChat(
                connection = connection,
                apiKey = apiKey,
                turns = listOf(
                    AiChatTurn(
                        role = "system",
                        content = "待办动作必须是可解析的 json 代码块。\n" +
                            "新建（立即落库）格式：\n" + AiPromptBuilder.TODO_JSON_SHAPE + "\n" +
                            "修改/删除（等待用户确认）格式：\n" + AiPromptBuilder.TODO_CHANGE_JSON_SHAPE + "\n" +
                            "完成/取消完成（立即生效）格式：\n" + AiPromptBuilder.TODO_COMPLETE_JSON_SHAPE,
                    ),
                    AiChatTurn(role = "user", content = reply.take(REPAIR_CONTEXT_MAX)),
                    AiChatTurn(role = "user", content = repairPrompt),
                ),
            )
        } catch (e: CancellationException) {
            throw e // 取消语义不能吞：吞掉会让「正文已完整」的落盘被跳过
        } catch (_: Exception) {
            null // 修复失败按「无动作」处理
        } ?: return null
        val repairedJson = AiTodoDraftCodec.extractJsonBlock(repaired) ?: return null
        return AiTodoDraftCodec.parseTodoDrafts(repairedJson)
    }

    /**
     * 落盘（阻塞 I/O，经 Dispatchers.IO）；失败只影响下次装载（历史可弃）。
     * [persistMutex] 串行化所有落盘，且快照在锁内取：后台 checkpoint 的旧快照不会
     * 覆盖随后完成的最终落盘。
     */
    private suspend fun persist() {
        withContext(Dispatchers.IO) {
            persistMutex.withLock {
                // 每场对话各自截最旧：上限是单对话语义（一场长对话不会挤掉别的对话）
                val file = AiChatFile(
                    conversations = _conversations.value.map { conversation ->
                        conversation.copy(messages = conversation.messages.takeLast(MAX_PERSISTED_MESSAGES))
                    },
                    activeConversationId = _activeConversationId.value,
                )
                store.save(file)
            }
        }
    }

    companion object {
        /** 发给模型的历史窗口（条数；system prompt 不在此） */
        const val HISTORY_WINDOW = 30

        /** 落盘的消息上限（每场对话各自截最旧，见计划 §4.3） */
        const val MAX_PERSISTED_MESSAGES = 200

        /** 对话名截断长度：取首条用户消息开头自动命名 */
        const val TITLE_MAX_LENGTH = 16

        /** 修复重试时携带的原文上限（防超长回复撑爆重试请求） */
        const val REPAIR_CONTEXT_MAX = 4000

        /** 孤儿图片清理的宽限期：比它新的未引用文件（已导入未发送的待发送图片）不删 */
        const val ORPHAN_IMAGE_GRACE_MILLIS = 24 * 60 * 60 * 1000L

        /** 流式期间半截 checkpoint 的节流间隔（进程被杀时尽量少丢内容） */
        const val CHECKPOINT_INTERVAL_MILLIS = 2000L

        /** 应用侧装配入口（AppContainer）；测试直接构造注入 fake store / fake client / fake writer */
        suspend fun create(
            store: AiChatStore,
            settings: AiSettingsStore,
            client: AiClient,
            contextProvider: () -> AiChatContext,
            persister: AiTodoPersister,
            changeWriter: AiTodoChangeWriter,
            imageStore: AiImageStore,
        ): AiChatRepository =
            AiChatRepository(
                store,
                settings,
                client,
                contextProvider,
                persister,
                changeWriter,
                imageStore,
            ).also { it.bootstrap() }
    }
}
