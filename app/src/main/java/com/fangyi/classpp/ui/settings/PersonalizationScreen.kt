package com.fangyi.classpp.ui.settings

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SheetBottomSlack
import com.fangyi.classpp.ui.motion.LocalPageOverlayActive
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.motion.rubberBandVerticalScroll
import com.fangyi.classpp.ui.schedule.CardHeightPreferences
import com.fangyi.classpp.ui.schedule.CardHeights
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.SettingsSectionSpacing
import com.fangyi.classpp.ui.theme.ThemeMode
import com.kyant.backdrop.backdrops.layerBackdrop
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

/**
 * 个性化子页（设置 → 个性化）：由 [SettingsScreen] 以整页覆盖转场组合在根页之上
 * （与设置页覆盖层同源，见 ui.motion 的 PageOverlayTransition），返回按钮/返回键经 [onBack] 回到设置根页。
 *
 * 骨架与设置根页同构（Scaffold + 滚动内容列 + 顶栏叠加），正文承载 app 级外观与个性化设置：
 * 「颜色模式」三选一（跟随系统/浅色/深色，[themeMode] / [onThemeModeChange]），
 * 行尾显示当前模式，点行弹选择对话框，点选项即时生效并关闭——对话框下方的页面同步变色，
 * 反馈直观，无需「确定」一步；「课表」分组为课程卡片高度滑条（[cardHeights] /
 * [onCardHeightsChange]，范围与默认值随 [repository] 激活课表的 5/7 天模式切换）
 * 与「显示教师」开关（[showTeacher] / [onShowTeacherChange]，控制课程卡片上教师行的显隐）。
 * 后续外观类设置项（字号、图标风格等）继续加入本页。
 */
@Composable
fun PersonalizationScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    themeMode: ThemeMode = ThemeMode.System,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    repository: ScheduleRepository? = null,
    // State 实例透传（本层不读 .value，值只在 ScheduleSection 叶子内读）：
    // 拖动滑条时本页整体 skip，只有滑条卡片子树每帧重组
    cardHeights: State<CardHeights> = mutableStateOf(CardHeights()),
    onCardHeightsChange: (CardHeights) -> Unit = {},
    // 课程卡片「显示教师」开关（State 实例透传纪律同上）
    showTeacher: State<Boolean> = mutableStateOf(true),
    onShowTeacherChange: (Boolean) -> Unit = {},
    // 玻璃材质开关（State 实例透传纪律同上，值只在 ColorModeSection 叶子内读）
    glassStyle: State<Boolean> = mutableStateOf(false),
    onGlassStyleChange: (Boolean) -> Unit = {},
) {
    // 当前激活课表决定滑条走 5 天还是 7 天的范围/默认值；无课表（加载中/空）回退 5 天
    val schedule by remember(repository) {
        repository?.activeSchedule ?: MutableStateFlow<Schedule?>(null)
    }.collectAsState()
    val daysPerWeek = schedule?.daysPerWeek ?: 5
    Scaffold(
        modifier = modifier,
        // 沉浸式：只吃左右 inset，状态栏/手势条区域交给内容与顶栏自己铺满（同设置根页）
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        // 顶栏叠在内容之上：内容整屏铺开（hazeSource），首屏经 topBarHeight 内缩到顶栏之下，
        // 上滑时从顶栏背后滚过，顶栏用 Haze 对其做自上而下的渐变背景模糊
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val hazeState = rememberHazeState()
            // 顶栏返回按钮的液态玻璃：采样层挂下方正文列（与 hazeSource 同一节点，
            // Haze 管顶栏磨砂、Backdrop 只喂返回按钮）；null = 玻璃关闭，标记不挂载
            val glassBackdrop = rememberSettingsGlassBackdrop()
            var topBarHeight by remember { mutableStateOf(0.dp) }
            val density = LocalDensity.current
            // 手势条（小白条）区域：内容可铺到屏幕底，末尾留出这段避免被遮挡
            val navBarPadding = WindowInsets.navigationBars
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues()
                .calculateBottomPadding()

            // 采样源始终存在：正文列挂 hazeSource，顶栏背后才有内容可采（不可省）
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState)
                    // 玻璃返回按钮开启时同一节点再挂液态玻璃采样标记
                    .then(
                        if (glassBackdrop != null) {
                            Modifier.layerBackdrop(glassBackdrop)
                        } else {
                            Modifier
                        },
                    )
                    // 橡皮筋增强版 verticalScroll：内容不足一屏时也能拉出橡皮筋
                    // （ui.motion 的 rubberBandVerticalScroll）
                    .rubberBandVerticalScroll(rememberScrollState())
                    .padding(horizontal = PageHorizontalSpacing)
                    .padding(
                        // 首项距顶栏留 TopBarContentGap（页自有节奏），尾部余量与浮层同源
                        top = topBarHeight + TopBarContentGap,
                        bottom = navBarPadding + SheetBottomSlack,
                    ),
                verticalArrangement = Arrangement.spacedBy(SettingsSectionSpacing),
            ) {
                ColorModeSection(
                    mode = themeMode,
                    onSelect = onThemeModeChange,
                    glassStyle = glassStyle,
                    onGlassStyleChange = onGlassStyleChange,
                )
                ScheduleSection(
                    daysPerWeek = daysPerWeek,
                    cardHeights = cardHeights,
                    onCardHeightsChange = onCardHeightsChange,
                    showTeacher = showTeacher,
                    onShowTeacherChange = onShowTeacherChange,
                )
            }

            SettingsTopBar(
                title = stringResource(R.string.settings_personalization),
                onBack = onBack,
                // 子页在覆盖层隐藏时仍常驻组合、整页停在屏外：只在活跃期挂 Haze 效果，
                // 避免对屏外页面做源捕获/模糊（Haze 2 下会触发 RenderThread 递归崩溃）
                hazeState = if (LocalPageOverlayActive.current) hazeState else null,
                buttonBackdrop = glassBackdrop,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .onGloballyPositioned { coords ->
                        // 顶栏实测高度 = 内容首屏内缩量（px → dp）
                        topBarHeight = with(density) { coords.size.height.toFloat().toDp() }
                    },
            )
        }
    }
}

/**
 * 「课表」分组：课程卡片高度滑条 + 「显示教师」开关，各占一张独立卡片（CardSection
 * 卡间距分隔）。滑条范围与默认值随 [daysPerWeek]
 * 切换——5 天 100–200 默认 150、7 天 120–300 默认 160（[CardHeightPreferences]，
 * 默认值即现行行高），滑至默认值附近自动吸附并震动；值等于默认时右上显示「默认」，
 * 否则显示具体 dp 值。开关控制课程卡片上教师行的显隐（[TeacherDisplayPreferences]），
 * 关闭后地点行上移、行高预算自动归还课程名/地点。
 */
@Composable
private fun ScheduleSection(
    daysPerWeek: Int,
    cardHeights: State<CardHeights>,
    onCardHeightsChange: (CardHeights) -> Unit,
    showTeacher: State<Boolean>,
    onShowTeacherChange: (Boolean) -> Unit,
) {
    // 值读取点（叶子）：拖动时只有本函数及其子树（卡片/滑条）每帧重组
    val sevenDay = daysPerWeek > 5
    val default = if (sevenDay) CardHeightPreferences.Default7 else CardHeightPreferences.Default5
    val height = if (sevenDay) cardHeights.value.seven else cardHeights.value.five
    val showTeacherOnCard = showTeacher.value
    CardSection(title = stringResource(R.string.section_schedule)) {
        SettingsCard(
            items = listOf(
                SettingsCardItem.Slider(
                    label = stringResource(R.string.card_height_label),
                    value = height.toFloat(),
                    onValueChange = { value ->
                        val newHeight = value.roundToInt()
                        // 事件回调里读 .value 不订阅组合，无重组开销
                        val current = cardHeights.value
                        onCardHeightsChange(
                            if (sevenDay) current.copy(seven = newHeight)
                            else current.copy(five = newHeight),
                        )
                    },
                    valueText = if (height == default) {
                        stringResource(R.string.card_height_default)
                    } else {
                        stringResource(R.string.card_height_value, height)
                    },
                    defaultValue = default.toFloat(),
                    valueRange = if (sevenDay) {
                        CardHeightPreferences.Range7
                    } else {
                        CardHeightPreferences.Range5
                    },
                ),
            ),
        )
        // 独立一张卡片：开关与滑条分开（组内卡间距 10dp，见 CardSection）
        SettingsCard(
            items = listOf(
                SettingsCardItem.Toggle(
                    label = stringResource(R.string.show_teacher),
                    checked = showTeacherOnCard,
                    onCheckedChange = onShowTeacherChange,
                ),
            ),
        )
    }
}

/**
 * 颜色模式分组：app 级三选一（跟随系统/浅色/深色），行尾显示当前模式。
 * 点行弹选择对话框，点选项即时生效并关闭——对话框下方的页面同步变色，反馈直观，
 * 无需「确定」一步。同组下方为「更高级的玻璃材质」开关（[glassStyle] /
 * [onGlassStyleChange]，控制底部导航栏三胶囊、待办页顶栏胶囊、设置页顶栏返回按钮、
 * 课表页顶栏周数胶囊与 OverlaySheet 顶栏按钮的 Backdrop 液态玻璃渲染 + Q弹按压；
 * 关闭即各自原实现，低版本与性能说明见描述文案，持久化见 GlassPreferences）。
 */
@Composable
private fun ColorModeSection(
    mode: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    glassStyle: State<Boolean>,
    onGlassStyleChange: (Boolean) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    // 颜色模式与玻璃材质同属外观分组：两卡收进同一 CardSection（无标题纯分组容器，
    // 组内卡间距 10dp），与「课表」分组的两卡结构对齐
    CardSection {
        SettingsCard(
            items = listOf(
                SettingsCardItem.Nav(
                    label = stringResource(R.string.appearance_theme_mode),
                    value = stringResource(mode.labelRes),
                    onClick = { picking = true },
                ),
            ),
        )
        // 独立一张卡片：开关与模式行分开（组内卡间距 10dp，见 CardSection）
        SettingsCard(
            items = listOf(
                SettingsCardItem.Toggle(
                    label = stringResource(R.string.glass_material),
                    description = stringResource(R.string.glass_material_desc),
                    checked = glassStyle.value,
                    onCheckedChange = onGlassStyleChange,
                ),
            ),
        )
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = {
                Text(stringResource(R.string.appearance_theme_mode))
            },
            text = {
                Column {
                    ThemeMode.entries.forEach { candidate ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // 行上无 clip，原位把 clickable 换成 pressClickable（自带按压源，
                                // 按压反馈取代涟漪）；通栏行用 RectangleShape，各行独立互不串亮
                                .pressClickable(RectangleShape) {
                                    onSelect(candidate)
                                    picking = false
                                }
                                // 行高给足触达面积；radio 与文字的间距随行内边距统一
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 点击由整行承担：radio 本身 onClick = null，只做状态展示
                            RadioButton(selected = candidate == mode, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(candidate.labelRes),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            },
            // 即时生效交互：唯一的按钮承担「关闭」，与设置页既有对话框的取消位一致
            confirmButton = {
                // M3 按钮的涟漪在组件内部硬编码、调用侧置不了 null：本阶段只叠加按压反馈——
                // 按压源交给按钮形参，pressFeedback 接在 modifier 链末尾（贴按钮本体，对齐 textShape）
                val press = remember { MutableInteractionSource() }
                TextButton(
                    onClick = { picking = false },
                    interactionSource = press,
                    modifier = Modifier.pressFeedback(press, ButtonDefaults.textShape),
                ) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}
