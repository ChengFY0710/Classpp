package com.fangyi.classpp.ui.theme

import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.UnevenRoundedRectangle

/**
 * 全局形状常量，统一走 [RoundedRectangle] 的 Continuous（连续曲率）风格，
 * 即 iOS 同款"圆角与直边平滑过渡"，替代 androidx 的 RoundedCornerShape。
 * 小圆角（课表格子 6dp 等）不做平滑，保留各文件内的 RoundedCornerShape。
 */

/** 底部浮层：顶部 36dp 平滑圆角，模糊与内容裁剪共用 */
val SheetShape = UnevenRoundedRectangle(topStart = 36.dp, topEnd = 36.dp)

/** 浮层内卡片 */
val SheetCardShape = RoundedRectangle(20.dp)

/** 页内对话框卡片（换课选择、切换器删除/放弃确认框） */
val DialogShape = RoundedRectangle(24.dp)

/** 长按/右键菜单、弹出选择卡 */
val MenuShape = RoundedRectangle(16.dp)

/** 设置页卡片、周选择卡片容器 */
val SettingsCardShape = RoundedRectangle(20.dp)

/** 设置页分段控件 */
val SegmentShape = RoundedRectangle(14.dp)

/** 对话框行、选择卡行 */
val RowShape = RoundedRectangle(12.dp)

/** 课表编辑条操作按钮 */
val EditActionShape = RoundedRectangle(10.dp)

/** 选周格子（添加课程面板、周选择浮层的周数方格） */
val WeekCellShape = RoundedRectangle(11.dp)

/** 顶栏胶囊按钮（全圆角）；投影与背景共用同一个形状 */
val PillShape = Capsule()

/** 主操作大按钮（创建课表等 M3 Button） */
val ButtonShape = Capsule()
