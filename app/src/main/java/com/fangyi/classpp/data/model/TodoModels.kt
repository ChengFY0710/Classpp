package com.fangyi.classpp.data.model

import kotlinx.serialization.Serializable

/** 待办存储文件与分享信封的格式版本；导入时拒绝更高版本 */
const val TODO_FORMAT_VERSION = 1

/** 待办时间的时刻形态：全天 / 时段 / 无。"无"表示没有时刻语义（可以只有日期，也可以全无） */
@Serializable
enum class TodoTimeKind { AllDay, Period, None }

/** 待办紧急程度：非常急 / 很急 / 急 / 不急 / 无（默认无） */
@Serializable
enum class TodoUrgency { Critical, High, Medium, Low, None }

/** 一条待办步骤（小待办）：可勾选的子项，标题必填 */
@Serializable
data class TodoStep(
    val id: String,
    val title: String,
    val done: Boolean = false,
)

/**
 * 一条待办。合法性由 TodoValidator 全量校验；此模型只承载数据。
 *
 * 字段全部带默认值：旧文件缺字段照常反序列化，新字段被旧版本 ignoreUnknownKeys
 * 忽略（前向兼容，无版本号变更），与 CourseEntry.note 同策略。
 *
 * @param dates 待办日期，可不连续多天；多天共用同一套时刻设置（如"周三和周五 14:00-16:00"），
 *   写入前由仓库 distinct + 排序归一化
 * @param timeKind 时刻形态；[TodoTimeKind.Period] 时 [startMinute]/[endMinute] 必须有效
 * @param startMinute 时段开始（当天分钟数 0..1439，展示用 TimeText.format）
 * @param endMinute 时段结束，须大于 [startMinute]
 * @param deadlineDate 截止日期（只此一天）与 [deadlineMinute] 成对出现或同时为空
 * @param deadlineMinute 截止时刻（当天分钟数）
 * @param tags 纯字符串标签；与课表课程标签的联动后续再做
 * @param completed 完成情况，新建默认 false
 * @param completedAtMillis 完成时刻（epoch millis），不变式：completed=false 时必为 null，
 *   由仓库在归一化时维护
 * @param createdAtMillis 创建时刻（epoch millis），由仓库在 addTodo 时补齐
 */
@Serializable
data class Todo(
    val id: String,
    val name: String,
    val dates: List<IsoDate> = emptyList(),
    val timeKind: TodoTimeKind = TodoTimeKind.None,
    val startMinute: Int? = null,
    val endMinute: Int? = null,
    val deadlineDate: IsoDate? = null,
    val deadlineMinute: Int? = null,
    val location: String = "",
    val tags: List<String> = emptyList(),
    val urgency: TodoUrgency = TodoUrgency.None,
    val note: String = "",
    val steps: List<TodoStep> = emptyList(),
    val completed: Boolean = false,
    val completedAtMillis: Long? = null,
    val createdAtMillis: Long = 0,
)

/** 待办整库文件信封：todos.json 的内容 */
@Serializable
data class TodoFile(
    val formatVersion: Int = TODO_FORMAT_VERSION,
    val todos: List<Todo> = emptyList(),
)
