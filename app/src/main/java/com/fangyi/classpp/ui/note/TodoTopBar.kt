package com.fangyi.classpp.ui.note

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.components.PopupMenuCard
import com.fangyi.classpp.ui.components.PopupMenuSection
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.rememberOffsetOverscrollFactory
import com.fangyi.classpp.ui.motion.rubberBandHorizontalScroll
import com.fangyi.classpp.ui.schedule.CardShadowBottomPadding
import com.fangyi.classpp.ui.schedule.CardShadowPadding
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** 顶栏胶囊高度（与 ScheduleHeader 的 WeekPill 同款 40dp） */
private val TopBarPillHeight = 40.dp

/** 胶囊横向间距 */
private val PillSpacing = 12.dp

/** 胶囊内边距：图标/文字距胶囊左右缘 */
private val PillInnerPadding = 16.dp

/** 胶囊内图标边长 */
private val PillIconSize = 24.dp

/** 左侧常驻图标胶囊宽度（图标 + 左右内边距） */
private val IconPillWidth = PillIconSize + PillInnerPadding * 2

/** 排序按钮右缘在顶栏坐标系里的位置：固定行自带 15dp 页边距（PageHorizontalSpacing） */
private val SortPillRightEdge = PageHorizontalSpacing + IconPillWidth * 2 + PillSpacing

/**
 * 裁切线：分组条视口左边界相对排序按钮右缘的偏移（与静止间距 [RestGap] 互相独立）。
 * 默认 -[TopBarPillHeight] / 2（半枚胶囊）：切割边完全藏进按钮不透明底之下——胶囊
 * 滑到按钮右缘即视觉消失，且按钮四角的胶囊曲率收缩区也不会露出裁切残影；
 * 调到 0 = 贴缘裁（按钮圆角附近可能看到细缝残影），正值 = 在按钮右侧空隙中悬空切割。
 */
private val ClipLineOffset = -TopBarPillHeight / 2

/** 首粒「全部」静止位与排序按钮右缘的间距（不影响裁切线位置） */
private val RestGap = PillSpacing

/** 阴影呼吸位：胶囊柔影上下各留一份，防 LazyRow 视口把阴影裁掉 */
private val ShadowOverhang = 8.dp

/** 胶囊柔影（WeekPill 同款）：大 elevation 换软边、低透明 spot 压深浅 */
private val PillShadowElevation = 45.dp
private val PillShadowColor = Color.Black.copy(alpha = 0.2f)

/**
 * 排序菜单卡顶缘与排序胶囊底缘的视觉间距。
 * 只管视觉值：上方 16dp 投影留白在定位时自动扣除，改这里不会牵动阴影活动空间。
 */
private val SortMenuPopupGap = 12.dp

/** 顶栏与状态栏的间距 */
private val TopBarTopPadding = 3.dp

/** 裁剪形状纵向的外扩量：容纳 45dp elevation 柔影的可见扩散范围 */
private val ShadowBleed = 200.dp

/**
 * 分组条的裁剪形状：左右封闭在节点边界（左 = 裁切线）、上下向外扩 [ShadowBleed]。
 * horizontalScroll 不自带视口裁剪，不裁则滑向排序按钮的胶囊会画进常驻区；但整体
 * clipToBounds 会把 45dp 柔影上下切平——故只封水平方向，纵向放行柔影。
 */
private val HorizontalClipShape = object : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val bleed = with(density) { ShadowBleed.toPx() }
        return Outline.Rectangle(
            Rect(
                left = 0f,
                top = -bleed,
                right = size.width + bleed,
                bottom = size.height + bleed,
            ),
        )
    }
}

/**
 * 待办页顶栏：左侧常驻图标胶囊（文件夹、排序）+ 右侧可滑动的分组胶囊条
 * （「全部」常驻首位 + 用户自定义分组）。
 *
 * 布局：左侧常驻群浮在上层；右侧分组条的视口左边界即裁切线（[ClipLineOffset]，
 * 相对排序按钮右缘），首粒「全部」静止位与按钮右缘保持 [RestGap] 间距——向左滑动
 * 时越过分界线的部分被视口直接裁掉，即「滑入排序按钮背后」（常驻群不透明，无缝隙穿帮）。
 *
 * 极限橡皮筋：经 LocalOverscrollFactory 注入 iOS 式橡皮筋效果，回弹弹簧取全局
 * [Motion.Settle]（与浮层拖拽松手回位同一令牌）；分组条用橡皮筋增强版
 * horizontalScroll（[rubberBandHorizontalScroll]）——分组不足一行、本无滚动范围时
 * 也能拉出橡皮筋，任何状态下滑动都有反馈。
 *
 * 材质：顶栏容器叠设置页同款的整条渐变模糊（SettingsTopBar 配方：25dp + 半分辨率
 * 输入 + 垂直渐变 mask，顶部最强向下渐弱，兜底色 background 画在模糊层之下）——
 * 模糊层从屏幕顶铺下，状态栏区域沉浸式覆盖；胶囊一律实心 surface（选中 primary），
 * 与课表页 WeekPill 浮在毛玻璃顶栏上同一套材质关系；[hazeState] 为 null（如
 * @Preview）时容器退化为不透明兜底色。
 *
 * 纯 UI：分组点击只切选中高亮（过滤由调用方后续接入）；文件夹回调本期空置；
 * 排序胶囊点击回调 [onSortClick]（宿主置展开态）；排序菜单经 [sortMenuExpanded]/
 * [onSortMenuDismiss]/[sortMenuSections] 三件套下发，[SortMenuPopup] 挂在胶囊同一
 * Box 里（父布局即锚点）：内容走 [PopupMenuCard] 白卡，壳用自定义锚定 Popup——
 * DropdownMenu 的壳自带默认阴影且换不掉，换壳才能吃上菜单卡的柔影。
 */
@Composable
fun TodoTopBar(
    groups: List<String>,
    selectedGroupIndex: Int,
    onGroupSelect: (Int) -> Unit,
    onFolderClick: () -> Unit,
    onSortClick: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    sortMenuExpanded: Boolean = false,
    onSortMenuDismiss: () -> Unit = {},
    sortMenuSections: List<PopupMenuSection> = emptyList(),
) {
    val overscrollFactory = rememberOffsetOverscrollFactory(animationSpec = Motion.Settle)
    // 磨砂 tint 随主题（hazeEffect 的 block 在绘制期执行、非 composable 上下文，取值提到 Box 之前）
    val hazeTint = MaterialTheme.classppColors.hazeTint
    CompositionLocalProvider(LocalOverscrollFactory provides overscrollFactory) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                // 兜底色：与页面同色，画在模糊层之下（背后无内容时逐帧一致）
                .background(MaterialTheme.colorScheme.background)
                .then(
                    if (hazeState != null) {
                        // 设置页顶栏同款渐变模糊：25dp + 半分辨率输入（Fixed(0.5)）+ mask
                        // 渐变（preferPerformance）——整页覆盖层转场逐帧重合成毛玻璃，
                        // 模糊预算必须收紧（SettingsTopBar 同一转场性能规格）
                        Modifier.hazeEffect(hazeState) {
                            blurRadius = 25.dp
                            progressive = HazeProgressive.verticalGradient(
                                startIntensity = 1f,
                                endIntensity = 0f,
                                preferPerformance = true,
                            )
                            inputScale = HazeInputScale.Fixed(0.5f)
                            tints = listOf(HazeTint(hazeTint.copy(alpha = 0.30f)))
                            noiseFactor = 0f
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = TopBarTopPadding),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(TopBarPillHeight + ShadowOverhang * 2),
                ) {
                    val scrollState = rememberScrollState()
                    // 视口左边界 = 裁切线（SortPillRightEdge + ClipLineOffset）；contentPadding
                    // 反向补偿裁切偏移，让静止间距只由 RestGap 决定——两个旋钮互不干扰。
                    // 分组条用橡皮筋增强版 horizontalScroll：不足一行时也能拉出橡皮筋。
                    // horizontalScroll 不自带视口裁剪（LazyRow 自带），须补裁剪——但整体
                    // clipToBounds 会把 45dp 柔影上下切平，故用只封水平方向的形状：
                    // 左缘 = 裁切线照常切割，纵向外扩放行柔影
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxHeight()
                            .padding(start = SortPillRightEdge + ClipLineOffset)
                            .fillMaxWidth()
                            .clip(HorizontalClipShape)
                            .rubberBandHorizontalScroll(scrollState)
                            .padding(
                                start = RestGap - ClipLineOffset,
                                top = ShadowOverhang,
                                end = PageHorizontalSpacing,
                                bottom = ShadowOverhang,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(PillSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GroupPill(
                            label = stringResource(R.string.todo_group_all),
                            selected = selectedGroupIndex == 0,
                            onClick = { onGroupSelect(0) },
                        )
                        groups.forEachIndexed { index, group ->
                            GroupPill(
                                label = group,
                                selected = selectedGroupIndex == index + 1,
                                onClick = { onGroupSelect(index + 1) },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = PageHorizontalSpacing),
                        horizontalArrangement = Arrangement.spacedBy(PillSpacing),
                    ) {
                        IconPill(
                            iconRes = R.drawable.ic_folder,
                            contentDescription = stringResource(R.string.cd_todo_folder),
                            onClick = onFolderClick,
                        )
                        // 排序胶囊 + 锚定其下的排序菜单：SortMenuPopup 挂在胶囊同一 Box 里
                        // （父布局即锚点），展开态与菜单分组内容由宿主下发
                        Box {
                            IconPill(
                                iconRes = R.drawable.ic_arrow_sort,
                                contentDescription = stringResource(R.string.cd_todo_sort),
                                onClick = onSortClick,
                            )
                            SortMenuPopup(
                                expanded = sortMenuExpanded,
                                onDismiss = onSortMenuDismiss,
                                sections = sortMenuSections,
                                hazeState = hazeState,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 排序菜单弹层：[PopupMenuCard] 白卡 + 自定义锚定 Popup（WeekPicker 周数弹窗同款范式）。
 *
 * 定位：卡片左缘对齐排序胶囊左缘、顶缘与胶囊底缘保持 [SortMenuPopupGap] 间距（0 = 贴住，
 * DropdownMenu 原位），越界钳进窗口；
 * Popup 内容四周含透明投影留白（左右上 [CardShadowPadding]、底部 [CardShadowBottomPadding]，
 * 45dp 柔影向下坠得最远）防被窗口边界裁剪，定位时只反向扣除左/上留白，让卡片视觉
 * 位置与留白无关。
 * [hazeState] 下发给 [PopupMenuCard] 做毛玻璃（跨窗口采样页面 hazeSource，WeekPicker
 * 周数弹窗同款），null（如 @Preview）退化为不透明白卡。
 *
 * 动画：scale 0.8→1（Motion.PopupScaleMillis）+ alpha 0→1（Motion.PopupFadeMillis），
 * 自胶囊所在的左上角长出；收起反向播放，播完才移除弹层。
 */
@Composable
private fun SortMenuPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    sections: List<PopupMenuSection>,
    hazeState: HazeState? = null,
) {
    // 收起动画期间保持弹层在场：targetState（开）或 currentState（关动画未完）任一为真
    val expandedState = remember { MutableTransitionState(false) }
    expandedState.targetState = expanded

    val density = LocalDensity.current
    val shadowPaddingPx = with(density) { CardShadowPadding.roundToPx() }
    // 视觉间距换算成内容坐标：Popup 内容含上方投影留白，定位时一并扣掉（WeekPicker 同款）
    val gapPx = with(density) { (SortMenuPopupGap - CardShadowPadding).roundToPx() }
    val positionProvider = remember(gapPx, shadowPaddingPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset = IntOffset(
                // 锚点坐标先扣投影留白，让「卡片」而非「含留白的内容」对齐胶囊；越界钳进窗口
                x = (anchorBounds.left - shadowPaddingPx)
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                y = (anchorBounds.bottom + gapPx)
                    .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
            )
        }
    }

    if (expandedState.currentState || expandedState.targetState) {
        val transition = rememberTransition(expandedState, label = "SortMenu")
        val scale by transition.animateFloat(
            transitionSpec = { tween(Motion.PopupScaleMillis, easing = Motion.Standard) },
            label = "scale",
        ) { visible -> if (visible) 1f else 0.8f }
        val alpha by transition.animateFloat(
            transitionSpec = { tween(Motion.PopupFadeMillis, easing = Motion.Standard) },
            label = "alpha",
        ) { visible -> if (visible) 1f else 0f }

        Popup(
            onDismissRequest = onDismiss,
            popupPositionProvider = positionProvider,
            // focusable：返回键收起；dismissOnClickOutside 默认开启
            properties = PopupProperties(focusable = true),
        ) {
            PopupMenuCard(
                sections = sections,
                hazeState = hazeState,
                // 投影留白垫在卡外（裁切线 = Popup 窗口边缘，留白多大柔影就有多少活动空间）：
                // 左右上 16dp、底部 48dp——光源在上投影向下坠得最远（WeekPicker 同款配方）；
                // 缩放/淡入排在其外，整卡（含柔影）一起变换
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                    .width(185.dp)
                    .padding(
                        start = CardShadowPadding,
                        top = CardShadowPadding,
                        end = CardShadowPadding,
                        bottom = CardShadowBottomPadding,
                    ),
            )
        }
    }
}

/** 顶栏胶囊的柔影：graphicsLayer 定形状投影（与 WeekPill / NavPill 同款范式） */
private fun Modifier.topBarPillShadow(): Modifier = graphicsLayer {
    shape = PillShape
    clip = true
    shadowElevation = PillShadowElevation.toPx()
    spotShadowColor = PillShadowColor
}

/** 分组胶囊：选中 = primary 底白字，未选 = surface 底黑字（与 NavPill 同一配色逻辑） */
@Composable
private fun GroupPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val contentColor =
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .height(TopBarPillHeight)
            .topBarPillShadow()
            .background(background, PillShape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = PillInnerPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.classppTextStyles.pillButton,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/** 常驻图标胶囊：surface 底 + primary 图标（设计图蓝色图标） */
@Composable
private fun IconPill(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(width = IconPillWidth, height = TopBarPillHeight)
            .topBarPillShadow()
            .background(MaterialTheme.colorScheme.surface, PillShape)
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = PillInnerPadding),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(PillIconSize),
        )
    }
}

private val PreviewGroups = listOf("作业", "日常琐事", "考试复习", "社团活动", "购物清单", "长期计划")

@Preview(showBackground = true, widthDp = 412, name = "待办顶栏")
@Composable
private fun TodoTopBarPreview() {
    ClassppTheme {
        TodoTopBarPreviewContent()
    }
}

@Preview(showBackground = true, widthDp = 412, name = "待办顶栏（深色）")
@Composable
private fun TodoTopBarDarkPreview() {
    ClassppTheme(darkTheme = true) {
        TodoTopBarPreviewContent()
    }
}

@Composable
private fun TodoTopBarPreviewContent() {
    var selected by remember { mutableIntStateOf(0) }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TodoTopBar(
            groups = PreviewGroups,
            selectedGroupIndex = selected,
            onGroupSelect = { selected = it },
            onFolderClick = {},
            onSortClick = {},
        )
    }
}
