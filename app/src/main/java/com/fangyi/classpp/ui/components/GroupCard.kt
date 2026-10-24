package com.fangyi.classpp.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.PressShade
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.RowShape
import com.fangyi.classpp.ui.theme.SettingsCardShape
import com.fangyi.classpp.ui.theme.SheetFieldHeight
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.isDarkTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.math.roundToInt

/** 行内左右内距（与 [SettingsCard] 同规格） */
private val RowHorizontalPadding = 16.dp

/** 行内上下内距（与 [SettingsCard] 同规格） */
private val RowVerticalPadding = 14.dp

/** 值文字 ↔ 尾部箭头间距（与 [SettingsCard] 同规格） */
private val ValueTrailingSpacing = 6.dp

/** 编辑态勾选标记尺寸：与 [Chevron] 同 20dp，也和 PopupMenuCard 的勾选图标同规格 */
private val CheckMarkSize = 20.dp

/** 置顶标记：行首 pin 图标尺寸同 [Chevron] 20dp、色取 primary 对齐设计稿，与标题间距同带图标行的 12dp */
private val PinMarkSize = 20.dp
private val PinMarkSpacing = 12.dp

/** 行首图标尺寸：与 [SettingsCardwithIcon] 行首图标同 24dp */
private val IconSize = 24.dp

/** 行首图标与标题间距：与 [SettingsCardwithIcon] 的 12dp 同规格 */
private val IconSpacing = 12.dp

/** 拖拽浮层起手放大倍率：比按压反馈（1.04）再大一点，读作「被拾起」 */
private const val DraggedFloatScale = 1.05f

/** 拖拽浮层描边宽度与透明度：不用投影，靠这一圈极淡描边界定浮层边界 */
private val FloatBorderWidth = 1.dp
private const val FloatBorderAlpha = 0.35f

/**
 * 分组卡条目：左侧标题（+ 可选灰色描述），右侧蓝色当前值（可选）+ 蓝色箭头，与 [SettingsCard]
 * 的 Nav 行同款。固定「标题 / 内容 + 箭头」两栏，不支持自定义尾部槽位。
 *
 * [id] 是条目身份键：拖拽排序与多选都按它匹配——重排后各行状态（让位位移、选中标记）跟着条目
 * 走，不会串到邻行，因此同一列表内 id 必须唯一。
 *
 * [icon] 是行首图标：null = 不渲染不占位（行布局与不带图标时完全一致）；非 null 时按
 * [SettingsCardwithIcon] 行首图标规格渲染（24dp、与标题间距 12dp），普通态、编辑态与拖拽浮层
 * 同步显示；与 [pinned] 同现时图标在前、pin 标记在后。
 *
 * [iconTint] 调整 [icon] 颜色：null 时取 onSurface（随深浅色模式），传色值可自定义（如标签的
 * 彩色图标）；[icon] 为 null 时忽略。
 *
 * [locked] 是只读锁定标记：锁序行为与 [pinned] 完全一致（不可起手拖拽、落点不进锁定前缀、
 * 编辑态长按只多选），但不渲染置顶图标——供「展示但不可管理」的行使用（如分组浮层标签卡的
 * 课程标签）；与 [pinned] 一样须构成连续前缀排在最前，可与 [pinned] 混用（锁定区长度按两者
 * 之和计）。
 *
 * [pinned] 是置顶标记：行首挂 primary 色 pin 图标（`cd_pinned`），普通态、编辑态与拖拽浮层同步
 * 显示；同时置顶项在编辑态锁序——不可起手拖拽，见 [GroupCard] 的置顶契约。
 */
data class GroupCardItem(
    val id: String,
    val label: String,
    val description: String? = null,
    val value: String? = null,
    val pinned: Boolean = false,
    val locked: Boolean = false,
    @DrawableRes val icon: Int? = null,
    val iconTint: Color? = null,
)

/**
 * 分组卡片：白底（surface）+ [SettingsCardShape] 连续曲率圆角，内含一至多行 [GroupCardItem]。
 * 外观、间距与 [SettingsCard] **完全同规格**（同字体角色、同行内 16/14 内距、同行高下限
 * [rowMinHeight] 判在整卡再均摊给各行），三枚调参参数同名同义同默认；只是把行内容固定为
 * 「标题 + 值 + 箭头」，并为编辑态留出手势与动效。
 *
 * ## 编辑态由调用控件控制
 * [editing] 与 [selectedIds] 都是入参：长按条目只回调 [onEnterEdit]（带上被长按条目的 id），
 * 由调用方决定何时进入编辑态、是否顺带选中；退出编辑态、批量操作入口同样由调用方自备
 * （本组件不提供「完成」按钮，也不提供卡片空白处/卡外点击退出）。
 *
 * - 非编辑态：点击 = [onItemClick]（Nav 行语义），长按 = [onEnterEdit]；
 * - 编辑态：点击 = [onToggleSelect]（多选切换），长按 = 拾起该条拖拽排序；右侧值文字与箭头
 *   淡出/横向收放换为勾选标记——未选 `ic_checkbox_unchecked`（灰）、选中 `ic_checkbox_checked`
 *   （蓝 primary）。
 *
 * ## 行首图标
 * [GroupCardItem.icon] 非 null 时行首挂 24dp 图标、与标题间距 12dp（对齐 [SettingsCardwithIcon]
 * 行首规格），普通态、编辑态与拖拽浮层同步显示；颜色默认 onSurface 随深浅色，可用
 * [GroupCardItem.iconTint] 自定义（如标签的彩色图标）。不传 icon 的条目不渲染不占位，布局与
 * 不带图标时完全一致。
 *
 * ## 置顶
 * [GroupCardItem.pinned] 的条目行首挂 pin 标记（编辑态与拖拽浮层都保留），并被锁序：
 *
 * - 置顶项不可起手拖拽（编辑态长按无响应，点击多选不受影响）——置顶项之间的顺序由调用方的
 *   置顶操作顺序决定，组件只锁不排；
 * - 落点槽位排除置顶区：置顶区 = 列表开头的连续 pinned 前缀（调用方须把置顶项排在最前），
 *   [groupCardTarget] 只在置顶区之后的槽位里选落点——普通行拖不进置顶区，置顶前缀因此不会
 *   被打散；浮层拖到置顶区上方时，落点与让位都钳在置顶区末槽。
 *
 * 置顶/取消置顶的入口与数据逻辑也由调用方自备（同「批量操作入口」约定）：编辑态选中 → 调用方
 * 的动作入口（如浮层顶栏）→ 调用方按置顶操作顺序把 [items] 同步重排为「置顶前缀 + 其余」。
 *
 * ## 只读锁定行
 * [GroupCardItem.locked] 是 [GroupCardItem.pinned] 锁序的无图标变体（「展示但不可管理」的行，
 * 如分组浮层标签卡的课程标签）：同样须构成连续前缀排在最前，不可起手拖拽、落点不进锁定区
 * （锁定区长度按 pinned/locked 前缀之和计），编辑态长按只多选；点击与多选行为与普通行一致。
 *
 * ## 拖拽排序
 * [onMove] 为 null 时编辑态内不启动拖拽（该行为退化为纯多选列表）。拖拽过程：
 * 长按到点即拾起（浮层放大 [DraggedFloatScale] 上浮 + 一次长按触感），浮层纵向跟手，
 * 其余条目按 [groupCardShift] 实时让位；松手时浮层中心仍在卡片内 → 缓动到目标槽位后回传
 * [onMove] 并定格，浮层已拖出卡片（无效区域）→ 走 [Motion.Settle] 弹簧回弹原位、布局恢复。
 * 全程缓动、不使用投影；浮层不透明底 + 极淡描边把下面的条目盖住。
 *
 * **[onMove] 契约**：调用方必须在回调内**同步**把 [items] 重排成新顺序（典型：编辑草稿列表
 * `add(to, removeAt(from))`）。组件不持有显示顺序，落位动画收尾的同一帧才回调并清空拖拽态——
 * 若调用方不能同步重排（例如等写盘结果再刷新），会出现「落位后又弹回旧顺序」。
 *
 * 长列表请自行留意：卡片是内容高度、不滚动，超过一屏的条目拖到屏幕外不会自动滚动。
 *
 * @param items 显示顺序即列表顺序；id 需唯一，置顶项须作为连续前缀排在最前，重排后由调用方重新传入
 * @param editing 编辑态（长按条目后由调用方置 true；拖拽期间被置回 false 会立即收场回位）
 * @param selectedIds 编辑态下亮起勾选标记的条目 id
 * @param onEnterEdit 非编辑态长按条目
 * @param onItemClick 非编辑态点击条目
 * @param onToggleSelect 编辑态点击条目（多选切换）
 * @param onMove 拖拽落位回调（from / to 为 [items] 下标）；null = 不启用拖拽
 * @param rowSpacing 行间距，默认 0 = 无缝堆叠（同 [SettingsCard]）
 * @param rowMinHeight 行高下限，默认 60dp 判在整卡（同 [SettingsCard]）
 * @param contentVerticalPadding 卡内首行之前 / 末行之后的额外留白（同 [SettingsCard]）
 */
@Composable
fun GroupCard(
    items: List<GroupCardItem>,
    modifier: Modifier = Modifier,
    editing: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    onEnterEdit: (String) -> Unit = {},
    onItemClick: (String) -> Unit = {},
    onToggleSelect: (String) -> Unit = {},
    onMove: ((fromIndex: Int, toIndex: Int) -> Unit)? = null,
    rowSpacing: Dp = 0.dp,
    rowMinHeight: Dp = SheetFieldHeight,
    contentVerticalPadding: Dp = 0.dp,
) {
    val scope = rememberCoroutineScope()
    val dragState = remember(scope) { GroupCardDragState(scope) }
    // 行布局注册表：普通字段，行在 onGloballyPositioned 里上报，拖拽起手时一次性快照
    val registry = remember { GroupCardRowRegistry() }
    val density = LocalDensity.current
    val spacingPx = with(density) { rowSpacing.roundToPx() }
    val densityValue = density.density
    // 手势回调在 pointerInput 里长期存活，读这些 State 才能拿到最新值
    val latestItems = rememberUpdatedState(items)
    val latestOnMove = rememberUpdatedState(onMove)
    val cardHeightPx = remember { mutableIntStateOf(0) }
    val canDrag = editing && onMove != null

    // 拖拽期间编辑态被关掉 / 条目集合变了：会话作废（浮层回弹、布局恢复，不回调 onMove）。
    // 自己的落位提交也会改 items，但那时会话已清空，这里自然是空转
    LaunchedEffect(editing, items.map { it.id }) {
        val session = dragState.session ?: return@LaunchedEffect
        if (!editing || session.ids != latestItems.value.map { it.id }) dragState.cancel()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 拖拽期间把整卡抬到同层相邻卡片之上：浮层拖出卡片时仍盖住后面的内容
            .zIndex(if (dragState.session != null) 1f else 0f)
            .onSizeChanged { cardHeightPx.intValue = it.height },
    ) {
        // 卡片本体：圆角裁剪 + 背景；行不各自裁剪，按压反馈的放大溢出由这层兜住
        Layout(
            content = {
                items.forEachIndexed { index, item ->
                    key(item.id) {
                        GroupCardRow(
                            item = item,
                            index = index,
                            editing = editing,
                            selected = item.id in selectedIds,
                            dragState = dragState,
                            // 置顶项与锁定项锁序：不可起手拖拽（点击多选不受影响）
                            canDrag = canDrag && !(item.pinned || item.locked),
                            pressScope = scope,
                            onItemClick = onItemClick,
                            onEnterEdit = onEnterEdit,
                            onToggleSelect = onToggleSelect,
                            startDrag = { id ->
                                registry.snapshot(latestItems.value.map { it.id }, spacingPx)
                                    ?.let { snapshot ->
                                        dragState.start(
                                            itemId = id,
                                            snapshot = snapshot,
                                            // 与槽位快照同帧取锁定区长度（置顶前缀 + 只读锁定前缀）：
                                            // 普通行落点槽位下限
                                            minTargetIndex = latestItems.value
                                                .takeWhile { it.pinned || it.locked }
                                                .count(),
                                        )
                                    }
                                    ?: false
                            },
                            dragBy = { deltaY -> dragState.dragBy(deltaY) },
                            endDrag = { landed ->
                                if (landed) {
                                    dragState.drop(
                                        cardHeightPx = cardHeightPx.intValue,
                                        items = latestItems.value,
                                        onMove = latestOnMove.value,
                                    )
                                } else {
                                    dragState.cancel()
                                }
                            },
                            registerLayout = { id, top, height -> registry.register(id, top, height) },
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(SettingsCardShape)
                .background(MaterialTheme.colorScheme.surface),
        ) { measurables, constraints ->
            // 与 SettingsCard 同一套整卡下限算法：先用 intrinsic 量各行自然高（同一 Measurable
            // 一次布局只能 measure 一次），整卡不足下限时把差额均摊给各行撑高
            val minHeightPx = rowMinHeight.roundToPx()
            val insetPx = contentVerticalPadding.roundToPx()
            val width = constraints.maxWidth
            val naturals = measurables.map { it.minIntrinsicHeight(width) }
            val contentHeight = naturals.sum() + spacingPx * (naturals.size - 1).coerceAtLeast(0) +
                insetPx * 2
            val cardHeight = if (naturals.isEmpty()) 0 else
                contentHeight.coerceAtLeast(minHeightPx).coerceAtMost(constraints.maxHeight)
            val extra = cardHeight - contentHeight
            val placeables = measurables.mapIndexed { index, measurable ->
                val share = if (extra > 0) {
                    extra / measurables.size + if (index < extra % measurables.size) 1 else 0
                } else 0
                // 自然高可能超过父级给的最大高，min 不得越过 max
                measurable.measure(
                    constraints.copy(minHeight = (naturals[index] + share).coerceAtMost(constraints.maxHeight)),
                )
            }
            layout(width, cardHeight) {
                var y = insetPx
                placeables.forEach { placeable ->
                    placeable.place(0, y)
                    y += placeable.height + spacingPx
                }
            }
        }

        // 拖拽浮层：卡片本体的兄弟节点（不被圆角裁剪），始终画在最上层
        val session = dragState.session
        if (session != null) {
            val draggedItem = items.firstOrNull { it.id == session.ids.getOrNull(session.fromIndex) }
            if (draggedItem != null) {
                GroupCardDragFloat(
                    session = session,
                    item = draggedItem,
                    selected = draggedItem.id in selectedIds,
                    densityValue = densityValue,
                )
            }
        }
    }
}

/**
 * 单行渲染：按压反馈（放大 + 明暗）与手势都铺满整行宽，padding 在它们内侧——与 [SettingsCard]
 * 的行同款。
 *
 * 手势按编辑态二选一，且**任一时刻只有一个手势检测器**（见 [Modifier.groupCardEditGesture]）：
 * 非编辑态走 combinedClickable（点击 + 长按进编辑态），编辑态走自建检测器（点击多选 / 长按拖动），
 * 从结构上杜绝「拖拽抬手被当成点击」的竞争。
 *
 * 让位位移与占位行隐藏都读在 graphicsLayer 里：拖拽每帧只重画本行、不触发重组。
 */
@Composable
private fun GroupCardRow(
    item: GroupCardItem,
    index: Int,
    editing: Boolean,
    selected: Boolean,
    dragState: GroupCardDragState,
    canDrag: Boolean,
    pressScope: CoroutineScope,
    onItemClick: (String) -> Unit,
    onEnterEdit: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    startDrag: (String) -> Boolean,
    dragBy: (Float) -> Unit,
    endDrag: (Boolean) -> Unit,
    registerLayout: (id: String, top: Int, height: Int) -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val rowShade = if (MaterialTheme.isDarkTheme) PressShade.Brighten else PressShade.Darken
    // 被拖条目本体让位给浮层：原地只留空档（不画内容），槽位仍占着卡片高度
    val dragged = dragState.draggedId == item.id
    val gesture = if (editing) {
        Modifier.groupCardEditGesture(
            itemId = item.id,
            selected = selected,
            press = press,
            pressScope = pressScope,
            dragState = dragState,
            canDrag = canDrag,
            haptics = haptics,
            onToggleSelect = onToggleSelect,
            startDrag = startDrag,
            dragBy = dragBy,
            endDrag = endDrag,
        )
    } else {
        Modifier.combinedClickable(
            interactionSource = press,
            indication = null,
            onClick = { onItemClick(item.id) },
            onLongClick = { onEnterEdit(item.id) },
        )
    }
    GroupCardRowContent(
        item = item,
        editing = editing,
        selected = selected,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = if (dragged) 0f else 1f
                translationY = dragState.shiftFor(index)
            }
            // 上报静态槽位与行高（键为 id，重排后仍对得上），拖拽起手时快照
            .onGloballyPositioned { coords ->
                registerLayout(item.id, coords.positionInParent().y.roundToInt(), coords.size.height)
            }
            .pressFeedback(press, RectangleShape, shade = rowShade)
            .then(gesture),
    )
}

/** 行内容：（行首图标）（置顶标记）+ 标题块 + 尾部（编辑态勾选标记 / 普通态值文字 + 箭头）；浮层复用同一份，保证落位前后像素一致 */
@Composable
private fun GroupCardRowContent(
    item: GroupCardItem,
    editing: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item.icon?.let { icon ->
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = item.iconTint ?: MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(IconSize),
            )
            Spacer(Modifier.width(IconSpacing))
        }
        if (item.pinned) {
            Icon(
                painter = painterResource(R.drawable.ic_pin),
                contentDescription = stringResource(R.string.cd_pinned),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(PinMarkSize),
            )
            Spacer(Modifier.width(PinMarkSpacing))
        }
        SettingsRowText(label = item.label, description = item.description, modifier = Modifier.weight(1f))
        GroupCardRowTrailing(item = item, editing = editing, selected = selected)
    }
}

/**
 * 尾部两态切换：普通态 = 蓝色当前值（可选）+ 箭头；编辑态 = 勾选标记。
 * 用 fade + [SizeTransform]（而非 Crossfade）：尾部宽度变化交给尺寸动画平滑收放，标题可用宽度
 * 跟着缓动走，不会在动画结尾因容器取两态最大宽而跳一下。
 */
@Composable
private fun GroupCardRowTrailing(
    item: GroupCardItem,
    editing: Boolean,
    selected: Boolean,
) {
    AnimatedContent(
        targetState = editing,
        transitionSpec = {
            fadeIn(tween(Motion.FastMillis, easing = Motion.Decelerate)) togetherWith
                fadeOut(tween(Motion.FastMillis, easing = Motion.Accelerate)) using
                SizeTransform(clip = false) { _, _ ->
                    tween(Motion.FastMillis, easing = Motion.Decelerate)
                }
        },
        label = "groupCardRowTrailing",
    ) { isEditing ->
        if (isEditing) {
            Icon(
                painter = painterResource(
                    if (selected) R.drawable.ic_checkbox_checked else R.drawable.ic_checkbox_unchecked,
                ),
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.classppColors.secondaryText,
                modifier = Modifier.size(CheckMarkSize),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.value != null) {
                    Text(text = item.value, style = MaterialTheme.classppTextStyles.fieldValue)
                    Spacer(Modifier.width(ValueTrailingSpacing))
                }
                Chevron()
            }
        }
    }
}

/**
 * 拖拽浮层：不透明 surface 底 + [RowShape] 圆角 + 极淡描边（不用投影，靠描边与放大读作上浮），
 * 高度锁定为起手快照的行高——整卡下限均摊把行撑高时，浮层与占位行仍然等高，落位不会跳。
 */
@Composable
private fun GroupCardDragFloat(
    session: GroupCardDragSession,
    item: GroupCardItem,
    selected: Boolean,
    densityValue: Float,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(session.heightDp(session.fromIndex, densityValue))
            // 位置与缩放都读在 layer 里：跟手期间只走绘制，既不改布局也不触发重组；
            // translationY 在父坐标系里平移，不受 scale 影响
            .graphicsLayer {
                translationY = session.tops.getOrElse(session.fromIndex) { 0 } + session.floatOffsetPx
                scaleX = session.floatScaleValue
                scaleY = session.floatScaleValue
            }
            .clip(RowShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = FloatBorderWidth,
                color = MaterialTheme.classppColors.secondaryText.copy(alpha = FloatBorderAlpha),
                shape = RowShape,
            ),
    ) {
        GroupCardRowContent(
            item = item,
            editing = true,
            selected = selected,
            modifier = Modifier.align(Alignment.CenterStart),
        )
    }
}

/**
 * 编辑态手势：点击 = 多选切换，长按到点 = 拾起拖拽。一个检测器包办两种手势，谁先成立谁生效——
 * 长按赢下后同一检测器继续消费位移直到抬手，期间不会再有第二个检测器把抬手读成点击。
 * （`clickable` + `detectDragGesturesAfterLongPress` 共存时的消费归属依赖 Compose 内部派发顺序，
 * 这里不赌它：编辑态只在下面这一段里处理指针。）
 *
 * 抬手若发生在长按到点之前 = 点击（[onToggleSelect]）；被父级滚动抢走（返回 null）不算点击；
 * 长按到点则发一次长按触感并进入拖拽，拖拽期间编辑态也可能被调用方关掉（由状态层收场）。
 * 语义手动挂 Role.Checkbox + toggleableState：多选行对无障碍读屏仍是可勾选行。
 */
private fun Modifier.groupCardEditGesture(
    itemId: String,
    selected: Boolean,
    press: MutableInteractionSource,
    pressScope: CoroutineScope,
    dragState: GroupCardDragState,
    canDrag: Boolean,
    haptics: HapticFeedback,
    onToggleSelect: (String) -> Unit,
    startDrag: (String) -> Boolean,
    dragBy: (Float) -> Unit,
    endDrag: (Boolean) -> Unit,
): Modifier = this
    .pointerInput(itemId, canDrag) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // 指针事件跑在受限挂起作用域里，只能同步写状态：按压反馈的交互事件转投普通作用域
            // 发出（同一作用域按序派发，Press/Release/Cancel 的相对顺序不变）
            val pressDown = PressInteraction.Press(down.position)
            pressScope.launch { press.emit(pressDown) }
            try {
                val up = withTimeout(viewConfiguration.longPressTimeoutMillis) {
                    waitForUpOrCancellation()
                }
                if (up != null) {
                    pressScope.launch { press.emit(PressInteraction.Release(pressDown)) }
                    // 另有会话在飞（别人刚松手的落位动画）时不响应：避免动画期间误选出第二项
                    if (!dragState.isBusy) onToggleSelect(itemId)
                } else {
                    pressScope.launch { press.emit(PressInteraction.Cancel(pressDown)) }
                }
            } catch (_: PointerEventTimeoutCancellationException) {
                pressScope.launch { press.emit(PressInteraction.Release(pressDown)) }
                if (canDrag && startDrag(itemId)) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    endDrag(awaitGroupCardDrag(dragBy, down.id))
                }
            }
        }
    }
    .semantics {
        role = Role.Checkbox
        toggleableState = ToggleableState(selected)
        onClick {
            onToggleSelect(itemId)
            true
        }
    }

/**
 * 长按之后的拖拽跟踪：消费后续所有位移直到抬手，返回 true = 正常抬手（可落位）；被父级手势
 * 抢走（位移已被消费）则返回 false，由调用方作废本次拖拽。
 */
private suspend fun AwaitPointerEventScope.awaitGroupCardDrag(
    dragBy: (Float) -> Unit,
    pointerId: PointerId,
): Boolean {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId }
        if (change == null) {
            if (event.changes.none { it.pressed }) return false
            continue
        }
        if (change.changedToUpIgnoreConsumed()) {
            change.consume()
            return true
        }
        if (change.isConsumed) return false
        val deltaY = change.positionChange().y
        if (deltaY != 0f) dragBy(deltaY)
        change.consume()
    }
}

/** 起手快照：显示顺序的 id、各行静态顶（父坐标系 px）与行高、行距（px） */
private class GroupCardDragSnapshot(
    val ids: List<String>,
    val tops: List<Int>,
    val heights: List<Int>,
    val spacingPx: Int,
)

/**
 * 行布局注册表：行在 onGloballyPositioned 里按 id 上报静态槽位（顶与高）。
 * 普通字段不参与重组；按 id 存所以重排后不会错位，拖拽起手时一次性快照 [snapshot]。
 */
private class GroupCardRowRegistry {
    private val tops = mutableMapOf<String, Int>()
    private val heights = mutableMapOf<String, Int>()

    fun register(id: String, top: Int, height: Int) {
        tops[id] = top
        heights[id] = height
    }

    /** 任一行还没上报过布局就返回 null（该帧不允许起手拖拽，下一帧布局完成后自会齐） */
    fun snapshot(ids: List<String>, spacingPx: Int): GroupCardDragSnapshot? {
        if (ids.isEmpty()) return null
        val snapshotTops = ids.map { tops[it] ?: return null }
        val snapshotHeights = ids.map { heights[it] ?: return null }
        return GroupCardDragSnapshot(
            ids = ids,
            tops = snapshotTops,
            heights = snapshotHeights,
            spacingPx = spacingPx,
        )
    }
}

/**
 * 一次拖拽会话：静态槽位快照 + 目标槽位 + 浮层位移/缩放 + 各行的让位动画。
 * 会话在松手后仍保留到落位（或回弹）动画收尾——期间占位行保持隐藏、浮层继续绘制，
 * 收尾的同一帧才由 [GroupCardDragState] 清空，布局因此定格而不闪。
 */
@Stable
private class GroupCardDragSession(
    val fromIndex: Int,
    val ids: List<String>,
    val tops: List<Int>,
    val heights: List<Int>,
    val spacingPx: Int,
    /** 落点槽位下限 = 置顶区长度（起手时随槽位快照同帧取值）；起手槽位在其内即置顶项，[start] 会拒绝 */
    val minTargetIndex: Int,
) {
    /** 浮层当前目标槽位（跟手期间随最近的落点变化） */
    var targetIndex by mutableIntStateOf(fromIndex)

    /**
     * 浮层相对「原位」的纵向偏移与放大倍率。都是普通状态而非 Animatable：跟手期间由指针事件
     * **同步**写入（指针作用域是受限挂起作用域，写状态可以、调挂起函数不行），落位/回弹时
     * 由 [GroupCardDragState.settleAndFinish] 的动画逐帧写入。
     */
    var floatOffsetPx by mutableFloatStateOf(0f)
    var floatScaleValue by mutableFloatStateOf(1f)

    /** 每个条目一条让位动画（拖拽中的条目本身不动） */
    val shifts = mutableMapOf<String, Animatable<Float, AnimationVector1D>>()

    /** 让位动画驱动与收尾动画：清空会话时一并取消 */
    var driverJob: Job? = null
    var settleJob: Job? = null

    /** 收尾动画已启动：避免重复落位（如编辑态在动画期间被关掉再触发一次取消） */
    var finishing = false

    /** [index] 行的让位位移目标（px） */
    fun shiftTargetFor(index: Int): Int = groupCardShift(
        index = index,
        from = fromIndex,
        target = targetIndex,
        draggedHeight = heights.getOrElse(fromIndex) { 0 },
        spacingPx = spacingPx,
    )

    /** 快照行高转 dp（浮层锁高用） */
    fun heightDp(index: Int, density: Float): Dp =
        (heights.getOrElse(index) { 0 } / density).dp
}

/**
 * 分组卡拖拽状态：创建/清空会话、跟手、落位与回弹。整个组件只持有这一个状态对象，
 * 编辑态与选中集都在调用方（见 [GroupCard]）。
 */
@Stable
private class GroupCardDragState(private val scope: CoroutineScope) {

    /** 当前会话：非空 = 浮层在飞（含落位/回弹收尾），行渲染据此让位、浮层据此绘制 */
    var session: GroupCardDragSession? by mutableStateOf(null)
        private set

    /** 有会话在飞（拖拽中或收尾动画中）：新手势的点击在此期间不生效 */
    val isBusy: Boolean get() = session != null

    /** 拖拽中的条目 id（浮层用它与占位行隐藏判定） */
    val draggedId: String? get() = session?.let { it.ids.getOrNull(it.fromIndex) }

    /** 第 [index] 行的让位位移（px）：无会话恒 0。读在 graphicsLayer 里，跟手只重画不重组 */
    fun shiftFor(index: Int): Float {
        val current = session ?: return 0f
        if (index == current.fromIndex) return 0f
        val id = current.ids.getOrNull(index) ?: return 0f
        return current.shifts[id]?.value ?: 0f
    }

    /**
     * 起手：建会话、浮层放大上浮、每条目一条让位动画；已有会话在飞、id 不在快照、或起手槽位
     * 落在置顶区内（[minTargetIndex] 之前 = 置顶项，锁序不可拖）则拒绝
     */
    fun start(itemId: String, snapshot: GroupCardDragSnapshot, minTargetIndex: Int): Boolean {
        if (session != null) return false
        val fromIndex = snapshot.ids.indexOf(itemId)
        if (fromIndex < 0 || fromIndex < minTargetIndex) return false
        val current = GroupCardDragSession(
            fromIndex = fromIndex,
            ids = snapshot.ids,
            tops = snapshot.tops,
            heights = snapshot.heights,
            spacingPx = snapshot.spacingPx,
            minTargetIndex = minTargetIndex,
        )
        current.ids.forEach { current.shifts[it] = Animatable(0f) }
        session = current
        current.driverJob = scope.launch {
            launch {
                animateFrom(current.floatScaleValue, DraggedFloatScale) { value ->
                    current.floatScaleValue = value
                }
            }
            current.ids.forEachIndexed { index, id ->
                if (index == current.fromIndex) return@forEachIndexed
                launch {
                    // 目标槽位翻转时重新发射并从当前位置改向（collectLatest 掐掉上一段未播完的
                    // 位移动画）：快速连跨多槽时让位连续跟手，不排队逐段播
                    snapshotFlow { current.shiftTargetFor(index) }
                        .collectLatest { target ->
                            current.shifts.getValue(id).animateTo(
                                target.toFloat(),
                                tween(Motion.ReorderMillis, easing = Motion.Decelerate),
                            )
                        }
                }
            }
        }
        return true
    }

    /**
     * 跟手：浮层纵向跟手指 1:1（同步写状态，不带缓动），并按浮层中心重算最近的落点槽位。
     * 非挂起：指针事件处理跑在受限挂起作用域里，只能同步写状态。
     */
    fun dragBy(deltaY: Float) {
        val current = session ?: return
        val offset = current.floatOffsetPx + deltaY
        current.floatOffsetPx = offset
        current.targetIndex = groupCardTarget(
            tops = current.tops,
            heights = current.heights,
            from = current.fromIndex,
            floatCenterY = current.tops.getOrElse(current.fromIndex) { 0 } + offset +
                current.heights.getOrElse(current.fromIndex) { 0 } / 2f,
            minTargetIndex = current.minTargetIndex,
        )
    }

    /**
     * 松手：浮层中心仍在卡片内 → 缓动到目标槽位，收尾的同一帧回调 [onMove] 并清空会话
     * （调用方同步重排，各行在新槽位上位移归零，像素严丝合缝）；已拖出卡片（无效区域）→
     * 目标先复位（各行缓动归位），浮层走 [Motion.Settle] 弹簧回原位，布局恢复。
     */
    fun drop(
        cardHeightPx: Int,
        items: List<GroupCardItem>,
        onMove: ((fromIndex: Int, toIndex: Int) -> Unit)?,
    ) {
        val current = session ?: return
        val center = current.tops.getOrElse(current.fromIndex) { 0 } + current.floatOffsetPx +
            current.heights.getOrElse(current.fromIndex) { 0 } / 2f
        val valid = center >= 0f && center <= cardHeightPx.toFloat()
        settleAndFinish(
            session = current,
            target = if (valid) current.targetIndex else current.fromIndex,
            valid = valid,
            items = items,
            onMove = onMove,
        )
    }

    /** 从 [from] 缓动到 [to]（默认重排时长 + Decelerate），逐帧写进 [apply] */
    private suspend fun animateFrom(
        from: Float,
        to: Float,
        spec: AnimationSpec<Float> = tween(Motion.ReorderMillis, easing = Motion.Decelerate),
        apply: (Float) -> Unit,
    ) {
        Animatable(from).animateTo(to, spec) { apply(value) }
    }

    /** 取消（编辑态被关掉、条目集合变化）：与无效松手同款——回弹原位、布局恢复，不回调 [onMove] */
    fun cancel() {
        val current = session ?: return
        settleAndFinish(
            session = current,
            target = current.fromIndex,
            valid = false,
            items = emptyList(),
            onMove = null,
        )
    }

    private fun settleAndFinish(
        session: GroupCardDragSession,
        target: Int,
        valid: Boolean,
        items: List<GroupCardItem>,
        onMove: ((fromIndex: Int, toIndex: Int) -> Unit)?,
    ) {
        if (session.finishing) return
        session.finishing = true
        // 目标槽位复位/定格：让位动画由 driverJob 里各自的 snapshotFlow 收尾
        session.targetIndex = target
        val fromTop = session.tops.getOrElse(session.fromIndex) { 0 }
        session.settleJob = scope.launch {
            coroutineScope {
                launch {
                    if (valid) {
                        val landing = groupCardLandingTop(
                            tops = session.tops,
                            heights = session.heights,
                            from = session.fromIndex,
                            target = target,
                        )
                        animateFrom(session.floatOffsetPx, (landing - fromTop).toFloat()) {
                            session.floatOffsetPx = it
                        }
                    } else {
                        // 无效区域：弹簧回原位（Motion.Settle 的本职用途——浮层拖拽松手后的回位）
                        animateFrom(session.floatOffsetPx, 0f, spec = Motion.Settle) {
                            session.floatOffsetPx = it
                        }
                    }
                }
                launch {
                    animateFrom(session.floatScaleValue, 1f) { session.floatScaleValue = it }
                }
            }
            // 按 id 在新列表里定位：拖拽期间列表若被动过（id 仍在），落位下标依然算得对
            if (valid && target != session.fromIndex) {
                val fromId = session.ids.getOrNull(session.fromIndex)
                val toId = session.ids.getOrNull(target)
                val from = items.indexOfFirst { it.id == fromId }
                val to = items.indexOfFirst { it.id == toId }
                if (from >= 0 && to >= 0 && from != to) onMove?.invoke(from, to)
            }
            clear(session)
        }
    }

    /** 清空会话：取消所有动画；会话清空的同一帧各行位移归零、占位行显形、浮层消失 */
    private fun clear(current: GroupCardDragSession) {
        if (session === current) session = null
        current.driverJob?.cancel()
        current.settleJob = null
    }
}

/**
 * 落点：把第 [from] 槽的条目放到第 [target] 槽之后，浮层顶边应落在的 y（px，父坐标系）。
 * 向下移动时接住被挤上来的那一行让出的空档——加上两者高度差，变高列表也不留缝、不重叠
 * （均高列表退化为 `tops[target]`）。
 */
internal fun groupCardLandingTop(tops: List<Int>, heights: List<Int>, from: Int, target: Int): Int {
    val top = tops.getOrElse(target) { 0 }
    return if (target > from) {
        top + heights.getOrElse(target) { 0 } - heights.getOrElse(from) { 0 }
    } else {
        top
    }
}

/**
 * 让位位移：拖拽 [from] 槽条目、落点为 [target] 槽时，第 [index] 行相对自己静态位置的纵向
 * 位移（px）。让位量恒为「被拖条目高 + 行距」，均高与变高列表都不会与其他行重叠；
 * 原地（target == from）时全为 0。
 */
internal fun groupCardShift(
    index: Int,
    from: Int,
    target: Int,
    draggedHeight: Int,
    spacingPx: Int,
): Int = when {
    index > from && index <= target -> -(draggedHeight + spacingPx)
    index >= target && index < from -> draggedHeight + spacingPx
    else -> 0
}

/**
 * 目标槽位：取「该槽落点处条目的中心」离浮层中心最近的槽。相邻两槽的翻转点正好是两者落点的
 * 中点，跟手时槽位切换与视觉位置一致；变高列表按各自落点算，不会偏向矮行。
 *
 * [minTargetIndex] 起的槽位才有落点资格（置顶区锁序，见 [GroupCard]）：浮层拖进置顶区时取
 * 其后第一槽，置顶前缀永不参与让位；下限越界（置顶区吞掉整卡）时无候选，退回原槽。
 */
internal fun groupCardTarget(
    tops: List<Int>,
    heights: List<Int>,
    from: Int,
    floatCenterY: Float,
    minTargetIndex: Int = 0,
): Int {
    if (tops.isEmpty()) return from.coerceAtLeast(0)
    val draggedHeight = heights.getOrElse(from) { 0 }
    var best = -1
    var bestDistance = Float.MAX_VALUE
    for (target in minTargetIndex.coerceAtLeast(0) until tops.size) {
        val center = groupCardLandingTop(tops, heights, from, target) + draggedHeight / 2f
        val distance = abs(center - floatCenterY)
        if (distance < bestDistance) {
            bestDistance = distance
            best = target
        }
    }
    return if (best >= 0) best else from.coerceAtLeast(0)
}

private val PreviewItems = listOf(
    GroupCardItem(id = "homework", label = "作业", value = "12", pinned = true),
    GroupCardItem(id = "daily", label = "日常琐事", value = "5"),
    GroupCardItem(id = "ideas", label = "灵感", description = "随手记下的想法", value = "3"),
    GroupCardItem(id = "archive", label = "归档"),
)

@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390, name = "分组卡·普通态")
@Composable
private fun GroupCardPreview() {
    ClassppTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            GroupCard(items = PreviewItems)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390, name = "分组卡·编辑态多选")
@Composable
private fun GroupCardEditingPreview() {
    ClassppTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            GroupCard(
                items = PreviewItems,
                editing = true,
                selectedIds = setOf("homework", "ideas"),
                onMove = { _, _ -> },
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390, name = "分组卡·深色编辑态")
@Composable
private fun GroupCardEditingDarkPreview() {
    ClassppTheme(darkTheme = true) {
        Column(modifier = Modifier.padding(16.dp)) {
            GroupCard(
                items = PreviewItems,
                editing = true,
                selectedIds = setOf("daily"),
                onMove = { _, _ -> },
            )
        }
    }
}

/** 行首图标预览数据：模拟标签卡——彩色 tag 图标 + 计数值，与不带图标的行混排 */
private val PreviewIconItems = listOf(
    GroupCardItem(
        id = "work",
        label = "标签名",
        value = "15",
        pinned = true,
        icon = R.drawable.ic_tag,
        iconTint = Color(0xFF1677FF),
    ),
    GroupCardItem(id = "life", label = "标签名", value = "54", icon = R.drawable.ic_tag, iconTint = Color(0xFF52C41A)),
    GroupCardItem(id = "plain", label = "不带图标的行", value = "3"),
)

@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390, name = "分组卡·行首图标")
@Composable
private fun GroupCardIconPreview() {
    ClassppTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            GroupCard(items = PreviewIconItems)
        }
    }
}
