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
import com.fangyi.classpp.ui.LocalTodayState
import com.fangyi.classpp.ui.motion.NoOpIndication
import com.fangyi.classpp.ui.rememberTodayState

// 显式装配的槽位之外，secondary / tertiary / outlineVariant 等停留 M3 紫色基线：
// 当前业务零消费属有意留白，任何槽位使用前必须先在此显式装配（见《颜色使用规范》第 12 节）。
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
    onError = OnError,
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
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    scrim = DarkScrim,
)

/**
 * colorScheme 槽位之外的自定义主题色。
 * 浅色/深色各一套取值，由 [ClassppTheme] 按模式 provide。
 */
@Immutable
data class ClassppColors(
    /**
     * 次级说明文字灰：H 取主色、S/L 取默认灰，随主题色切换
     * （默认蓝为浅 #ABAFB4 / 深 #7E858D）。
     */
    val secondaryText: Color,
    /**
     * 说明行标题灰（SettingsCard 纯文字行）：固定中性灰、不随主题色切换，
     * 比 [secondaryText] 更实——弱化层级但保持标题可读
     * （浅 #787F87 / 深 #AEB2B7）。
     */
    val secondaryGrey: Color,
    /**
     * primary/error 染色玻璃上的前景色（图标/文字）：浅 #FFFFFF / 深 #E4E7EA。
     * 玻璃染色后底材偏暗，深色下不能再沿用 [MaterialTheme.colorScheme.onPrimary]
     * 的深蓝图文；白色玻璃仍用各自的 onSurface/primary 内容色。
     */
    val onPrimaryGlass: Color,
    /**
     * 选中方格的不透明 primaryContainer 替身：半透明底色在按压反馈提亮/压暗时会透出
     * 下层色导致闪烁，方格选中底改用本实色。随 [ClassppTheme] 的主题色取值
     * （见 [ThemeAccent]）；默认蓝为浅 #E2EDFB / 深 #394C67。
     */
    val primaryContainerSolid: Color,
    /** 卡面细弱元素（卡片内分隔线、点状轨道）：浅 = outline 同值，深 = 卡面上可见的中灰 */
    val onSurfaceCard: Color,
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
     * 开关（ClassppSwitch）不可点击时的置灰轨道色：浅 #EEEEEE / 深 #1C1E22，
     * 不论 checked 与否——与 [negative] 的「弱化」语义分开，专表禁用。
     */
    val switchDisabled: Color,
    /**
     * 底部弹出选择卡（BottomSelectSheet）卡底色：浅色 = 白卡浮于浅灰页面；
     * 深色与页面背景同值（最深一档），不再像 surface 那样比页面亮一档。
     */
    val bottomSheetColor: Color,
    /**
     * 紧急程度旗标三色（UrgentFlagCard）：橙 = 很急、黄 = 急、绿 = 不急；
     * 红 = colorScheme.error、灰 = [secondaryText]，不在此列。深浅各一套。
     */
    val urgentOrange: Color,
    val urgentYellow: Color,
    val urgentGreen: Color,
    /**
     * 存储用量三段色（数据管理页的用量条与图例专用）：应用大小 = 固定品牌蓝、
     * 用户数据 = 黄、缓存 = 绿。深浅各一套；不参与主题色切换，避免绿色系主题
     * 与缓存绿撞色；也不用于任何业务强调或旗标。
     */
    val storageApp: Color,
    val storageUser: Color,
    val storageCache: Color,
    /**
     * AI 对话浮层底部「思考光场」四色（AiThinkingAurora 专用）：浅色 = 蓝 → 青 → 薄荷
     * → 黄绿；深色 = 同色相压暗的深极光。全 app 唯一的多色装饰性渐变，只允许
     * [com.fangyi.classpp.ui.ai.AiThinkingAurora] 消费，不得挪作其它背景或强调色。
     */
    val aiAuroraBlue: Color,
    val aiAuroraCyan: Color,
    val aiAuroraMint: Color,
    val aiAuroraLime: Color,
)

private val LightClassppColors = ClassppColors(
    secondaryText = SecondaryText,
    secondaryGrey = SecondaryGrey,
    onPrimaryGlass = OnPrimaryGlass,
    primaryContainerSolid = PrimaryContainerSolid,
    onSurfaceCard = OnSurfaceCard,
    hazeTint = HazeTint,
    negative = Negative,
    switchDisabled = SwitchDisabled,
    bottomSheetColor = BottomSheetColor,
    urgentOrange = UrgentOrange,
    urgentYellow = UrgentYellow,
    urgentGreen = UrgentGreen,
    storageApp = StorageApp,
    storageUser = StorageUser,
    storageCache = StorageCache,
    aiAuroraBlue = AiAuroraBlue,
    aiAuroraCyan = AiAuroraCyan,
    aiAuroraMint = AiAuroraMint,
    aiAuroraLime = AiAuroraLime,
)

private val DarkClassppColors = ClassppColors(
    secondaryText = DarkSecondaryText,
    secondaryGrey = DarkSecondaryGrey,
    onPrimaryGlass = DarkOnPrimaryGlass,
    primaryContainerSolid = DarkPrimaryContainerSolid,
    onSurfaceCard = DarkOnSurfaceCard,
    hazeTint = DarkHazeTint,
    negative = DarkNegative,
    switchDisabled = DarkSwitchDisabled,
    bottomSheetColor = DarkBottomSheetColor,
    urgentOrange = DarkUrgentOrange,
    urgentYellow = DarkUrgentYellow,
    urgentGreen = DarkUrgentGreen,
    storageApp = DarkStorageApp,
    storageUser = DarkStorageUser,
    storageCache = DarkStorageCache,
    aiAuroraBlue = DarkAiAuroraBlue,
    aiAuroraCyan = DarkAiAuroraCyan,
    aiAuroraMint = DarkAiAuroraMint,
    aiAuroraLime = DarkAiAuroraLime,
)

private val LocalClassppColors = staticCompositionLocalOf { LightClassppColors }

/** 默认 = 浅色一组（与 [ClassppColors] 同一约定），未包 [ClassppTheme] 时预览不崩 */
private val LocalClassppTextStyles = staticCompositionLocalOf {
    classppTextStyles(LightColorScheme, LightClassppColors)
}

private val LocalClassppDarkTheme = staticCompositionLocalOf { false }

private val LocalThemeAccent = staticCompositionLocalOf { ThemeAccent.Blue }

/** 当前主题色：设置页顶栏冻结键等色彩相关缓存的失效依据（同 [isDarkTheme]） */
val MaterialTheme.themeAccent: ThemeAccent
    @Composable get() = LocalThemeAccent.current

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
    accent: ThemeAccent = ThemeAccent.Blue,
    content: @Composable () -> Unit,
) {
    // 主题色改 primary 家族、页面背景、深色 surface、分割线、遮罩与次级文字；ColorScheme 无 equals，
    // 必须 remember 住实例，否则每次重组都会重建派生色并连带重建 textStyles
    val colorScheme = remember(accent, darkTheme) {
        val base = if (darkTheme) DarkColorScheme else LightColorScheme
        val primary = accent.primary(darkTheme)
        base.copy(
            primary = primary,
            onPrimary = accent.onPrimary(darkTheme),
            primaryContainer = primary.copy(
                alpha = if (darkTheme) PrimaryContainerAlphaDark else PrimaryContainerAlphaLight,
            ),
            inversePrimary = primary.copy(
                alpha = if (darkTheme) InversePrimaryAlphaDark else InversePrimaryAlphaLight,
            ),
            background = accent.background(darkTheme),
            surface = accent.surface(darkTheme),
            outline = accent.outline(darkTheme),
            scrim = accent.scrim(darkTheme),
        )
    }
    val classppColors = remember(accent, darkTheme) {
        (if (darkTheme) DarkClassppColors else LightClassppColors).copy(
            secondaryText = accent.secondaryText(darkTheme),
            primaryContainerSolid = accent.containerSolid(darkTheme),
        )
    }
    // 角色化文字样式由主题色派生：深浅/主题色切换时重建，同一主题下实例稳定（static local 不额外失效）
    val textStyles = remember(colorScheme, classppColors) {
        classppTextStyles(colorScheme, classppColors)
    }
    CompositionLocalProvider(
        LocalClassppColors provides classppColors,
        LocalClassppTextStyles provides textStyles,
        LocalClassppDarkTheme provides darkTheme,
        LocalThemeAccent provides accent,
        // 「今天」注入（见 [rememberTodayState]）：跨零点自动跳天、回前台对表。页面里所有
        // 按天派生的显示（倒计时、截止配色、今天/明天相对词、逾期分组）读 [LocalToday]
        // 而非系统时钟——后者挂过夜只会停在打开那天的日期
        LocalTodayState provides rememberTodayState(),
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
