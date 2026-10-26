package com.fangyi.classpp.data.wakeup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wakeup 分享口令导入的中间数据模型：适配脚本（wake_up.js）经 JS 桥回传的原始 JSON 形态。
 * 字段名是**脚本侧写死的契约**（见指南「桥接协议契约」），只加不改；解析走宽松配置，
 * 缺字段回落默认值，映射成 Classpp 课表时再做合法性过滤。
 */

/**
 * 脚本 `saveImportedCourses` 回传的单条课程。
 * @param day 星期几（1~7）
 * @param weeks 上课周次列表（脚本已按单双周展开；Wakeup 会把同一门课拆成多条下发）
 */
@Serializable
data class WakeupCourse(
    val name: String = "",
    val teacher: String? = null,
    val position: String? = null,
    val day: Int = 0,
    val weeks: List<Int> = emptyList(),
    val startSection: Int = 0,
    val endSection: Int = 0,
    val isCustomTime: Boolean = false,
    val customStartTime: String? = null,
    val customEndTime: String? = null,
)

/**
 * 脚本 `savePresetTimeSlots` 回传的作息条目。脚本字段为 `node`，契约文档另有 `number`
 * 形态，两者兼容（取正数者）。
 */
@Serializable
data class WakeupTimeSlot(
    val number: Int = 0,
    val node: Int = 0,
    val startTime: String? = null,
    val endTime: String? = null,
) {
    /** 节次编号（1-based；两者都缺/非正时该条在映射时被丢弃） */
    val slotNumber: Int get() = if (number > 0) number else node
}

/** 脚本 `saveCourseConfig` 回传的学期配置（均可缺省，映射时各自兜底） */
@Serializable
data class WakeupCourseConfig(
    /** 开学日期，脚本侧已格式化为 yyyy-MM-dd */
    val semesterStartDate: String? = null,
    val semesterTotalWeeks: Int = 0,
    /** 每节时长（分钟），写入课表的 slotDurationMinutes */
    val defaultClassDuration: Int = 0,
    /** 节间休息（分钟），课程超出作息范围时续生成新节用 */
    val defaultBreakDuration: Int = 0,
)

/** 导入结果聚合：课程 + 可选作息 + 可选学期配置 */
data class WakeupSharePayload(
    val courses: List<WakeupCourse> = emptyList(),
    val timeSlots: List<WakeupTimeSlot> = emptyList(),
    val config: WakeupCourseConfig = WakeupCourseConfig(),
)

/** 脚本输出不严格（字段可缺、可空）：宽松解析，缺字段回落默认值 */
internal val WakeupScriptJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = false
}
