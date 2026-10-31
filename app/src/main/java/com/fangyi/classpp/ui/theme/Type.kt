package com.fangyi.classpp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Material3 的 15 个文字槽位全部按方案 B 显式定义，避免 M3 组件静默回退 baseline。 */
val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 64.sp,
        lineHeight = 68.sp,
        letterSpacing = 0.sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 48.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    // 23sp：UI 最大常规档，与 ClassppTextTokens.pageTitle / detailTitle 对齐。
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    // 20sp：M3 AlertDialog 标题槽，与 ClassppTextTokens.dialogTitle / sheetTitle 同档。
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    ),
)

/**
 * 角色化文字样式：app 内反复出现的「同一角色」文字收敛到这一处定义，
 * 设置页与浮层卡片组件只按角色取用，用法：
 * `Text(text = ..., style = MaterialTheme.classppTextStyles.fieldLabel)`。
 *
 * 字面值来自 [ClassppTextTokens]（唯一来源）；颜色取自当前主题
 * （onSurface / primary / secondaryText），随 [ClassppTheme] 深浅切换。
 * 角色样式本身不含行高与字距：行卡 label/value 继承 [Typography.bodyLarge]
 * 的 24sp / 0sp；分组标题用 [settingsRowMetrics] 补 20sp / 0sp。
 */
@Immutable
class ClassppTextStyles(
    /** 顶栏居中标题：20sp SemiBold onSurface（设置页顶栏与浮层顶栏同款） */
    val topBarTitle: TextStyle,
    /** 分组标题（卡片外的次级灰字）：14sp Medium secondaryText */
    val sectionTitle: TextStyle,
    /** 行卡左侧 label：16sp SemiBold onSurface */
    val fieldLabel: TextStyle,
    /** 输入占位文字：16sp Medium secondaryText */
    val fieldPlaceholder: TextStyle,
    /** 行卡右侧值：16sp Medium primary */
    val fieldValue: TextStyle,
    /** 胶囊按钮文字：16sp SemiBold；颜色随按钮配色由调用方传入（onPrimary/onSurface/onError），样式本身不带色 */
    val pillButton: TextStyle,
    /** 待办卡片属性行（时间、标签文字）：14sp Normal secondaryText */
    val noteProperty: TextStyle,
)

/** 按当前 colorScheme 与自定义色构造一组角色化文字样式（由 [ClassppTheme] 提供） */
fun classppTextStyles(colorScheme: ColorScheme, colors: ClassppColors) = ClassppTextStyles(
    topBarTitle = ClassppTextTokens.sheetTitle.copy(color = colorScheme.onSurface),
    sectionTitle = ClassppTextTokens.sectionTitle.copy(color = colors.secondaryText),
    fieldLabel = ClassppTextTokens.fieldLabel.copy(color = colorScheme.onSurface),
    fieldPlaceholder = ClassppTextTokens.placeholder.copy(color = colors.secondaryText),
    fieldValue = ClassppTextTokens.bodyValue.copy(color = colorScheme.primary),
    pillButton = ClassppTextTokens.buttonText,
    noteProperty = ClassppTextTokens.secondaryText.copy(color = colors.secondaryText),
)

/**
 * 分组标题的行高与字距：方案 B 的 20sp 行高 + 中文 0 字距。
 * 通用分组 [com.fangyi.classpp.ui.components.CardSection] 与待办分组头使用，改这里同步生效。
 */
internal fun TextStyle.settingsRowMetrics() = copy(lineHeight = 20.sp, letterSpacing = 0.sp)
