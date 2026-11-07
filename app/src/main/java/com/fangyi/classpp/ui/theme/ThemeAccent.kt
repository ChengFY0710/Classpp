package com.fangyi.classpp.ui.theme

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.fangyi.classpp.R

// ===== 主题色八档（文件私有）=====
// 每档手工调校浅/深两套主色、前景色、选中实色与压暗遮罩；默认蓝直接复用 Color.kt 既有
// 常量，保证默认观感逐位一致。页面背景、深色 surface 与次级说明文字不在此定义：由 HSL
// 从主色派生（H = 主色色相，S/L 取对应中性色；浅色 surface 恒 #FFFFFF）。scrim 保持
// 既有 alpha（浅 20% / 深 75%），只换暗色色相。对比度约束（primary/onPrimary ≥ 4.5:1）
// 由 ThemeAccentTest 固化。
private val TealPrimary = Color(0xFF007C8C)
private val TealPrimaryContainerSolid = Color(0xFFE0F1F3)
private val TealScrim = Color(0x332A3B3D)

private val GreenPrimary = Color(0xFF0C7F38)
private val GreenPrimaryContainerSolid = Color(0xFFE1F3E7)
private val GreenScrim = Color(0x332C3C33)

private val PurplePrimary = Color(0xFF6B4BE0)
private val PurplePrimaryContainerSolid = Color(0xFFEDE8FB)
private val PurpleScrim = Color(0x332F2A3C)

private val OrangePrimary = Color(0xFFB85600)
private val OrangePrimaryContainerSolid = Color(0xFFF8EBDE)
private val OrangeScrim = Color(0x333C332A)

private val PinkPrimary = Color(0xFFD6336C)
private val PinkPrimaryContainerSolid = Color(0xFFFAE6ED)
private val PinkScrim = Color(0x333C2A33)

// 厦大蓝：浅色取给定的品牌 navy #204586；深色按既有惯例提亮
private val XmuBluePrimary = Color(0xFF204586)
private val XmuBluePrimaryContainerSolid = Color(0xFFE4E9F4)
private val XmuBlueScrim = Color(0x332A303C)

// 山大红：浅色取给定的品牌 red #86201D；深色按既有惯例提亮
private val SduRedPrimary = Color(0xFF86201D)
private val SduRedPrimaryContainerSolid = Color(0xFFF6E5E3)
private val SduRedScrim = Color(0x333C2A2A)

private val DarkTealPrimary = Color(0xFF4FC8D4)
private val DarkTealOnPrimary = Color(0xFF003A40)
private val DarkTealPrimaryContainerSolid = Color(0xFF2E464B)
private val DarkTealScrim = Color(0xBF061011)

private val DarkGreenPrimary = Color(0xFF55CC7C)
private val DarkGreenOnPrimary = Color(0xFF00391A)
private val DarkGreenPrimaryContainerSolid = Color(0xFF2F4437)
private val DarkGreenScrim = Color(0xBF07100A)

private val DarkPurplePrimary = Color(0xFFA98BFF)
private val DarkPurpleOnPrimary = Color(0xFF2A1060)
private val DarkPurplePrimaryContainerSolid = Color(0xFF3B3556)
private val DarkPurpleScrim = Color(0xBF0E0B14)

private val DarkOrangePrimary = Color(0xFFF2933D)
private val DarkOrangeOnPrimary = Color(0xFF4A2600)
private val DarkOrangePrimaryContainerSolid = Color(0xFF4C3B2A)
private val DarkOrangeScrim = Color(0xBF130C05)

private val DarkPinkPrimary = Color(0xFFFF7DA0)
private val DarkPinkOnPrimary = Color(0xFF4D0A20)
private val DarkPinkPrimaryContainerSolid = Color(0xFF4C2F3B)
private val DarkPinkScrim = Color(0xBF140A0D)

private val DarkXmuBluePrimary = Color(0xFF8FB2FF)
private val DarkXmuBlueOnPrimary = Color(0xFF0A1A40)
private val DarkXmuBluePrimaryContainerSolid = Color(0xFF303C5C)
private val DarkXmuBlueScrim = Color(0xBF0B0E14)

private val DarkSduRedPrimary = Color(0xFFFF8A80)
private val DarkSduRedOnPrimary = Color(0xFF450B08)
private val DarkSduRedPrimaryContainerSolid = Color(0xFF4A2B29)
private val DarkSduRedScrim = Color(0xBF140B0A)

// 半透明槽位的派生透明度：与改动前的蓝值逐位一致（0x1F/0x4D/0x45/0x99）
internal const val PrimaryContainerAlphaLight = 0x1F / 255f
internal const val PrimaryContainerAlphaDark = 0x45 / 255f
internal const val InversePrimaryAlphaLight = 0x4D / 255f
internal const val InversePrimaryAlphaDark = 0x99 / 255f

// ===== 页面背景的 HSL 固定端 =====
// S/L 与中性底一致：浅 #F2F4F6（S 18.18% / L 95.69%）、深 #0F1114（S 14.29% / L 6.86%）；
// H 取当前档位主色。默认蓝按此派生与改动前的两值逐位一致。
private const val BackgroundSaturationLight = 2f / 11f
private const val BackgroundLightnessLight = 244f / 255f
private const val BackgroundSaturationDark = 1f / 7f
private const val BackgroundLightnessDark = 17.5f / 255f

// ===== 次级说明文字的 HSL 固定端 =====
// S/L 取自默认灰：浅 #ABAFB4（S 5.66% / L 68.82%）、深 #7E858D（S 6.17% / L 52.35%）；
// H 取当前档位主色。默认蓝按此派生与改动前的两值逐位一致。
private const val SecondaryTextSaturationLight = 9f / 159f
private const val SecondaryTextLightnessLight = 175.5f / 255f
private const val SecondaryTextSaturationDark = 15f / 243f
private const val SecondaryTextLightnessDark = 133.5f / 255f

// ===== 深色卡片面（surface）的 HSL 固定端 =====
// S/L 取自默认深色 surface #292D32（S 9.89% / L 17.84%），H 取当前深色主色；
// 浅色 surface 恒 #FFFFFF，不随主题色。除默认蓝与厦大蓝外，其余档在深色面上
// S 降至 6%，避免低明度底色过艳。
private const val SurfaceSaturationDark = 9f / 91f
private const val SurfaceSaturationDarkSoft = 0.06f
private const val SurfaceLightnessDark = 45.5f / 255f

// ===== outline 的 HSL 固定端 =====
// S/L 取自默认 outline：浅 #DCE2E7（S 18.64% / L 88.43%）、深 #2A2F36（S 12.5% / L 18.82%）；
// H 取当前主色。默认蓝的 outline 色相与主色不完全一致，特判保留原值。
private const val OutlineSaturationLight = 11f / 59f
private const val OutlineLightnessLight = 225.5f / 255f
private const val OutlineSaturationDark = 1f / 8f
private const val OutlineLightnessDark = 48f / 255f

/** sRGB 色相（0..360）；仅用于由主色派生页面背景。 */
private fun Color.hueDegrees(): Float {
    val max = maxOf(red, green, blue)
    val delta = max - minOf(red, green, blue)
    if (delta == 0f) return 0f
    val raw = when (max) {
        red -> 60f * (((green - blue) / delta) % 6f)
        green -> 60f * ((blue - red) / delta + 2f)
        else -> 60f * ((red - green) / delta + 4f)
    }
    return if (raw < 0f) raw + 360f else raw
}

/**
 * 主题色（强调色）八选一：换 primary 家族——primary / onPrimary / primaryContainer /
 * inversePrimary / [ClassppColors.primaryContainerSolid]——以及页面背景 [background]、
 * 深色卡片面 [surface]、分割线 [outline]、次级说明文字 [secondaryText] 与弹层压暗遮罩
 * [scrim]；其余背景层（error、紧急旗标、AI 光场）与 [ClassppColors.storageApp] 均固定。
 * 枚举顺序即色板顺序（蓝/厦大蓝/青/绿/紫/橙/粉/山大红）。
 *
 * 业务代码只在主题色选择器里读色球颜色（[primary]），其余场景一律读
 * `colorScheme` / `classppColors` 槽位。
 */
enum class ThemeAccent(@StringRes val labelRes: Int, private val tokens: AccentTokens) {
    Blue(
        R.string.appearance_accent_blue,
        AccentTokens(
            lightPrimary = Primary,
            lightOnPrimary = OnPrimary,
            lightContainerSolid = PrimaryContainerSolid,
            lightScrim = Scrim,
            darkPrimary = DarkPrimary,
            darkOnPrimary = DarkOnPrimary,
            darkContainerSolid = DarkPrimaryContainerSolid,
            darkScrim = DarkScrim,
        ),
    ),
    XmuBlue(
        R.string.appearance_accent_xmu_blue,
        AccentTokens(
            lightPrimary = XmuBluePrimary,
            lightOnPrimary = OnPrimary,
            lightContainerSolid = XmuBluePrimaryContainerSolid,
            lightScrim = XmuBlueScrim,
            darkPrimary = DarkXmuBluePrimary,
            darkOnPrimary = DarkXmuBlueOnPrimary,
            darkContainerSolid = DarkXmuBluePrimaryContainerSolid,
            darkScrim = DarkXmuBlueScrim,
        ),
    ),
    Teal(
        R.string.appearance_accent_teal,
        AccentTokens(
            lightPrimary = TealPrimary,
            lightOnPrimary = OnPrimary,
            lightContainerSolid = TealPrimaryContainerSolid,
            lightScrim = TealScrim,
            darkPrimary = DarkTealPrimary,
            darkOnPrimary = DarkTealOnPrimary,
            darkContainerSolid = DarkTealPrimaryContainerSolid,
            darkScrim = DarkTealScrim,
            darkSurfaceSaturation = SurfaceSaturationDarkSoft,
        ),
    ),
    Green(
        R.string.appearance_accent_green,
        AccentTokens(
            lightPrimary = GreenPrimary,
            lightOnPrimary = OnPrimary,
            lightContainerSolid = GreenPrimaryContainerSolid,
            lightScrim = GreenScrim,
            darkPrimary = DarkGreenPrimary,
            darkOnPrimary = DarkGreenOnPrimary,
            darkContainerSolid = DarkGreenPrimaryContainerSolid,
            darkScrim = DarkGreenScrim,
            darkSurfaceSaturation = SurfaceSaturationDarkSoft,
        ),
    ),
    Purple(
        R.string.appearance_accent_purple,
        AccentTokens(
            lightPrimary = PurplePrimary,
            lightOnPrimary = OnPrimary,
            lightContainerSolid = PurplePrimaryContainerSolid,
            lightScrim = PurpleScrim,
            darkPrimary = DarkPurplePrimary,
            darkOnPrimary = DarkPurpleOnPrimary,
            darkContainerSolid = DarkPurplePrimaryContainerSolid,
            darkScrim = DarkPurpleScrim,
            darkSurfaceSaturation = SurfaceSaturationDarkSoft,
        ),
    ),
    Orange(
        R.string.appearance_accent_orange,
        AccentTokens(
            lightPrimary = OrangePrimary,
            lightOnPrimary = OnPrimary,
            lightContainerSolid = OrangePrimaryContainerSolid,
            lightScrim = OrangeScrim,
            darkPrimary = DarkOrangePrimary,
            darkOnPrimary = DarkOrangeOnPrimary,
            darkContainerSolid = DarkOrangePrimaryContainerSolid,
            darkScrim = DarkOrangeScrim,
            darkSurfaceSaturation = SurfaceSaturationDarkSoft,
        ),
    ),
    Pink(
        R.string.appearance_accent_pink,
        AccentTokens(
            lightPrimary = PinkPrimary,
            lightOnPrimary = OnPrimary,
            lightContainerSolid = PinkPrimaryContainerSolid,
            lightScrim = PinkScrim,
            darkPrimary = DarkPinkPrimary,
            darkOnPrimary = DarkPinkOnPrimary,
            darkContainerSolid = DarkPinkPrimaryContainerSolid,
            darkScrim = DarkPinkScrim,
            darkSurfaceSaturation = SurfaceSaturationDarkSoft,
        ),
    ),
    SduRed(
        R.string.appearance_accent_sdu_red,
        AccentTokens(
            lightPrimary = SduRedPrimary,
            lightOnPrimary = OnPrimary,
            lightContainerSolid = SduRedPrimaryContainerSolid,
            lightScrim = SduRedScrim,
            darkPrimary = DarkSduRedPrimary,
            darkOnPrimary = DarkSduRedOnPrimary,
            darkContainerSolid = DarkSduRedPrimaryContainerSolid,
            darkScrim = DarkSduRedScrim,
            darkSurfaceSaturation = SurfaceSaturationDarkSoft,
        ),
    ),
    ;

    fun primary(darkTheme: Boolean): Color =
        if (darkTheme) tokens.darkPrimary else tokens.lightPrimary

    fun onPrimary(darkTheme: Boolean): Color =
        if (darkTheme) tokens.darkOnPrimary else tokens.lightOnPrimary

    fun containerSolid(darkTheme: Boolean): Color =
        if (darkTheme) tokens.darkContainerSolid else tokens.lightContainerSolid

    /** 页面背景：H 取当前主色，S/L 固定取中性底（见文件顶部的 HSL 常量） */
    fun background(darkTheme: Boolean): Color = Color.hsl(
        hue = primary(darkTheme).hueDegrees(),
        saturation = if (darkTheme) BackgroundSaturationDark else BackgroundSaturationLight,
        lightness = if (darkTheme) BackgroundLightnessDark else BackgroundLightnessLight,
    )

    /** 卡片/浮层底：浅色恒 #FFFFFF；深色 H 取当前主色，S/L 取默认深色 surface（除默认蓝/厦大蓝外 S 降至 6%） */
    fun surface(darkTheme: Boolean): Color =
        if (!darkTheme) {
            Surface
        } else {
            Color.hsl(
                hue = primary(true).hueDegrees(),
                saturation = tokens.darkSurfaceSaturation,
                lightness = SurfaceLightnessDark,
            )
        }

    /** 次级说明文字灰：H 取当前主色，S/L 固定取默认灰（见文件顶部的 HSL 常量） */
    fun secondaryText(darkTheme: Boolean): Color = Color.hsl(
        hue = primary(darkTheme).hueDegrees(),
        saturation = if (darkTheme) SecondaryTextSaturationDark else SecondaryTextSaturationLight,
        lightness = if (darkTheme) SecondaryTextLightnessDark else SecondaryTextLightnessLight,
    )

    /** 分割线/网格线：默认蓝保留原值；其余档 H 取当前主色，S/L 取默认 outline */
    fun outline(darkTheme: Boolean): Color = when (this) {
        Blue -> if (darkTheme) DarkOutline else Outline
        else -> Color.hsl(
            hue = primary(darkTheme).hueDegrees(),
            saturation = if (darkTheme) OutlineSaturationDark else OutlineSaturationLight,
            lightness = if (darkTheme) OutlineLightnessDark else OutlineLightnessLight,
        )
    }

    fun scrim(darkTheme: Boolean): Color =
        if (darkTheme) tokens.darkScrim else tokens.lightScrim
}

@Immutable
private data class AccentTokens(
    val lightPrimary: Color,
    val lightOnPrimary: Color,
    val lightContainerSolid: Color,
    val lightScrim: Color,
    val darkPrimary: Color,
    val darkOnPrimary: Color,
    val darkContainerSolid: Color,
    val darkScrim: Color,
    val darkSurfaceSaturation: Float = SurfaceSaturationDark,
)
