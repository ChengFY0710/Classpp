package com.fangyi.classpp.data.backup

import com.fangyi.classpp.data.ScheduleJson
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 备份包结构非法（缺 manifest、entry 名越界、解压体积超限等） */
class BackupArchiveException(message: String) : Exception(message)

/**
 * 备份 ZIP 的读写实现（纯 JVM，不依赖 Context，便于单元测试）。
 *
 * 包结构（固定、entry 名白名单）：
 * ```
 * manifest.json             备份清单（第一个 entry，导入时可只读它做预检）
 * data/schedules.json       课表整库（ScheduleFile）
 * data/todos.json           待办整库（TodoFile，含回收站/归档/分组/步骤）
 * data/tags.json            标签整库（TagFile）
 * data/ai_chat.json         AI 对话整库（AiChatFile）
 * data/day_images.json      背景图图库元数据（BackupDayImages）
 * images/<name>.jpg         背景图原文件（逐张流式写入，不攒内存）
 * ```
 *
 * 安全约束：只接受上述白名单 entry 名；图片名必须是纯文件名（无路径分隔符），
 * 因此解压天然不会写出目标目录（zip-slip）。单 entry 与整包解压体积都设上限，
 * 防压缩炸弹。
 */
object BackupArchive {
    const val ENTRY_MANIFEST = "manifest.json"
    const val ENTRY_SCHEDULES = "data/schedules.json"
    const val ENTRY_TODOS = "data/todos.json"
    const val ENTRY_TAGS = "data/tags.json"
    const val ENTRY_AI_CHAT = "data/ai_chat.json"
    const val ENTRY_DAY_IMAGES = "data/day_images.json"
    const val IMAGE_ENTRY_PREFIX = "images/"

    /** manifest 上限：正常只有几百字节，超过视为损坏 */
    private const val MaxManifestBytes = 1L * 1024 * 1024

    /** 单个 entry 的解压上限（背景图单张远小于此值） */
    private const val MaxEntryBytes = 128L * 1024 * 1024

    /** 整包解压上限 */
    private const val MaxTotalBytes = 1024L * 1024 * 1024

    private val FixedEntries = setOf(
        ENTRY_MANIFEST,
        ENTRY_SCHEDULES,
        ENTRY_TODOS,
        ENTRY_TAGS,
        ENTRY_AI_CHAT,
        ENTRY_DAY_IMAGES,
    )

    /** 导出所需内容：各整库 JSON 文本 + 图片源目录与文件名列表 */
    data class Payload(
        val manifest: BackupManifest,
        val schedulesJson: String,
        val todosJson: String,
        val tagsJson: String,
        val aiChatJson: String,
        val dayImagesJson: String,
        val imageDir: File,
        val imageNames: List<String>,
    )

    /** 按固定结构写出 ZIP；流由调用方提供（导出走 contentResolver） */
    fun write(out: OutputStream, payload: Payload) {
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            // manifest 必须在最前：导入预检只读到它即可停止
            zip.putNextEntry(ZipEntry(ENTRY_MANIFEST))
            zip.writeText(ScheduleJson.encodeStorage(payload.manifest))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(ENTRY_SCHEDULES))
            zip.writeText(payload.schedulesJson)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(ENTRY_TODOS))
            zip.writeText(payload.todosJson)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(ENTRY_TAGS))
            zip.writeText(payload.tagsJson)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(ENTRY_AI_CHAT))
            zip.writeText(payload.aiChatJson)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(ENTRY_DAY_IMAGES))
            zip.writeText(payload.dayImagesJson)
            zip.closeEntry()

            // 图片逐张流转：内存里始终只有一块缓冲区。清单里声明了却读不到的文件
            // 直接判失败——宁可导出失败，也不生成「元数据说有、包里没有」的残缺备份
            payload.imageNames.filter(::isSafeImageName).distinct().forEach { name ->
                val file = File(payload.imageDir, name)
                if (!file.isFile) throw BackupArchiveException("missing image: $name")
                zip.putNextEntry(ZipEntry(IMAGE_ENTRY_PREFIX + name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * 只读到 manifest 即返回（导入前确认弹窗的预检）：不解析其余 entry，也不解压图片。
     * 找不到 manifest 或解析失败返回 null，由调用方映射为「无法识别该备份」。
     */
    fun readManifest(input: InputStream): BackupManifest? {
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.isDirectory) continue
                if (entry.name != ENTRY_MANIFEST) continue
                val bytes = zip.readAtMost(MaxManifestBytes) ?: return null
                return runCatching {
                    ScheduleJson.decodeStorage<BackupManifest>(bytes.toString(Charsets.UTF_8))
                }.getOrNull()
            }
        }
    }

    /**
     * 解压到 [targetDir]（调用方负责清理）。只写白名单 entry；图片名必须为纯文件名，
     * 因此不存在 zip-slip 写出目标目录的路径。
     *
     * @throws BackupArchiveException entry 名越界、单 entry / 整包超限
     */
    fun extract(input: InputStream, targetDir: File) {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw BackupArchiveException("cannot create staging dir")
        }
        var totalBytes = 0L
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (!isAllowedEntry(entry.name)) {
                    throw BackupArchiveException("unexpected entry: ${entry.name}")
                }
                val target = File(targetDir, entry.name)
                target.parentFile?.mkdirs()
                var entryBytes = 0L
                target.outputStream().use { out ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        totalBytes += read
                        if (entryBytes > MaxEntryBytes || totalBytes > MaxTotalBytes) {
                            throw BackupArchiveException("entry too large: ${entry.name}")
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    /** 背景图文件名安全判定：纯文件名、非空、不含路径分隔符、非 . / .. */
    fun isSafeImageName(name: String): Boolean {
        if (name.isEmpty() || name.length > 255) return false
        if (name == "." || name == "..") return false
        if (name.contains('/') || name.contains('\\')) return false
        if (name.contains(File.separatorChar)) return false
        return true
    }

    private fun isAllowedEntry(name: String): Boolean {
        if (name in FixedEntries) return true
        if (!name.startsWith(IMAGE_ENTRY_PREFIX)) return false
        return isSafeImageName(name.removePrefix(IMAGE_ENTRY_PREFIX))
    }

    private fun ZipOutputStream.writeText(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
    }

    /**
     * 读取当前 entry 至多 [limit] 字节；超过上限或读失败返回 null。
     * ZipInputStream 的 read 在当前 entry 末尾返回 -1，不会跨 entry。
     */
    private fun ZipInputStream.readAtMost(limit: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
