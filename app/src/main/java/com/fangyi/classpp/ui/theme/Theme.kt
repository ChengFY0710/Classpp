package com.fangyi.classpp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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

/**
 * colorScheme 槽位之外的自定义主题色。
 * 接入深色模式时在此增加一套取值，并在 [ClassppTheme] 中按模式 provide。
 */
@Immutable
class ClassppColors(
    /** 次级说明文字灰 */
    val secondaryText: Color,
    /** 选中态浅蓝容器（不透明） */
    val primaryContainerNontrans: Color,
)

private val LightClassppColors = ClassppColors(
    secondaryText = Color(0xFFABAFB4),
    primaryContainerNontrans = Color(0xFFE0EDFD),
)

private val LocalClassppColors = staticCompositionLocalOf { LightClassppColors }

/** 主题自定义色访问入口，用法：`MaterialTheme.classppColors.secondaryText` */
val MaterialTheme.classppColors: ClassppColors
    @Composable get() = LocalClassppColors.current

@Composable
fun ClassppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content,
    )
}
