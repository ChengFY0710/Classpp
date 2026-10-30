package com.fangyi.classpp.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 语义化文字 token（阶段 1 建立，阶段 2 迁移调用点，阶段 3 按方案 B 调值）。
 *
 * 取值说明：
 * - 方案 B 阶梯：UI 文字 `11 / 12 / 14 / 16 / 20 / 24sp`，展示数字 `32 / 52 / 64sp`。
 * - 特殊展示字号（不进入常规阶梯）：`23sp` 仅页面顶栏（[pageTitle]）；
 *   `17sp` 仅课表页顶栏周数选择胶囊（[weekPill]）与课表日期带（[courseGridDate]）；
 *   `22sp` 仅周数选择卡内数字（[weekGridNumber]）；`40sp` 仅横向数字滚轮（[horizontalWheelNumber]）。
 * - 中文正文/描述字距统一 `0sp`；只有 ≥20sp 的展示标题保留负字距，
 *   14sp 动作标签（[actionLabel]）用 `0.8sp` 正字距补齐小字可读性。
 * - token 只描述字号 / 字重 / 行高 / 字距 / 字体家族，不包含颜色；颜色仍由
 *   `MaterialTheme.colorScheme` / `ClassppColors` 在调用处或 [ClassppTextStyles] 中派生。
 * - `Text` 未显式传 `style` 时会继承 `LocalTextStyle`（[Typography.bodyLarge]：
 *   16sp / Normal / 24sp / 0sp）；未写行高、字距的 token 也会继承该值。
 * - 时间、倒计时、数字滚轮统一用 [tabularNumbers] 的等宽数字。
 */
object ClassppTextTokens {
    // ---------- 标题族 ----------
    /** 页面顶栏日期标题：23sp SemiBold，特殊展示字号；仅此级展示标题保留负字距。 */
    val pageTitle = TextStyle(fontSize = 23.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp, letterSpacing = (-0.3).sp)
    /** 台历日期副标题：24sp Medium，与页面标题同档。 */
    val calendarSubtitle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium, lineHeight = 28.sp, letterSpacing = (-0.3).sp)
    /** 台历大日期：64sp 思源宋体 Heavy；24sp 行高是台历几何的紧行盒锚点，不随字号放大。 */
    val displayDate = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Black, fontFamily = SerifDisplay, lineHeight = 24.sp, letterSpacing = 0.sp)
    /** 台历节日/节气：52sp 思源宋体 Heavy，行高同 [displayDate]。 */
    val displayDateMark = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.Black, fontFamily = SerifDisplay, lineHeight = 24.sp, letterSpacing = 0.sp)
    /** 详情页标题（课程/待办）：24sp SemiBold。 */
    val detailTitle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp, letterSpacing = 0.sp)
    /** 浮层顶栏标题：20sp SemiBold。 */
    val sheetTitle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    /** 确认框标题：20sp SemiBold（方案 B 取消 Bold）。 */
    val dialogTitle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp, letterSpacing = 0.sp)
    /** 空态标题：20sp SemiBold。 */
    val emptyTitle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp, letterSpacing = 0.sp)
    /** 卡片标题（日历月标题）：16sp SemiBold。 */
    val cardTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, letterSpacing = 0.sp)
    /** 课程提醒卡标题：16sp SemiBold，单行紧行高。 */
    val alertCardTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, letterSpacing = 0.sp)
    /** 任务卡标题：16sp SemiBold。 */
    val taskCardTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, letterSpacing = 0.sp)
    /** 课程任务卡标题：16sp SemiBold。 */
    val courseTaskCardTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, letterSpacing = 0.sp)
    /** 密集卡片标题（课表课程名）：12sp SemiBold。 */
    val compactCardTitle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp, letterSpacing = 0.sp)
    /** 课表切换卡标题：16sp SemiBold；行高 20，按单行设置卡标题处理。 */
    val listRowTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, letterSpacing = 0.sp)
    /** 交替课选择行标题：16sp SemiBold。 */
    val alternateRowTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, letterSpacing = 0.sp)
    /** 分组标题：14sp Medium。 */
    val sectionTitle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
    /** 分组头「全选/取消全选」文字按钮：14sp SemiBold，行高 20；与 [sectionTitle] 同字号，仅字重提高一档。 */
    val sectionAction = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, letterSpacing = 0.sp)

    // ---------- 正文 / 表单族 ----------
    /** 行卡左侧 label：16sp SemiBold。 */
    val fieldLabel = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    /** 行卡右侧值 / 输入值：16sp Medium（右侧 label 与行内输入值同字重）。 */
    val bodyValue = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
    /** 输入占位：16sp Medium；与输入值同字重，输入前后不跳字重。 */
    val placeholder = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
    /** 次级说明（待办属性行）：14sp Normal；行高继承 24sp 以容纳倒计时数字。 */
    val secondaryText = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal)
    /** 小号次级说明（标签文字）：14sp Normal。 */
    val secondaryTextSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp)
    /** 设置页描述：14sp Normal。 */
    val settingsDescription = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp)
    /** 大号次级说明（添加课程/课程详情）：14sp Medium。 */
    val secondaryTextLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp, letterSpacing = 0.sp)
    /** 确认框正文：14sp Normal。 */
    val dialogMessage = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp, letterSpacing = 0.sp)
    /** 空态提示：14sp Normal。 */
    val emptyHint = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp, letterSpacing = 0.sp)
    /** 课表切换卡日期范围：14sp Normal。 */
    val captionText = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp, letterSpacing = 0.sp)
    /** 大号空态提示（图片页）：14sp Normal。 */
    val emptyHintLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp, letterSpacing = 0.sp)

    // ---------- 元信息 / 动作族 ----------
    /** 卡片信息行（教师/地点/备注）：14sp Normal。 */
    val metaText = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp, letterSpacing = 0.sp)
    /** 密集提示文字（交替课提示）：14sp Normal。 */
    val hintText = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp, letterSpacing = 0.sp)
    /** 卡片时间行：12sp Medium，等宽数字。 */
    val metaTextSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp).tabularNumbers()
    /** 课程卡「上课」标签：12sp Medium。 */
    val tagText = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.sp)
    /** 任务卡标签：12sp Medium。 */
    val compactTagText = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.sp)
    /** 课表课程卡时间行：12sp Medium，等宽数字避免时间切换抖动（添加卡起止时间同源）。 */
    val courseCardMeta = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.sp).tabularNumbers()
    /** 课表课程卡教师行：11sp Medium，行高 16 与时间行同节奏。 */
    val courseCardTeacher = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.sp)
    /** 课表课程卡地点行：11sp Medium，与教师行同档。 */
    val courseCardLocation = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.sp)
    /** 备忘标签 chip：12sp Normal。 */
    val tagChip = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)
    /** 胶囊按钮：16sp SemiBold。 */
    val buttonText = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    /** 文字按钮（设置-通知与提醒「发布」）：16sp Medium，弱于胶囊按钮的 SemiBold。 */
    val textButton = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp, letterSpacing = 0.sp)
    /** 底栏标签：11sp Medium。 */
    val navLabel = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 13.sp, letterSpacing = 0.sp)
    /** 顶栏/编辑栏图标下动作标签：14sp Medium，字距 0.8sp，行高 16 不变。 */
    val actionLabel = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.8.sp)

    // ---------- 日历 / 数字族 ----------
    /** 日历星期：16sp SemiBold。 */
    val calendarWeekday = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, letterSpacing = 0.sp)
    /** 日历日号：16sp SemiBold。 */
    val calendarDay = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp, letterSpacing = 0.sp)
    /** 日程页「回到今天」/ 课表页「返回本周」：16sp SemiBold。 */
    val calendarAction = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp, letterSpacing = 0.sp)
    /** 课表页顶栏周数选择胶囊：17sp SemiBold，特殊展示字号；仅胶囊使用，行高 20 不变。 */
    val weekPill = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, letterSpacing = 0.sp)
    /** 周数选择卡内数字：22sp Medium，特殊展示字号；仅周数选择卡方格使用，行高 24 不变。 */
    val weekGridNumber = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp, letterSpacing = 0.sp)
    /** 周数/重复设置方格：20sp Medium。 */
    val weekCell = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp, letterSpacing = 0.sp)
    /** 课表日期带日期行：17sp Medium，特殊展示字号（与周数选择胶囊同档）。 */
    val courseGridDate = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp, letterSpacing = 0.sp)
    /** 时间轴刻度：12sp Medium，等宽数字。 */
    val timelineLabel = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.sp).tabularNumbers()
    /** 时间轴当前时刻胶囊：11sp Medium，等宽数字。 */
    val timelineNowChip = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.sp).tabularNumbers()
    /** 日期滚轮数字：32sp Medium，等宽数字。 */
    val dateWheelNumber = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Medium).tabularNumbers()
    /** 竖向数字滚轮：32sp Medium，等宽数字。 */
    val timeWheelNumber = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Medium).tabularNumbers()
    /** 横向数字滚轮：40sp Medium，等宽数字，特殊展示字号。 */
    val horizontalWheelNumber = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Medium).tabularNumbers()
    /** 滚轮单位：16sp Medium。 */
    val wheelUnit = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
    /** 倒计时数字：24sp SemiBold，等宽数字。 */
    val countdownNumber = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold).tabularNumbers()
    /** 倒计时单位：14sp Normal，等宽数字。 */
    val countdownUnit = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal).tabularNumbers()
}

/**
 * 等宽数字（tabular figures）：1/0 等数字同宽，滚轮换值、倒计时跳秒时不会左右抖动。
 * 时间、倒计时、数字滚轮统一使用。
 */
fun TextStyle.tabularNumbers(): TextStyle = copy(fontFeatureSettings = "tnum")
