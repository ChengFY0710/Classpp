package com.fangyi.classpp.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 三套 Json 配置：
 * - [storage]：带默认值的通用存储/传输载荷（同步状态、远端声明、凭据密文等），
 *   encodeDefaults 保持 true——这些文件不是高频写放大来源，且部分对端脚本/旧版本
 *   依赖显式字段；
 * - [storageLean]：四个应用整库（课表/待办/标签/AI 历史）专用的紧凑输出，
 *   encodeDefaults = false（缺省字段不落盘，体积降 20%~40%）；解码仍用 [storage]，
 *   缺字段由 Kotlin 默认值补齐，前向/后向兼容语义不变。整库信封的 formatVersion
 *   用 @EncodeDefault(ALWAYS) 强制落盘，版本闸门不受影响；
 * - [share]：分享载荷 pretty-print，便于用户肉眼核对/手工编辑。
 * 三者都 ignoreUnknownKeys（前向兼容）+ 严格模式。
 */
object ScheduleJson {
    val storage: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = false
        prettyPrint = false
    }

    val storageLean: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = false
        prettyPrint = false
    }

    val share: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = false
        prettyPrint = true
    }

    inline fun <reified T> decodeStorage(text: String): T = storage.decodeFromString(text)

    inline fun <reified T> encodeStorage(value: T): String = storage.encodeToString(value)

    /** 整库紧凑编码：缺省字段省略；解码统一走 [decodeStorage]（默认值补齐） */
    inline fun <reified T> encodeStorageLean(value: T): String =
        storageLean.encodeToString(value)

    inline fun <reified T> decodeShare(text: String): T = share.decodeFromString(text)

    inline fun <reified T> encodeShare(value: T): String = share.encodeToString(value)
}
