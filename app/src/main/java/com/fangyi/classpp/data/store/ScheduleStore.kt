package com.fangyi.classpp.data.store

import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.model.SCHEDULE_FORMAT_VERSION
import com.fangyi.classpp.data.model.ScheduleFile
import java.io.File

/**
 * 整库文件的读取结果；[F] 为各域的文件信封类型（课表 ScheduleFile / 待办 TodoFile），
 * 读写协议（tmp+bak+rename、损坏降级）在两个域间完全共享。
 */
sealed class LoadOutcome<out F> {
    abstract val issue: LoadIssue

    /** 解析成功（[issue] 标注是否从备份恢复） */
    data class Loaded<F>(
        val file: F,
        override val issue: LoadIssue = LoadIssue.None,
    ) : LoadOutcome<F>()

    /** 新起点：首次安装，或数据全损后重置 */
    data class Fresh<F>(val file: F, override val issue: LoadIssue) : LoadOutcome<F>()
}

/** 读取过程中发现的数据问题（映射为仓库的 LoadState 供 UI 提示） */
enum class LoadIssue { None, RestoredFromBackup, ResetAfterCorruption }

/** 写入结果：失败时仓库回滚内存态 */
sealed class StoreResult {
    data object Ok : StoreResult()
    data class Failed(val detail: String) : StoreResult()
}

/**
 * 整库持久化。实现为阻塞 I/O，由仓库包到 Dispatchers.IO。
 * 单文件方案：`activeScheduleId` 与课表列表必须原子一致。
 */
interface ScheduleStore {
    fun load(): LoadOutcome<ScheduleFile>
    fun save(file: ScheduleFile): StoreResult
}

/**
 * 基于单 JSON 文件的实现，写入为 tmp+rename 提交流程：
 *
 * 1. 写 `schedules.json.tmp`（fsync 落盘）；
 * 2. `tmp` 复制为 `schedules.json.bak` —— **备份即本次将要提交的数据**，
 *    主文件日后损坏时可无损恢复到最近一次保存（绝不复制可能已损坏的主文件去覆盖 bak）；
 * 3. `tmp` rename 覆盖主文件（POSIX 原子替换；Windows 上失败则删主文件后重试，
 *    此间隙若崩溃可由 bak 恢复）。
 *
 * 读取降级：主文件坏 → 试 bak → 双坏则主文件改名 `schedules.corrupt-<millis>.json`
 * 保留取证并返回空库。`formatVersion` 高于当前版本按损坏处理（防新版本字段写坏旧库）。
 */
class FileScheduleStore(private val dir: File) : ScheduleStore {

    private val main = File(dir, MAIN_NAME)
    private val tmp = File(dir, TMP_NAME)
    private val bak = File(dir, BAK_NAME)

    override fun load(): LoadOutcome<ScheduleFile> {
        if (!dir.exists()) dir.mkdirs()

        if (!main.exists()) {
            return LoadOutcome.Fresh(ScheduleFile(), LoadIssue.None)
        }

        // 主文件可解析且版本兼容 → 正常载入；否则（损坏或未来版本）降级试 bak
        val mainParsed = parseFile(main)
        if (mainParsed != null && mainParsed.formatVersion <= SCHEDULE_FORMAT_VERSION) {
            return LoadOutcome.Loaded(mainParsed, LoadIssue.None)
        }

        val bakParsed = parseFile(bak)
        if (bakParsed != null && bakParsed.formatVersion <= SCHEDULE_FORMAT_VERSION) {
            return LoadOutcome.Loaded(bakParsed, LoadIssue.RestoredFromBackup)
        }

        // 双坏：保留主文件取证，返回空库
        if (main.exists()) {
            val quarantine = File(dir, "$CORRUPT_PREFIX${System.currentTimeMillis()}.json")
            main.renameTo(quarantine)
        }
        return LoadOutcome.Fresh(ScheduleFile(), LoadIssue.ResetAfterCorruption)
    }

    override fun save(file: ScheduleFile): StoreResult {
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

    private fun parseFile(file: File): ScheduleFile? {
        if (!file.exists()) return null
        return try {
            ScheduleJson.decodeStorage<ScheduleFile>(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val MAIN_NAME = "schedules.json"
        const val TMP_NAME = "schedules.json.tmp"
        const val BAK_NAME = "schedules.json.bak"
        const val CORRUPT_PREFIX = "schedules.corrupt-"
    }
}
