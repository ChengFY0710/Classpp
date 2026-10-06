package com.fangyi.classpp.data.store

import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.model.TODO_FORMAT_VERSION
import com.fangyi.classpp.data.model.TodoFile
import java.io.File

/**
 * 待办整库持久化。实现为阻塞 I/O，由仓库包到 Dispatchers.IO。
 * 与 [ScheduleStore] 平行：同一套文件协议，只是文件与信封类型不同。
 */
interface TodoStore {
    fun load(): LoadOutcome<TodoFile>
    fun save(file: TodoFile): StoreResult
}

/**
 * 基于单 JSON 文件的实现，写入为 tmp+rename 提交流程，与 FileScheduleStore 完全一致
 * （协议细节见其 KDoc）：tmp（fsync）→ bak（备份本次将提交的数据）→ rename 覆盖主文件；
 * 读取降级 主文件坏 → bak → 双坏改名 `todos.corrupt-<millis>.json` 留证并返回空库。
 * `formatVersion` 高于当前版本按损坏处理。
 */
class FileTodoStore(private val dir: File) : TodoStore {

    private val main = File(dir, MAIN_NAME)
    private val tmp = File(dir, TMP_NAME)
    private val bak = File(dir, BAK_NAME)

    override fun load(): LoadOutcome<TodoFile> {
        if (!dir.exists()) dir.mkdirs()

        if (!main.exists()) {
            return LoadOutcome.Fresh(TodoFile(), LoadIssue.None)
        }

        // 主文件可解析且版本兼容 → 正常载入；否则（损坏或未来版本）降级试 bak
        val mainParsed = parseFile(main)
        if (mainParsed != null && mainParsed.formatVersion <= TODO_FORMAT_VERSION) {
            return LoadOutcome.Loaded(mainParsed, LoadIssue.None)
        }

        val bakParsed = parseFile(bak)
        if (bakParsed != null && bakParsed.formatVersion <= TODO_FORMAT_VERSION) {
            return LoadOutcome.Loaded(bakParsed, LoadIssue.RestoredFromBackup)
        }

        // 双坏：保留主文件取证，返回空库
        if (main.exists()) {
            val quarantine = File(dir, "$CORRUPT_PREFIX${System.currentTimeMillis()}.json")
            main.renameTo(quarantine)
        }
        return LoadOutcome.Fresh(TodoFile(), LoadIssue.ResetAfterCorruption)
    }

    override fun save(file: TodoFile): StoreResult {
        if (!dir.exists()) dir.mkdirs()
        val text = try {
            ScheduleJson.encodeStorage(file)
        } catch (e: Exception) {
            return StoreResult.Failed("serialize failed: ${e.message}")
        }

        // 1. tmp 落盘
        try {
            tmp.outputStream().use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
        } catch (e: Exception) {
            tmp.delete()
            return StoreResult.Failed("write tmp failed: ${e.message}")
        }

        // 2. tmp → bak（备份 = 本次将提交的新数据，损坏恢复零丢失）
        try {
            tmp.copyTo(bak, overwrite = true)
        } catch (_: Exception) {
            // 备份失败不阻断主流程：本次保存的数据本身是完好的
        }

        // 3. tmp → 主文件（POSIX 原子替换；失败删主重试，间隙由 bak 兜底）
        if (!tmp.renameTo(main)) {
            main.delete()
            if (!tmp.renameTo(main)) {
                tmp.delete()
                return StoreResult.Failed("rename tmp over main failed")
            }
        }
        return StoreResult.Ok
    }

    private fun parseFile(file: File): TodoFile? {
        if (!file.exists()) return null
        return try {
            ScheduleJson.decodeStorage<TodoFile>(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val MAIN_NAME = "todos.json"
        const val TMP_NAME = "todos.json.tmp"
        const val BAK_NAME = "todos.json.bak"
        const val CORRUPT_PREFIX = "todos.corrupt-"
    }
}
