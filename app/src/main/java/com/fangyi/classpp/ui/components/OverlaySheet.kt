package com.fangyi.classpp.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.SheetShape
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch

/**
 * 距屏幕顶端的距离——**后期调整浮层位置只改这一个变量**。
 * 键盘弹起不会改变浮层的位置与高度（见 [SheetImeBehavior.ContentScroll]）。
 */
val SheetTopInset: Dp = 56.dp

val SheetSectionSpacingBetween: Dp = 12.dp // 卡片与卡片间的间距
val SheetSectionSpacingBottom:Dp = 14.dp  // 卡片组底与下一个卡片组间距（如果需要）

/**
 * 内容末尾的额外滚动余量（对应设置页 `BottomScrollSlack = 120.dp`）：
 * 拉大可滑动范围，末尾的卡片能滑得更高、离底边更远。想调滑动上限改这一个值。
 */
val SheetBottomSlack: Dp = 120.dp

/** 入场时长：从屏幕底部滑入，减速曲线（先快后慢） */
private const val SheetEnterMillis = 320

/** 出场时长：向下滑出，加速曲线（先慢后快） */
private const val SheetExitMillis = 280

/** 拖拽关闭的位移阈值：下拉超过浮层自身高度的这么多比例即关闭 */
private const val DragDismissFraction = 0.25f

/** 拖拽关闭的速度阈值：松手时下滑速度超过它（快速一甩）也直接关闭 */
private val DragDismissVelocity: Dp = 800.dp

/** 浮层完全显示时的遮罩强度（与全 app 浮层遮罩同一规格） */
private const val ScrimAlpha = 0.32f

/** 顶栏总高（拖拽条 + 按钮行），滚动内容顶部为它留位。 */
private val TopBarHeight = 78.dp

/**
 * 顶栏按钮行（把手下的关闭/确认胶囊）的横向边距：刻意大于内容卡片的
 * [PageHorizontalSpacing]（15dp），胶囊贴边更收、与卡片左缘不齐平（设计如此）。
 */
private val TopBarRowHorizontalPadding: Dp = 15.dp

/**
 * 键盘与浮层的关系（未来不同浮层可选不同行为）：
 * - [ContentScroll]：浮层本体不动，**滚动内容末尾**按键盘高度追加 Spacer 让位——
 *   本次添加/编辑课程面板用这个（距顶固定、不随键盘改变位置和高度）；
 * - [IgnoreIme]：完全不理会键盘，键盘盖住哪里算哪里。
 */
enum class SheetImeBehavior { ContentScroll, IgnoreIme }

/** 顶栏右侧动作（取消 / 删除…），样式由调用方给出以复用同一颗胶囊按钮。 */
data class SheetTopAction(
    val label: String,
    @DrawableRes val icon: Int,
    val containerColor: Color,
    val contentColor: Color,
    val onClick: () -> Unit,
)

/**
 * 浮层卡片容器：贯穿整个 app 的浮层设计元素。
 *
 * 结构：全屏遮罩（点按关闭）→ 距顶 [topInset] 定位 → 上圆角 [SheetShape] 卡片撑到屏幕底。
 * 顶栏（拖拽条 + 居中标题 + 确认/动作双胶囊，[confirmAtEnd] 可对调左右）叠在滚动内容之上，
 * 用 haze 对其下的滚动内容做**渐变模糊**——照搬课表设置页顶栏的规格，
 * 并由 Surface 的 shape clip 收敛在圆角内，不溢出卡片。
 *
 * 进出场与拖拽关闭由组件自己完成：
 * - 挂载即入场：从屏幕底部滑入（先快后慢），遮罩同步压暗；
 * - 关闭是「两段式」——[onDismiss]（遮罩点击 / 返回 / 确认 / 顶栏动作 / 下拉松手超过阈值）
 *   只表示**请求关闭**，调用方收到后清掉自己的状态使 [visible] 变 false，组件随即向下滑出
 *   （先慢后快）、遮罩同步变淡，**播完后才回调 [onDismissed]**，调用方在这一刻把浮层移出组合，
 *   收场期间内容保持原样不闪空；
 * - 顶栏整条可垂直拖拽、浮层跟手下移，遮罩跟着变淡；松手超过位移/速度阈值走关闭，否则弹回。
 *
 * [confirmIcon] 配置左胶囊图标（默认对勾）；[confirmLabel] 传 null 时**不渲染**左侧确认
 * 胶囊（纯展示浮层用，如课程详情），[onConfirm] 随之闲置；[bottomContent] 可选钉底槽位——
 * 画在导航栏上方、带自底向上渐变兜底，滚动内容从其下淡出（如切换课表浮层底部常驻的导出/导入按钮）。
 *
 * haze 的采样源挂在浮层内部的滚动列上：浮层被遮罩盖住后背后的课表网格对顶栏不可见，
 * 只需模糊浮层自身内容，因此容器内自建 hazeState，调用方无需传任何模糊状态。
 */
@Composable
fun OverlaySheet(
    title: String,
    confirmLabel: String? = null,
    onConfirm: () -> Unit = {},
    rightAction: SheetTopAction,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onDismissed: () -> Unit = {},
    confirmIcon: Int = com.fangyi.classpp.R.drawable.ic_checkmark_circle,
    // true = 确认胶囊到右侧、动作胶囊（取消/删除）到左侧，两颗胶囊的图标排布随位置镜像
    // （左颗图标在前、右颗文字在前，图标始终落在胶囊外侧）
    confirmAtEnd: Boolean = false,
    topInset: Dp = SheetTopInset,
    imeBehavior: SheetImeBehavior = SheetImeBehavior.ContentScroll,
    bottomContent: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val hazeState = rememberHazeState()
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    // 内容让位键盘：取 IME 与导航栏的较大者（键盘弹起时 IME 已包含导航区），
    // 挂在滚动内容末尾而非容器上——容器的位置与高度因此不随键盘变化。
    val bottomInsetDp = when (imeBehavior) {
        SheetImeBehavior.ContentScroll -> {
            val imeBottom = WindowInsets.ime.getBottom(density)
            val navBottom = WindowInsets.navigationBars.getBottom(density)
            with(density) { maxOf(imeBottom, navBottom).toDp() }
        }
        SheetImeBehavior.IgnoreIme -> {
            val navBottom = WindowInsets.navigationBars.getBottom(density)
            with(density) { navBottom.toDp() }
        }
    }

    // 点空白处收起（无涟漪）
    val scrimInteraction = remember { MutableInteractionSource() }
    // 吃掉落在卡片上的点击，避免穿透到遮罩把浮层关掉（卡内控件的消费优先）
    val cardInteraction = remember { MutableInteractionSource() }

    // ——— 进出场与拖拽共用一条进度：0 = 完全显示，1 = 完全滑出屏幕下方 ———
    // 拖拽直接改写它（跟手），浮层位移与遮罩透明度都从它派生，各段动画之间天然连续
    val hiddenFraction = remember { Animatable(1f) }
    // 滑动全程 = 浮层自身高度；layout 先于 draw 测出，首帧不会闪现在最终位置
    var travelPx by remember { mutableStateOf(0) }
    // 已决定关闭（收场动画可能被中途拖拽打断，打断后松手要能续播而不是卡死在半路）
    var exiting by remember { mutableStateOf(false) }
    // 关闭已受理：出场期间面板仍在组合里，确认/顶栏动作/遮罩的二次点击一律不放行
    //（防双击「确认」把新建课程存两次）；表单校验失败不会置位，修正后还能再点确认
    var closeRequested by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val scrimColor = MaterialTheme.colorScheme.scrim
    val dismissVelocityPx = with(density) { DragDismissVelocity.toPx() }

    // 收场动画本体：从当前进度继续滑到全隐（拖拽中断后重入，也是从当前位置接着走）
    suspend fun runExit() {
        hiddenFraction.animateTo(1f, tween(SheetExitMillis, easing = FastOutLinearInEasing))
        onDismissed()
    }

    // 开始收场：幂等。可见性驱动的关闭（调用方清状态）与拖拽关闭最终都汇到这里
    fun beginExit() {
        if (exiting) return
        exiting = true
        closeRequested = true
        keyboard?.hide()
        scope.launch { runExit() }
    }

    // 打开滑入 / 关闭滑出；关闭入口全部汇到 beginExit，调用方只负责清状态
    LaunchedEffect(visible) {
        if (visible) {
            exiting = false
            closeRequested = false
            hiddenFraction.animateTo(0f, tween(SheetEnterMillis, easing = LinearOutSlowInEasing))
        } else {
            beginExit()
        }
    }

    // 顶栏整条可拖拽：跟手改写进度；松手超过位移/速度阈值 → 走关闭（清状态 → 统一出场），
    // 否则弹回。拖拽与顶栏按钮点击互不干扰（点击不产生滑动位移）
    val dragState = rememberDraggableState { delta ->
        scope.launch {
            hiddenFraction.snapTo(
                (hiddenFraction.value + delta / travelPx.coerceAtLeast(1)).coerceIn(0f, 1f),
            )
        }
    }
    val topBarDragModifier = Modifier.draggable(
        orientation = Orientation.Vertical,
        state = dragState,
        onDragStopped = { velocity ->
            when {
                // 收场途中被拖拽打断：从当前位置续播收场
                exiting -> scope.launch { runExit() }
                hiddenFraction.value >= DragDismissFraction || velocity >= dismissVelocityPx -> {
                    if (!closeRequested) {
                        closeRequested = true
                        onDismiss()
                    }
                }
                else -> scope.launch {
                    hiddenFraction.animateTo(
                        0f,
                        spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    )
                }
            }
        },
    )

    // 出场期间面板还在组合里：顶栏动作与确认按钮都过一遍闸门，防二次触发
    val guardedRightAction = rightAction.copy(
        onClick = { if (!closeRequested) rightAction.onClick() },
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            // 遮罩随进度压暗：完全显示时 0.32，拖拽下拉/滑出时跟手变淡到全透明
            .drawBehind {
                val alpha = ScrimAlpha * (1f - hiddenFraction.value)
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
                .fillMaxSize()
                .padding(top = topInset)
                .onSizeChanged { travelPx = it.height }
                // 滑动位移只打在浮层这层：遮罩留在原地，卡片连阴影带模糊一起动
                .graphicsLayer { translationY = hiddenFraction.value * travelPx },
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = cardInteraction,
                        indication = null,
                        onClick = {},
                    ),
                shape = SheetShape,
                color = MaterialTheme.colorScheme.background,
                shadowElevation = 8.dp,
            ) {
                Box {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            // 顶栏模糊的采样源：滚动内容从顶栏底下滚过时被渐变模糊
                            .hazeSource(hazeState)
                            .verticalScroll(scrollState)
                            // 点空白（卡片间隙/留白/尾部余量）取消聚焦收起键盘：
                            // SheetTextField / SheetTextArea 一族的通用宿主行为
                            .clearFocusOnTap()
                            // 横向边距 = 设置页同款页边距（PageHorizontalSpacing），卡片宽度与设置页一致
                            .padding(horizontal = PageHorizontalSpacing),
                    ) {
                        // 为叠在上方的顶栏留位；滚动后内容进入顶栏区域并被模糊
                        Spacer(Modifier.height(TopBarHeight))
                        content()
                        // 尾部余量 = 键盘/导航让位 + 可调滚动余量，拉大可滑动范围
                        Spacer(Modifier.height(bottomInsetDp + SheetBottomSlack))
                    }
                    OverlaySheetTopBar(
                        title = title,
                        confirmLabel = confirmLabel,
                        confirmIcon = confirmIcon,
                        onConfirm = { if (!closeRequested) onConfirm() },
                        rightAction = guardedRightAction,
                        confirmAtEnd = confirmAtEnd,
                        hazeState = hazeState,
                        dragModifier = topBarDragModifier,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    // 钉底内容槽（可选）：渐变兜底让滚动内容从其下淡出，导航栏留白由 inset 吃掉
                    bottomContent?.let { slot ->
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(
                                    androidx.compose.ui.graphics.Brush.verticalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                            MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                                        ),
                                    ),
                                )
                                .navigationBarsPadding()
                                .padding(top = 12.dp, bottom = 24.dp),
                        ) {
                            slot()
                        }
                    }
                }
            }
        }
    }
}

/**
 * 顶栏：拖拽条 + 确认胶囊 + 居中标题 + 动作胶囊（默认左确认右动作，[confirmAtEnd] 时对调）。
 * hazeEffect 打在这层上（渐变 1→0、白 30% tint，与设置页同规格），
 * 按钮与标题画在模糊层之上保持清晰；整层被 Surface 裁进浮层圆角。
 * 整条顶栏是下拉关闭的手势区（[dragModifier] 挂垂直拖拽），按钮点击不受影响。
 */
@Composable
private fun OverlaySheetTopBar(
    title: String,
    confirmLabel: String?,
    confirmIcon: Int,
    onConfirm: () -> Unit,
    rightAction: SheetTopAction,
    confirmAtEnd: Boolean = false,
    hazeState: HazeState,
    dragModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    // hazeEffect 的 block 在绘制期执行、非 composable 上下文：tint 取值提到 modifier 之前
    val hazeTint = MaterialTheme.classppColors.hazeTint
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(TopBarHeight)
            // 不透明兜底（同设置页顶栏）：haze 的渐进模糊是"均匀模糊层 × 渐变透明度"的混合，
            // 兜底若带透明度，锐利内容会从透明处透出、与模糊层叠成重影（蓝描边/蓝格子最明显）；
            // 不透明兜底让混合只发生在"模糊 vs 平色"之间，锐利内容被完全挡住
            .background(MaterialTheme.colorScheme.background)
            .hazeEffect(hazeState) {
                blurRadius = 32.dp
                progressive = HazeProgressive.verticalGradient(
                    startIntensity = 1f,
                    endIntensity = 0f,
                )
                tints = listOf(HazeTint(hazeTint.copy(alpha = 0.30f)))
                noiseFactor = 0f
            }
            .then(dragModifier),
    ) {
        // 拖拽小横条：提示整条顶栏可下拉关闭（手势区域为整条顶栏）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .offset(y = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        // 把手色 = 网格线 outline：同「底上略凸出一线」的弱对比语义，深浅主题各自成立
                        .background(MaterialTheme.colorScheme.outline),
                )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = TopBarRowHorizontalPadding,
                    end = TopBarRowHorizontalPadding,
                    top = 7.dp,
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                // 无确认胶囊时改右对齐：SpaceBetween 会把唯一的右侧按钮推到左边
                horizontalArrangement = if (confirmLabel == null) {
                    Arrangement.End
                } else {
                    Arrangement.SpaceBetween
                },
            ) {
                if (confirmLabel == null) {
                    // 仅动作胶囊：保持右位、文字在前图标在后
                    SheetPillButton(
                        label = rightAction.label,
                        icon = rightAction.icon,
                        containerColor = rightAction.containerColor,
                        contentColor = rightAction.contentColor,
                        iconAtEnd = true,
                        onClick = rightAction.onClick,
                    )
                } else if (confirmAtEnd) {
                    // 确认在右、动作（取消/删除）在左：图标排布随位置镜像，
                    // 两颗胶囊的图标始终落在各自外侧（左颗图标在前、右颗文字在前）
                    SheetPillButton(
                        label = rightAction.label,
                        icon = rightAction.icon,
                        containerColor = rightAction.containerColor,
                        contentColor = rightAction.contentColor,
                        iconAtEnd = false,
                        onClick = rightAction.onClick,
                    )
                    SheetPillButton(
                        label = confirmLabel,
                        icon = confirmIcon,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        iconAtEnd = true,
                        onClick = onConfirm,
                    )
                } else {
                    SheetPillButton(
                        label = confirmLabel,
                        icon = confirmIcon,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        onClick = onConfirm,
                    )
                    SheetPillButton(
                        label = rightAction.label,
                        icon = rightAction.icon,
                        containerColor = rightAction.containerColor,
                        contentColor = rightAction.contentColor,
                        iconAtEnd = true,
                        onClick = rightAction.onClick,
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.classppTextStyles.topBarTitle,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** 顶栏胶囊按钮。图标为「实心圆+镂空图形」，整体 tint 后即得设计稿效果（确认图标在前，
 *  取消/删除文字在前——见 [iconAtEnd]）；投影同设置页返回按钮的大柔影做法（形状为胶囊）；
 *  [iconSize] 独立控制图标大小（顶栏钮与底部常驻钮可各配各的）。 */
@Composable
fun SheetPillButton(
    label: String,
    @DrawableRes icon: Int,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconAtEnd: Boolean = false,
    iconSize: Dp = 30.dp,
    topPadding: Dp = 8.dp,
    bottomPadding: Dp = 8.dp,
    startPadding: Dp = 13.dp,  // 文字加宽宽度改这里，文字所在那侧的外边距比图标侧多 5dp（8+5）：图标在左(确认)加宽右侧，图标在右(删除/取消)加宽左侧
    endPadding: Dp = 8.dp,
) {
    Row(
        modifier = modifier
            // 大柔影：高 shadowElevation 撑开模糊半径，低透明度阴影色压住存在感
            //（同设置页返回按钮的投影做法）
            .graphicsLayer {
                shape = PillShape
                clip = true
                shadowElevation = 45.dp.toPx()
                spotShadowColor = Color.Black.copy(alpha = 0.3f)
            }
            .background(containerColor, PillShape)
            .clickable(onClick = onClick)
            .padding(
                start = if (iconAtEnd) startPadding else endPadding,
                end = if (iconAtEnd) endPadding else startPadding,
                top = topPadding,
                bottom = bottomPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val iconComposable: @Composable () -> Unit = {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(iconSize),
            )
        }
        if (!iconAtEnd) iconComposable()
        Text(
            text = label,
            style = MaterialTheme.classppTextStyles.pillButton,
            color = contentColor,
        )
        if (iconAtEnd) iconComposable()
    }
}

/** 分组标题：浅灰字，走统一角色样式（16sp Medium secondaryText，与设置页 SettingsSection 同款）；
 *  左缩进 4dp 与设置页 SettingsSection 标题一致。 */
@Composable
fun SheetSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.classppTextStyles.sectionTitle,
        modifier = modifier.padding(start = 4.dp),
    )
}
