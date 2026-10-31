package com.fangyi.classpp.data.xmu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 厦门大学课程中心（TronClass LMS，lnt.xmu.edu.cn）只读接口的远端 DTO。
 *
 * 字段以 2026-10-30 同源实测为准（指导文档 §2.2/§2.3）；平台加字段靠
 * [com.fangyi.classpp.data.ScheduleJson] 的 ignoreUnknownKeys 容错，不崩、不丢本地数据。
 *
 * 类型纪律：`id`/`course_id` 用 Long（防 activity id 增长溢出 Int）、`course_code` 是
 * 23 位数字串必须按 String 解析（进数值类型会丢精度）、`end_time` 按可空处理。
 */
@Serializable
data class XmuTodoListDto(
    // 不允许缺省：缺 key 是平台改版/错误响应的信号，必须走 FormatChanged，
    // 不能静默解析成空列表让 UI 报「已是最新」
    @SerialName("todo_list") val todoList: List<XmuTodoDto>,
)

/** `/api/todos` 的单条待办；`type` 实测只有 "homework"，其他取值未知、解析保持容错 */
@Serializable
data class XmuTodoDto(
    val id: Long = 0,
    val title: String = "",
    val type: String = "",
    @SerialName("course_id") val courseId: Long = 0,
    @SerialName("course_code") val courseCode: String = "",
    @SerialName("course_name") val courseName: String = "",
    @SerialName("course_type") val courseType: Int = 0,
    @SerialName("end_time") val endTime: String? = null,
    @SerialName("is_locked") val isLocked: Boolean = false,
    @SerialName("is_student") val isStudent: Boolean = false,
)

/**
 * `/api/profile` 的账号信息（同步前探活 + 账号一致性比对 + 展示）。
 * 实测返回含 id / user_no / name；其余字段 ignoreUnknownKeys 忽略。
 */
@Serializable
data class XmuProfileDto(
    val id: Long = 0,
    @SerialName("user_no") val userNo: String = "",
    val name: String = "",
)
