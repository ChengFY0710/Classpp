package com.fangyi.classpp.data.xmu

import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.store.LoadOutcome
import com.fangyi.classpp.data.store.LoadIssue
import com.fangyi.classpp.data.store.StoreResult
import java.io.File

/**
 * 课程中心同步状态持久化（阻塞 I/O，由仓库包到 Dispatchers.IO）。
 * 与 FileTodoStore 完全同一套文件协议（细节见其 KDoc）：tmp（fsync）→ bak（备份本次
 * 将提交的数据）→ rename 原子覆盖主文件；读取降级 主文件坏 → bak → 双坏改名
 * `xmu-sync.corrupt-<millis>.json` 留证并返回空状态。formatVersion 高于当前版本按损坏处理。
 */
class XmuSyncStore(private val dir: File) {

    private val main = File(dir, MAIN_NAME)
    private val tmp = File(dir, TMP_NAME)
    private val bak = File(dir, BAK_NAME)

    fun load(): LoadOutcome<XmuSyncFile> {
        if (!dir.exists()) dir.mkdirs()

        if (!main.exists()) {
            return LoadOutcome.Fresh(XmuSyncFile(), LoadIssue.None)
        }

        // 主文件可解析且版本兼容 → 正常载入；否则（损坏或未来版本）降级试 bak
        val mainParsed = parseFile(main)
        if (mainParsed != null && mainParsed.formatVersion <= XMU_SYNC_FORMAT_VERSION) {
            return LoadOutcome.Loaded(mainParsed, LoadIssue.None)
        }

        val bakParsed = parseFile(bak)
        if (bakParsed != null && bakParsed.formatVersion <= XMU_SYNC_FORMAT_VERSION) {
            return LoadOutcome.Loaded(bakParsed, LoadIssue.RestoredFromBackup)
        }

        // 双坏：保留主文件取证，返回空状态（同步映射可重建，不值得惊动用户）
        if (main.exists()) {
            val quarantine = File(dir, "$CORRUPT_PREFIX${System.currentTimeMillis()}.json")
            main.renameTo(quarantine)
        }
        return LoadOutcome.Fresh(XmuSyncFile(), LoadIssue.ResetAfterCorruption)
    }

    fun save(file: XmuSyncFile): StoreResult {
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

    /** 退出登录/账号切换时物理删除三件套 */
    fun delete() {
        main.delete()
        tmp.delete()
        bak.delete()
    }

    private fun parseFile(file: File): XmuSyncFile? {
        if (!file.exists()) return null
        return try {
            ScheduleJson.decodeStorage<XmuSyncFile>(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val MAIN_NAME = "xmu-sync.json"
        const val TMP_NAME = "xmu-sync.json.tmp"
        const val BAK_NAME = "xmu-sync.json.bak"
        const val CORRUPT_PREFIX = "xmu-sync.corrupt-"
    }
}
