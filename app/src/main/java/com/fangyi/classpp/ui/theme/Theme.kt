package com.fangyi.classpp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    inversePrimary = InversePrimary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    onSurfaceVariant = OnSurfaceVariant,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
    outline = Outline,
    error = Error,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
    scrim = Scrim,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    inversePrimary = DarkInversePrimary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    outline = DarkOutline,
    error = DarkError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    scrim = DarkScrim,
)

/**
 * colorScheme 槽位之外的自定义主题色。
 * 浅色/深色各一套取值，由 [ClassppTheme] 按模式 provide。
 */
@Immutable
class ClassppColors(
    /** 次级说明文字灰 */
    val secondaryText: Color,
    /** 选中态浅蓝容器（不透明） */
    val primaryContainerNontrans: Color,
    /**
     * 顶栏等 Haze 磨砂的叠加基础色（浅色 = 白、深色 = 浅灰蓝）。
     * 调用处按强度 copy(alpha)——各磨砂位强度不同（0.30f / 0.6f），基础色只有一个来源。
     */
    val hazeTint: Color,
)

private val LightClassppColors = ClassppColors(
    secondaryText = Color(0xFFABAFB4),
    primaryContainerNontrans = Color(0xFFE0EDFD),
    hazeTint = Color.White,
)

private val DarkClassppColors = ClassppColors(
    secondaryText = Color(0xFF7E858D),
    primaryContainerNontrans = Color(0xFF1E3A5E),
    hazeTint = Color(0xFFD9E1EA),
)

private val LocalClassppColors = staticCompositionLocalOf { LightClassppColors }

private val LocalClassppDarkTheme = staticCompositionLocalOf { false }

/** 主题自定义色访问入口，用法：`MaterialTheme.classppColors.secondaryText` */
val MaterialTheme.classppColors: ClassppColors
    @Composable get() = LocalClassppColors.current

/**
 * 当前是否深色主题：跟随 [ClassppTheme] 的入参（含设置页的手动覆盖），
 * 而非系统设置。colorScheme 槽位之外的颜色（置灰规范色、Haze tint 等）按它选深浅取值。
 */
val MaterialTheme.isDarkTheme: Boolean
    @Composable get() = LocalClassppDarkTheme.current

@Composable
fun ClassppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    CompositionLocalProvider(
        LocalClassppColors provides if (darkTheme) DarkClassppColors else LightClassppColors,
        LocalClassppDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
