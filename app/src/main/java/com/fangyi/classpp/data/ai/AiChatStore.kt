package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.store.LoadIssue
import com.fangyi.classpp.data.store.LoadOutcome
import com.fangyi.classpp.data.store.StoreResult
import java.io.File
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * 对话历史整库信封：ai_chat.json 的内容（system prompt 每次现拼，不入文件）。
 *
 * v2 起为多对话模型：[conversations] 持有全部对话、[activeConversationId] 指向当前
 * 活跃对话；[messages] 是 v1 的顶层消息列表，仅作旧文件迁移读取用——迁移后恒为
 * 空列表（仓库 bootstrap 负责 v1 → v2 的包装与重写落盘）。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AiChatFile(
    // 整库 lean 写入时版本号仍强制落盘（见 ScheduleFile 同款说明）
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val formatVersion: Int = AI_CHAT_FORMAT_VERSION,
    val conversations: List<AiConversation> = emptyList(),
    val activeConversationId: String? = null,
    val messages: List<AiChatMessage> = emptyList(),
)

/**
 * 对话历史持久化。实现为阻塞 I/O，由仓库包到 Dispatchers.IO。
 * 与 [com.fangyi.classpp.data.store.FileTodoStore] 同一套文件协议，只是文件与信封类型不同。
 */
interface AiChatStore {
    fun load(): LoadOutcome<AiChatFile>

    /** 完整落盘：tmp fsync + bak + rename（最终回复/管理操作） */
    fun save(file: AiChatFile): StoreResult

    /**
     * 流式中的半截 checkpoint：最终落盘会兜底，checkpoint 只求“进程被杀时尽量少丢”，
     * 不值得每次 fsync + 复制整库 bak。默认退化为 [save]（测试 fake / 其它实现零改动）。
     */
    fun saveCheckpoint(file: AiChatFile): StoreResult = save(file)
}

/**
 * 基于单 JSON 文件的实现，写入为 tmp+rename 提交流程，与 FileTodoStore 完全一致
 * （协议细节见其 KDoc）：tmp（fsync）→ bak（备份本次将提交的数据）→ rename 覆盖主文件；
 * 读取降级 主文件坏 → bak → 双坏改名 `ai_chat.corrupt-<millis>.json` 留证并返回空库；
 * 主文件缺失（进程在删主与改名之间被杀）同样由 bak 兜底。
 * `formatVersion` 高于当前版本按损坏处理（对话历史可弃，降级语义无害）。
 */
class FileAiChatStore(private val dir: File) : AiChatStore {

    private val main = File(dir, MAIN_NAME)
    private val tmp = File(dir, TMP_NAME)
    private val bak = File(dir, BAK_NAME)

    override fun load(): LoadOutcome<AiChatFile> {
        if (!dir.exists()) dir.mkdirs()

        if (!main.exists()) {
            // 没有主文件不等于没有数据：save 先写 bak 再 rename，崩在中间时只剩 bak
            val bakParsed = parseFile(bak)
            if (bakParsed != null && bakParsed.formatVersion <= AI_CHAT_FORMAT_VERSION) {
                return LoadOutcome.Loaded(bakParsed, LoadIssue.RestoredFromBackup)
            }
            return LoadOutcome.Fresh(AiChatFile(), LoadIssue.None)
        }

        // 主文件可解析且版本兼容 → 正常载入；否则（损坏或未来版本）降级试 bak
        val mainParsed = parseFile(main)
        if (mainParsed != null && mainParsed.formatVersion <= AI_CHAT_FORMAT_VERSION) {
            return LoadOutcome.Loaded(mainParsed, LoadIssue.None)
        }

        val bakParsed = parseFile(bak)
        if (bakParsed != null && bakParsed.formatVersion <= AI_CHAT_FORMAT_VERSION) {
            return LoadOutcome.Loaded(bakParsed, LoadIssue.RestoredFromBackup)
        }

        // 双坏：保留主文件取证，返回空库
        if (main.exists()) {
            val quarantine = File(dir, "$CORRUPT_PREFIX${System.currentTimeMillis()}.json")
            main.renameTo(quarantine)
        }
        return LoadOutcome.Fresh(AiChatFile(), LoadIssue.ResetAfterCorruption)
    }

    override fun save(file: AiChatFile): StoreResult = saveInternal(file, durable = true)

    override fun saveCheckpoint(file: AiChatFile): StoreResult =
        saveInternal(file, durable = false)

    /**
     * @param durable true = 最终/管理落盘：tmp fsync + 复制 bak；false = 流式 checkpoint：
     *   只写 tmp 并 rename（文件仍是原子替换，崩溃最多丢最近 5s 的半截文本），
     *   不承担整库 bak 的写放大与 fsync 等待。
     */
    private fun saveInternal(file: AiChatFile, durable: Boolean): StoreResult {
        if (!dir.exists()) dir.mkdirs()
        val text = try {
            AiJson.storageLean.encodeToString(AiChatFile.serializer(), file)
        } catch (e: Exception) {
            return StoreResult.Failed("serialize failed: ${e.message}")
        }

        // 1. tmp 落盘（checkpoint 不 fsync：写合并阶段允许掉电丢最后一小段）
        try {
            tmp.outputStream().use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                if (durable) out.fd.sync()
            }
        } catch (e: Exception) {
            tmp.delete()
            return StoreResult.Failed("write tmp failed: ${e.message}")
        }

        // 2. tmp → bak（仅完整落盘；checkpoint 不复制整库，避免流式期间常驻写放大）
        if (durable) {
            try {
                tmp.copyTo(bak, overwrite = true)
            } catch (_: Exception) {
                // 备份失败不阻断主流程：本次保存的数据本身是完好的
            }
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

    private fun parseFile(file: File): AiChatFile? {
        if (!file.exists()) return null
        return try {
            AiJson.storage.decodeFromString(AiChatFile.serializer(), file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val MAIN_NAME = "ai_chat.json"
        const val TMP_NAME = "ai_chat.json.tmp"
        const val BAK_NAME = "ai_chat.json.bak"
        const val CORRUPT_PREFIX = "ai_chat.corrupt-"
    }
}
