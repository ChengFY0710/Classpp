package com.fangyi.classpp.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全宽页面（设置页、各浮层）内容的横向边距：内容卡片与屏幕两侧的距离。
 * 设置页（SettingsScreen）与浮层骨架（OverlaySheet，含其顶栏按钮行）共用，
 * 保证两处的卡片宽度一致——改这一个值，两侧同步。
 */
val PageHorizontalSpacing: Dp = 15.dp

/**
 * 卡片分组（CardSection、NoteCardSection）内的纵向卡间距：标题↔首卡、卡↔卡同一个值。
 * 两个组件共用它作默认值，设置页与各浮层（加课面板、新建待办、课表设置）都取默认，
 * 不再逐处覆盖——改这一个值，全局卡片节奏同步。
 */
val CardSectionSpacing: Dp = 10.dp

/**
 * 浮层滚动内容里**顶层块之间的间距**：OverlaySheet 的 content 里那些「没被 CardSection
 * 收进分组」的块——输入框、文本块、单张卡片，也包括直接排成一列的卡片列表（切换课表浮层）。
 * 新建待办、新建课表、课程详情、切换课表四个浮层共用。
 *
 * 与 [CardSectionSpacing] 的分工：分组的**组内**「卡↔卡」是 10dp，这里是**组与组之间**
 * （以及组与组外孤块之间）的 12dp——一个管组内节奏，一个管组间节奏。
 */
val SheetSectionSpacingBetween: Dp = 12.dp

/**
 * [SheetSectionSpacingBetween] 的「手写 Spacer」版本：某段内容没套
 * `Column(verticalArrangement = spacedBy(...))` 时，用它自己补一份同样的顶层块留白。
 * 目前只有切换课表浮层的 CreateScheduleContent（课表名输入框 ↔ 学期日期卡）。
 */
val SheetSectionSpacingBottom: Dp = 14.dp

/**
 * 浮层行卡与设置卡统一行高（行高下限）：输入框（SheetTextField）与信息展示框
 * （SheetInfoCard）按它取 `heightIn(min)`，设置卡（SettingsCard、SettingsCardwithIcon，
 * PopupSelectCard 选择行随之）以它为整卡行高下限，保证各卡等高；
 * 系统字体放大时行高同步增长，等高关系依旧成立。
 * 例外：输入值超宽自动换行时输入行卡按行数长高（上下内距不变），其余卡维持此高度。
 */
val SheetFieldHeight: Dp = 60.dp
