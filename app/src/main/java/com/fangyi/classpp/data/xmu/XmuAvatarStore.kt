package com.fangyi.classpp.data.xmu

import java.io.File

/**
 * 已缓存的头像：文件 + 版本号（= 文件修改时间）。
 *
 * 版本号是给 UI 用的：换头像时真实路径不变（`avatar.img` 就地覆盖），`File` 相等会让
 * StateFlow 不去重、Coil 的 FileKeyer 之外的重组也被跳过——把版本塞进 Compose 的 `key`
 * 才能真的重建请求、换成新图。
 */
data class XmuAvatarImage(val file: File, val version: Long)

/**
 * 课程中心头像缓存（`filesDir/xmu/avatar.img`）：单账号单文件，退出登录/账号切换时随
 * [XmuSyncRepository.resetSyncData] 一并删除。
 *
 * 与同步状态文件同处 `filesDir/xmu/`——备份与设备迁移规则按目录整体排除（见
 * `backup_rules.xml` / `data_extraction_rules.xml`），头像不会进入云备份。
 *
 * 落盘先写 `avatar.img.tmp` 再 rename：进程半途被杀不会留下半张图。
 */
class XmuAvatarStore(dir: File) {

    private val root = File(dir, XmuSyncStore.DIR_NAME)
    private val main = File(root, FILE_NAME)
    private val tmp = File(root, TMP_NAME)

    /** 已缓存的头像（null = 还没有）；阻塞 I/O，调用方包到 Dispatchers.IO */
    fun cachedImage(): XmuAvatarImage? =
        main.takeIf { it.length() > 0 }?.let { XmuAvatarImage(it, it.lastModified()) }

    /**
     * 写入头像原始字节：空数据或超过 [MAX_BYTES] 直接拒绝（调用方已做过内容校验，
     * 这里只兜底磁盘与体积）。返回新缓存用于刷新 UI，null 表示没写成——头像纯装饰，
     * 调用方静默跳过。
     */
    fun save(bytes: ByteArray): XmuAvatarImage? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        return try {
            root.mkdirs()
            tmp.outputStream().use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            // rename 不能覆盖已存在的文件（Windows 直接失败）：先删旧图再换名
            if (!tmp.renameTo(main)) {
                main.delete()
                if (!tmp.renameTo(main)) {
                    tmp.delete()
                    return null
                }
            }
            cachedImage()
        } catch (_: Exception) {
            tmp.delete()
            null
        }
    }

    fun delete() {
        main.delete()
        tmp.delete()
    }

    companion object {
        private const val FILE_NAME = "avatar.img"
        private const val TMP_NAME = "avatar.img.tmp"

        /** 头像体积上限 2MB：正常头像远小于它，超限按异常响应丢弃（不落盘、不重试） */
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}
