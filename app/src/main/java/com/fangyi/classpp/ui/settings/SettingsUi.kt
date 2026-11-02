package com.fangyi.classpp.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.hazeSources
import com.fangyi.classpp.ui.components.hazeTopBarStyle
import com.fangyi.classpp.ui.motion.FreezeFrame
import com.fangyi.classpp.ui.motion.OffsetOverscrollEffect
import com.fangyi.classpp.ui.motion.transitionFreeze
import com.fangyi.classpp.ui.schedule.totalWeeks
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.glassPressFeedback
import com.fangyi.classpp.ui.theme.glassSurface
import com.fangyi.classpp.ui.theme.isGlassStyleEnabled
import com.fangyi.classpp.ui.theme.rememberGlassBackdrop
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur

/**
 * 设置页组件：顶栏 [SettingsTopBar] 与学期设置卡 [TermDatesCard]。
 * 通用卡片已收敛到 components 的新版 SettingsCard（导航/开关/选择/自定义四种行型），
 * 旧版 SettingsCard / SettingRow 已随节次卡迁移删除。
 *
 * 配色约定：页面底 `colorScheme.background`、卡底 `Surface`、强调 `colorScheme.primary`、
 * 次级文字 `classppColors.secondaryText`。
 */

// ---- 顶栏返回按钮液态玻璃：参数与接线收在 ui.theme 的 LiquidGlass（默认 Pill 档）----

/**
 * 设置页顶栏返回按钮的液态玻璃采样层（根页/个性化/通知三页共用）：返回 null =
 * 玻璃关闭（「更高级的玻璃材质」开关或 API < 31），按钮维持原实心底、layerBackdrop
 * 不必挂载；非 null = 调用方把它经 layerBackdrop 标记到本页滚动内容节点上（与顶栏
 * Haze 磨砂的 hazeSource 同一节点，Backdrop 只喂返回按钮，两套采样互不干扰），再
 * 原样传给 [SettingsTopBar] 的 buttonBackdrop。采样内容 = 页面底色 + 滚动内容——
 * 内容从顶栏背后滚过，玻璃才有东西可折射；返回按钮在顶栏层、不在采样节点内，
 * 玻璃不会照见自己。
 */
@Composable
internal fun rememberSettingsGlassBackdrop(): LayerBackdrop? {
    val backdrop = rememberGlassBackdrop(MaterialTheme.colorScheme.background)
    return if (isGlassStyleEnabled()) backdrop else null
}

/**
 * 标题渐显窗口：内容有效位移（滚动 − 过滚动平移）到 100dp 开始出现，150dp 完全显现；
 * 回滚时对称消失。只用于标题淡入淡出，不参与布局（顶栏高度与内容内缩不变）。
 */
private val TitleFadeStart = 100.dp
private val TitleFadeEnd = 150.dp

/**
 * 顶栏标题随内容位移的透明度（纯函数，供单测）：[scrollPx] 在 [startPx] 之前全隐，
 * 在 [endPx] 及之后全显，两者之间用 Hermite smoothstep 过渡。[scrollPx] 由宿主编成
 * 「滚动值 − 过滚动视觉位移」：内容不足一屏时滚动值恒 0，但橡皮筋仍会把内容向上
 * 拖进顶栏下方，负的过滚动平移等效于多滚了这么多。
 *
 * Hermite smoothstep（边界导数为 0），与日程页顶栏淡入同一形态，避免起止处出现
 * 透明度折角；[endPx] ≤ [startPx] 时退化为阶跃。
 */
internal fun settingsTitleAlpha(
    scrollPx: Float,
    startPx: Float,
    endPx: Float,
): Float {
    if (endPx <= startPx) return if (scrollPx < startPx) 0f else 1f
    val t = ((scrollPx - startPx) / (endPx - startPx)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * [SettingsTopBar] 标题的渐显计算：读取宿主的 [scrollState]（以及内容不足一屏时
 * 承担橡皮筋的 [overscrollEffect]），返回只在绘制期求值的 lambda（`graphicsLayer`
 * 里调用），滚动/过滚动过程零重组。
 *
 * 有效位移 = `scrollState.value − visualOffset`：向上过滚动（负平移）计入，向下
 * 过滚动（正平移）抵消；到 100dp 开始出现，150dp 完全显现，回滚对称消失。内容一屏
 * 装得下且未过滚动时标题保持隐藏。
 */
@Composable
internal fun rememberSettingsTitleAlpha(
    scrollState: ScrollState,
    overscrollEffect: OffsetOverscrollEffect? = null,
): () -> Float {
    val density = LocalDensity.current
    val startPx = with(density) { TitleFadeStart.toPx() }
    val endPx = with(density) { TitleFadeEnd.toPx() }
    return remember(scrollState, overscrollEffect, startPx, endPx) {
        {
            // viewportSize 与效果渲染节点阻尼用的容器尺寸同源（滚动容器实测高度）
            val overscrollPx = overscrollEffect
                ?.visualOffset(scrollState.viewportSize.toFloat())
                ?: 0f
            settingsTitleAlpha(
                scrollPx = scrollState.value - overscrollPx,
                startPx = startPx,
                endPx = endPx,
            )
        }
    }
}

/**
 * 设置页顶栏：左侧圆形返回按钮 + 居中加粗标题。
 *
 * 返回按钮叠放在 TopAppBar 之上、不进 navigationIcon 槽位：
 * [hazeState] 非空时对顶栏背后的滚动内容做背景模糊——顶部最强、向下渐弱（渐变模糊），
 * 与 ScheduleHeader 同一套 Haze 规格，兜底色画在模糊层之下；
 * null（如 @Preview）时退化为不透明背景。
 *
 * [buttonBackdrop] 非空（宿主经 [rememberSettingsGlassBackdrop] 门控下发）时返回按钮
 * 改液态玻璃底 + Q弹按压放大（pressScale：采样内容经模糊/折射裁进圆形，表面叠原
 * surface 染色——白玻璃；软弹簧果冻过冲按压）；null（默认，如 @Preview / 玻璃开关
 * 关闭）维持原实心底 + pressFeedback。
 *
 * 冻结（[freezeKey] 非空时启用）：只把**背景层**（兜底色 + Haze）包进
 * [transitionFreeze]，标题与玻璃返回按钮不入冻结层——玻璃采样必须保持实时。设置页
 * 覆盖层转场时整帧重放就位帧，避免 Haze 在移动页面上逐帧重采样；[recordWhen] 由宿主
 * 用「覆盖层活跃或启动预热帧」门控，隐藏态不录制（Haze 已卸载，录到的是无模糊平底色）。
 *
 * 标题渐显（可选）：[titleAlpha] 默认恒 1（预览、设置根页与独立调用）；个性化、通知、
 * 账号与同步三个子页经 [rememberSettingsTitleAlpha] 传入随滚动渐显的 lambda——内容
 * 进入顶栏下方后按 100dp→150dp 有效位移在绘制期淡入、回滚对称淡出（过滚动计入有效
 * 位移）。透明度在 `graphicsLayer` 里求值，滚动过程零重组；标题始终占位，顶栏高度与
 * 内容内缩不随之变化。视觉上隐藏时仍保留语义，TalkBack 可播报页面身份。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    titleAlpha: () -> Float = { 1f },
    hazeState: HazeState? = null,
    buttonBackdrop: Backdrop? = null,
    freezeKey: (() -> Any?)? = null,
    recordWhen: () -> Boolean = { true },
    isTransforming: () -> Boolean = { false },
) {
    // 冻结资源：顶栏常驻组合（设置页四屏都是覆盖层内容），实例随之常驻
    val freezeLayer = rememberGraphicsLayer()
    val freezeFrame = remember { FreezeFrame() }
    // 外层只承载尺寸与调用方 modifier（align/高度回报）：背景拆成 matchParentSize 的
    // 独立兄弟节点，冻结层才能只包住背景+模糊，不包标题与玻璃按钮
    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(
                    if (freezeKey != null) {
                        Modifier.transitionFreeze(
                            layer = freezeLayer,
                            frame = freezeFrame,
                            key = freezeKey,
                            isTransforming = isTransforming,
                            recordWhen = recordWhen,
                        )
                    } else {
                        Modifier
                    },
                )
                // 兜底色：与页面同色，画在模糊层之下（背后无内容时逐帧一致）
                .background(MaterialTheme.colorScheme.background)
                .then(
                    if (hazeState != null) {
                        // 转场性能规格：25dp + 下采样档（Balanced = Fixed(0.5)）——整页覆盖层
                        // 转场逐帧重合成毛玻璃，模糊预算必须收紧（32dp 全分辨率 progressive
                        // 的 GPU 成本压不住帧预算）
                        Modifier.hazeBlur(
                            input = hazeSources(hazeState),
                            style = hazeTopBarStyle(),
                            performanceMode = HazePerformanceMode.Balanced,
                        )
                    } else {
                        Modifier
                    },
                ),
        )
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.classppTextStyles.topBarTitle,
                    // 绘制期读滚动状态：滚动不触发重组，只让标题图层按透明度重绘
                    modifier = Modifier.graphicsLayer { alpha = titleAlpha() },
                )
            },
            // 状态栏 inset 由顶栏自己吸收：磨砂背景一直铺到屏幕顶（沉浸式）
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        // 不用 IconButton：其内部涟漪以按压点为圆心、20dp 半径封顶，偏心按压盖不满圆钮。
        // 圆钮改用按压反馈（放大 + 提亮取代涟漪）：反馈放在柔影 graphicsLayer 之前，
        // 缩放连投影一起放大；按压源与点击共用，indication 置 null
        val press = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                //原槽位内垂直居中
                .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                .padding(start = 24.dp, top = 8.dp)  // 返回按钮位置
                .size(47.dp)
                // 按压反馈接在柔影图层之前：缩放连投影一起放大。玻璃模式换官方 Q弹
                // 按压放大（软弹簧果冻过冲 + 沿轮廓加色提亮）；非玻璃维持 pressFeedback
                //（封装见 ui.theme LiquidGlass）。放大两态同取 1.10（不随全局默认）
                .glassPressFeedback(press, CircleShape, buttonBackdrop)
                .graphicsLayer {    // 返回按钮投影
                    shape = CircleShape
                    clip = true
                    shadowElevation = 45.dp.toPx()
                    spotShadowColor = Color.Black.copy(alpha = 0.2f)
                }
                // 玻璃模式改由 drawBackdrop 出底（采样内容经模糊/折射裁进圆形，表面叠
                // surface 染色——白玻璃）；非玻璃实心底
                .glassSurface(
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.surface,
                    backdrop = buttonBackdrop,
                )
                .clickable(interactionSource = press, indication = null, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_left),
                contentDescription = stringResource(R.string.cd_settings_close),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(23.dp),
            )
        }
    }
}

/** 设置页身份：冻结键的一部分，保证四屏顶栏的帧绝不互相重放 */
internal enum class SettingsPage { Root, Personalization, Notification, XmuSync }

/**
 * 设置页冻结帧的会话代际：进多任务/回桌面后窗口不可见，系统会丢弃 RenderNode 的
 * 显示列表（同 ui.motion 的 transitionFreeze 注释），而设置页隐藏期不录制，旧帧会
 * 变成空帧。宿主在离开 RESUMED 时递增本代际，页内把它并入冻结键：代际一变即拒绝
 * 重放（回退实时绘制，最坏等于不做冻结），打开落定后重录新帧。
 */
internal val LocalSettingsFreezeEpoch = compositionLocalOf { 0 }

/**
 * 设置顶栏冻结帧的内容键：主题/顶栏高度/滚动位置/页面专有视觉输入任一变化即视为
 * 过期，转场期回退实时绘制（最坏等于不做冻结，绝不重放旧画面）。
 *
 * 只在绘制期求值（`transitionFreeze` 的 key/录制元数据），不参与重组。页面专有输入
 * 必须是不可变的值快照（滚动、开关值、账号状态等），不要把可变对象本身塞进来。
 */
internal data class SettingsTopBarFreezeKey(
    val page: SettingsPage,
    val darkTheme: Boolean,
    val topBarHeight: Dp,
    val scroll: Int,
    val epoch: Int = 0,
    val inputs: Any? = null,
)

/** 日期 → `2026-9-7`（无前导零），走 [R.string.date_hyphen_format] */
@Composable
internal fun IsoDate.toSettingsDateText(): String {
    val parts = toString().split('-')
    val year = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val month = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val day = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return stringResource(R.string.date_hyphen_format, year, month, day)
}

/**
 * 学期起止 + 总周数三行卡，顶部带「学期」分组标签。设置页与切换课表浮层的新建表单共用：
 * [onPickStart] / [onPickEnd] / [onPickWeeks] 均为请求回调，由调用方在浮层**之后**挂各自的
 * 选择弹窗（页内覆盖层画不进卡片里——浮层内容会被 Surface 裁剪，同日期轮的挂载约定）；
 * [onSetWeeks] 收到合法周数，调用方自行联动结束日（endForTotalWeeks）。
 *
 * 卡体是 components 的新版 [com.fangyi.classpp.ui.components.SettingsCard]
 * （三枚导航行：行内建 16/14 内距与 60dp 行高、涟漪通栏满行由卡片圆角裁剪），
 * 此前「contentPadding 下沉到各行」的通栏手法不再需要。
 */
@Composable
internal fun TermDatesCard(
    start: IsoDate,
    end: IsoDate,
    onPickStart: () -> Unit,
    onPickEnd: () -> Unit,
    onPickWeeks: () -> Unit,
    onSetWeeks: (weeks: Int) -> Unit,
) {
    // 与 Schedule.totalWeeks 同式（收拢在 totalWeeks 助手）：按所在周的周一相减
    val currentWeeks = totalWeeks(start, end)

    CardSection(title = stringResource(R.string.section_term)) {
        SettingsCard(
            items = listOf(
                SettingsCardItem.Nav(
                    label = stringResource(R.string.term_start),
                    value = start.toSettingsDateText(),
                    onClick = onPickStart,
                ),
                SettingsCardItem.Nav(
                    label = stringResource(R.string.term_end),
                    value = end.toSettingsDateText(),
                    onClick = onPickEnd,
                ),
                SettingsCardItem.Nav(
                    label = stringResource(R.string.term_weeks),
                    value = stringResource(R.string.term_weeks_value, currentWeeks),
                    onClick = onPickWeeks,
                ),
            ),
        )
    }
}

/** 顶栏单独预览：底色取自 colorScheme.background（= 页面底 #F2F4F6），与实际渲染一致 */
@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, name = "设置页顶栏")
@Composable
private fun SettingsTopBarPreview() {
    ClassppTheme {
        SettingsTopBar(
            title = stringResource(R.string.settings_title),
            onBack = {},
        )
    }
}

/** 学期设置卡预览：新 SettingsCard 三行导航（值 + 蓝色箭头），对照设计稿 */
@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, name = "学期设置卡")
@Composable
private fun TermDatesCardPreview() {
    ClassppTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            TermDatesCard(
                start = IsoDate.of(2026, 9, 7),
                end = IsoDate.of(2027, 1, 17),
                onPickStart = {},
                onPickEnd = {},
                onPickWeeks = {},
                onSetWeeks = {},
            )
        }
    }
}
