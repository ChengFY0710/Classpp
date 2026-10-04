package com.fangyi.classpp.ui.theme

import androidx.compose.ui.graphics.Color

// 品牌：蓝 #0077FF（见 res/values/colors.xml brand_blue）/ 黑 #000000 / 白 #FFFFFF / 背景浅灰 #F2F4F6（浅色专用）
// Primary 微调为 #006FEF，使白字对比度达到 4.65:1（WCAG AA）
val Primary = Color(0xFF006FEF)
val OnPrimary = Color(0xFFFFFFFF)
val PrimaryContainer = Color(0x1F006FEF)
val OnPrimaryContainer = Color(0x1A000000)

val SecondaryContainer = Color(0xFFDFE4EA)
val OnSecondaryContainer = Color(0xFF101820)
val TertiaryContainer = Color(0xFFCFEBED)
val OnTertiaryContainer = Color(0xFF002022)

val Background = Color(0xFFF2F4F6)
val OnBackground = Color(0xFF000000)

val Surface = Color(0xFFFFFFFF)
val OnSurface = Color(0xFF000000)
val OnSurfaceVariant = Color(0xFF444746)

val SurfaceContainer = Color(0xFFF2F4F6)
val SurfaceContainerHigh = Color(0xFFEAEDF1)
val SurfaceContainerHighest = Color(0xFFE4E7EB)

val Outline = Color(0xFFDCE2E7)

val InversePrimary = Color(0xFFA3C8FA)

val Error = Color(0xFFF44336)
val ErrorContainer = Color(0xFFF9DEDC)
val OnErrorContainer = Color(0xFF410E0B)

val Scrim = Color(0xFF000000)

val Correct = Color(0xFF22B14C)

// ===== 深色模式 =====
// 与浅色同构的冷灰阶梯：Background 最深、Surface（卡片）亮一档、容器再亮——
// 对应浅色「页面灰底 → 白卡」的层级关系反转。Primary 提亮为 tonal 80（#A3C8FA，
// 与浅色 InversePrimary 同源），保证深底上文字/图标对比达标。
val DarkPrimary = Color(0xFF3391FF)
val DarkOnPrimary = Color(0xFF003062)
val DarkPrimaryContainer = Color(0x45338FFF)
val DarkOnPrimaryContainer = Color(0x339AA2AC)

val DarkSecondaryContainer = Color(0xFF2B323A)
val DarkOnSecondaryContainer = Color(0xFFE1E7ED)
val DarkTertiaryContainer = Color(0xFF1E3C3F)
val DarkOnTertiaryContainer = Color(0xFFC9EAED)

val DarkBackground = Color(0xFF0F1114)
val DarkOnBackground = Color(0xFFE4E7EA)

val DarkSurface = Color(0xFF292D32)
val DarkOnSurface = Color(0xFFE4E7EA)
val DarkOnSurfaceVariant = Color(0xFF9BA2AB)

// 网格底与页面底同色，与浅色（SurfaceContainer = Background）同构
val DarkSurfaceContainer = Color(0xFF0F1114)
val DarkSurfaceContainerHigh = Color(0xFF1E2227)
val DarkSurfaceContainerHighest = Color(0xFF282D33)

val DarkOutline = Color(0xFF2A2F36)

val DarkInversePrimary = Color(0xFF3391FF)

val DarkError = Color(0xFFFF5449)
val DarkErrorContainer = Color(0xFF4A1C18)
val DarkOnErrorContainer = Color(0xFFFFD9D4)

val DarkScrim = Color(0xFF000000)
