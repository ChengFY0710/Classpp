package com.fangyi.classpp.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 两套 Json 配置：
 * - [storage]：本地整库文件，紧凑输出；
 * - [share]：分享载荷 pretty-print，便于用户肉眼核对/手工编辑。
 * 两者都 ignoreUnknownKeys（前向兼容）+ encodeDefaults（缺省值也落盘）+ 严格模式。
 */
object ScheduleJson {
    val storage: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
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

    inline fun <reified T> decodeShare(text: String): T = share.decodeFromString(text)

    inline fun <reified T> encodeShare(value: T): String = share.encodeToString(value)
}
