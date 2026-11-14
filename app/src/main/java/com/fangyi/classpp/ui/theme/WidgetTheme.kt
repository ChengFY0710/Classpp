package com.fangyi.classpp.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle as ComposeTextStyle
import androidx.compose.ui.text.font.FontWeight as ComposeFontWeight
import androidx.glance.text.FontWeight as GlanceFontWeight
import androidx.glance.text.TextStyle as GlanceTextStyle
import androidx.glance.unit.ColorProvider
import com.fangyi.classpp.data.model.CourseColor
import com.fangyi.classpp.ui.schedule.CourseColor as UiCourseColor
import com.fangyi.classpp.ui.schedule.barColor

/**
 * 桌面小组件（Glance）的取色与文字样式：widget 侧不写色值、不写字号。
 *
 * - 颜色：深浅与主题色经 [ThemePreferences] 解析后走 [buildClassppColorScheme] /
 *   [buildClassppColors]，与 App 内完全同源（主题色八档、深色 surface、次级文字灰都跟随）。
 * - 文字：字号 / 字重从 [ClassppTextTokens] 派生，这里只做 Compose → Glance 的类型转换。
 *
 * 平台限制（Glance 1.2.0，已登记到 docs/字体使用规范.md 第 14 节）：
 * 1. `androidx.glance.text.FontWeight` 只有 Normal / Medium / Bold，**没有 SemiBold**：
 *    规范里的 600 标题落到 Medium(500)。Bold 被规范禁用，不因平台缺档改用它。
 * 2. `TextStyle` 没有 lineHeight / fontFeatureSettings：单行短文本按系统行高渲染，
 *    时间数字不做 tnum——组件内文字不会原地换值，没有抖动问题。
 */
internal data class WidgetColors(
    val dark: Boolean,
    val surface: Color,
    val outline: Color,
    val onSurface: Color,
    val secondaryText: Color,
    val primary: Color,
    /** 当前节行的选中底：规范里的「行选择卡选中底」不透明实色。 */
    val currentRowBackground: Color,
) {
    /** 非当前课程的竖条：课程 18 色与课表 / 日程同源（浅深映射由 barColor 统一给出）。 */
    fun courseBar(color: CourseColor): Color =
        UiCourseColor.valueOf(color.name).barColor(dark)
}

/** widget 用到的全部文字样式，语义与 App 内的 token 一一对应。 */
internal data class WidgetTextStyles(
    /** 顶部「第 N 周 · 周X」：cardTitle（16sp SemiBold）。 */
    val headerTitle: GlanceTextStyle,
    /** 顶部「今日 N 节」：metaText（14sp Normal），次级灰。 */
    val headerMeta: GlanceTextStyle,
    /** 课程名：fieldLabel（16sp SemiBold），主文字色。 */
    val courseTitle: GlanceTextStyle,
    /** 时间 · 地点：settingsDescription（14sp Normal），次级灰。 */
    val courseMeta: GlanceTextStyle,
    /** 空态提示：emptyHint（14sp Normal），次级灰。 */
    val empty: GlanceTextStyle,
)

/** 按当前 App 外观（手动深浅 / 主题色）解析 widget 取色。 */
internal fun resolveWidgetColors(context: Context): WidgetColors {
    val dark = resolveWidgetDarkTheme(context)
    val accent = ThemePreferences.loadAccent(context)
    val scheme = buildClassppColorScheme(dark, accent)
    val colors = buildClassppColors(dark, accent)
    return WidgetColors(
        dark = dark,
        surface = scheme.surface,
        outline = scheme.outline,
        onSurface = scheme.onSurface,
        secondaryText = colors.secondaryText,
        primary = scheme.primary,
        currentRowBackground = colors.primaryContainerSolid,
    )
}

/** 外观模式 → 当前是否深色；与 MainActivity 首帧判定同一口径（跟随系统时读当前配置）。 */
internal fun resolveWidgetDarkTheme(context: Context): Boolean =
    when (ThemePreferences.load(context)) {
        ThemeMode.System ->
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

internal fun widgetTextStyles(colors: WidgetColors): WidgetTextStyles = WidgetTextStyles(
    headerTitle = ClassppTextTokens.cardTitle.toWidgetStyle(colors.onSurface),
    headerMeta = ClassppTextTokens.metaText.toWidgetStyle(colors.secondaryText),
    courseTitle = ClassppTextTokens.fieldLabel.toWidgetStyle(colors.onSurface),
    courseMeta = ClassppTextTokens.settingsDescription.toWidgetStyle(colors.secondaryText),
    empty = ClassppTextTokens.emptyHint.toWidgetStyle(colors.secondaryText),
)

private fun ComposeTextStyle.toWidgetStyle(color: Color): GlanceTextStyle = GlanceTextStyle(
    color = ColorProvider(color),
    fontSize = fontSize,
    fontWeight = fontWeight.toWidgetWeight(),
)

/** 600 / 500 → Medium（Glance 无 SemiBold）；其余 Normal。 */
private fun ComposeFontWeight?.toWidgetWeight(): GlanceFontWeight = when {
    this == null -> GlanceFontWeight.Normal
    weight >= 500 -> GlanceFontWeight.Medium
    else -> GlanceFontWeight.Normal
}
