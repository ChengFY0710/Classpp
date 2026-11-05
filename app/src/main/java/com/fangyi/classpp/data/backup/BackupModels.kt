package com.fangyi.classpp.data.backup

import kotlinx.serialization.Serializable

/** 备份包格式版本；读取时拒绝更高版本（与各整库 formatVersion 同策略） */
const val BACKUP_FORMAT_VERSION = 1

/** 备份包类型标识：manifest 里与 formatVersion 一起构成合法性闸门 */
const val BACKUP_KIND = "classpp.backup"

/**
 * 备份包清单（manifest.json，ZIP 的第一个 entry）。
 *
 * 只描述「这个包是什么、由哪个版本导出、里面有多少内容」——真正可还原的数据在
 * `data` 目录的 JSON 与 `images` 目录的图片里。计数用于导入前的覆盖确认弹窗，也让
 * 用户能在解压后快速核对包内容。
 */
@Serializable
data class BackupManifest(
    val formatVersion: Int = BACKUP_FORMAT_VERSION,
    val kind: String = BACKUP_KIND,
    val appVersionName: String = "",
    val exportedAtMillis: Long = 0,
    val counts: BackupCounts = BackupCounts(),
)

/** 备份内容计数（导入确认文案用） */
@Serializable
data class BackupCounts(
    val schedules: Int = 0,
    val courses: Int = 0,
    val todos: Int = 0,
    val tags: Int = 0,
    val aiMessages: Int = 0,
    val dayImages: Int = 0,
)

/**
 * 日程背景图图库的持久化元数据（day_image_prefs 的备份投影）。
 *
 * 图片文件本身在 `images/<name>` 里；这里只带「有哪些图、选中哪张、随机轮播与
 * 视差开关」——与 [com.fangyi.classpp.ui.day.DayImageStore] 的字段一一对应。
 * `randomPick`（当日命中）属于当天临时状态，不备份：恢复后由当前日期重新抽取。
 */
@Serializable
data class BackupDayImages(
    val formatVersion: Int = BACKUP_FORMAT_VERSION,
    val images: List<String> = emptyList(),
    val selected: String? = null,
    val randomEnabled: Boolean = false,
    val tiltEnabled: Boolean = true,
)

/** manifest 是否可被当前版本读取：类型正确且版本不高于当前 */
fun BackupManifest.isSupported(): Boolean =
    kind == BACKUP_KIND && formatVersion <= BACKUP_FORMAT_VERSION
