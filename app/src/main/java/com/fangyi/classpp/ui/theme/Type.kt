package com.fangyi.classpp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

/**
 * 角色化文字样式：app 内反复出现的「同一角色」文字收敛到这一处定义，
 * 设置页与浮层卡片组件只按角色取用，用法：
 * `Text(text = ..., style = MaterialTheme.classppTextStyles.fieldLabel)`。
 *
 * 颜色取自当前主题（onSurface / primary / secondaryText），随 [ClassppTheme] 深浅切换；
 * 只统一字号 / 粗细 / 颜色——行高与字距留给调用处按各自既有的排版节奏给出
 * （行卡类文字的 24sp / 0.5sp 用文件尾的 [settingsRowMetrics] 一行补齐）。
 */
@Immutable
class ClassppTextStyles(
    /** 顶栏居中标题：20sp Bold onSurface（设置页顶栏与浮层顶栏同款） */
    val topBarTitle: TextStyle,
    /** 分组标题（卡片外的次级灰字）：16sp Medium secondaryText */
    val sectionTitle: TextStyle,
    /** 行卡左侧 label：16sp SemiBold onSurface */
    val fieldLabel: TextStyle,
    /** 输入占位文字：16sp Medium secondaryText */
    val fieldPlaceholder: TextStyle,
    /** 行卡右侧值：16sp Medium primary */
    val fieldValue: TextStyle,
    /** 胶囊按钮文字：15sp SemiBold；颜色随按钮配色由调用方传入（onPrimary/onSurface/onError），样式本身不带色 */
    val pillButton: TextStyle,
    /** 浮层下拉菜单项：16sp Medium onSurface */
    val menuItem: TextStyle,
)

/** 按当前 colorScheme 与自定义色构造一组角色化文字样式（由 [ClassppTheme] 提供） */
fun classppTextStyles(colorScheme: ColorScheme, colors: ClassppColors) = ClassppTextStyles(
    topBarTitle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colorScheme.onSurface),
    sectionTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = colors.secondaryText),
    fieldLabel = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colorScheme.onSurface),
    fieldPlaceholder = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = colors.secondaryText),
    fieldValue = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = colorScheme.primary),
    pillButton = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    menuItem = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = colorScheme.onSurface),
)

/**
 * 行卡类文字（分组标题 / 行 label / 行值）的行高与字距：沿用原 bodyLarge 的取值，
 * 换成角色化样式后行距节奏保持不变（角色样式本身只统一字号 / 粗细 / 颜色）。
 * 设置页 SettingRow 与通用分组 CardSection 共用，改这里两处同步生效。
 */
internal fun TextStyle.settingsRowMetrics() = copy(lineHeight = 24.sp, letterSpacing = 0.5.sp)
