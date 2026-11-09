package com.fangyi.classpp.data.sync.engine

import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.store.LoadIssue
import com.fangyi.classpp.data.store.LoadOutcome
import com.fangyi.classpp.data.store.StoreResult
import java.io.File
import kotlinx.serialization.Serializable

/** `filesDir/sync/<id>/sync.json` 的信封版本（各校独立，互不影响） */
const val SCHOOL_SYNC_FORMAT_VERSION = 1

/**
 * 通用同步状态文件：远端键 ↔ 本地 id 的映射、内容哈希、账号 id 与上次同步/探活时刻。
 * 与 `todos.json` 完全独立——同步失败/损坏不波及用户待办库。
 *
 * 隐私纪律：条目只存映射、哈希与同步状态，不存标题/课程名原文（指导 §2.4 第 5 条）。
 */
@Serializable
data class SchoolSyncFile(
    val formatVersion: Int = SCHOOL_SYNC_FORMAT_VERSION,
    /** 当前连接的远端用户 id；空串 = 未连接，账号切换检测的比对键 */
    val uid: String = "",
    /** 展示用姓名（连接状态卡显示），可空 */
    val accountName: String = "",
    /** 已缓存的头像来源地址：与 `avatar.img` 成对，地址没变且文件在就不再重复下载 */
    val avatarUrl: String = "",
    /** 上次成功同步（commitSync 落盘）时刻 */
    val lastSyncAtMillis: Long = 0,
    /** 上次成功探活（prepare 拿到有效 profile）时刻，3 秒去抖用 */
    val lastProbeAtMillis: Long = 0,
    /**
     * 已处理条目映射（含删除过的）：删除不是「永久忽略」，远端仍列出时同步允许复活——
     * 回收站条目经 restoreTodo 复用 [SchoolSyncEntry.todoId]。
     */
    val entries: List<SchoolSyncEntry> = emptyList(),
)

/** 一条已处理条目的映射与哈希配对（键 = codec.remoteKey，校内唯一） */
@Serializable
data class SchoolSyncEntry(
    val remoteKey: String,
    val todoId: String,
    /** 同步时刻的远端内容哈希 */
    val remoteHash: String,
    /** 同步时刻的本地内容哈希（判断用户是否编辑过用） */
    val localHash: String,
    /**
     * 因「远端条目消失」被自动标记完成的时刻（= 当时写入 Todo.completedAtMillis）。
     * null = 未自动标记过；非 null 时若远端重新出现，且本地完成时刻仍与此值一致，
     * 则同步自动恢复为未完成（用户手动改过完成状态则只清空本基线，不回滚）。
     */
    val autoCompletedAtMillis: Long? = null,
)

/**
 * 通用同步状态持久化（阻塞 I/O，由引擎包到 Dispatchers.IO）：`filesDir/sync/<id>/sync.json`。
 *
 * 只复制原子协议，**不带任何学校的历史迁移**（旧根目录文件、旧 id 口径是各校适配层
 * 自己的事，指导 §2.6）。文件协议：
 * tmp（fsync）→ bak（备份本次将提交的数据）→ rename 原子覆盖主文件；
 * 读取降级 主文件坏 → bak → 双坏改名 `sync.corrupt-<millis>.json` 留证并返回空状态。
 * 主文件缺失（进程在删主与改名之间被杀）同样由 bak 兜底。
 * formatVersion 高于当前版本按损坏处理。
 *
 * `open` + [save] open：单测可注入「落盘失败」替身。
 */
open class SchoolSyncStore(
    /** 应用 filesDir（根目录固定为 `sync/<schoolId>/`） */
    rootDir: File,
    schoolId: String,
) {

    private val root = File(File(rootDir, ROOT_DIR_NAME), schoolId)
    private val main = File(root, MAIN_NAME)
    private val tmp = File(root, TMP_NAME)
    private val bak = File(root, BAK_NAME)

    fun load(): LoadOutcome<SchoolSyncFile> {
        root.mkdirs()

        if (!main.exists()) {
            val bakParsed = parseFile(bak)
            if (bakParsed != null && bakParsed.formatVersion <= SCHOOL_SYNC_FORMAT_VERSION) {
                return LoadOutcome.Loaded(bakParsed, LoadIssue.RestoredFromBackup)
            }
            return LoadOutcome.Fresh(SchoolSyncFile(), LoadIssue.None)
        }

        val mainParsed = parseFile(main)
        if (mainParsed != null && mainParsed.formatVersion <= SCHOOL_SYNC_FORMAT_VERSION) {
            return LoadOutcome.Loaded(mainParsed, LoadIssue.None)
        }

        val bakParsed = parseFile(bak)
        if (bakParsed != null && bakParsed.formatVersion <= SCHOOL_SYNC_FORMAT_VERSION) {
            return LoadOutcome.Loaded(bakParsed, LoadIssue.RestoredFromBackup)
        }

        // 双坏：保留主文件取证，返回空状态（同步映射可重建，不值得惊动用户）
        if (main.exists()) {
            main.renameTo(File(root, "$CORRUPT_PREFIX${System.currentTimeMillis()}.json"))
        }
        return LoadOutcome.Fresh(SchoolSyncFile(), LoadIssue.ResetAfterCorruption)
    }

    open fun save(file: SchoolSyncFile): StoreResult {
        root.mkdirs()
        val text = try {
            ScheduleJson.encodeStorage(file)
        } catch (e: Exception) {
            return StoreResult.Failed("serialize failed: ${e.message}")
        }

        try {
            tmp.outputStream().use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
        } catch (e: Exception) {
            tmp.delete()
            return StoreResult.Failed("write tmp failed: ${e.message}")
        }

        try {
            tmp.copyTo(bak, overwrite = true)
        } catch (_: Exception) {
            // 备份失败不阻断主流程：本次保存的数据本身是完好的
        }

        if (!tmp.renameTo(main)) {
            main.delete()
            if (!tmp.renameTo(main)) {
                tmp.delete()
                return StoreResult.Failed("rename tmp over main failed")
            }
        }
        return StoreResult.Ok
    }

    /** 退出登录/账号切换时物理删除状态文件与损坏隔离文件 */
    fun delete() {
        main.delete()
        tmp.delete()
        bak.delete()
        root.listFiles()
            ?.filter { it.name.startsWith(CORRUPT_PREFIX) }
            ?.forEach { it.delete() }
    }

    private fun parseFile(file: File): SchoolSyncFile? {
        if (!file.exists()) return null
        return try {
            ScheduleJson.decodeStorage<SchoolSyncFile>(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** 新校状态根目录（备份规则按目录整体排除，见 backup_rules.xml / data_extraction_rules.xml） */
        const val ROOT_DIR_NAME = "sync"
        const val MAIN_NAME = "sync.json"
        const val TMP_NAME = "sync.json.tmp"
        const val BAK_NAME = "sync.json.bak"
        const val CORRUPT_PREFIX = "sync.corrupt-"
    }
}
