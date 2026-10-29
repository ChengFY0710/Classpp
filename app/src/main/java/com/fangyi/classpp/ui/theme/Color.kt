package com.fangyi.classpp.ui.theme

import androidx.compose.ui.graphics.Color

// 品牌：蓝 #0077FF（Primary 即其微调版，见下行）/ 黑 #000000 / 白 #FFFFFF / 背景浅灰 #F2F4F6（浅色专用）
// Primary 微调为 #006FEF，使白字对比度达到 4.65:1（WCAG AA）
val Primary = Color(0xFF006FEF)
val OnPrimary = Color(0xFFFFFFFF)
val PrimaryContainer = Color(0x1F006FEF)
val OnPrimaryContainer = Color(0x1A000000)

/**
 * 选中方格的 primaryContainer 不透明版：半透明底在按压反馈提亮/压暗时会透出下层色、
 * 产生色相跳变闪烁，方格式的选中底改用它。浅色 = 蓝 12% 叠白后的实色。
 */
val PrimaryContainerSolid = Color(0xFFE2EDFB)
val DarkPrimaryContainerSolid = Color(0xFF394C67)

val Background = Color(0xFFF2F4F6)

val Surface = Color(0xFFFFFFFF)
val OnSurface = Color(0xFF000000)
val OnSurfaceVariant = Color(0xFF444746)

//BottomSelectSheet专属背景色，用于掩盖Haze 的 effect 图层内容被多缩了一次，左侧的亮边问题。
val BottomSheetColor = Color(0xFFFFFFFF)

// M3 对话框（AlertDialog/DatePickerDialog）的默认"高一级表面"底色，不可省
val SurfaceContainerHigh = Color(0xFFEAEDF1)

val Outline = Color(0xFFDCE2E7)

// 卡面细弱元素（卡片内分隔线、点状轨道）：浅色与 outline 同值，深色取卡面上可见的中灰
val OnSurfaceCard = Color(0xFFDCE2E7)

val InversePrimary = Color(0x4D006FEF)

val Error = Color(0xFFF44336)
val ErrorContainer = Color(0xFFF9DEDC)
val OnErrorContainer = Color(0xFF410E0B)

val Scrim = Color(0x332A333C)

/**
 * 学期开始日标记绿字（CourseGrid / ScheduleHeader），不分深浅系有意。
 * 与 urgentGreen（不急旗标/已完成分组）、标签草绿 #66BB6A 语义不同，勿混用。
 */
val Correct = Color(0xFF22B14C)

// 开关（ClassppSwitch）不可点击时的置灰轨道色，无论 checked 取该值
val SwitchDisabled = Color(0xFFEEEEEE)

// ===== 紧急程度旗标（UrgentFlagCard）：红用 Error、灰用 classppColors.secondaryText，橙/黄/绿在此 =====
// 对齐设计稿马卡龙外一档的高饱和取色；深色按 DarkPrimary 的提亮惯例整体浅一档，保深卡对比
val UrgentOrange = Color(0xFFF2921B)
val UrgentYellow = Color(0xFFF2C11C)
val UrgentGreen = Color(0xFF3BC222)

// ===== ClassppColors 扩展色的深浅取值（装配见 Theme.kt，语义注释也在装配处）=====

/** 次级说明文字灰 */
val SecondaryText = Color(0xFFABAFB4)
val DarkSecondaryText = Color(0xFF7E858D)

/** 「刻意弱化」置灰：浅色取卡片底；深色卡底上取更暗的灰，保持同等弱化视觉强度 */
val Negative = Color(0xFFCBCBCB)
val DarkNegative = Color(0xFF5C6269)

/** Haze 磨砂叠加基础色：浅色白提亮、深色黑压暗，调用处按强度 copy(alpha) */
val HazeTint = Color.White
val DarkHazeTint = Color.Black

// ===== 深色模式 =====
// 与浅色同构的冷灰阶梯：Background 最深、Surface（卡片）亮一档、容器再亮——
// 对应浅色「页面灰底 → 白卡」的层级关系反转。Primary 提亮为 #479CFF，
// 保证深底上文字/图标对比达标。
val DarkPrimary = Color(0xFF479CFF)
val DarkOnPrimary = Color(0xFF003062)
val DarkPrimaryContainer = Color(0x45479CFF)
val DarkOnPrimaryContainer = Color(0x339AA2AC)

val DarkBackground = Color(0xFF0F1114)

val DarkSurface = Color(0xFF292D32)
val DarkOnSurface = Color(0xFFE4E7EA)
val DarkOnSurfaceVariant = Color(0xFF9BA2AB)

val DarkBottomSheetColor = Color(0xFF0B0C0F)

val DarkSurfaceContainerHigh = Color(0xFF1E2227)

val DarkOutline = Color(0xFF2A2F36)

val DarkOnSurfaceCard = Color(0xFF4D5157)

val DarkInversePrimary = Color(0x99479CFF)

val DarkError = Color(0xFFFF5449)
val DarkErrorContainer = Color(0xFF4A1C18)
val DarkOnErrorContainer = Color(0xFFFFD9D4)

val DarkUrgentOrange = Color(0xFFF6A94C)
val DarkUrgentYellow = Color(0xFFF6CE52)
val DarkUrgentGreen = Color(0xFF67D257)

val DarkScrim = Color(0xBF000000)

// 开关（ClassppSwitch）不可点击时的置灰轨道色，无论 checked 取该值
val DarkSwitchDisabled = Color(0xFF1C1E22)
