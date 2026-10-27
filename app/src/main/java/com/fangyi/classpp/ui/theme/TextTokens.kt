package com.fangyi.classpp.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 语义化文字 token（阶段 1 建立，阶段 2 补齐调用点迁移所需的隐含值）。
 *
 * 取值说明：
 * - 这里记录的是**迁移前的渲染值**：阶段 2 只把调用点的裸字号常量换成 token，不改视觉；
 *   阶段 3 再按方案 B 统一调值。
 * - token 只描述字号 / 字重 / 行高 / 字距 / 字体家族，不包含颜色；颜色仍由
 *   `MaterialTheme.colorScheme` / `ClassppColors` 在调用处或 [ClassppTextStyles] 中派生。
 * - `Text` 未显式传 `style` 时会继承 `LocalTextStyle`（MaterialTheme 默认是
 *   `bodyLarge`：16sp / Medium / 24sp / 0.5sp）。阶段 2 把这些继承来的行高、字距一并写进
 *   对应 token，保证替换成 `style = token` 后渲染不变；通过角色样式 + `copy` 覆盖的调用点
 *   则按 `style` 的实际默认值记录。
 *
 * 方案 B 的目标值见 `.mimocode/docs/字体样式规范.md` 第 7.1 节。
 */
object ClassppTextTokens {
    // ---------- 标题族 ----------
    /** 页面顶栏日期标题；阶段 3 目标 24sp。 */
    val pageTitle = TextStyle(fontSize = 23.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp, letterSpacing = (-0.3).sp)
    /** 台历日期副标题；阶段 3 目标 24sp。 */
    val calendarSubtitle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp, letterSpacing = (-0.3).sp)
    /** 台历大日期；阶段 3 保持 64sp。 */
    val displayDate = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Black, fontFamily = SerifDisplay, lineHeight = 24.sp, letterSpacing = 0.5.sp)
    /** 台历节日/节气；阶段 3 目标 48sp。 */
    val displayDateMark = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.Black, fontFamily = SerifDisplay, lineHeight = 24.sp, letterSpacing = 0.5.sp)
    /** 详情页标题（课程/待办）；阶段 3 目标 24sp。 */
    val detailTitle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp, letterSpacing = 0.sp)
    /** 浮层顶栏标题；阶段 3 保持 20sp。 */
    val sheetTitle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    /** 浮层顶栏标题的 18sp 紧凑变体；阶段 3 并入 20sp。 */
    val sheetTitleCompact = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    /** 确认框标题；阶段 3 改为 SemiBold（方案 B 取消 Bold）。 */
    val dialogTitle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp, letterSpacing = 0.5.sp)
    /** 空态标题；阶段 3 保持 20sp。 */
    val emptyTitle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp, letterSpacing = 0.5.sp)
    /** 卡片标题（日历月标题）；阶段 3 保持 16sp。 */
    val cardTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp, letterSpacing = 0.5.sp)
    /** 课程提醒卡标题（紧行高 16sp）；阶段 3 保持 16sp。 */
    val alertCardTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp, letterSpacing = 0.5.sp)
    /** 任务卡标题（行高 20sp、角色样式默认字距）；阶段 3 保持 16sp。 */
    val taskCardTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
    /** 课程任务卡标题（行高 20sp，继承 bodyLarge 的 0.5sp 字距）；阶段 3 保持 16sp。 */
    val courseTaskCardTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, letterSpacing = 0.5.sp)
    /** 密集卡片标题（课表课程名）；阶段 3 保持 12sp。 */
    val compactCardTitle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp, letterSpacing = (-0.1).sp)
    /** 列表行标题（课表切换卡）；阶段 3 目标 20sp。 */
    val listRowTitle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp, letterSpacing = 0.5.sp)
    /** 交替课选择行标题；阶段 3 目标 16sp。 */
    val alternateRowTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp, letterSpacing = 0.5.sp)
    /** 分组标题；阶段 3 目标 14sp。 */
    val sectionTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)

    // ---------- 正文 / 表单族 ----------
    /** 行卡左侧 label；阶段 3 保持 16sp。 */
    val fieldLabel = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    /** 行卡右侧值 / 输入值；阶段 3 字重改 Normal。 */
    val bodyValue = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
    /** 小号正文；阶段 3 保持 14sp。 */
    val bodySmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp)
    /** 输入占位；阶段 3 保持 16sp。 */
    val placeholder = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
    /** 次级说明（待办属性行）；阶段 3 保持 14sp。 */
    val secondaryText = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal)
    /** 小号次级说明（标签文字）；阶段 3 目标 14sp。 */
    val secondaryTextSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal)
    /** 设置页描述（fieldPlaceholder 的 13sp/20sp 覆盖）；阶段 3 目标 14sp。 */
    val settingsDescription = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp)
    /** 大号次级说明（添加课程/课程详情）；阶段 3 目标 14sp 左右。 */
    val secondaryTextLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp, letterSpacing = 0.5.sp)
    /** 确认框正文；阶段 3 目标 14sp / Normal。 */
    val dialogMessage = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp, letterSpacing = 0.5.sp)
    /** 空态提示；阶段 3 字重改 Normal。 */
    val emptyHint = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp, letterSpacing = 0.5.sp)
    /** 课表切换卡日期范围；阶段 3 目标 14sp。 */
    val captionText = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp, letterSpacing = 0.5.sp)
    /** 大号空态提示（图片页）；阶段 3 目标 14sp / Normal。 */
    val emptyHintLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp, letterSpacing = 0.5.sp)

    // ---------- 元信息 / 动作族 ----------
    /** 卡片信息行（教师/地点/备注）；阶段 3 目标 14sp / Normal。 */
    val metaText = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp, letterSpacing = 0.5.sp)
    /** 密集提示文字（交替课提示）；阶段 3 目标 14sp。 */
    val hintText = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp, letterSpacing = 0.5.sp)
    /** 卡片时间行（等宽数字）；阶段 3 目标 12sp。 */
    val metaTextSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 19.sp).tabularNumbers()
    /** 课程卡「上课」标签；阶段 3 目标 12sp。 */
    val tagText = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp, letterSpacing = 0.5.sp)
    /** 任务卡标签（紧凑行高）；阶段 3 目标 12sp。 */
    val compactTagText = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 12.sp, letterSpacing = 0.5.sp)
    /** 课表课程卡时间/教师行；阶段 3 目标 12sp。 */
    val courseCardMeta = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 12.sp, letterSpacing = 0.5.sp)
    /** 课表课程卡地点行；阶段 3 目标 12sp。 */
    val courseCardLocation = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp, letterSpacing = 0.5.sp)
    /** 备忘标签 chip；阶段 3 目标 12sp。 */
    val tagChip = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)
    /** 胶囊按钮；阶段 3 保持 16sp SemiBold。 */
    val buttonText = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    /** 确认框胶囊按钮（继承 bodyLarge 的行高/字距）；阶段 3 并入 buttonText。 */
    val dialogButtonText = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp, letterSpacing = 0.5.sp)
    /** 文字按钮（当前仅通知测试）；阶段 3 改为 SemiBold。 */
    val textButton = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp, letterSpacing = 0.1.sp)
    /** 底栏标签；阶段 3 目标 12sp。 */
    val navLabel = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 11.sp, letterSpacing = 0.5.sp)
    /** 顶栏/编辑栏动作标签；阶段 3 目标 14sp。 */
    val actionLabel = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp, letterSpacing = 0.5.sp)

    // ---------- 日历 / 数字族 ----------
    /** 日历星期；阶段 3 目标 14sp。 */
    val calendarWeekday = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp, letterSpacing = 0.5.sp)
    /** 日历日号；阶段 3 目标 16sp。 */
    val calendarDay = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp, letterSpacing = 0.5.sp)
    /** 日历「回到今天」；阶段 3 目标 16sp / Normal。 */
    val calendarAction = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp, letterSpacing = 0.5.sp)
    /** 周胶囊；阶段 3 目标 20sp。 */
    val weekPill = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp, letterSpacing = 0.5.sp)
    /** 展开态周数方格；阶段 3 目标 24sp。 */
    val weekGridNumber = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp, letterSpacing = 0.5.sp)
    /** 周数方格；阶段 3 目标 16sp。 */
    val weekCell = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp, letterSpacing = 0.5.sp)
    /** 课表日期格；阶段 3 目标 16sp。 */
    val courseGridDate = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp, letterSpacing = 0.5.sp)
    /** 时间轴刻度；阶段 3 目标 12sp。 */
    val timelineLabel = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp, letterSpacing = 0.5.sp)
    /** 时间轴当前时刻胶囊；阶段 3 目标 12sp。 */
    val timelineNowChip = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp, letterSpacing = 0.5.sp)
    /** 日期滚轮数字；阶段 3 保持 32sp。 */
    val dateWheelNumber = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Medium).tabularNumbers()
    /** 竖向数字滚轮；阶段 3 目标 32sp。 */
    val timeWheelNumber = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Medium).tabularNumbers()
    /** 横向数字滚轮；阶段 3 目标 48sp。 */
    val horizontalWheelNumber = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Medium).tabularNumbers()
    /** 滚轮单位；阶段 3 保持 18sp。 */
    val wheelUnit = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium)
    /** 倒计时数字；阶段 3 目标 24sp。 */
    val countdownNumber = TextStyle(fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
    /** 倒计时单位；阶段 3 目标 14sp。 */
    val countdownUnit = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)
}

/**
 * 等宽数字（tabular figures）：1/0 等数字同宽，滚轮换值、倒计时跳秒时不会左右抖动。
 * 时间、倒计时、数字滚轮统一使用。
 */
fun TextStyle.tabularNumbers(): TextStyle = copy(fontFeatureSettings = "tnum")
