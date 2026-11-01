package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.Clock
import com.fangyi.classpp.data.LoadState
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.Todo
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
 * AI 对话仓库：内存真源（StateFlow）+ 磁盘持久化（[AiChatStore]），与 TodoRepository
 * 同构；流式增量直接写入消息流（UI 逐字渲染）。
 *
 * **调用编排**（见 .mimocode/docs/AI功能计划.md §6.3）：
 * - [send] 先对历史窗口拍快照 → 追加 user 消息与空的 assistant 占位 → 拼 system
 *   prompt（上下文快照 + 历史快照 + 本轮 user）→ [AiClient.streamChat] 流式 →
 *   完成后解析草稿块挂卡 → 落盘；
 * - 中断/失败保留半截文本（interrupted 标记 + [lastError]），重试走 [retryLast]
 *   （不重复追加 user 消息）；
 * - 发送 Job 归仓库自有的 [scope]（[sendInBackground] / [retryInBackground]）：收起
 *   对话浮层、旋转屏幕都不会中断流；停止 = [cancelSending]，[send] 捕获
 *   CancellationException 后先落盘半截再原样传播——取消语义不吞；
 * - 流过长时按 [CHECKPOINT_INTERVAL_MILLIS] 节流做半截 checkpoint，进程被杀也能
 *   恢复已收到的部分。
 *
 * **草稿确认（红线：AI 不静默写库）**：草稿卡只挂在 assistant 消息上，保存动作由
 * UI 层经 [updateDraftStatus] 回写状态并落盘；真正落库（todoRepository.addTodos）
 * 由 UI 层调用，本仓库不碰待办写路径。
 *
 * 写：Mutex 串行化（同一次对话期间禁止并发 send），流程 = 内存变更 → `store.save`
 * → 成功与否只影响下次装载（对话历史可弃，失败不回滚内存）。
 */
class AiChatRepository internal constructor(
    private val store: AiChatStore,
    private val settings: AiSettingsStore,
    private val client: AiClient,
    /** 上下文快照提供方：AppContainer 装配时读各仓库 StateFlow.value（同步、无 I/O） */
    private val contextProvider: () -> AiChatContext,
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

    /** 上次 checkpoint 时刻（[nowMillis] 口径）；只由流式回调线程写，读侧加 @Volatile */
    @Volatile
    private var lastCheckpointAt = 0L

    private val _messages = MutableStateFlow<List<AiChatMessage>>(emptyList())
    val messages: StateFlow<List<AiChatMessage>> = _messages.asStateFlow()

    private val _loadState = MutableStateFlow(LoadState.Ready)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    /** 发送中（发送键变停止键、输入禁用）；一次只允许一场对话 */
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    /** 最近一次失败的分类错误（UI 错误条 + 重试）；成功发送或用户关闭时清空 */
    private val _lastError = MutableStateFlow<AiException?>(null)
    val lastError: StateFlow<AiException?> = _lastError.asStateFlow()

    /** 配置是否就绪（连接 + Key）；bootstrap 与设置页保存后刷新，UI 据此走引导态 */
    private val _configured = MutableStateFlow(false)
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

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
        _messages.value = file.messages
        refreshConfigured()
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
        val (connection, apiKey) = loadCredentials()
        _configured.value = connection.isConfigured && apiKey != null
    }

    // ---------- 对话 ----------

    /**
     * UI 发送入口：Job 归仓库所有（不随浮层卸载/旋转取消），返回 null 表示已有
     * 发送在进行。异常不外抛——分类错误经 [lastError] 发布，停止走 [cancelSending]。
     */
    fun sendInBackground(text: String): Job? = launchSend { send(text) }

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
                _lastError.value = AiException(AiError.BadResponse, detail = e.message)
            }
        }
        activeSend.set(job)
        // 闸门只在完成回调里放行：任务「开跑前就被取消」时协程体不会执行，
        // 放在 finally 会把闸门永久卡死；完成回调对取消同样会触发
        job.invokeOnCompletion {
            activeSend.compareAndSet(job, null)
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
     * @throws CancellationException 调用方取消（半截文本已标记 interrupted 并落盘）
     */
    suspend fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        mutex.withLock {
            val (connection, apiKey) = loadCredentials()
            _configured.value = connection.isConfigured && apiKey != null
            if (!connection.isConfigured || apiKey == null) return

            _lastError.value = null
            _sending.value = true
            try {
                val userMessage = AiChatMessage(
                    role = AiChatRole.User,
                    content = trimmed,
                    createdAtMillis = nowMillis(),
                )
                // 占位 assistant 消息先入流，onDelta 按位更新它的 content
                val placeholder = AiChatMessage(
                    role = AiChatRole.Assistant,
                    content = "",
                    createdAtMillis = nowMillis(),
                )
                // 历史快照必须在本轮 user/占位入流之前拍：否则请求里会重复本轮 user，
                // 并夹带一条 content="" 的 assistant（部分网关直接回 400）
                val history = _messages.value.takeLast(HISTORY_WINDOW)
                _messages.update { it + userMessage + placeholder }
                lastCheckpointAt = nowMillis()

                val full = try {
                    client.streamChat(
                        connection = connection,
                        apiKey = apiKey,
                        turns = buildTurns(history, trimmed),
                        onDelta = { chunk ->
                            appendToLast(chunk)
                        },
                    )
                } catch (e: CancellationException) {
                    // 停止/退出：半截文本保留，标记 interrupted；取消的协程里 withContext
                    // 会直接抛出，落盘必须包 NonCancellable 才能执行完
                    markLastInterrupted()
                    withContext(NonCancellable) { persist() }
                    throw e
                } catch (e: AiException) {
                    markLastInterrupted()
                    _lastError.value = e
                    persist()
                    return
                }

                try {
                    finalizeLast(full, connection, apiKey)
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
     * 时静默返回。
     */
    suspend fun retryLast() {
        mutex.withLock {
            if (_messages.value.lastOrNull()?.takeIf { it.role == AiChatRole.Assistant }?.interrupted != true) {
                return
            }
            val userText = _messages.value.lastOrNull { it.role == AiChatRole.User }?.content ?: return
            // 先确认凭据再动消息：未配置时直接返回，不留下「尾巴被摘掉」的坏状态
            val (connection, apiKey) = loadCredentials()
            _configured.value = connection.isConfigured && apiKey != null
            if (!connection.isConfigured || apiKey == null) return
            _messages.update { list ->
                list.dropLastWhile { it.role == AiChatRole.Assistant }
            }
            // 尾部那条 user 就是本次要重发的：留在 history 里会与下面追加的 userText 重复
            val history = _messages.value.dropLast(1).takeLast(HISTORY_WINDOW)
            _lastError.value = null
            _sending.value = true
            try {
                val placeholder = AiChatMessage(
                    role = AiChatRole.Assistant,
                    content = "",
                    createdAtMillis = nowMillis(),
                )
                _messages.update { it + placeholder }
                lastCheckpointAt = nowMillis()
                val full = try {
                    client.streamChat(
                        connection = connection,
                        apiKey = apiKey,
                        turns = buildTurns(history, userText),
                        onDelta = { chunk -> appendToLast(chunk) },
                    )
                } catch (e: CancellationException) {
                    // 取消路径的落盘同样包 NonCancellable（见 send 的同款注释）
                    markLastInterrupted()
                    withContext(NonCancellable) { persist() }
                    throw e
                } catch (e: AiException) {
                    markLastInterrupted()
                    _lastError.value = e
                    persist()
                    return
                }
                try {
                    finalizeLast(full, connection, apiKey)
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
     * 清空对话历史（设置页「清空对话历史」）；落盘失败不回滚内存（历史可弃）。
     * 走 [persist] 而非直接 store.save：所有落盘在 [persistMutex] 上串行——在途
     * checkpoint 的旧快照不会晚于清空落盘，把刚清空的旧历史写回磁盘。
     */
    suspend fun clearHistory() {
        mutex.withLock {
            _messages.value = emptyList()
            _lastError.value = null
            // 用户已两次确认：落盘不能被浮层关闭取消，否则重启后旧历史回归
            withContext(NonCancellable) { persist() }
        }
    }

    /** 关闭错误条（UI 的「知道了」）；下次失败会重新发布 */
    fun dismissError() {
        _lastError.value = null
    }

    // ---------- 设置读写（AI 设置浮层经此读写，store 不直接出包） ----------

    suspend fun loadConnection(): AiConnection =
        withContext(Dispatchers.IO) { settings.loadConnection() }

    suspend fun saveConnection(connection: AiConnection): Boolean =
        withContext(Dispatchers.IO) { settings.saveConnection(connection) }

    /** 解密失败与未配置同形返回 null；UI 用 [secureAvailable] 区分两种语义 */
    suspend fun loadApiKey(): String? = withContext(Dispatchers.IO) {
        val key = settings.loadApiKey()
        _secureAvailable.value = settings.secureAvailable
        key
    }

    /** null = 清除已存 Key */
    suspend fun saveApiKey(key: String?): Boolean = withContext(Dispatchers.IO) {
        val saved = settings.saveApiKey(key)
        _secureAvailable.value = settings.secureAvailable
        saved
    }

    // ---------- 草稿卡 ----------

    /**
     * 批量回写草稿处置状态（保存成功 → Saved；用户放弃 → Dismissed）：一次内存更新
     * + 一次落盘——「保存所选」N 条不再重写 N 次整个文件。落盘包 NonCancellable：
     * 用户已确认的处置不能因浮层关闭丢失（否则会「待办已建、草稿仍 Pending」）。
     */
    suspend fun updateDraftStatuses(messageIndex: Int, statuses: Map<Int, AiDraftStatus>) {
        if (statuses.isEmpty()) return
        mutex.withLock {
            val current = _messages.value.getOrNull(messageIndex) ?: return
            val drafts = current.drafts ?: return
            val updatedStatuses = drafts.statuses.toMutableList().also { list ->
                for ((draftIndex, status) in statuses) {
                    if (draftIndex in list.indices) list[draftIndex] = status
                }
            }
            if (updatedStatuses == drafts.statuses) return
            val updated = current.copy(drafts = drafts.copy(statuses = updatedStatuses))
            _messages.update { list -> list.toMutableList().also { it[messageIndex] = updated } }
            withContext(NonCancellable) { persist() }
        }
    }

    /** 回写一条草稿的处置状态（单条 dismiss/编辑保存用；语义同 [updateDraftStatuses]） */
    suspend fun updateDraftStatus(messageIndex: Int, draftIndex: Int, status: AiDraftStatus) {
        updateDraftStatuses(messageIndex, mapOf(draftIndex to status))
    }

    // ---------- 内部 ----------

    /**
     * 本次请求的消息序列：system prompt（上下文快照）+ 历史窗口快照（不含本轮
     * user）+ 本轮 user 消息。历史含草稿卡消息时只发文本，不重发草稿内容；
     * 空 assistant（取消/失败残留的占位）不发送——对任何服务商都是噪声，
     * 个别网关会因 `content=""` 直接回 400。
     */
    private fun buildTurns(history: List<AiChatMessage>, userText: String): List<AiChatTurn> {
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
            .map { AiChatTurn(role = it.role.name.lowercase(), content = it.content) }
        return listOf(AiChatTurn(role = "system", content = system)) + turns +
            AiChatTurn(role = "user", content = userText)
    }

    /** 流式增量追加到尾部 assistant 消息（StateFlow 原位更新，UI 逐字渲染） */
    private fun appendToLast(chunk: String) {
        _messages.update { list ->
            if (list.isEmpty()) return@update list
            val last = list.last()
            if (last.role != AiChatRole.Assistant) return@update list
            list.toMutableList().also { it[it.lastIndex] = last.copy(content = last.content + chunk) }
        }
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
     * 流结束（成功）：先把完整正文写进消息（取消也不会丢），再解析草稿块挂卡。
     * 草稿解析失败会发起一次修复重试（同连接复用契约），该阶段被取消时正文已成立。
     */
    private suspend fun finalizeLast(full: String, connection: AiConnection, apiKey: String) {
        _messages.update { list ->
            val last = list.lastOrNull() ?: return@update list
            if (last.role != AiChatRole.Assistant) return@update list
            list.toMutableList().also {
                it[it.lastIndex] = last.copy(content = full, interrupted = false)
            }
        }
        val drafts = extractDrafts(full, connection, apiKey) ?: return
        _messages.update { list ->
            val last = list.lastOrNull() ?: return@update list
            if (last.role != AiChatRole.Assistant) return@update list
            list.toMutableList().also {
                it[it.lastIndex] = last.copy(drafts = drafts)
            }
        }
    }

    /**
     * 取消/失败：标记 interrupted；半截里若已有完整草稿块也就地解析挂卡，用户仍可保存。
     * 这里**只做本地解析**——取消路径协程已失效，发起修复请求只会立刻抛取消。
     */
    private fun markLastInterrupted() {
        _messages.update { list ->
            val last = list.lastOrNull() ?: return@update list
            if (last.role != AiChatRole.Assistant) return@update list
            list.toMutableList().also {
                it[it.lastIndex] = last.copy(
                    interrupted = true,
                    drafts = parseDraftsOrNull(last.content) ?: last.drafts,
                )
            }
        }
    }

    /** 本地解析草稿块（不发起网络）；无块或解析失败返回 null */
    private fun parseDraftsOrNull(reply: String): AiMessageDrafts? {
        val json = AiTodoDraftCodec.extractJsonBlock(reply) ?: return null
        return AiTodoDraftCodec.parseTodoDrafts(json)?.toMessageDrafts()
    }

    /**
     * 从回复文本提取草稿（无块 = null）；解析失败带错误自动重试一次（把上次输出
     * 原样附回，请模型修正后重新输出），仍失败返回 null（视为纯对话）。
     *
     * 凭据由本次发送一路传入（同一场对话内不会变），省掉每次回复的重复解密；
     * 取消必须原样抛出——否则调用方的落盘会被跳过。
     */
    private suspend fun extractDrafts(
        reply: String,
        connection: AiConnection,
        apiKey: String,
    ): AiMessageDrafts? {
        if (!connection.isConfigured) return null

        val json = AiTodoDraftCodec.extractJsonBlock(reply) ?: return null
        val first = AiTodoDraftCodec.parseTodoDrafts(json)
        if (first != null) return first.toMessageDrafts()

        // 修复重试：非流式轻量收口——复用流式通道但整块收，UI 无感（增量丢弃）。
        // system 带上草稿 JSON 契约：原实现只说「之前声明的格式」，模型手里并没有声明。
        val repairPrompt = "你上一条回复里的 JSON 无法解析。请只输出一个修正后的 ```json 代码块，" +
            "严格遵循系统消息给出的格式，不要输出其他内容。"
        val repaired = try {
            client.streamChat(
                connection = connection,
                apiKey = apiKey,
                turns = listOf(
                    AiChatTurn(
                        role = "system",
                        content = "待办草稿必须是可解析的 json 代码块，格式（字段都可省略）：\n" +
                            AiPromptBuilder.TODO_JSON_SHAPE,
                    ),
                    AiChatTurn(role = "user", content = reply.take(REPAIR_CONTEXT_MAX)),
                    AiChatTurn(role = "user", content = repairPrompt),
                ),
            )
        } catch (e: CancellationException) {
            throw e // 取消语义不能吞：吞掉会让「正文已完整」的落盘被跳过
        } catch (_: Exception) {
            null // 修复失败按「无草稿」处理
        } ?: return null
        val repairedJson = AiTodoDraftCodec.extractJsonBlock(repaired) ?: return null
        return AiTodoDraftCodec.parseTodoDrafts(repairedJson)?.toMessageDrafts()
    }

    private fun AiTodoDraftPayload.toMessageDrafts(): AiMessageDrafts? {
        if (todos.isEmpty()) return null
        return AiMessageDrafts(
            drafts = todos,
            statuses = List(todos.size) { AiDraftStatus.Pending },
        )
    }

    /**
     * 落盘（阻塞 I/O，经 Dispatchers.IO）；失败只影响下次装载（历史可弃）。
     * [persistMutex] 串行化所有落盘，且快照在锁内取：后台 checkpoint 的旧快照不会
     * 覆盖随后完成的最终落盘。
     */
    private suspend fun persist() {
        withContext(Dispatchers.IO) {
            persistMutex.withLock {
                val file = AiChatFile(messages = _messages.value.takeLast(MAX_PERSISTED_MESSAGES))
                store.save(file)
            }
        }
    }

    companion object {
        /** 发给模型的历史窗口（条数；system prompt 不在此） */
        const val HISTORY_WINDOW = 30

        /** 落盘的消息上限（超限截最旧，见计划 §4.3） */
        const val MAX_PERSISTED_MESSAGES = 200

        /** 修复重试时携带的原文上限（防超长回复撑爆重试请求） */
        const val REPAIR_CONTEXT_MAX = 4000

        /** 流式期间半截 checkpoint 的节流间隔（进程被杀时尽量少丢内容） */
        const val CHECKPOINT_INTERVAL_MILLIS = 2000L

        /** 应用侧装配入口（AppContainer）；测试直接构造注入 fake store / fake client */
        suspend fun create(
            store: AiChatStore,
            settings: AiSettingsStore,
            client: AiClient,
            contextProvider: () -> AiChatContext,
        ): AiChatRepository = AiChatRepository(store, settings, client, contextProvider).also { it.bootstrap() }
    }
}
