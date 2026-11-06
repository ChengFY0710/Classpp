package com.fangyi.classpp.ui.theme

import androidx.compose.ui.graphics.Color

// 品牌：蓝 #0077FF（Primary 即其微调版，见下行）/ 黑 #000000 / 白 #FFFFFF / 背景浅灰 #F2F4F6（浅色专用）
// Primary 微调为 #006FEF，使白字对比度达到 4.65:1（WCAG AA）
// 主题色八档色板见 ThemeAccent.kt；默认蓝档即复用本文件的 Primary 家族
val Primary = Color(0xFF006FEF)
val OnPrimary = Color(0xFFFFFFFF)
// primary/error 染色玻璃上的前景色（装配见 Theme.kt 的 ClassppColors.onPrimaryGlass）
val OnPrimaryGlass = Color(0xFFFFFFFF)
val PrimaryContainer = Color(0x1F006FEF)
val OnPrimaryContainer = Color(0x1A000000)

/**
 * 选中方格的 primaryContainer 不透明版：半透明底在按压反馈提亮/压暗时会透出下层色、
 * 产生色相跳变闪烁，方格式的选中底改用它。浅色 = 蓝 12% 叠白后的实色。
 * 默认蓝档由 ThemeAccent.Blue 复用；其余主题色的实色在 ThemeAccent.kt 逐档手调。
 */
val PrimaryContainerSolid = Color(0xFFE2EDFB)
val DarkPrimaryContainerSolid = Color(0xFF394C67)

// 默认蓝档页面底；其余主题色由 ThemeAccent.background() 按 HSL 派生（H 取主色，S/L 同本值）
val Background = Color(0xFFF2F4F6)

// 浅色卡片面恒为白色，不随主题色
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
// 与 Error 成对的前景色：装配到 colorScheme.onError
val OnError = Color(0xFFFFFFFF)
val ErrorContainer = Color(0xFFF9DEDC)
val OnErrorContainer = Color(0xFF410E0B)

// 默认蓝档压暗遮罩（alpha 固定 20%）；其余主题色在 ThemeAccent.kt
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

// ===== 存储用量三段（数据管理页用量条与图例专用）=====
// 蓝 = 应用大小（固定品牌蓝，不随主题色切换，避免绿色系主题与缓存绿撞色）；
// 黄/绿对齐设计稿的用户数据/缓存两段。只允许 DataManagementScreen 的用量条与图例消费。
val StorageApp = Color(0xFF006FEF)
val StorageUser = Color(0xFFF2B93E)
val StorageCache = Color(0xFF4CC38A)

// ===== AI 对话浮层底部光场（AiThinkingAurora 专用，全 app 唯一多色装饰性渐变）=====
// 左蓝 → 青 → 薄荷 → 右黄绿，四颗软边光斑叠成设计稿的极光场；只由 AiThinkingAurora
// 经 classppColors.aiAurora* 消费，不得挪作其它背景或业务强调色。
// 浅色取设计稿输入区实测的柔和色，不取高饱和原色——光场由多个光斑叠加后再经白玻璃提亮。
val AiAuroraBlue = Color(0xFF5FA4F6)
val AiAuroraCyan = Color(0xFF7FCBE4)
val AiAuroraMint = Color(0xFFA0D7C4)
val AiAuroraLime = Color(0xFFD8EE9F)

// ===== ClassppColors 扩展色的深浅取值（装配见 Theme.kt，语义注释也在装配处）=====

/** 默认蓝档次级说明文字灰；其余主题色由 ThemeAccent.secondaryText() 按 HSL 派生（H 取主色，S/L 同本值） */
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
// primary/error 染色玻璃上的前景色（装配见 Theme.kt 的 ClassppColors.onPrimaryGlass）
val DarkOnPrimaryGlass = Color(0xFFE4E7EA)
val DarkPrimaryContainer = Color(0x45479CFF)
val DarkOnPrimaryContainer = Color(0x339AA2AC)

// 默认蓝档深色页面底；其余主题色由 ThemeAccent.background() 按 HSL 派生（H 取主色，S/L 同本值）
val DarkBackground = Color(0xFF0F1114)

// 默认蓝档深色卡片面；其余主题色由 ThemeAccent.surface() 按 HSL 派生（H 取主色，S/L 同本值）
val DarkSurface = Color(0xFF292D32)
val DarkOnSurface = Color(0xFFE4E7EA)
val DarkOnSurfaceVariant = Color(0xFF9BA2AB)

val DarkBottomSheetColor = Color(0xFF0B0C0F)

val DarkSurfaceContainerHigh = Color(0xFF1E2227)

val DarkOutline = Color(0xFF2A2F36)

val DarkOnSurfaceCard = Color(0xFF4D5157)

val DarkInversePrimary = Color(0x99479CFF)

val DarkError = Color(0xFFFF5449)
// 与 Error 成对的前景色：装配到 colorScheme.onError
val DarkOnError = Color(0xFF7A0700)
val DarkErrorContainer = Color(0xFF4A1C18)
val DarkOnErrorContainer = Color(0xFFFFD9D4)

val DarkUrgentOrange = Color(0xFFF6A94C)
val DarkUrgentYellow = Color(0xFFF6CE52)
val DarkUrgentGreen = Color(0xFF67D257)

// 存储用量三段：深色下与紧急旗标同档提亮，保证深卡上的分段仍可区分（应用段固定品牌蓝，不随主题色）
val DarkStorageApp = Color(0xFF479CFF)
val DarkStorageUser = Color(0xFFF6CE52)
val DarkStorageCache = Color(0xFF67D257)

// 深色光场 = 同色相压暗（明度 35–45%），在深底上呈现低亮度极光，不采用浅色值直接发光
val DarkAiAuroraBlue = Color(0xFF2E6FB8)
val DarkAiAuroraCyan = Color(0xFF2A7E8C)
val DarkAiAuroraMint = Color(0xFF357A52)
val DarkAiAuroraLime = Color(0xFF8A9A3A)

// 默认蓝档深色压暗遮罩（alpha 固定 75%）；其余主题色在 ThemeAccent.kt
val DarkScrim = Color(0xBF000000)

// 开关（ClassppSwitch）不可点击时的置灰轨道色，无论 checked 取该值
val DarkSwitchDisabled = Color(0xFF1C1E22)
