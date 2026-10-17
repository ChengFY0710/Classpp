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
 * @param repeat 重复规则（自定义日期的重复），见 [RepeatRule]；无重复语义时为默认值
 * @param timeKind 时刻形态；[TodoTimeKind.Period] 时 [startMinute]/[endMinute] 必须有效
 * @param startMinute 时段开始（当天分钟数 0..1439，展示用 TimeText.format）
 * @param endMinute 时段结束，须大于 [startMinute]
 * @param deadlineDate 截止日期（只此一天）与 [deadlineMinute] 成对出现或同时为空
 * @param deadlineMinute 截止时刻（当天分钟数）
 * @param tags 纯字符串标签；与课表课程标签的联动后续再做
 * @param group 所属分组名（[TodoGroup] 的身份键），null = 未分组。引用完整性由仓库在
 *   归一化时维护：trim、空串与引用不存在的组一律清回 null
 * @param completed 完成情况，新建默认 false
 * @param completedAtMillis 完成时刻（epoch millis），不变式：completed=false 时必为 null，
 *   由仓库在归一化时维护
 * @param createdAtMillis 创建时刻（epoch millis），由仓库在 addTodo 时补齐
 * @param countdown 倒计时开关：true = 待办列表卡在截止时间左侧显示剩余天数（`N天` /
 *   `逾期N天`）。不变式：无 [deadlineDate] 时必为 false，由仓库在归一化时维护
 * @param pinned 置顶标记：true = 待办列表顶部「已置顶」分组成员（无置顶待办时整组隐藏）
 */
@Serializable
data class Todo(
    val id: String,
    val name: String,
    val dates: List<IsoDate> = emptyList(),
    val repeat: RepeatRule = RepeatRule(),
    // 重复待办的统计（= 一组截止日期不同的虚拟独立待办，按 repeat 规则展开的应完成日期序列计）：
    // 字段全部带默认值，旧文件缺字段照常反序列化（与 Todo 字段的前向兼容策略一致）。
    // 合法性由仓库归一化维护：计数不为负、逾期结算水位单调前进（见 TodoRepository）
    /** 完成次数：完成标记 false→true 每次 +1，取消完成不回退（历史事实不抹去） */
    val completedCount: Int = 0,
    /** 已落账的逾期次数（错过的应完成日期，随写库结算进水位） */
    val overdueCount: Int = 0,
    /** 逾期结算水位（epochDay 的最后已结算日；-1 = 尚未结算，从首日锚起算）。展示口径见 TodoQuery.displayOverdueCount */
    val overdueSweptThrough: Long = -1,
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
    val group: String? = null,
    val countdown: Boolean = false,
    val pinned: Boolean = false,
)

/**
 * 待办整库文件信封：todos.json 的内容（分组与待办同文件——删除分组时级联把该组待办置回
 * 未分组，同文件单次提交原子完成）。
 *
 * [formatVersion] 不随 [groups] 增设而升版：纯增量字段，旧版本靠 ignoreUnknownKeys + 默认值
 * 兼容读（降级只丢分组、不触发损坏闸门），与 Todo 字段的前向兼容策略一致。
 */
@Serializable
data class TodoFile(
    val formatVersion: Int = TODO_FORMAT_VERSION,
    val todos: List<Todo> = emptyList(),
    val groups: List<TodoGroup> = emptyList(),
)

/**
 * 一个待办分组：[name] 即身份键（当前以名字唯一，多选/重排/删除都按它匹配），[pinned] 为置顶标记。
 *
 * **不变式**：分组列表里 [pinned] 的项必须构成**连续前缀**（GroupCard 的置顶契约——置顶区锁序、
 * 落点槽位排除置顶区）。持有方每次置顶/取消置顶后用稳定划分（`filter { pinned } + filter { !pinned }`）
 * 维护该不变式：新置顶项落前缀尾部（顺序即置顶操作顺序），取消置顶项原位不动。
 *
 * 随 [TodoFile.groups] 落盘（TodoRepository.setGroups 统一写入，写入前做同样的归一化划分）。
 */
@Serializable
data class TodoGroup(
    val name: String,
    val pinned: Boolean = false,
)
