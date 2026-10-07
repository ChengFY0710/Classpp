package com.fangyi.classpp.data

import android.content.Context
import com.fangyi.classpp.data.model.TagEntry
import com.fangyi.classpp.data.model.TagFile
import com.fangyi.classpp.data.store.FileTagStore
import com.fangyi.classpp.data.store.LoadIssue
import com.fangyi.classpp.data.store.LoadOutcome
import com.fangyi.classpp.data.store.StoreResult
import com.fangyi.classpp.data.store.TagStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 用户自定义标签仓库：内存真源（StateFlow）+ 磁盘持久化（[TagStore]），与 TodoRepository 同构。
 *
 * - 读：StateFlow 直接 collect，无 I/O；
 * - 写：Mutex 串行化，流程 = 归一化 → `store.save` → 成功才发布到 StateFlow
 *   （落盘先行，失败即内存零变更，天然无回滚问题）。
 *
 * 标签只有增删与改色（名字是唯一键，改名 = 全量待办回写，本期不做）。课程标签不在
 * 这里——它由课表数据派生（courses.distinctBy { it.name }），不入库；标签库只管
 * 用户在标签选择卡里自建的标签。
 *
 * 构造：测试用 [create] 注入 fake store；应用内用 [get] 进程级单例。
 */
class TagRepository internal constructor(
    private val store: TagStore,
) {
    private val mutex = Mutex()

    // canonical in-memory file state（mutex 守护下写入；读走 StateFlow）
    private val fileState = MutableStateFlow(TagFile())

    private val _tags = MutableStateFlow<List<TagEntry>>(emptyList())
    val tags: StateFlow<List<TagEntry>> = _tags.asStateFlow()

    private val _loadState = MutableStateFlow(LoadState.Ready)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    // ---------- 生命周期 ----------

    /** 从磁盘载入（阻塞 I/O，经 Dispatchers.IO） */
    private suspend fun bootstrap() {
        val outcome = withContext(Dispatchers.IO) { store.load() }
        _loadState.value = when (outcome.issue) {
            LoadIssue.None -> LoadState.Ready
            LoadIssue.RestoredFromBackup -> LoadState.RestoredFromBackup
            LoadIssue.ResetAfterCorruption -> LoadState.ResetAfterCorruption
        }
        publish(
            when (outcome) {
                is LoadOutcome.Loaded<TagFile> -> outcome.file
                is LoadOutcome.Fresh<TagFile> -> outcome.file
            },
        )
    }

    private fun publish(file: TagFile) {
        fileState.value = file
        _tags.value = file.tags
    }

    /** 落盘成功才发布内存态 */
    private suspend fun commit(next: TagFile): TagOpResult {
        val result = withContext(Dispatchers.IO) { store.save(next) }
        return when (result) {
            StoreResult.Ok -> {
                publish(next)
                TagOpResult.Ok
            }
            is StoreResult.Failed -> TagOpResult.Err(TagError.PersistFailed(result.detail))
        }
    }

    // ---------- 标签 CRUD ----------

    /** 新增标签：trim 非空、库内不重名后追加（新标签排在末尾，保持创建顺序） */
    suspend fun addTag(name: String, colorArgb: Long? = null): TagOpResult = mutex.withLock {
        val prepared = name.trim()
        if (prepared.isEmpty()) return@withLock TagOpResult.Err(TagError.BlankName)
        if (fileState.value.tags.any { it.name == prepared }) {
            return@withLock TagOpResult.Err(TagError.DuplicateName(prepared))
        }
        commit(TagFile(fileState.value.formatVersion, fileState.value.tags + TagEntry(prepared, colorArgb)))
    }

    /** 按名字删除标签 */
    suspend fun removeTag(name: String): TagOpResult = mutex.withLock {
        if (fileState.value.tags.none { it.name == name }) {
            return@withLock TagOpResult.Err(TagError.NotFound(name))
        }
        commit(TagFile(fileState.value.formatVersion, fileState.value.tags.filterNot { it.name == name }))
    }

    /** 改标签色（颜色选择器接入后用）；null = 回到跟随主题 Primary */
    suspend fun setTagColor(name: String, colorArgb: Long?): TagOpResult = mutex.withLock {
        if (fileState.value.tags.none { it.name == name }) {
            return@withLock TagOpResult.Err(TagError.NotFound(name))
        }
        commit(
            TagFile(
                fileState.value.formatVersion,
                fileState.value.tags.map { if (it.name == name) it.copy(colorArgb = colorArgb) else it },
            ),
        )
    }

    companion object {
        /** 构建并从磁盘载入（阻塞 I/O 经 Dispatchers.IO） */
        suspend fun create(store: TagStore): TagRepository = TagRepository(store).also { it.bootstrap() }

        private val instanceMutex = Mutex()
        private var instance: TagRepository? = null

        /** 应用内进程级单例（首次调用完成磁盘载入） */
        suspend fun get(context: Context): TagRepository {
            instance?.let { return it }
            return instanceMutex.withLock {
                instance ?: create(FileTagStore(context.filesDir)).also { instance = it }
            }
        }
    }
}
