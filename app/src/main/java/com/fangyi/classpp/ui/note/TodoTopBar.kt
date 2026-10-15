package com.fangyi.classpp.ui.note

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.components.PopupMenuCard
import com.fangyi.classpp.ui.components.PopupMenuPopup
import com.fangyi.classpp.ui.components.PopupMenuSection
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.motion.TabTransitionState
import com.fangyi.classpp.ui.motion.rememberOffsetOverscrollFactory
import com.fangyi.classpp.ui.motion.rubberBandHorizontalScroll
import com.fangyi.classpp.ui.motion.tabTransitionFreeze
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.EditActionShape
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

/** 分组条胶囊行首图标边长：置顶图钉与特殊分组页图标同款（GroupCard 置顶标记同款 20dp） */
private val GroupTabIconSize = 20.dp

/** 分组条胶囊内行首图标与文字的间距（GroupCard 置顶标记同款 12dp） */
private val GroupTabIconSpacing = 12.dp

/** 左侧常驻图标胶囊宽度（图标 + 左右内边距） */
private val IconPillWidth = PillIconSize + PillInnerPadding * 2

/** 更多按钮右缘在顶栏坐标系里的位置：固定行自带 15dp 页边距（PageHorizontalSpacing） */
private val MorePillRightEdge = PageHorizontalSpacing + IconPillWidth

/**
 * 裁切线：分组条视口左边界相对更多按钮右缘的偏移（与静止间距 [RestGap] 互相独立）。
 * 默认 -[TopBarPillHeight] / 2（半枚胶囊）：切割边完全藏进按钮不透明底之下——胶囊
 * 滑到按钮右缘即视觉消失，且按钮四角的胶囊曲率收缩区也不会露出裁切残影；
 * 调到 0 = 贴缘裁（按钮圆角附近可能看到细缝残影），正值 = 在按钮右侧空隙中悬空切割。
 */
private val ClipLineOffset = -TopBarPillHeight / 2

/** 首粒胶囊（置顶分组或「全部」）静止位与更多按钮右缘的间距（不影响裁切线位置） */
private val RestGap = PillSpacing

/** 阴影呼吸位：胶囊柔影上下各留一份，防 LazyRow 视口把阴影裁掉 */
private val ShadowOverhang = 8.dp

/** 胶囊柔影：大 elevation 换软边、低透明 spot 压深浅 */
private val PillShadowElevation = 36.dp
private val PillShadowColor = Color.Black.copy(alpha = 0.1f)

/**
 * 动作行菜单卡（排序/分类）顶缘与动作块底缘的视觉间距。
 * 只管视觉值：上方 16dp 投影留白在定位时自动扣除，改这里不会牵动阴影活动空间。
 */
private val BarActionMenuPopupGap = 12.dp

/** 顶栏与状态栏的间距 */
private val TopBarTopPadding = 0.dp

/** 裁剪形状纵向的外扩量：容纳 45dp elevation 柔影的可见扩散范围 */
private val ShadowBleed = 200.dp

/** 展开动作行高度：纵向「图标 + 标签」块在其中垂直居中（课表编辑栏 EditActionBarHeight 同规格） */
private val TopBarActionBarHeight = 64.dp

/** 动作行与上方胶囊行的间距（课表编辑栏状态栏下留白同款 6dp） */
private val TopBarActionBarTopGap = 6.dp

/** 动作块图标边长 / 标签字号 / 图标与标签间距 / 块内水平边距（课表编辑栏 EditAction 同规格） */
private val TopBarActionIconSize = 30.dp
private val TopBarActionLabelTextSize = 13.sp
private val TopBarActionLabelGap = 2.dp
private val TopBarActionInnerPadding = 8.dp

/**
 * 展开动作行的总占位高（上方留白 + 行高）：宿主列表让位用。公开 internal 让
 * [com.fangyi.classpp.ui.note.TodoScreen] 直接引用，不再走手工镜像常量。
 */
internal val TopBarActionRowSpace = TopBarActionBarTopGap + TopBarActionBarHeight

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
 * 待办特殊分组页：分组浮层第一部分（状态/留存）的六个入口——点入后宿主收起浮层，
 * 顶栏分组条首位出现对应胶囊（[SpecialGroupPill]），再点一次胶囊退出。已归档/最近
 * 删除数据层暂无该概念，本期仅 UI（列表恒空、空状态文案占位）。
 *
 * [iconRes]/[labelRes] 供胶囊渲染，[emptyHintRes] 供空列表占位；强调色不在枚举里
 * 存死（Color 非资源语义），按条目在 [SpecialGroupPill] 处映射：已完成 = 绿
 * （urgentGreen）、已逾期/最近删除 = 红（error）、其余 = primary 蓝。
 */
internal enum class TodoSpecialSection(
    @DrawableRes val iconRes: Int,
    @StringRes val labelRes: Int,
    @StringRes val emptyHintRes: Int,
) {
    Completed(
        R.drawable.ic_checkbox_checked,
        R.string.todo_section_completed,
        R.string.todo_empty_completed,
    ),
    Incomplete(
        R.drawable.ic_checkbox_unchecked,
        R.string.todo_section_incomplete,
        R.string.todo_empty_incomplete,
    ),
    Overdue(
        R.drawable.ic_checkbox_warning,
        R.string.todo_section_overdue,
        R.string.todo_empty_overdue,
    ),
    Pinned(R.drawable.ic_pin, R.string.todo_section_pinned, R.string.todo_empty_pinned),
    Archived(R.drawable.ic_archive, R.string.todo_section_archived, R.string.todo_empty_archived),
    Trash(R.drawable.ic_delete, R.string.todo_section_trash, R.string.todo_empty_trash),
}

/**
 * 待办页顶栏：左侧常驻「更多」胶囊（•••）+ 右侧可滑动的分组胶囊条
 * （置顶分组固定最前 + 「全部」/「未分组」常驻 + 其余用户自定义分组）。
 *
 * 分组条视觉顺序：置顶分组（[pinnedGroupCount] 个，行首 20dp 图钉，GroupCard 置顶
 * 标记同款规格，未选 primary 蓝 / 选中 onPrimary 白）→「全部」→「未分组」→ 未置顶
 * 分组（各段内部保持 [groups] 原序）；仓库维持置顶分组构成 [groups] 连续前缀的不变式，
 * 前 [pinnedGroupCount] 个即置顶组。
 *
 * 特殊分组页（[specialSection] 非 null）：分组条最前再插一粒对应胶囊（[SpecialGroupPill]），
 * 位于置顶自定义分组之前，打开后**常驻**——[specialSectionSelected] = 页面在场（强调色
 * 整粒底 + 白内容）；false = 页面已离开但胶囊保留（surface 底 + 强调色内容，设计图未选
 * 样式），此时常规胶囊恢复选中态高亮。点击已选中的特殊胶囊 = 退出关闭（[onSpecialSectionClick]，
 * 胶囊一并消失）；点击未选中的 = 回到该特殊分组页。
 *
 * 布局：左侧常驻群浮在上层；右侧分组条的视口左边界即裁切线（[ClipLineOffset]，
 * 相对更多按钮右缘），首粒胶囊（置顶分组或「全部」）静止位与按钮右缘保持 [RestGap]
 * 间距——向左滑动
 * 时越过分界线的部分被视口直接裁掉，即「滑入更多按钮背后」（常驻群不透明，无缝隙穿帮）。
 *
 * 展开：点击更多胶囊翻转 [menuExpanded]，胶囊行之下展开一条动作行（分组 / 排序 /
 * 分类 / 视图 / 概览，「图标在上、标签在下」，课表编辑栏 EditAction 同规格）——
 * 动作行画在顶栏模糊层之上，模糊 Box 包裹整条 Column、随展开自动长高覆盖；
 * 进出场 expand/shrink + 淡入淡出（Motion.ExpandMillis，进场 Decelerate、退场
 * Accelerate，NoteCardSection 同款），列表让位由宿主按 [TopBarActionRowSpace]
 * 同步让出。收起更多胶囊即收起动作行——排序菜单锚在动作行的排序块上，宿主收起时
 * 应同步关闭菜单（锚点随行消失）。
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
 * [tabTransition] 非 null 时启用转场冻结层（[tabTransitionFreeze]）：tab 转场进行中
 * 整帧重放就位时录制的栏面（含模糊输出），绕开 Haze 对转场中间态几何的重采样——
 * 与课表页顶栏同一机制，杜绝快速切 tab 时毛玻璃闪缺、露出纯兜底色帧。
 *
 * 分组胶囊下标语义：0 = 全部、1 = 未分组、2 起 = 用户分组（[groups] 下标偏移 2）——
 * 置顶分组只是视觉位置前移，下标仍按 [groups] 原序偏移，宿主（TodoScreen）的过滤
 * 与分组浮层的跳转映射不受影响。点击切选中高亮，可见列表由宿主（TodoScreen）按下标
 * 派生过滤；更多胶囊点击回调
 * [onMoreClick]（宿主翻转展开态）；展开后排序块点击回调 [onSortClick]（宿主置
 * 菜单展开态），分组块点击回调 [onGroupClick]（宿主打开待办分组浮层），
 * 分类块点击回调 [onCategoryClick]（宿主置分类菜单展开态），
 * 视图/概览回调本期空置（接入功能时再加参数）；分类块配色/可用性经
 * [categoryEnabled]/[categoryFiltered] 下发——特殊分组页在场置灰（negative）不可点，
 * 分组显隐未全选（有分组被隐藏）时 primary 提示；排序菜单
 * 经 [sortMenuExpanded]/[onSortMenuDismiss]/[sortMenuSections] 三件套下发，
 * 分类菜单经 [categoryMenuExpanded]/[onCategoryMenuDismiss]/[categoryMenuSections]
 * 同款三件套下发；[BarActionMenuPopup] 挂在动作块同一 Box 里（父布局即锚点）：
 * 走 [PopupMenuPopup] 共享弹层（[PopupMenuCard] 白卡）——DropdownMenu 的壳自带
 * 默认阴影且换不掉，换壳才能吃上菜单卡的柔影。
 */
@Composable
internal fun TodoTopBar(
    groups: List<String>,
    pinnedGroupCount: Int,
    selectedGroupIndex: Int,
    onGroupSelect: (Int) -> Unit,
    onMoreClick: () -> Unit,
    onSortClick: () -> Unit,
    onGroupClick: () -> Unit,
    specialSection: TodoSpecialSection? = null,
    specialSectionSelected: Boolean = false,
    onSpecialSectionClick: () -> Unit = {},
    // 分类块状态配色：false = 置灰（negative）不可点；true 且 [categoryFiltered] = primary 提示
    categoryEnabled: Boolean = true,
    // 分组显隐过滤生效中（四组未全选）= 分类块 primary 提示有分组被隐藏
    categoryFiltered: Boolean = false,
    onCategoryClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    tabTransition: TabTransitionState? = null,
    menuExpanded: Boolean = false,
    sortMenuExpanded: Boolean = false,
    onSortMenuDismiss: () -> Unit = {},
    sortMenuSections: List<PopupMenuSection> = emptyList(),
    categoryMenuExpanded: Boolean = false,
    onCategoryMenuDismiss: () -> Unit = {},
    categoryMenuSections: List<PopupMenuSection> = emptyList(),
) {
    val overscrollFactory = rememberOffsetOverscrollFactory(animationSpec = Motion.Settle)
    // 磨砂 tint 随主题（hazeEffect 的 block 在绘制期执行、非 composable 上下文，取值提到 Box 之前）
    val hazeTint = MaterialTheme.classppColors.hazeTint
    // 转场冻结层：就位帧（含模糊输出）录制于此，tab 转场期间整帧重放
    val transitionFreezeLayer = rememberGraphicsLayer()
    CompositionLocalProvider(LocalOverscrollFactory provides overscrollFactory) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                // 转场冻结层包在兜底色与 hazeEffect 之外（最外层 draw）：录制帧才是
                // 「兜底色 + 模糊 + 内容」的完整栏面，转场期间整条重放、不重采样
                .then(
                    if (tabTransition != null) {
                        Modifier.tabTransitionFreeze(transitionFreezeLayer, tabTransition)
                    } else {
                        Modifier
                    },
                )
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
                    // 特殊分组页胶囊在分组条首位：打开时若分组条停在右侧滚动位，首粒胶囊会
                    // 藏在更多按钮背后看不见——跳转打开即滚回首位（切常规 tab 不动滚动位）
                    LaunchedEffect(specialSection) {
                        if (specialSection != null) scrollState.animateScrollTo(0)
                    }
                    // 视口左边界 = 裁切线（MorePillRightEdge + ClipLineOffset）；contentPadding
                    // 反向补偿裁切偏移，让静止间距只由 RestGap 决定——两个旋钮互不干扰。
                    // 分组条用橡皮筋增强版 horizontalScroll：不足一行时也能拉出橡皮筋。
                    // horizontalScroll 不自带视口裁剪（LazyRow 自带），须补裁剪——但整体
                    // clipToBounds 会把 45dp 柔影上下切平，故用只封水平方向的形状：
                    // 左缘 = 裁切线照常切割，纵向外扩放行柔影
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxHeight()
                            .padding(start = MorePillRightEdge + ClipLineOffset)
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
                        // 特殊分组页胶囊：被跳转的特殊页打开后固定分组条最前（置顶自定义分组
                        // 之前）且常驻——选中（页面在场）= 整粒一色，未选 = 白底彩字；再点
                        // 已选中的退出关闭、点未选中的回到该页（行为由宿主按状态接管）
                        specialSection?.let { section ->
                            SpecialGroupPill(
                                section = section,
                                selected = specialSectionSelected,
                                onClick = onSpecialSectionClick,
                            )
                        }
                        // 视觉顺序：置顶分组固定最前（特殊胶囊之后、「全部」之前，行首图钉），
                        // 之后依次「全部」「未分组」与未置顶分组，各段内部保持 [groups] 原序。
                        // 下标语义不变（0 = 全部、1 = 未分组、2 起按 [groups] 原序偏移 2）：
                        // 置顶组占 [groups] 前缀，其原下标恰好就是视觉最前段的 i + 2；未置顶段
                        // 整体后移 [pinnedGroupCount] 个位置补回原下标。
                        // 特殊页在场时常规胶囊一律不亮选中态（高亮由首位特殊胶囊独占）；
                        // 离开页面（胶囊保留未选）后常规选中态恢复
                        val regularSelection =
                            if (specialSectionSelected) Int.MIN_VALUE else selectedGroupIndex
                        val pinnedGroups = groups.take(pinnedGroupCount)
                        val unpinnedGroups = groups.drop(pinnedGroupCount)
                        pinnedGroups.forEachIndexed { index, group ->
                            GroupPill(
                                label = group,
                                pinned = true,
                                selected = regularSelection == index + 2,
                                onClick = { onGroupSelect(index + 2) },
                            )
                        }
                        GroupPill(
                            label = stringResource(R.string.todo_group_all),
                            selected = regularSelection == 0,
                            onClick = { onGroupSelect(0) },
                        )
                        // 未分组固定占下标 1（全部之后、用户分组之前），与用户分组编辑无关
                        GroupPill(
                            label = stringResource(R.string.todo_group_ungrouped),
                            selected = regularSelection == 1,
                            onClick = { onGroupSelect(1) },
                        )
                        unpinnedGroups.forEachIndexed { index, group ->
                            GroupPill(
                                label = group,
                                selected = regularSelection == index + 2 + pinnedGroupCount,
                                onClick = { onGroupSelect(index + 2 + pinnedGroupCount) },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = PageHorizontalSpacing),
                    ) {
                        IconPill(
                            iconRes = R.drawable.ic_more_horizontal,
                            contentDescription = stringResource(R.string.cd_todo_more),
                            onClick = onMoreClick,
                        )
                    }
                }
                // 展开动作行：画在顶栏模糊层之上（外层 Box 包裹整条 Column、随展开自动
                // 长高，模糊跟着覆盖）；进出场同 NoteCardSection——expand/shrink + 淡入
                // 淡出，Motion.ExpandMillis 一条时长，进场 Decelerate、退场 Accelerate。
                // 列表让位由宿主按 TopBarActionRowSpace 用同帧同曲线动画同步让出
                AnimatedVisibility(
                    visible = menuExpanded,
                    enter = expandVertically(
                        animationSpec = tween(Motion.ExpandMillis, easing = Motion.Decelerate),
                        expandFrom = Alignment.Top,
                    ) + fadeIn(tween(Motion.ExpandMillis, easing = Motion.Decelerate)),
                    exit = shrinkVertically(
                        animationSpec = tween(Motion.ExpandMillis, easing = Motion.Accelerate),
                        shrinkTowards = Alignment.Top,
                    ) + fadeOut(tween(Motion.ExpandMillis, easing = Motion.Accelerate)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = TopBarActionBarTopGap)
                            .height(TopBarActionBarHeight),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 分组块打开待办分组浮层；同排的分类/视图/概览仍是占位
                        // （保留按压反馈，接入功能时再加回调参数）
                        TodoBarAction(
                            label = stringResource(R.string.todo_action_group),
                            iconRes = R.drawable.ic_folder,
                            onClick = onGroupClick,
                            modifier = Modifier
                                .weight(1f)
                                .wrapContentWidth(Alignment.CenterHorizontally),
                        )
                        // 排序块 + 锚定其下的排序菜单：BarActionMenuPopup 挂在动作块同一
                        // Box 里（内层 Box 包住动作块即锚点，菜单卡左缘对齐动作块左缘，与
                        // 原先锚定常驻胶囊的行为一致），展开态与菜单分组内容由宿主下发。
                        // 外层 Box 只负责占一等份格槽并居中，避免锚点扩大到整格
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box {
                                TodoBarAction(
                                    label = stringResource(R.string.todo_action_sort),
                                    iconRes = R.drawable.ic_arrow_sort,
                                    onClick = onSortClick,
                                )
                                BarActionMenuPopup(
                                    expanded = sortMenuExpanded,
                                    onDismiss = onSortMenuDismiss,
                                    sections = sortMenuSections,
                                    hazeState = hazeState,
                                )
                            }
                        }
                        // 分类块 + 锚定其下的分类菜单：与排序块同构（外层占格槽、内层锚点），
                        // 菜单项是分组显隐的多选开关，展开态与勾选内容由宿主下发。
                        // 块色随状态：特殊分组页在场 = negative 置灰不可点（该页本身就是
                        // 过滤口径，显隐开关无意义）；四组未全选 = primary 提示有分组被隐藏；
                        // 其余默认 onSurface
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box {
                                TodoBarAction(
                                    label = stringResource(R.string.todo_action_category),
                                    iconRes = R.drawable.ic_filter,
                                    onClick = onCategoryClick,
                                    contentColor = when {
                                        !categoryEnabled -> MaterialTheme.classppColors.negative
                                        categoryFiltered -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                    enabled = categoryEnabled,
                                )
                                BarActionMenuPopup(
                                    expanded = categoryMenuExpanded,
                                    onDismiss = onCategoryMenuDismiss,
                                    sections = categoryMenuSections,
                                    hazeState = hazeState,
                                )
                            }
                        }
                        TodoBarAction(
                            label = stringResource(R.string.todo_action_view),
                            iconRes = R.drawable.ic_layout_row_three,
                            onClick = {},
                            modifier = Modifier
                                .weight(1f)
                                .wrapContentWidth(Alignment.CenterHorizontally),
                        )
                        TodoBarAction(
                            label = stringResource(R.string.todo_action_overview),
                            iconRes = R.drawable.ic_clipboard_task,
                            onClick = {},
                            modifier = Modifier
                                .weight(1f)
                                .wrapContentWidth(Alignment.CenterHorizontally),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 动作行菜单弹层（排序/分类块共用）：[PopupMenuPopup] 共享弹层宿主（[PopupMenuCard]
 * 白卡 + 自定义锚定 Popup），投影留白、越界钳制与 scale/alpha 进出场均由宿主处理
 * （见 [PopupMenuPopup]）。
 *
 * 定位：卡片左缘对齐动作块左缘、顶缘与动作块底缘保持 [BarActionMenuPopupGap] 间距
 * （0 = 贴住，DropdownMenu 原位）。
 * [hazeState] 下发给 [PopupMenuCard] 做毛玻璃（跨窗口采样页面 hazeSource，WeekPicker
 * 周数弹窗同款），null（如 @Preview）退化为不透明白卡。
 */
@Composable
private fun BarActionMenuPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    sections: List<PopupMenuSection>,
    hazeState: HazeState? = null,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { BarActionMenuPopupGap.roundToPx() }
    PopupMenuPopup(
        expanded = expanded,
        onDismiss = onDismiss,
        sections = sections,
        hazeState = hazeState,
        cardPosition = { anchorBounds, _, _ ->
            IntOffset(anchorBounds.left, anchorBounds.bottom + gapPx)
        },
    )
}

/** 顶栏胶囊的柔影：graphicsLayer 定形状投影（与 WeekPill / NavPill 同款范式） */
private fun Modifier.topBarPillShadow(): Modifier = graphicsLayer {
    shape = PillShape
    clip = true
    shadowElevation = PillShadowElevation.toPx()
    spotShadowColor = PillShadowColor
}

/**
 * 分组胶囊：选中 = primary 底白字，未选 = surface 底黑字（与 NavPill 同一配色逻辑）。
 * [pinned] = 置顶分组：行首 20dp 图钉（GroupCard 置顶标记同款规格 + 12dp 间距），
 * 配色随选中态切换——未选 primary 蓝、选中 onPrimary 白（图钉纯装饰不进语义，
 * 文字已承载胶囊语义）。
 */
@Composable
private fun GroupPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pinned: Boolean = false,
) {
    val background =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val contentColor =
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    // 按压态的唯一来源：pressFeedback 与 selectable 共用（涟漪由按压反馈取代，故 selectable 不再要 Indication）
    val pressInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(TopBarPillHeight)
            // 按压反馈：整体放大 + 主体提亮；接在柔影图层之前，投影随组件一起放大。
            // 顶栏胶囊按压放大单独调大到 1.10（与课表顶栏同规格，不随全局默认）
            .pressFeedback(
                interactionSource = pressInteraction,
                shape = PillShape,
                scale = 1.10f,
            )
            .topBarPillShadow()
            .background(background, PillShape)
            .selectable(
                selected = selected,
                interactionSource = pressInteraction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = PillInnerPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (pinned) {
                Icon(
                    painter = painterResource(R.drawable.ic_pin),
                    contentDescription = null,
                    tint = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                modifier = Modifier.size(GroupTabIconSize),
            )
            Spacer(Modifier.width(GroupTabIconSpacing))
            }
            Text(
                text = label,
                style = MaterialTheme.classppTextStyles.pillButton,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

/**
 * 特殊分组页强调色：已完成 = 绿（[com.fangyi.classpp.ui.theme.ClassppColors.urgentGreen]）、
 * 已逾期/最近删除 = 红（colorScheme.error），未完成/已置顶/已归档 = primary 蓝（设计图六色）。
 */
@Composable
private fun TodoSpecialSection.accentColor(): Color = when (this) {
    TodoSpecialSection.Completed -> MaterialTheme.classppColors.urgentGreen
    TodoSpecialSection.Overdue, TodoSpecialSection.Trash -> MaterialTheme.colorScheme.error
    TodoSpecialSection.Incomplete, TodoSpecialSection.Pinned, TodoSpecialSection.Archived ->
        MaterialTheme.colorScheme.primary
}

/**
 * 特殊分组页胶囊（已完成/未完成/已逾期/已置顶/已归档/最近删除）：行首语义图标 + 标签 +
 * 尾部关闭✕（ic_dismiss），整粒同色——[selected]（页面在场）= 强调色底 + 白内容，
 * 未选 = surface 底 + 强调色内容（设计图两种形态）。打开后常驻分组条首位，点击整粒
 * 切换：已选中 = 退出关闭（胶囊一并消失）、未选中 = 回到该页——行为由宿主按选中态
 * 接管（✕ 不单设命中区，同 [GroupPill] 的整粒点击语义）；图标与✕纯装饰不进语义树，
 * 标签文字已承载胶囊语义。规格同 [GroupPill]（40dp 高、16dp 内距、20dp 图标 + 12dp
 * 间距、按压放大 1.10 + 柔影）。
 */
@Composable
private fun SpecialGroupPill(
    section: TodoSpecialSection,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = section.accentColor()
    val background = if (selected) accent else MaterialTheme.colorScheme.surface
    val contentColor = if (selected) Color.White else accent
    // 按压态的唯一来源：pressFeedback 与 selectable 共用（涟漪由按压反馈取代，故 selectable 不再要 Indication）
    val pressInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(TopBarPillHeight)
            .pressFeedback(
                interactionSource = pressInteraction,
                shape = PillShape,
                scale = 1.10f,
            )
            .topBarPillShadow()
            .background(background, PillShape)
            .selectable(
                selected = selected,
                interactionSource = pressInteraction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = PillInnerPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(section.iconRes),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(GroupTabIconSize),
            )
            Spacer(Modifier.width(GroupTabIconSpacing))
            Text(
                text = stringResource(section.labelRes),
                style = MaterialTheme.classppTextStyles.pillButton,
                color = contentColor,
                maxLines = 1,
            )
            Spacer(Modifier.width(GroupTabIconSpacing))
            Icon(
                painter = painterResource(R.drawable.ic_dismiss),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(GroupTabIconSize),
            )
        }
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
    // 按压态的唯一来源：pressFeedback 与 clickable 共用（涟漪由按压反馈取代，故 clickable 不再要 Indication）
    val pressInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(width = IconPillWidth, height = TopBarPillHeight)
            // 按压反馈：整体放大 + 主体提亮；接在柔影图层之前，投影随组件一起放大。
            // 顶栏胶囊按压放大单独调大到 1.10（与分组胶囊同规格，不随全局默认）
            .pressFeedback(
                interactionSource = pressInteraction,
                shape = PillShape,
                scale = 1.10f,
            )
            .topBarPillShadow()
            .background(MaterialTheme.colorScheme.surface, PillShape)
            .clickable(
                interactionSource = pressInteraction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
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

/**
 * 顶栏展开动作块：图标在上、13sp Medium 标签在下（课表编辑栏 EditAction 同规格），
 * 两者水平居中对齐，整块可点。图标与标签同色（[contentColor]，默认 onSurface 设计图
 * 深色；分类块用它表达状态——分组显隐过滤生效中 = primary、特殊分组页在场 = negative
 * 置灰）。[enabled] = false 时整块不可点，点击与按压反馈一并停。文字已承载语义，
 * 故图标 contentDescription 为空（同 EditAction）。
 */
@Composable
private fun TodoBarAction(
    label: String,
    @DrawableRes iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier
            // 按压反馈（自带按压源 + 点击，涟漪由其取代）放在 clip 之前：
            // 缩放/提亮作用于整块动作区，EditActionShape 圆角裁剪在其内侧；
            // 放大倍率与课表编辑栏动作块同规格单独调大到 1.10（不随全局默认）
            .pressClickable(EditActionShape, scale = 1.10f, enabled = enabled, onClick = onClick)
            .clip(EditActionShape)
            .padding(horizontal = TopBarActionInnerPadding, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(TopBarActionIconSize),
        )
        Spacer(Modifier.height(TopBarActionLabelGap))
        Text(
            text = label,
            color = contentColor,
            fontSize = TopBarActionLabelTextSize,
            fontWeight = FontWeight.Medium,
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

@Preview(showBackground = true, widthDp = 412, name = "待办顶栏（特殊分组页）")
@Composable
private fun TodoTopBarSpecialPreview() {
    ClassppTheme {
        TodoTopBarPreviewContent(specialSection = TodoSpecialSection.Completed)
    }
}

@Preview(showBackground = true, widthDp = 412, name = "待办顶栏（特殊胶囊未选中）")
@Composable
private fun TodoTopBarSpecialInactivePreview() {
    ClassppTheme {
        // 胶囊常驻但页面已离开：白底彩字形态，常规选中态恢复（选中「全部」）
        TodoTopBarPreviewContent(
            specialSection = TodoSpecialSection.Completed,
            specialSectionSelected = false,
        )
    }
}

@Preview(showBackground = true, widthDp = 412, name = "待办顶栏（展开）")
@Composable
private fun TodoTopBarExpandedPreview() {
    ClassppTheme {
        TodoTopBarPreviewContent(initialMenuExpanded = true)
    }
}

@Preview(showBackground = true, widthDp = 412, name = "待办顶栏（深色，展开）")
@Composable
private fun TodoTopBarDarkPreview() {
    ClassppTheme(darkTheme = true) {
        TodoTopBarPreviewContent(initialMenuExpanded = true)
    }
}

@Composable
private fun TodoTopBarPreviewContent(
    initialMenuExpanded: Boolean = false,
    specialSection: TodoSpecialSection? = null,
    specialSectionSelected: Boolean = true,
) {
    var selected by remember { mutableIntStateOf(0) }
    var menuExpanded by remember { mutableStateOf(initialMenuExpanded) }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TodoTopBar(
            groups = PreviewGroups,
            // 预览固定 1 个置顶分组（首组「作业」行首图钉），覆盖置顶最前的排布分支
            pinnedGroupCount = 1,
            specialSection = specialSection,
            specialSectionSelected = specialSectionSelected,
            onSpecialSectionClick = {},
            selectedGroupIndex = selected,
            onGroupSelect = { selected = it },
            onMoreClick = { menuExpanded = !menuExpanded },
            onSortClick = {},
            onGroupClick = {},
            menuExpanded = menuExpanded,
        )
    }
}
