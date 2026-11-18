package com.fangyi.classpp.data.shiguang

import com.fangyi.classpp.data.wakeup.WakeupCourse
import com.fangyi.classpp.data.wakeup.WakeupCourseConfig
import com.fangyi.classpp.data.wakeup.WakeupTimeSlot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 拾光适配脚本回传的结果信封（shim 写入页面变量，原生轮询后解析）。
 *
 * 脚本桥接协议与 Wakeup 同源：课程/作息/学期配置字段完全一致，因此直接复用
 * `data.wakeup` 的三个脚本模型；[errorCode] 是 shim 自有的失败分类（脚本原文可能为
 * 空或不可读），`error` 保留脚本给出的可读文案。
 */
@Serializable
internal data class ShiguangScriptResult(
    val ok: Boolean = false,
    val errorCode: String? = null,
    val error: String? = null,
    val courses: List<WakeupCourse>? = null,
    val timeSlots: List<WakeupTimeSlot>? = null,
    val config: WakeupCourseConfig? = null,
)

/** shim 失败分类：由 UI 映射成本地化文案 */
internal object ShiguangErrorCode {
    const val NO_COURSES = "NO_COURSES"
    const val INVALID_DATA = "INVALID_DATA"
    const val SCRIPT_ERROR = "SCRIPT_ERROR"
}

/** 脚本 JSON 不严格（脚本可能输出 null/缺字段/多余字段）：宽松解析，映射阶段再校验 */
internal val ShiguangScriptJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = false
}

internal fun parseShiguangScriptResult(raw: String): ShiguangScriptResult? =
    runCatching { ShiguangScriptJson.decodeFromString<ShiguangScriptResult>(raw) }.getOrNull()
