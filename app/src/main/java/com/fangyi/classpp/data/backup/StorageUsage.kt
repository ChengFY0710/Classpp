package com.fangyi.classpp.data.backup

import android.content.Context
import com.fangyi.classpp.ui.day.DayImageStore
import java.io.File

/**
 * 存储空间用量三段（数据管理页的用量条与图例）。
 *
 * 口径：
 * - [appBytes]：安装包本体（base APK + split APK）；原生库与 dex 都在 APK 内，不另计。
 * - [userBytes]：用户内容 = 4 个整库 JSON + `day_images/` + `shared_prefs/`。
 *   整库的 `.tmp` / `.bak` / `.corrupt-*` 是恢复副本与损坏隔离，不计入（避免同一份数据
 *   被重复统计）；`xmu/`（账号头像、同步状态）与 `wakeup/`（可再下载的脚本缓存）不计入。
 * - [cacheBytes]：`cacheDir` + `codeCacheDir`（Coil 图片缓存落在 cacheDir）。
 */
data class StorageUsage(
    val appBytes: Long,
    val userBytes: Long,
    val cacheBytes: Long,
) {
    val totalBytes: Long get() = appBytes + userBytes + cacheBytes
}

/**
 * 用量计算与缓存清理（阻塞 I/O，调用方包 Dispatchers.IO）。
 * 求和逻辑抽成接收 [File] 的纯函数，JVM 单测可直接用 TemporaryFolder 覆盖。
 */
object StorageUsageReader {

    /** 计入「用户数据」的整库文件（与各 Store 的 MAIN_NAME 同源） */
    private val UserDataFiles = listOf("schedules.json", "todos.json", "tags.json", "ai_chat.json")

    fun read(context: Context): StorageUsage = StorageUsage(
        appBytes = appBytes(context),
        userBytes = userBytes(context),
        cacheBytes = dirBytes(context.cacheDir) + dirBytes(context.codeCacheDir),
    )

    /** 用户数据 = 4 个整库 + 背景图目录 + 偏好文件目录（递归） */
    fun userBytes(context: Context): Long {
        val filesDir = context.filesDir
        return UserDataFiles.sumOf { File(filesDir, it).length() } +
            dirBytes(DayImageStore.imageDir(context)) +
            dirBytes(sharedPrefsDir(context))
    }

    /** 应用大小 = 安装包（base + split，去重后求和） */
    fun appBytes(context: Context): Long {
        val info = context.applicationInfo
        val paths = buildList {
            add(info.sourceDir)
            info.publicSourceDir?.let(::add)
            info.splitSourceDirs?.let { addAll(it) }
        }
        return paths.distinct().sumOf { path -> File(path).length() }
    }

    /**
     * 目录总字节数（递归）。目录不存在或不可读返回 0；不在统计里做链接/权限的额外
     * 防御——这些目录都由应用自己创建，不产生符号链接。
     */
    fun dirBytes(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0
        val files = dir.listFiles() ?: return 0
        return files.sumOf { file ->
            when {
                file.isDirectory -> dirBytes(file)
                file.isFile -> file.length()
                else -> 0
            }
        }
    }

    /**
     * 清空缓存目录（只删内容，保留目录本身）：`cacheDir` 与 `codeCacheDir`。
     * 逐项 try-catch——单个文件被占用不应阻断其余清理。
     */
    fun clearCache(context: Context) {
        listOf(context.cacheDir, context.codeCacheDir).forEach { dir ->
            dir?.listFiles()?.forEach { child -> runCatching { child.deleteRecursively() } }
        }
    }

    private fun sharedPrefsDir(context: Context): File =
        File(context.applicationInfo.dataDir, "shared_prefs")
}
