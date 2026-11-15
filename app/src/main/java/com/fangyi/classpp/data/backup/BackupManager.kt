package com.fangyi.classpp.data.backup

import android.content.Context
import android.net.Uri
import com.fangyi.classpp.appVersionName
import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.ai.AI_CHAT_FORMAT_VERSION
import com.fangyi.classpp.data.ai.AiChatFile
import com.fangyi.classpp.data.ai.AiChatStore
import com.fangyi.classpp.data.ai.FileAiChatStore
import com.fangyi.classpp.data.model.SCHEDULE_FORMAT_VERSION
import com.fangyi.classpp.data.model.ScheduleFile
import com.fangyi.classpp.data.model.TAG_FORMAT_VERSION
import com.fangyi.classpp.data.model.TODO_FORMAT_VERSION
import com.fangyi.classpp.data.model.TagFile
import com.fangyi.classpp.data.model.TodoFile
import com.fangyi.classpp.data.store.FileScheduleStore
import com.fangyi.classpp.data.store.FileTagStore
import com.fangyi.classpp.data.store.FileTodoStore
import com.fangyi.classpp.data.store.LoadOutcome
import com.fangyi.classpp.data.store.ScheduleStore
import com.fangyi.classpp.data.store.StoreResult
import com.fangyi.classpp.data.store.TagStore
import com.fangyi.classpp.data.store.TodoAttachmentStore
import com.fangyi.classpp.data.store.TodoStore
import com.fangyi.classpp.ui.day.DayImageStore
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 备份/恢复结果 */
sealed interface BackupResult<out T> {
    data class Ok<T>(val value: T) : BackupResult<T>
    data class Err(val reason: BackupError) : BackupResult<Nothing>
}

/** 备份失败原因（UI 映射为不同 toast 文案） */
enum class BackupError {
    /** 内容 URI 打不开 / 读流失败 */
    ReadFailed,

    /** 不是本应用的备份包，或包内 JSON 损坏 */
    InvalidArchive,

    /** 备份版本高于当前应用（需要升级应用） */
    UnsupportedVersion,

    /** 写盘失败（空间不足、权限异常等） */
    WriteFailed,
}

/**
 * 全量用户内容备份/恢复（阻塞 I/O，全部经 [Dispatchers.IO]）。
 *
 * 以「整库信封」为单位直接读写各 Store 的主文件，而不是走分享编解码器——后者会重发
 * ID、丢弃回收站/归档条目并清来源标记，只能用于分享，不能用于完整备份。整库直读直写
 * 保留原 ID，AI 对话里的 `todoIds` 与 XMU 同步映射的引用因此不会断。
 *
 * 备份范围 = 课表（含时间方案）、待办（含回收站/归档/分组/步骤/附件）、标签、日程背景图
 * 及图库元数据、AI 对话记录。**不含**任何账号凭据、WebView 数据、XMU 同步状态、
 * 应用偏好设置与可再下载的脚本缓存（见 [StorageUsage] 的口径说明）。待办附件文件
 * 随 `attachments/` entry 一并打包（元数据在 `todos.json` 里）；导出时只收集仍存在的
 * 附件文件，缺失项会从**备份内**的待办元数据中剔除，避免生成打不开的悬空附件。
 * 导入时备份里没有对应文件的附件元数据同样会被剔除，兼容旧版本不含附件文件的备份。
 * 系统自动备份默认也包含 `filesDir/todo_attachments`。
 *
 * 导入语义为**覆盖恢复**：先整包解压到 cache 暂存目录并解析校验全部 JSON，全部合法
 * 后才落盘；任一整库写入失败会回滚已写入的域。调用方（数据管理页）负责在成功后
 * 重载各仓库。
 */
class BackupManager(private val context: Context) {

    /** 预检：只读 manifest，供覆盖确认弹窗展示内容计数 */
    suspend fun inspect(uri: Uri): BackupResult<BackupManifest> = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext BackupResult.Err(BackupError.ReadFailed)
            val manifest = stream.use { BackupArchive.readManifest(it) }
                ?: return@withContext BackupResult.Err(BackupError.InvalidArchive)
            if (!manifest.isSupported()) {
                return@withContext BackupResult.Err(BackupError.UnsupportedVersion)
            }
            BackupResult.Ok(manifest)
        } catch (_: BackupArchiveException) {
            BackupResult.Err(BackupError.InvalidArchive)
        } catch (_: ZipException) {
            BackupResult.Err(BackupError.InvalidArchive)
        } catch (_: IOException) {
            BackupResult.Err(BackupError.ReadFailed)
        } catch (_: Exception) {
            BackupResult.Err(BackupError.ReadFailed)
        }
    }

    /** 导出全部用户内容到 [uri]（CreateDocument 返回的目标文件） */
    suspend fun exportTo(uri: Uri): BackupResult<BackupManifest> = withContext(Dispatchers.IO) {
        val filesDir = context.filesDir
        val schedules = FileScheduleStore(filesDir).load().asFile()
        val todos = FileTodoStore(filesDir).load().asFile()
        val tags = FileTagStore(filesDir).load().asFile()
        val aiChat = FileAiChatStore(filesDir).load().asFile()
        val attachmentDir = File(filesDir, TodoAttachmentStore.DIRECTORY_NAME)
        val attachmentStore = TodoAttachmentStore(attachmentDir)
        // 只打包仍存在的附件文件；元数据里的悬空项从备份内剔除（本机数据不动）
        val attachmentRefs = todos.todos.asSequence()
            .flatMap { it.attachments.asSequence() }
            .filter { attachmentStore.file(it)?.isFile == true }
            .map { BackupArchive.AttachmentRef(id = it.id, fileName = it.fileName) }
            .distinctBy { it.relativePath }
            .toList()
        val includedAttachmentPaths = attachmentRefs.mapTo(mutableSetOf()) { it.relativePath }
        val backupTodos = todos.copy(
            todos = todos.todos.map { todo ->
                val kept = todo.attachments.filter {
                    "${it.id}/${it.fileName}" in includedAttachmentPaths
                }
                if (kept.size == todo.attachments.size) todo else todo.copy(attachments = kept)
            },
        )
        val dayImageNames = DayImageStore.loadImages(context)
            .filter { BackupArchive.isSafeImageName(it) }
            .filter { DayImageStore.imageFile(context, it).isFile }

        val dayImages = BackupDayImages(
            images = dayImageNames,
            selected = DayImageStore.loadSelected(context)?.takeIf { it in dayImageNames },
            randomEnabled = DayImageStore.loadRandomEnabled(context),
            tiltEnabled = DayImageStore.loadTiltParallaxEnabled(context),
        )
        val manifest = BackupManifest(
            appVersionName = appVersionName(context),
            exportedAtMillis = System.currentTimeMillis(),
            counts = BackupCounts(
                schedules = schedules.schedules.size,
                courses = schedules.schedules.sumOf { it.courses.size },
                todos = todos.todos.size,
                tags = tags.tags.size,
                // 多对话模型（v2）：统计所有对话的消息总数；v1 旧文件迁移前顶层 messages 兜底
                aiMessages = aiChat.conversations.sumOf { it.messages.size } + aiChat.messages.size,
                dayImages = dayImageNames.size,
                attachments = attachmentRefs.size,
            ),
        )
        val payload = BackupArchive.Payload(
            manifest = manifest,
            schedulesJson = ScheduleJson.encodeStorage(schedules),
            todosJson = ScheduleJson.encodeStorage(backupTodos),
            tagsJson = ScheduleJson.encodeStorage(tags),
            aiChatJson = ScheduleJson.encodeStorage(aiChat),
            dayImagesJson = ScheduleJson.encodeStorage(dayImages),
            imageDir = DayImageStore.imageDir(context),
            imageNames = dayImageNames,
            attachmentDir = attachmentDir,
            attachments = attachmentRefs,
        )
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                BackupArchive.write(out, payload)
            } ?: return@withContext BackupResult.Err(BackupError.WriteFailed)
            BackupResult.Ok(manifest)
        } catch (_: Exception) {
            BackupResult.Err(BackupError.WriteFailed)
        }
    }

    /** 覆盖恢复：解压 → 整包校验 → 写整库 → 替换背景图目录与图库元数据 */
    suspend fun importFrom(uri: Uri): BackupResult<BackupManifest> = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "backup-import-${System.currentTimeMillis()}")
        try {
            staging.deleteRecursively()
            context.contentResolver.openInputStream(uri)?.use { input ->
                BackupArchive.extract(input, staging)
            } ?: return@withContext BackupResult.Err(BackupError.ReadFailed)

            val manifest = readEntry<BackupManifest>(staging, BackupArchive.ENTRY_MANIFEST)
                ?: return@withContext BackupResult.Err(BackupError.InvalidArchive)
            if (!manifest.isSupported()) {
                return@withContext BackupResult.Err(BackupError.UnsupportedVersion)
            }

            val schedules = readEntry<ScheduleFile>(staging, BackupArchive.ENTRY_SCHEDULES)
                ?: return@withContext BackupResult.Err(BackupError.InvalidArchive)
            val todos = readEntry<TodoFile>(staging, BackupArchive.ENTRY_TODOS)
                ?: return@withContext BackupResult.Err(BackupError.InvalidArchive)
            val tags = readEntry<TagFile>(staging, BackupArchive.ENTRY_TAGS)
                ?: return@withContext BackupResult.Err(BackupError.InvalidArchive)
            val aiChat = readEntry<AiChatFile>(staging, BackupArchive.ENTRY_AI_CHAT)
                ?: return@withContext BackupResult.Err(BackupError.InvalidArchive)
            // 图库元数据是备份结构的固定成员：缺失说明包不完整，按损坏处理
            val dayImages = readEntry<BackupDayImages>(staging, BackupArchive.ENTRY_DAY_IMAGES)
                ?: return@withContext BackupResult.Err(BackupError.InvalidArchive)

            val unsupported = schedules.formatVersion > SCHEDULE_FORMAT_VERSION ||
                todos.formatVersion > TODO_FORMAT_VERSION ||
                tags.formatVersion > TAG_FORMAT_VERSION ||
                aiChat.formatVersion > AI_CHAT_FORMAT_VERSION ||
                dayImages.formatVersion > BACKUP_FORMAT_VERSION
            if (unsupported) {
                return@withContext BackupResult.Err(BackupError.UnsupportedVersion)
            }

            // 附件自洽：只还原包内确实有文件、且路径安全的元数据；旧版本备份（不含
            // attachments entry）或悬空项从待办里剔除，避免恢复后出现打不开的附件行
            val attachmentPrefix = BackupArchive.ATTACHMENT_ENTRY_PREFIX
            val restoredAttachments = todos.todos.asSequence()
                .flatMap { it.attachments.asSequence() }
                .filter { attachment ->
                    val relativePath = "${attachment.id}/${attachment.fileName}"
                    BackupArchive.isSafeAttachmentEntry(relativePath) &&
                        File(staging, attachmentPrefix + relativePath).isFile
                }
                .map { BackupArchive.AttachmentRef(id = it.id, fileName = it.fileName) }
                .distinctBy { it.relativePath }
                .toList()
            val restoredAttachmentPaths = restoredAttachments
                .mapTo(mutableSetOf()) { it.relativePath }
            val restoredTodos = todos.copy(
                todos = todos.todos.map { todo ->
                    val kept = todo.attachments.filter {
                        "${it.id}/${it.fileName}" in restoredAttachmentPaths
                    }
                    if (kept.size == todo.attachments.size) todo else todo.copy(attachments = kept)
                },
            )

            // 图片列表必须自洽：名字安全、且包里确实有对应文件。任何缺项都按损坏处理，
            // 不做「静默少还原几张」——覆盖语义下用户无从发现缺了图
            val imageNames = dayImages.images.distinct()
            val imagesConsistent = imageNames.all { name ->
                BackupArchive.isSafeImageName(name) &&
                    File(staging, BackupArchive.IMAGE_ENTRY_PREFIX + name).isFile
            }
            if (!imagesConsistent) {
                return@withContext BackupResult.Err(BackupError.InvalidArchive)
            }

            if (!applyData(schedules, restoredTodos, tags, aiChat)) {
                return@withContext BackupResult.Err(BackupError.WriteFailed)
            }
            if (
                !replaceDir(
                    staging = staging,
                    entryPrefix = BackupArchive.IMAGE_ENTRY_PREFIX,
                    live = DayImageStore.imageDir(context),
                    entryNames = imageNames,
                )
            ) {
                return@withContext BackupResult.Err(BackupError.WriteFailed)
            }
            if (
                !replaceDir(
                    staging = staging,
                    entryPrefix = BackupArchive.ATTACHMENT_ENTRY_PREFIX,
                    live = File(context.filesDir, TodoAttachmentStore.DIRECTORY_NAME),
                    entryNames = restoredAttachments.map { it.relativePath },
                )
            ) {
                return@withContext BackupResult.Err(BackupError.WriteFailed)
            }
            DayImageStore.saveImages(context, imageNames)
            DayImageStore.saveSelected(context, dayImages.selected?.takeIf { it in imageNames })
            DayImageStore.saveRandomEnabled(context, dayImages.randomEnabled)
            DayImageStore.saveTiltParallaxEnabled(context, dayImages.tiltEnabled)
            BackupResult.Ok(manifest)
        } catch (_: BackupArchiveException) {
            BackupResult.Err(BackupError.InvalidArchive)
        } catch (_: ZipException) {
            BackupResult.Err(BackupError.InvalidArchive)
        } catch (_: IOException) {
            BackupResult.Err(BackupError.ReadFailed)
        } catch (_: Exception) {
            BackupResult.Err(BackupError.ReadFailed)
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * 写四个整库；任一失败则把已写入的域回滚为写前内容（磁盘满等罕见场景）。
     * 校验已在调用方完成，这里只处理 I/O 成败。
     */
    private fun applyData(
        schedules: ScheduleFile,
        todos: TodoFile,
        tags: TagFile,
        aiChat: AiChatFile,
    ): Boolean {
        val filesDir = context.filesDir
        val scheduleStore: ScheduleStore = FileScheduleStore(filesDir)
        val todoStore: TodoStore = FileTodoStore(filesDir)
        val tagStore: TagStore = FileTagStore(filesDir)
        val aiStore: AiChatStore = FileAiChatStore(filesDir)

        val previousSchedules = scheduleStore.load().asFile()
        val previousTodos = todoStore.load().asFile()
        val previousTags = tagStore.load().asFile()
        val previousAiChat = aiStore.load().asFile()

        var ok = scheduleStore.save(schedules) == StoreResult.Ok
        ok = ok && todoStore.save(todos) == StoreResult.Ok
        ok = ok && tagStore.save(tags) == StoreResult.Ok
        ok = ok && aiStore.save(aiChat) == StoreResult.Ok
        if (ok) return true

        // 尽力回滚：失败原因若是磁盘满，回滚可能同样失败，但不做只会留下更混乱的半套数据
        runCatching {
            scheduleStore.save(previousSchedules)
            todoStore.save(previousTodos)
            tagStore.save(previousTags)
            aiStore.save(previousAiChat)
        }
        return false
    }

    /**
     * 用备份内文件替换应用私有目录（背景图 `day_images` / 待办附件 `todo_attachments`）：
     * 先在同级目录组装，再整体换名，避免清空旧目录后拷贝失败留下半套文件。失败时旧目录
     * 原样保留。[entryNames] 是相对 entry 前缀的路径（附件为 `<id>/<fileName>`，可含一层
     * 子目录）。空列表 = 备份里没有该类文件，恢复后目录为空（覆盖语义）。
     */
    private fun replaceDir(
        staging: File,
        entryPrefix: String,
        live: File,
        entryNames: List<String>,
    ): Boolean {
        val parent = live.parentFile ?: return false
        val staged = File(parent, "${live.name}.staged")
        val old = File(parent, "${live.name}.old")
        staged.deleteRecursively()
        old.deleteRecursively()
        if (!staged.mkdirs()) return false

        for (name in entryNames) {
            val source = File(staging, entryPrefix + name)
            if (!source.isFile) {
                staged.deleteRecursively()
                return false
            }
            val target = File(staged, name)
            val copied = runCatching {
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
            }.isSuccess
            if (!copied) {
                staged.deleteRecursively()
                return false
            }
        }

        if (live.exists() && !live.renameTo(old)) {
            staged.deleteRecursively()
            return false
        }
        if (!staged.renameTo(live)) {
            // 换名失败：把旧目录放回原位，保持导入前状态
            if (old.exists()) old.renameTo(live)
            staged.deleteRecursively()
            return false
        }
        old.deleteRecursively()
        return true
    }

    private inline fun <reified T> readEntry(dir: File, name: String): T? {
        val file = File(dir, name)
        if (!file.isFile) return null
        return runCatching {
            ScheduleJson.decodeStorage<T>(file.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun <F> LoadOutcome<F>.asFile(): F = when (this) {
        is LoadOutcome.Loaded<F> -> file
        is LoadOutcome.Fresh<F> -> file
    }
}
