package com.fangyi.classpp.ui.settings

import android.os.Build
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.hazeSources
import com.fangyi.classpp.ui.components.hazeTopBarStyle
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.motion.pressScale
import com.fangyi.classpp.ui.schedule.totalWeeks
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
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

// ---- 顶栏返回按钮液态玻璃的手感/观感参数（对齐待办顶栏/浮层顶栏玻璃胶囊的取值）----

/** 玻璃模糊半径：按钮小、底后是顶栏磨砂下的页面内容，轻模糊够读 */
private val SettingsGlassBlurRadius = 4.dp

/** 玻璃折射高度/深度（官方玻璃指示器的量级：10/14dp） */
private val SettingsGlassLensHeight = 10.dp
private val SettingsGlassLensDepth = 14.dp

/** 玻璃表面染色浓度：叠原 surface 底色——白玻璃，色相不变、透出玻璃感 */
private const val SettingsGlassTintAlpha = 0.70f

/**
 * 设置页顶栏返回按钮的液态玻璃采样层（根页/个性化/通知三页共用）：返回 null =
 * 玻璃关闭（「更高级的玻璃材质」开关或 API < 31），按钮维持原实心底、layerBackdrop
 * 不必挂载；非 null = 调用方把它经 layerBackdrop 标记到本页滚动内容节点上（与顶栏
 * Haze 磨砂的 hazeSource 同一节点，Backdrop 只喂返回按钮，两套采样互不干扰），再
 * 原样传给 [SettingsTopBar] 的 buttonBackdrop。采样内容 = 页面底色 + 滚动内容——
 * 内容从顶栏背后滚过，玻璃才有东西可折射（官方教程：底色必须画进采样层，否则漏
 * 透明像素）；返回按钮在顶栏层、不在采样节点内，玻璃不会照见自己。
 */
@Composable
internal fun rememberSettingsGlassBackdrop(): LayerBackdrop? {
    val enabled = LocalGlassStyle.current && Build.VERSION.SDK_INT >= 31
    // 底色经 State 中转：draw 块读不了组合上下文，靠状态读取驱动主题切换重画
    val bgColor = rememberUpdatedState(MaterialTheme.colorScheme.background)
    val backdrop = rememberLayerBackdrop {
        drawRect(bgColor.value)
        drawContent()
    }
    return if (enabled) backdrop else null
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    buttonBackdrop: Backdrop? = null,
) {
    // Haze 2：Style 在组合期构建（tint 由 hazeTopBarStyle 内部读主题）
    Box(
        modifier = modifier
            .fillMaxWidth()
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
    ) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.classppTextStyles.topBarTitle,
                )
            },
            // 状态栏 inset 由顶栏自己吸收：磨砂背景一直铺到屏幕顶（沉浸式）
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        // 不用 IconButton：其内部涟漪以按压点为圆心、20dp 半径封顶，偏心按压盖不满圆钮。
        // 圆钮改用按压反馈（ui.motion 的 pressFeedback，放大 + 提亮取代涟漪）：pressFeedback
        // 放在柔影 graphicsLayer 之前，缩放连投影一起放大；按压源与点击共用，indication 置 null
        val press = remember { MutableInteractionSource() }
        // 玻璃双门控（开关 + SDK）已在 rememberSettingsGlassBackdrop 处完成，此处只看非空
        val useGlass = buttonBackdrop != null && Build.VERSION.SDK_INT >= 31
        // 玻璃表面染色：叠原 surface 底色（组合侧读好——onDrawSurface 是 DrawScope，
        // 读不了 Composable 上下文）
        val surfaceTint = MaterialTheme.colorScheme.surface.copy(alpha = SettingsGlassTintAlpha)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                //原槽位内垂直居中
                .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                .padding(start = 24.dp, top = 8.dp)  // 返回按钮位置
                .size(47.dp)
                // 按压反馈接在柔影图层之前：缩放连投影一起放大。玻璃模式换官方 Q弹按压
                // 放大（pressScale：软弹簧果冻过冲 + 沿轮廓加色提亮，底部导航栏/浮层顶栏/
                // 待办顶栏玻璃按钮同款手感）；放大两态同取 1.10（不随全局默认）
                .then(
                    if (useGlass) {
                        Modifier.pressScale(press, CircleShape, pressedScale = 1.10f)
                    } else {
                        Modifier.pressFeedback(press, CircleShape, scale = 1.10f)
                    },
                )
                .graphicsLayer {    // 返回按钮投影
                    shape = CircleShape
                    clip = true
                    shadowElevation = 45.dp.toPx()
                    spotShadowColor = Color.Black.copy(alpha = 0.2f)
                }
                // 玻璃模式改由 drawBackdrop 出底：采样内容（页面底色 + 滚动内容）经模糊/
                // 折射裁进圆形，表面叠原 surface 染色——白玻璃；缩放会让采样跟着放大
                //（无 layerBlock 补偿），blur + 染色 + 1.10 小倍率下不可感知，与各处玻璃
                // 按钮同一取舍。lens 折射仅 API 33+，31-32 只留模糊+染色
                .then(
                    if (useGlass && buttonBackdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = buttonBackdrop,
                            shape = { CircleShape },
                            effects = {
                                blur(SettingsGlassBlurRadius.toPx())
                                if (Build.VERSION.SDK_INT >= 33) {
                                    lens(SettingsGlassLensHeight.toPx(), SettingsGlassLensDepth.toPx())
                                }
                            },
                            onDrawSurface = { drawRect(surfaceTint) },
                        )
                    } else {
                        Modifier.background(MaterialTheme.colorScheme.surface, CircleShape)
                    },
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
