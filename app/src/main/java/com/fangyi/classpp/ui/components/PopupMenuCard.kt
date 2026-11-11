package com.fangyi.classpp.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.motion.PopupGlassCard
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.motion.pressSelectable
import com.fangyi.classpp.ui.theme.MenuShape
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import dev.chrisbanes.haze.HazeState

/** 勾选图标边长；未勾选行保留同宽空槽，勾选/未勾选的文字左缘对齐（设计稿） */
private val CheckIconSize = 20.dp

/** 行首图标边长（[PopupMenuItem.icon]）：比勾选槽宽，槽宽随之取本值，图标型菜单内各行仍左缘对齐 */
private val MenuIconSize = 24.dp

private val RowHorizontalPadding = 25.dp
private val RowVerticalPadding = 12.dp

/** 勾选图标与文字的间距 */
private val CheckLabelSpacing = 12.dp

/** 分割线内缩：左右各收 8dp，比行内容更贴边（设计稿分割线明显宽于文字区） */
private val DividerHorizontalInset = 8.dp

/** 分割线上下留白 */
private val DividerVerticalPadding = 8.dp

/** 键盘焦点行的高亮透明度（只在方向键导航后显示，不与按压反馈抢视觉） */
private const val FocusHighlightAlpha = 0.10f

/**
 * 弹出菜单项：文案 + 勾选态 + 行首图标 + 置灰态 + 危险态 + 点击动作
 * （收起菜单由调用方与动作一起处理，这里只管渲染）。
 *
 * @param checked 勾选态：行首画勾选标记、内容色取 primary
 * @param icon 行首图标（可选）：与勾选槽共位，传入时该槽改画图标（勾选标记让位，两者语义互斥）；
 *   图标型菜单的行首槽加宽到 [MenuIconSize]
 * @param enabled false = 置灰不可点：内容色取主题置灰色 `classppColors.negative`，行不吃点击与按压反馈
 * @param destructive 危险动作（删除 / 归档等）：内容色取主题 error，且内容层会在它之前自动补
 *   一条分割线与普通项隔离（iOS destructive item / Material error 色同语义）
 * @param maxLines 文案行数上限：默认 1（超长省略，菜单不被长文案撑宽）；需要多行的场景显式放开
 */
data class PopupMenuItem(
    val label: String,
    val checked: Boolean = false,
    @DrawableRes val icon: Int? = null,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val maxLines: Int = 1,
    val onClick: () -> Unit = {},
)

/** 弹出菜单分组：纯视觉分段；[showDivider] = 与上一分组之间画分割线（可选，首组忽略） */
data class PopupMenuSection(
    val items: List<PopupMenuItem>,
    val showDivider: Boolean = false,
)

/**
 * 弹出选择菜单卡（设计稿：白圆角卡 + 左侧勾选行 + 按压反馈 + 组间可选分割线）。
 * [MenuShape] 卡壳（[PopupGlassCard] 共享配方：投影 / 毛玻璃 / 分层动画契约都在那里）
 * + [PopupMenuContent] 内容；自带容器的宿主（如 DropdownMenu）直接用 [PopupMenuContent]
 * 铺内容，避免双层卡片。
 *
 * 宽度：默认收窄到最宽行（上限 [MenuMaxWidth]）；要固定/铺满宽度给 [modifier] 加
 * width/widthIn/fillMaxWidth，行是 fillMaxWidth 会跟着撑开。
 *
 * [interactive] = false（退场期间）时所有行立即置为不可点、内容列停止滚动（A2）；
 * [paneTitle] 暴露为无障碍窗格标题；[focusFirstItem] 在首帧后把焦点落到第一个可用项
 * （仅 focusable 弹层传 true）；[maxHeight] 是内容高度上限，超出转内部滚动（B2）。
 * [selectionMode] 为 null 时按「任一行带勾选态」自动判定：勾选型菜单行用
 * `selectable(role = RadioButton)` 暴露选中语义，动作型菜单行用 `clickable(role = Button)`。
 *
 * 与弹出动画的联动（均 lambda 延迟读取，draw 阶段取值、零重组，宿主经
 * [com.fangyi.classpp.ui.motion.rememberPopupMotion] 取值）：
 * [blurProgress] ∈ [0,1] 缩放投影强度（默认恒 1f 完整阴影）；[contentAlpha] 只淡内容行
 * （整卡的首尾淡入淡出由宿主缩放层统一做，见
 * [com.fangyi.classpp.ui.motion.Motion.PopupCardFadeMillis]）；[blurScale] 供毛玻璃子层
 * 反向缩放、动画全程与真实背景 1:1 对位；[blurOrigin] 是生长锚点在卡面坐标系下的比例点
 * （反向缩放绕同一点，两层相乘为恒等）。
 *
 * 材质：默认不透明白卡；[hazeState] 非空时白底之上叠 Haze 毛玻璃（跨 Popup 窗口采样
 * 宿主页面的 hazeSource，样式 16dp + 70% tint，为菜单卡单独调过的值）；null（如
 * @Preview）退化为不透明白卡。宿主 [com.fangyi.classpp.ui.components.PopupMenuPopup]
 * 退场全程保持活采样（防护约定见其 KDoc 与 PopupActionGate），毛玻璃不中途卸载。
 */
@Composable
fun PopupMenuCard(
    sections: List<PopupMenuSection>,
    modifier: Modifier = Modifier,
    blurProgress: () -> Float = { 1f },
    hazeState: HazeState? = null,
    contentAlpha: () -> Float = { 1f },
    blurScale: () -> Float = { 1f },
    blurOrigin: (Size) -> TransformOrigin = { TransformOrigin(0f, 0f) },
    interactive: Boolean = true,
    paneTitle: String? = null,
    focusFirstItem: Boolean = false,
    maxHeight: Dp? = null,
    selectionMode: Boolean? = null,
) {
    PopupGlassCard(
        shape = MenuShape,
        // 36dp 柔影 + 15% 黑：光源在上投影向下坠得最远
        shadowElevation = 36.dp,
        spotShadowColor = Color.Black.copy(alpha = 0.15f),
        hazeState = hazeState,
        modifier = modifier,
        blurOrigin = blurOrigin,
        // 菜单卡单独调过的毛玻璃（默认配方 12dp + 60%，见 hazeCardStyle）
        blurStyle = hazeCardStyle(radius = 16.dp, tintAlpha = 0.7f),
        blurScale = blurScale,
        contentAlpha = contentAlpha,
        blurProgress = blurProgress,
    ) {
        PopupMenuContent(
            sections = sections,
            interactive = interactive,
            paneTitle = paneTitle,
            focusFirstItem = focusFirstItem,
            maxHeight = maxHeight,
            selectionMode = selectionMode,
        )
    }
}

/**
 * 弹出选择菜单的内容（无卡片壳）：勾选行 + 组间可选分割线。
 *
 * - 行：fieldLabel（16sp SemiBold）；勾选行 = primary 色 + 左侧 ic_checkmark，
 *   未勾选行 = onSurface、勾选槽占位保文字对齐；传 [PopupMenuItem.icon] 的图标型菜单
 *   在行首槽画 24dp 图标（槽随之加宽）；置灰行（`enabled = false`）= negative 色、
 *   图标/文案同色、不吃点击；危险项（`destructive = true`）= error 色、且自动在前面补
 *   分割线；点击走按压反馈（放大 + 提亮）；
 * - 分组：[PopupMenuSection.showDivider] 控制与上一分组之间的 [HorizontalDivider]
 *   （outlineVariant）——单组菜单不传分割线即为设计稿中右两态的无缝形态；
 * - 宽度：收窄到最宽行（IntrinsicSize.Max，CourseContextMenu 同款），上限 [MenuMaxWidth]；
 *   文案默认单行省略；宿主用 modifier 给固定宽度（width/widthIn/fillMaxWidth）时以其为准；
 * - 高度：[maxHeight] 以内自然高，超出转内部滚动（滚动条本身不额外占宽）；
 * - 收起由调用方与动作一起处理（这里只管渲染）。
 */
@Composable
fun PopupMenuContent(
    sections: List<PopupMenuSection>,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    paneTitle: String? = null,
    focusFirstItem: Boolean = false,
    maxHeight: Dp? = null,
    selectionMode: Boolean? = null,
) {
    // 单选型菜单判定：显式优先；未指定时「任一行带勾选态」即按单选语义暴露
    val resolvedSelectionMode = selectionMode
        ?: sections.any { section -> section.items.any { it.checked } }
    val scrollState = rememberScrollState()
    val firstItemFocusRequester = remember { FocusRequester() }
    // 方向键导航后才显示焦点高亮：手机触摸打开时不抢视觉（首焦点仍然落位，键盘可达）
    var keyboardNavigationActive by remember { mutableStateOf(false) }
    val firstEnabledIndex = remember(sections) {
        var index = 0
        var found = -1
        for (section in sections) {
            for (item in section.items) {
                if (item.enabled) {
                    found = index
                    break
                }
                index++
            }
            if (found >= 0) break
        }
        found
    }
    val announcedPaneTitle = paneTitle
    LaunchedEffect(focusFirstItem) {
        if (!focusFirstItem) return@LaunchedEffect
        // 等首帧量测完成再请求：FocusRequester 在节点 attach 前 requestFocus 会抛异常
        withFrameNanos { }
        runCatching { firstItemFocusRequester.requestFocus() }
    }
    Column(
        modifier = modifier
            .widthIn(max = MenuMaxWidth)
            .then(if (maxHeight != null) Modifier.heightIn(max = maxHeight) else Modifier)
            .width(IntrinsicSize.Max)
            .verticalScroll(scrollState, enabled = interactive)
            .focusGroup()
            .semantics { announcedPaneTitle?.let { this.paneTitle = it } },
    ) {
        var itemIndex = 0
        var previousWasDestructive = false
        var dividerJustDrawn = false
        sections.forEachIndexed { sectionIndex, section ->
            if (sectionIndex > 0 && section.showDivider && itemIndex > 0) {
                MenuDivider()
                dividerJustDrawn = true
            }
            section.items.forEach { item ->
                // 危险项与普通项之间自动补一条分割线（与上一分组分割线不重复）
                if (
                    item.destructive &&
                    itemIndex > 0 &&
                    !previousWasDestructive &&
                    !dividerJustDrawn
                ) {
                    MenuDivider()
                }
                PopupMenuRow(
                    item = item,
                    interactive = interactive,
                    selectionMode = resolvedSelectionMode,
                    showFocusHighlight = keyboardNavigationActive,
                    focusRequester = if (itemIndex == firstEnabledIndex) {
                        firstItemFocusRequester
                    } else {
                        null
                    },
                    onKeyNavigation = { keyboardNavigationActive = true },
                )
                previousWasDestructive = item.destructive
                dividerJustDrawn = false
                itemIndex++
            }
        }
    }
}

/** 分组 / 危险项前的分割线（同一配方：左右各内缩 8dp、上下 8dp 留白） */
@Composable
private fun MenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(
            horizontal = DividerHorizontalInset,
            vertical = DividerVerticalPadding,
        ),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * 菜单行：行首槽（勾选标记或图标，未勾选/无图标时占位对齐）+ 文案；
 * 内容色：置灰（negative）> 危险（error）> 勾选（primary）> 默认（onSurface），
 * 图标与文案同色。
 *
 * 无障碍（B4）：勾选型菜单走 [pressSelectable]（选中态 + RadioButton 角色），动作型菜单走
 * [pressClickable]（Button 角色）；置灰态由 clickable/selectable 自动带 disabled 语义。
 * 方向键上下在行间移动焦点（Enter / 空格由 clickable 族自带，不能重复处理，否则一次
 * 按键会执行两次），焦点行在方向键导航后画一层浅 primary 底色。
 */
@Composable
private fun PopupMenuRow(
    item: PopupMenuItem,
    interactive: Boolean,
    selectionMode: Boolean,
    showFocusHighlight: Boolean,
    focusRequester: FocusRequester?,
    onKeyNavigation: () -> Unit,
) {
    val contentColor = when {
        !item.enabled -> MaterialTheme.classppColors.negative
        item.destructive -> MaterialTheme.colorScheme.error
        item.checked -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val enabled = item.enabled && interactive
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                },
            )
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        onKeyNavigation()
                        focusManager.moveFocus(FocusDirection.Down)
                        true
                    }
                    Key.DirectionUp -> {
                        onKeyNavigation()
                        focusManager.moveFocus(FocusDirection.Up)
                        true
                    }
                    else -> false
                }
            }
            // 行无自有 clip：矩形提亮原位接在行上，卡壳 MenuShape clip（祖先）兜住缩放溢出
            .then(
                if (selectionMode) {
                    Modifier.pressSelectable(
                        selected = item.checked,
                        shape = RectangleShape,
                        enabled = enabled,
                        onClick = item.onClick,
                    )
                } else {
                    Modifier.pressClickable(
                        shape = RectangleShape,
                        enabled = enabled,
                        onClick = item.onClick,
                    )
                },
            )
            // 键盘焦点底色：只在方向键导航后显示（触摸打开的首焦点不闪高亮）
            .background(
                if (focused && showFocusHighlight) {
                    MaterialTheme.colorScheme.primary.copy(alpha = FocusHighlightAlpha)
                } else {
                    Color.Transparent
                },
            )
            .padding(start = 20.dp, end = RowHorizontalPadding, top = RowVerticalPadding, bottom = RowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            // 图标型菜单的行首槽取图标边长，勾选型维持原槽宽（既有菜单的文字左缘不动）
            Modifier.width(if (item.icon != null) MenuIconSize else CheckIconSize),
            contentAlignment = Alignment.CenterStart,
        ) {
            when {
                item.icon != null -> Icon(
                    painter = painterResource(item.icon),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(MenuIconSize),
                )
                item.checked -> Icon(
                    painter = painterResource(R.drawable.ic_checkmark),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(CheckIconSize),
                )
            }
        }
        Spacer(Modifier.width(CheckLabelSpacing))
        Text(
            text = item.label,
            style = MaterialTheme.classppTextStyles.fieldLabel,
            color = contentColor,
            maxLines = item.maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
