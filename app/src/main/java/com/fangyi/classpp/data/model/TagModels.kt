package com.fangyi.classpp.data.model

import kotlinx.serialization.Serializable

/** 标签存储文件的格式版本；读取时拒绝更高版本 */
const val TAG_FORMAT_VERSION = 1

/**
 * 一条用户自定义标签。名字即唯一键——Todo.tags 存的是标签名字符串，改名意味着
 * 全量待办回写，本期不提供改名；此模型只承载数据，合法性由 TagRepository 写入前
 * 归一化保证（trim 非空、库内不重名）。
 *
 * 字段全部带默认值：旧文件缺字段照常反序列化，新字段被旧版本 ignoreUnknownKeys
 * 忽略（前向兼容，无版本号变更），与 CourseEntry.note 同策略。
 *
 * @param name 标签名
 * @param colorArgb 标签色 ARGB（无符号 0xAARRGGBB，UI 层用 Color(Long) 还原）；
 *   null = 跟随主题 Primary（深浅色各自取值），颜色选择器接入后写入具体色值
 */
@Serializable
data class TagEntry(
    val name: String,
    val colorArgb: Long? = null,
)

/** 标签整库文件信封：tags.json 的内容 */
@Serializable
data class TagFile(
    val formatVersion: Int = TAG_FORMAT_VERSION,
    val tags: List<TagEntry> = emptyList(),
)
