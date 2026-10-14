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
 * 卡片分组（CardSection、NoteCardSection）内的纵向**卡间距（卡↔卡）**。
 * 两个组件共用它作默认值，设置页与各浮层（加课面板、新建待办、课表设置）都取默认，
 * 不再逐处覆盖——改这一个值，全局卡片节奏同步。
 *
 * 「标题↔首卡」已与它解耦，另走 [CardSectionTitleSpacing]（当前同为 10dp，却是两个独立值）。
 */
val CardSectionSpacing: Dp = 10.dp

/**
 * 卡片分组**标题与其下方内容（首卡）**之间的间距：CardSection、NoteCardSection 共用。
 * 与 [CardSectionSpacing]（卡↔卡）是两个独立值——想让标题更贴近或更远离内容，
 * 只改这一个，卡片之间的节奏不受影响。
 */
val CardSectionTitleSpacing: Dp = 8.dp

/**
 * 浮层滚动内容里**顶层块之间的间距**：所有 OverlaySheet 的内容列统一用它（加课面板、
 * 新建/编辑待办、选择分组、待办详情、待办分组、新建课表、切换课表、课表设置、课程详情）。
 *
 * 顶层块按浮层内容规范一律是 `CardSection`（见其 KDoc）——成组的卡片列表与孤块
 * （输入框、单张卡片）都收进分组，唯二的例外是直接的文本内容与待办标签行，它们也按本值
 * 与相邻分组相隔。
 *
 * 与组内间距的分工：分组的**组内**「卡↔卡」是 [CardSectionSpacing]、「标题↔首卡」是
 * [CardSectionTitleSpacing]（当前均为 10dp），这里是**组与组之间**（以及组与组外的裸元素
 * 之间）的 12dp——组内节奏与组间节奏分开，两个维度各自独立。
 * 课程详情卡片单独设置10dp，其余统一使用SheetSectionSpacingBetween变量
 */
val SheetSectionSpacingBetween: Dp = 12.dp

/**
 * 设置类页面的**分组间距**：设置根页 / 个性化 / 通知与提醒的内容分组之间用它。
 * 只管设置页面——浮层（OverlaySheet）一律走 [SheetSectionSpacingBetween]（12dp）。
 *
 * 三档分工：分组内「卡↔卡」是 [CardSectionSpacing]（10dp）、「标题↔首卡」是
 * [CardSectionTitleSpacing]（8dp）；浮层内容列的顶层块之间是 [SheetSectionSpacingBetween]
 * （12dp）（课程详情卡片单独设置10dp，其余统一使用SheetSectionSpacingBetween变量）；设置类页面组与组之间的留白最大，取本值（18dp）。
 */
val SettingsSectionSpacing: Dp = 18.dp

/**
 * 浮层行卡与设置卡统一行高（行高下限）：输入框（SheetTextField）与信息展示框
 * （SheetInfoCard）按它取 `heightIn(min)`，设置卡（SettingsCard、SettingsCardwithIcon，
 * PopupSelectCard 选择行随之）以它为整卡行高下限，保证各卡等高；
 * 系统字体放大时行高同步增长，等高关系依旧成立。
 * 例外：输入值超宽自动换行时输入行卡按行数长高（上下内距不变），其余卡维持此高度。
 */
val SheetFieldHeight: Dp = 60.dp
