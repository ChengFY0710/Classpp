package com.fangyi.classpp.data.model

import kotlinx.serialization.Serializable

/**
 * 待办的重复规则（日期卡「自定义 → 重复」的取值），随 [Todo.repeat] 落库。
 *
 * 字段全部带默认值：旧文件缺字段照常反序列化（与 [Todo] 字段的前向兼容策略一致，
 * 无版本号变更）。合法性由仓库在归一化时消洗——[interval] 夹紧到 1..99、[marks] 按
 * [unit] 的合法域过滤（越界剔除、[RepeatUnit.Day] 清空）、[type] 非 [RepeatType.Custom]
 * 时后三参重置为默认值。
 */
@Serializable
enum class RepeatType { None, Daily, Weekly, Monthly, Yearly, Custom }

/** 自定义重复的频率单位：[RepeatRule.marks] 的语义随它切换 */
@Serializable
enum class RepeatUnit { Day, Week, Month, Year }

/**
 * [RepeatRule.marks] 的合法值域：周 = 周几 1..7（周一=1）、月 = 几号 1..31、年 = 几月 1..12；
 * 天无方格语义（空区间）。仓库消洗与 UI 方格生成共用这一份定义。
 */
val RepeatUnit.markRange: IntRange
    get() = when (this) {
        RepeatUnit.Week -> 1..7
        RepeatUnit.Month -> 1..31
        RepeatUnit.Year -> 1..12
        RepeatUnit.Day -> IntRange.EMPTY
    }

/**
 * 一条重复规则：预设档位（无/每天/每周/每月/每年）或自定义（每 [interval] [unit] 于
 * [marks] 标记处重复）。
 *
 * @param type 重复类型；非 [RepeatType.Custom] 时其余字段不参与语义（保持默认值）
 * @param unit 自定义频率单位；[RepeatUnit.Day] 无方格语义，[marks] 恒空
 * @param interval 重复间隔（每 N 个单位），1..99
 * @param marks 方格标记集合——[RepeatUnit.Week] = 周几 1..7（周一=1）、
 *   [RepeatUnit.Month] = 每月几号 1..31、[RepeatUnit.Year] = 几月 1..12
 */
@Serializable
data class RepeatRule(
    val type: RepeatType = RepeatType.None,
    val unit: RepeatUnit = RepeatUnit.Week,
    val interval: Int = 1,
    val marks: Set<Int> = emptySet(),
)
