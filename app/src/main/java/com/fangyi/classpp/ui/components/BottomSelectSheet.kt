package com.fangyi.classpp.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.theme.BottomSelectSheetShape
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.launch

/** 卡片距屏幕左右/底缘的边距：私有值，不随全局页边距走（卡片悬浮感与内容节奏解耦） */
private val CardEdgeMargin: Dp = 10.dp

/** 顶栏按钮行距卡片左右缘：与浮层顶栏按钮行同款 15dp（不随 [CardEdgeMargin] 联动） */
private val TopBarRowHorizontalPadding: Dp = 15.dp

/** 顶栏按钮行距卡片顶缘：与按钮行横向边距同一节奏 */
private val TopBarTopPadding: Dp = 15.dp

/** 内容距顶栏按钮底缘的距离 */
private val ContentTopSpacing: Dp = 15.dp

/** 内容距卡片底缘的距离 */
private val ContentBottomPadding: Dp = 20.dp

/**
 * 卡片投影的 elevation：只负责撑开柔影的模糊半径，浓淡由 BottomSelectSheet 的
 * shadowAlpha 参数调（同 [SheetPillButton] 的大柔影做法：高 elevation + 低透明度阴影色）。
 */
private val CardShadowElevation: Dp = 32.dp

/**
 * 卡片磨砂的模糊半径：
 */
private val CardBlurRadius: Dp = 20.dp

/**
 * 底部弹出选择卡：内容高度不固定的白卡片弹窗，各种选择器的宿主容器（选择日期、
 * 选择时间、选择周数、每节课时间、选择总节数……）。只负责骨架——顶栏 + 内容槽；
 * 具体选择器作为 [content] 塞进来。
 *
 * 结构：全屏遮罩（点按关闭）→ 底部居中的卡片（左右/底部边距统一为私有常量 12dp
 * [CardEdgeMargin]，底部再让开导航栏；[BottomSelectSheetShape] 四角 36dp 平滑圆角，
 * 与浮层顶部圆角一致）→
 * 顶栏（取消胶囊 + 居中标题 + 确认胶囊，按钮同 OverlaySheet 顶栏的 [SheetPillButton]，
 * 确认在右、取消在左，图标落各自外侧）→ 内容槽（距按钮底缘 [ContentTopSpacing]、
 * 距卡片底缘 [ContentBottomPadding]，两个间距都收在文件顶部的常量里，改一处全局生效）。
 *
 * 进出场由组件自己完成，与 OverlaySheet 同一套两段式关闭：
 * - 挂载即入场：从屏幕底部滑入（ui.motion 的 [Motion.Overlay]，与浮层同一条非线性
 *   曲线，退场同曲线、时长更短），遮罩同步压暗；
 * - [onDismiss]（遮罩点击 / 返回键 / 取消 / 确认）只表示**请求关闭**，调用方收到后清掉自己的状态使
 *   [visible] 变 false，组件随即向下滑出（[Motion.Overlay]，退场不带回弹）、遮罩同步变淡，
 *   **播完后才回调 [onDismissed]**，调用方在这一刻把弹窗移出组合；
 * - 不做拖拽关闭（卡片矮、内容少，点遮罩/按钮足够）。
 *
 * 遮罩随进度压暗不变；卡片本体是一块**磨砂玻璃**：[hazeState] 非空时在 bottomSheetColor
 * 底色之上叠加背景模糊（hazeEffect 画在底色之上、内容之下，同 [PopupMenuCard] /
 * WeekPickerCardShell 的白卡毛玻璃配方：CardBlurRadius 12dp + hazeTint 60%），背后的
 * hazeSource 内容透出毛玻璃质感；null = 纯 bottomSheetColor 平底卡。宿主把页面/浮层内容挂
 * hazeSource 后将同一状态传进来。
 *
 * [cancelLabel] / [confirmLabel] 默认取全局「取消/确认」文案；确认按钮的点击回调
 * [onConfirm] 只在非收场状态下放行（防双击重复提交），[confirmEnabled] = false 时点击与
 * 按压反馈一并失效、胶囊按项目置灰语义着色（输入类弹卡的实时校验用）。
 * 卡片投影是一层大柔影：模糊半径由 CardShadowElevation 撑开、浓度由 [shadowAlpha] 调
 * （0 = 无影），同 [SheetPillButton] 的投影做法。
 *
 * **键盘让位**：卡片底部除导航栏外还让开键盘（只补 ime 超出导航栏的差值）——键盘弹起时
 * 整卡抬到键盘上方不被遮住，弹起当帧就位、收起后 600ms 缓回（同 OverlaySheet 尾部余量
 * 的机制）；出场滑出量程同步计入让位量。滚轮卡族键盘恒收起，渲染与动画行为不变。
 */
@Composable
fun BottomSelectSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onDismissed: () -> Unit = {},
    cancelLabel: String = stringResource(R.string.settings_cancel),
    confirmLabel: String = stringResource(R.string.edit_confirm),
    onConfirm: () -> Unit = {},
    // 确认胶囊可用态：false 时点击与按压反馈一并失效，胶囊按项目置灰语义着色
    // （白色底 + negative 内容色，同 EditActionRow 禁用钮先例）
    confirmEnabled: Boolean = true,
    // 卡片投影浓度：只调透明度（0 = 无影，1 = 纯黑最浓），模糊半径由 CardShadowElevation 撑
    shadowAlpha: Float = 0.3f,
    // 卡片磨砂的采样源：非空时卡片在 bottomSheetColor 底色之上叠背景模糊（见 KDoc），
    // null = 纯 bottomSheetColor 平底卡。宿主把页面/浮层内容挂 hazeSource 后将同一状态传进来
    hazeState: HazeState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 点空白处收起（无涟漪）
    val scrimInteraction = remember { MutableInteractionSource() }
    // 吃掉落在卡片上的点击，避免穿透到遮罩把弹窗关掉（卡内控件的消费优先）
    val cardInteraction = remember { MutableInteractionSource() }

    // ——— 进出场共用一条进度：0 = 完全显示，1 = 完全滑出屏幕下方 ———
    // 滑动位移与遮罩透明度都从它派生
    val hiddenFraction = remember { Animatable(1f) }
    // 滑动全程 = 卡片自身高度 + 底部让位（导航栏 inset + 底部边距）；layout 先于 draw
    // 测出，首帧不会闪现在最终位置
    var travelPx by remember { mutableStateOf(0f) }
    // 收场已启动：出场期间面板仍在组合里，确认/取消/遮罩的二次点击一律不放行
    var exiting by remember { mutableStateOf(false) }
    var closeRequested by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val scrimColor = MaterialTheme.colorScheme.scrim
    // hazeEffect 的 block 在绘制期执行、非 composable 上下文：tint 取值提到 modifier 之前
    val cardHazeTint = MaterialTheme.classppColors.hazeTint
    // 键盘让位：键盘弹起时整卡抬到键盘上方（只补 ime 超出导航栏的差值，不与
    // navigationBarsPadding 双计；弹起当帧就位、收起后 600ms 缓回，复用 OverlaySheet
    // 尾部余量的同一机制）。滚轮卡族键盘恒收起 → 让位量恒 0，渲染与动画不变。
    // 让位量必须同时计入 bottomClearancePx：出场滑出量程 = 卡高 + 底部让位，
    // 不含键盘高度的话带键盘收场会滑不到底、卡在半途
    val navBottomPx = WindowInsets.navigationBars.getBottom(density)
    val imeLiftPx = rememberTailReservePx(
        target = (WindowInsets.ime.getBottom(density) - navBottomPx).coerceAtLeast(0),
        imeVisible = rememberImeVisible(),
    )
    val bottomClearancePx =
        navBottomPx + with(density) { CardEdgeMargin.toPx() } + imeLiftPx

    // 收场动画本体：从当前进度滑到全隐，播完才回调 onDismissed
    suspend fun runExit() {
        hiddenFraction.animateTo(1f, tween(Motion.SheetExitMillis, easing = Motion.Overlay))
        onDismissed()
    }

    // 开始收场：幂等。可见性驱动的关闭（调用方清状态）最终都汇到这里
    fun beginExit() {
        if (exiting) return
        exiting = true
        closeRequested = true
        scope.launch { runExit() }
    }

    // 打开滑入（带超出回弹）/ 关闭滑出（不带回弹）；关闭入口全部汇到 beginExit，
    // 调用方只负责清状态
    LaunchedEffect(visible) {
        if (visible) {
            exiting = false
            closeRequested = false
            hiddenFraction.animateTo(
                0f,
                tween(Motion.SheetEnterMillis, easing = Motion.Overlay),
            )
        } else {
            beginExit()
        }
    }

    // 返回键同遮罩点击（只请求关闭）：只在可见时注册生效，组合顺序靠后的覆盖层先收到
    // 返回键（同 FadeOverlayDialog），收场期间自动让路
    BackHandler(enabled = visible, onBack = onDismiss)

    Box(
        modifier = modifier
            .fillMaxSize()
            // 遮罩随进度压暗：完全显示时用 scrim 色自带的透明度（强度已按深浅模式烘进
            // 色值，此处不再叠加），滑出时跟手变淡到全透明
            .drawBehind {
                val alpha = 1f - hiddenFraction.value
                if (alpha > 0f) drawRect(color = scrimColor, alpha = alpha)
            }
            .clickable(
                interactionSource = scrimInteraction,
                indication = null,
                onClick = { if (!closeRequested) onDismiss() },
            ),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                // 键盘让位（见 imeLiftPx 声明处）：键盘在场时把卡片整体抬到键盘上方，
                // 键盘收起后缓回——输入类弹卡因此不会被键盘遮住
                .padding(bottom = with(density) { imeLiftPx.toDp() })
                .padding(
                    start = CardEdgeMargin,
                    end = CardEdgeMargin,
                    bottom = CardEdgeMargin,
                )
                .onSizeChanged { travelPx = it.height + bottomClearancePx }
                // 滑动位移只打在卡片这层：遮罩留在原地；hiddenFraction 冲过 0
                // （回弹段）即卡片越过终位再落回
                .graphicsLayer { translationY = hiddenFraction.value * travelPx }
                .clickable(
                    interactionSource = cardInteraction,
                    indication = null,
                    onClick = {},
                ),
        ) {
            Surface(
                modifier = Modifier.graphicsLayer {
                    shape = BottomSelectSheetShape
                    clip = true
                    // 大柔影（同 SheetPillButton）：elevation 撑开模糊半径、阴影色 alpha 压浓淡；
                    // 只调透明度改 shadowAlpha（0 = 无影），想更散改 CardShadowElevation
                    shadowElevation = CardShadowElevation.toPx()
                    ambientShadowColor = Color.Black.copy(alpha = shadowAlpha.coerceIn(0f, 1f))
                    spotShadowColor = Color.Black.copy(alpha = shadowAlpha.coerceIn(0f, 1f))
                },
                shape = BottomSelectSheetShape,
                color = MaterialTheme.classppColors.bottomSheetColor,
            ) {
                Column(
                    modifier = Modifier
                        // 卡片磨砂（hazeState 非空时）：hazeEffect 画在 Surface 底色之上、
                        // 内容之下（同 PopupMenuCard 白卡毛玻璃配方），玻璃感由 tint 统一
                        .then(
                            if (hazeState != null) {
                                Modifier.hazeEffect(hazeState) {
                                    blurRadius = CardBlurRadius
                                    tints = listOf(HazeTint(cardHazeTint.copy(alpha = 0.6f)))
                                    noiseFactor = 0f
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    // 顶栏：取消胶囊 + 居中标题 + 确认胶囊（同 OverlaySheet 顶栏的按钮行，
                    // 标题以整行宽度居中、叠在按钮行之上）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = TopBarRowHorizontalPadding,
                                end = TopBarRowHorizontalPadding,
                                top = TopBarTopPadding,
                            ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            SheetPillButton(
                                label = cancelLabel,
                                icon = R.drawable.ic_dismiss_circle,
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.height(TopBarPillHeight),
                                onClick = { if (!closeRequested) onDismiss() },
                            )
                            SheetPillButton(
                                label = confirmLabel,
                                icon = R.drawable.ic_checkmark_circle,
                                // 禁用态用项目置灰语义（白底 + negative 内容色，同 EditActionRow）
                                containerColor = if (confirmEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                contentColor = if (confirmEnabled) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.classppColors.negative
                                },
                                modifier = Modifier.height(TopBarPillHeight),
                                iconAtEnd = true,
                                enabled = confirmEnabled,
                                onClick = { if (!closeRequested && confirmEnabled) onConfirm() },
                            )
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.classppTextStyles.topBarTitle.copy(fontSize = 18.sp),
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    // 内容槽：距顶栏按钮底缘 [ContentTopSpacing]、距卡片底缘
                    // [ContentBottomPadding]，卡片高度随内容自适应
                    Column(
                        modifier = Modifier.padding(
                            top = ContentTopSpacing,
                            bottom = ContentBottomPadding,
                        ),
                    ) {
                        content()
                    }
                }
            }
        }
    }
}
