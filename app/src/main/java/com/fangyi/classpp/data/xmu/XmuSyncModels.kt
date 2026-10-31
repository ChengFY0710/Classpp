package com.fangyi.classpp.data.xmu

import kotlinx.serialization.Serializable

/**
 * xmu-sync.json 的信封版本（独立于 todos.json 的 TODO_FORMAT_VERSION）。
 *
 * 2：本地 id 口径改为 `xmu-{courseId}-{activityId}`（v1 为 `xmu-{activityId}`）。
 * v1 文件仍可读取：旧条目按 [XmuSyncEntry.todoId] 沿用旧 id，映射丢失时按
 * [XmuTodoMapper.legacyLocalId] 兜底找回；首次落盘会写成新版本号。
 */
const val XMU_SYNC_FORMAT_VERSION = 2

/**
 * 课程中心同步状态文件（`filesDir/xmu/xmu-sync.json`）：远端 id ↔ 本地 id 的映射、
 * 内容哈希、账号 id 与上次同步/探活时刻。与 todos.json 完全独立——同步失败/损坏
 * 不波及用户待办库。
 *
 * 隐私纪律：条目只存哈希，不存标题/课程名原文，缩小本地隐私面（指导 §5.1）。
 */
@Serializable
data class XmuSyncFile(
    val formatVersion: Int = XMU_SYNC_FORMAT_VERSION,
    /** 当前连接的课程中心用户 id（/api/profile 的 id），账号切换检测的比对键 */
    val uid: String = "",
    /** 展示用姓名（连接状态卡显示），可空 */
    val accountName: String = "",
    /** 上次成功同步（commitSync 落盘）时刻 */
    val lastSyncAtMillis: Long = 0,
    /** 上次成功探活（prepareSync 拉到有效 profile）时刻，3 秒去抖用 */
    val lastProbeAtMillis: Long = 0,
    /**
     * 已处理条目映射（含删除过的）：删除不是「永久忽略」，远端仍列出时同步允许复活——
     * 回收站条目经 restoreTodo 复用 [XmuSyncEntry.todoId]，彻底删除的按新增重新导入。
     */
    val entries: List<XmuSyncEntry> = emptyList(),
)

/** 一条已处理条目的映射与哈希配对 */
@Serializable
data class XmuSyncEntry(
    val remoteId: Long,
    val courseId: Long,
    val todoId: String,
    /** 同步时刻的远端内容哈希，P2 判断远端是否变化用 */
    val remoteHash: String,
    /** 同步时刻的本地内容哈希，P2 判断用户是否编辑过用 */
    val localHash: String,
)
