package com.fangyi.classpp.data.store

import com.fangyi.classpp.data.model.TodoAttachment
import java.io.File
import java.io.InputStream

/**
 * 待办附件的私有文件仓库：目录布局 `todo_attachments/<attachmentId>/<fileName>`。
 *
 * 每条附件独占一个目录，原文件名只做路径分隔符 / 非法字符清洗后保留，因此用系统 App
 * 打开时外部应用看到的仍是可读文件名；目录名用附件 id 隔离，同名文件互不冲突。
 *
 * 纯 JVM 实现（不依赖 Android API）：仓库在 IO 线程调用，单测直接用临时目录验证。
 * 文件生命周期由 [com.fangyi.classpp.data.TodoRepository] 驱动——添加时复制、删除附件 /
 * 彻底删除待办时清理目录、装载时清扫孤儿目录。
 */
class TodoAttachmentStore(private val dir: File) {

    /** 附件文件路径；id / 文件名不安全（可能越出私有目录）时返回 null */
    fun file(attachment: TodoAttachment): File? {
        if (!isSafeId(attachment.id) || !isSafeFileName(attachment.fileName)) return null
        return File(File(dir, attachment.id), attachment.fileName)
    }

    /**
     * 把 [input] 复制成附件文件，返回字节数；流为空、id / 文件名不安全或写入失败返回 null
     * （失败会删掉半成品目录）。
     */
    fun write(id: String, fileName: String, input: InputStream?): Long? {
        if (!isSafeId(id) || !isSafeFileName(fileName) || input == null) return null
        val targetDir = File(dir, id)
        return try {
            targetDir.deleteRecursively()
            if (!targetDir.mkdirs() && !targetDir.isDirectory) return null
            val target = File(targetDir, fileName)
            target.outputStream().use { out ->
                input.use { it.copyTo(out, CopyBufferSize) }
            }
            target.length()
        } catch (_: Exception) {
            targetDir.deleteRecursively()
            null
        }
    }

    /** 删除一条附件的整个目录；id 不安全时忽略 */
    fun delete(attachment: TodoAttachment) {
        if (!isSafeId(attachment.id)) return
        File(dir, attachment.id).deleteRecursively()
    }

    /** 批量删除（彻底删除待办 / 清理过期回收站条目时用） */
    fun deleteAll(attachments: Collection<TodoAttachment>) {
        attachments.forEach(::delete)
    }

    /**
     * 清扫未被任何待办引用的附件目录：只删存在超过 [minAgeMillis] 的条目——应用损坏回退到
     * 旧备份时，刚写入但还没进旧快照的附件不会立刻被误删；显式删除路径不走这里的年龄阈值。
     */
    fun cleanupOrphans(
        referencedIds: Set<String>,
        minAgeMillis: Long = DefaultOrphanMinAgeMillis,
    ) {
        val entries = dir.listFiles() ?: return
        val cutoff = System.currentTimeMillis() - minAgeMillis.coerceAtLeast(0)
        entries.forEach { entry ->
            if (entry.name !in referencedIds && entry.lastModified() <= cutoff) {
                entry.deleteRecursively()
            }
        }
    }

    companion object {
        /** filesDir 下的附件根目录名 */
        const val DIRECTORY_NAME = "todo_attachments"

        /** 孤儿目录默认保留 24 小时再清理 */
        const val DefaultOrphanMinAgeMillis = 24L * 60 * 60 * 1000

        private const val MaxFileNameLength = 120
        private const val MaxIdLength = 64
        private const val CopyBufferSize = 16 * 1024
        private const val FallbackFileName = "attachment"
        private const val IllegalFileNameChars = "<>:\"|?*"

        /**
         * 由原始显示名生成私有目录内的安全文件名：去掉路径段、控制字符与路径/文件系统
         * 非法字符，限制长度并保留扩展名；空名 / `.` / `..` 回落为 `attachment`。
         */
        fun safeFileName(name: String): String {
            val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
            val cleaned = buildString(base.length) {
                base.forEach { ch ->
                    append(
                        if (ch.isISOControl() || ch == '/' || ch == '\\' || ch in IllegalFileNameChars) {
                            '_'
                        } else {
                            ch
                        },
                    )
                }
            }
            val normalized = when {
                cleaned.isEmpty() || cleaned == "." || cleaned == ".." -> FallbackFileName
                else -> cleaned
            }
            if (normalized.length <= MaxFileNameLength) return normalized
            val dot = normalized.lastIndexOf('.')
            val hasExtension = dot > 0 && normalized.length - dot <= 16
            return if (hasExtension) {
                normalized.take(MaxFileNameLength - (normalized.length - dot)) + normalized.substring(dot)
            } else {
                normalized.take(MaxFileNameLength)
            }
        }

        /** 校验持久化的文件名是安全纯文件名（无路径分隔符 / 控制字符 / Windows 非法字符） */
        fun isSafeFileName(name: String): Boolean {
            if (name.isEmpty() || name.length > 255) return false
            if (name == "." || name == "..") return false
            return name.none { ch ->
                ch == '/' || ch == '\\' || ch.isISOControl() || ch in IllegalFileNameChars
            }
        }

        /** 校验附件 id 可安全用作目录名（UUID 形态：字母、数字、连字符、下划线） */
        fun isSafeId(id: String): Boolean {
            if (id.isEmpty() || id.length > MaxIdLength) return false
            if (id == "." || id == "..") return false
            return id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        }
    }
}
