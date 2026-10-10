package com.fangyi.classpp.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.fangyi.classpp.ui.motion.NoOpIndication

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    inversePrimary = InversePrimary,
    background = Background,
    surface = Surface,
    onSurface = OnSurface,
    onSurfaceVariant = OnSurfaceVariant,
    // M3 对话框（AlertDialog/DatePickerDialog）的默认底色就是它，不可省：
    // 不传会静默回退库默认的淡紫灰 baseline
    surfaceContainerHigh = SurfaceContainerHigh,
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
    background = DarkBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
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
    /**
     * 顶栏等 Haze 磨砂的叠加基础色（浅色 = 白提亮、深色 = 深底色压暗）。
     * 调用处按强度 copy(alpha)——各磨砂位强度不同（0.30f / 0.6f），基础色只有一个来源。
     */
    val hazeTint: Color,
    /**
     * 置灰规范色（需求 7）：本周不上的课等「刻意弱化」场景。
     * 浅色 = 卡片底 #CBCBCB；深色卡底上取更暗的灰，保持同等弱化的视觉强度。
     */
    val negative: Color,
    /**
     * 紧急程度旗标三色（UrgentFlagCard）：橙 = 很急、黄 = 急、绿 = 不急；
     * 红 = colorScheme.error、灰 = [secondaryText]，不在此列。深浅各一套。
     */
    val urgentOrange: Color,
    val urgentYellow: Color,
    val urgentGreen: Color,
)

private val LightClassppColors = ClassppColors(
    secondaryText = Color(0xFFABAFB4),
    hazeTint = Color.White,
    negative = Color(0xFFCBCBCB),
    urgentOrange = UrgentOrange,
    urgentYellow = UrgentYellow,
    urgentGreen = UrgentGreen,
)

private val DarkClassppColors = ClassppColors(
    secondaryText = Color(0xFF7E858D),
    hazeTint = Color.Black,
    negative = Color(0xFF5C6269),
    urgentOrange = DarkUrgentOrange,
    urgentYellow = DarkUrgentYellow,
    urgentGreen = DarkUrgentGreen,
)

private val LocalClassppColors = staticCompositionLocalOf { LightClassppColors }

/** 默认 = 浅色一组（与 [ClassppColors] 同一约定），未包 [ClassppTheme] 时预览不崩 */
private val LocalClassppTextStyles = staticCompositionLocalOf {
    classppTextStyles(LightColorScheme, LightClassppColors)
}

private val LocalClassppDarkTheme = staticCompositionLocalOf { false }

/** 主题自定义色访问入口，用法：`MaterialTheme.classppColors.secondaryText` */
val MaterialTheme.classppColors: ClassppColors
    @Composable get() = LocalClassppColors.current

/** 角色化文字样式访问入口，用法：`MaterialTheme.classppTextStyles.fieldLabel`（定义见 Type.kt） */
val MaterialTheme.classppTextStyles: ClassppTextStyles
    @Composable get() = LocalClassppTextStyles.current

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
    val classppColors = if (darkTheme) DarkClassppColors else LightClassppColors
    // 角色化文字样式由主题色派生：深浅切换时重建，同一主题下实例稳定（static local 不额外失效）
    val textStyles = remember(colorScheme, classppColors) {
        classppTextStyles(colorScheme, classppColors)
    }
    CompositionLocalProvider(
        LocalClassppColors provides classppColors,
        LocalClassppTextStyles provides textStyles,
        LocalClassppDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
        ) {
            // 涟漪整体退场：点击反馈统一由 ui.motion 的按压反馈（放大 + 提亮）接管。
            // 两路涟漪各走一个开关——裸 clickable 读 LocalIndication；M3 按钮内部是
            // 硬编码 ripple()、只看 LocalRippleConfiguration（提供 null 即彻底不画，
            // 官方注释明示）。两者都必须嵌在 MaterialTheme 之内，才盖得住它提供的默认值。
            // 已知取舍：M3 DatePicker/TimePicker 内部格子同属硬编码 ripple，会一并失去
            // 涟漪，靠选中态变色反馈；如需恢复可在这些弹层外局部提供回原配置。
            CompositionLocalProvider(
                LocalIndication provides NoOpIndication,
                LocalRippleConfiguration provides null,
            ) { content() }
        }
    }
}
