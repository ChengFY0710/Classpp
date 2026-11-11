package com.fangyi.classpp.ui.components

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.motion.PressShade
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.RowCardPaddingHorizontal
import com.fangyi.classpp.ui.theme.RowCardPaddingVertical
import com.fangyi.classpp.ui.theme.SettingsCardShape
import com.fangyi.classpp.ui.theme.SheetFieldHeight
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.isDarkTheme
import dev.chrisbanes.haze.HazeState

/**
 * 非 focusable 菜单收起后，极短窗口内忽略行的「打开」请求：点开着的选择行时，
 * 弹层的 ACTION_OUTSIDE（收起）与落到行上的点击是同一个手势（非 focusable 窗口
 * 不消费外部触摸），不拦的话菜单会「刚收起又立刻重开」——退场动画播到一半被反向
 * 进场，果冻回弹被截断，看起来像重播了一段无回弹的开场。
 * 同 [AiChatInputBar] 的非 focusable 弹层防重开。
 */
private const val MenuReopenGuardMillis = 200L

/**
 * 设置卡条目：导航行（label + 主题色箭头）、开关行（label + [ClassppSwitch]）、
 * 选择行（label + 蓝色当前值 + 上下箭头，点行弹菜单）、滑块行（label + 右上当前值 +
 * 整宽 [ClassppSlider]）、滑块组（label + 多行「当前值 + 滑条」）或自定义行（尾部槽位
 * 放任意控件），均可带灰色描述文字（自动换行、行卡随之长高）。
 */
sealed interface SettingsCardItem {
    val label: String
    val description: String?

    /** 导航行：整行可点，右侧可带 primary 色当前值，尾部主题色箭头 */
    data class Nav(
        override val label: String,
        override val description: String? = null,
        val value: String? = null,
        val onClick: () -> Unit,
    ) : SettingsCardItem

    /**
     * 开关行：整行可点切换（按压反馈铺满整行），尾部 [ClassppSwitch] 只作视觉件。
     * [enabled] = false 时整行不可点、按压反馈一并消失（开关保持当前态，未激活轨道
     * 本身即置灰观感）——供「条件不满足、开关锁定」的场景（如无重复待办的截止随重复）。
     */
    data class Toggle(
        override val label: String,
        override val description: String? = null,
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
        val enabled: Boolean = true,
    ) : SettingsCardItem

    /**
     * 选择行：整行可点弹出菜单，菜单开着时再点整行收起（完整播放退场动画），
     * 再点一次重新打开（打开前自动收起键盘与焦点；菜单为非 focusable 弹层，
     * 不抢窗口焦点，避免 IME 收起动画期间键盘闪烁），
     * [value] 为当前值的展示文字，[items] 只传合法选项（由调用方裁剪）。
     */
    data class Select(
        override val label: String,
        override val description: String? = null,
        val value: String,
        val items: List<Pair<Int, String>>,
        val selectedId: Int,
        val onPick: (Int) -> Unit,
    ) : SettingsCardItem

    /**
     * 自定义行：label（+ 可选描述）与 [trailing] 同行垂直居中，无整行点击（尾部控件自理）。
     * 供暂未收敛为标准行型的控件使用（如节次卡的加减按钮、时间胶囊）；
     * [trailing] = null（默认）为纯文字行：不渲染尾部槽位，标题与描述撑满整行可用宽
     * （描述不再预留避让尾部控件的间隙），标题取 secondaryGrey 弱化层级——说明/简介类
     * 展示行同款；
     * [footer] 可选，画在整行之下、占满行宽（如节次上限的溢出提示），内距由内容自理。
     */
    data class Custom(
        override val label: String,
        override val description: String? = null,
        val trailing: (@Composable RowScope.() -> Unit)? = null,
        val footer: (@Composable () -> Unit)? = null,
    ) : SettingsCardItem

    /**
     * 滑块行：label（+ 可选描述）与右上蓝色当前值 [valueText] 同行，下方整宽 [ClassppSlider]。
     * 无整行点击，拖动滑条即改值；[defaultValue] 为吸附默认值（灰色标记球位置），
     * [valueText] 由调用方格式化（同 Nav/Select 的 value 惯例）。
     */
    data class Slider(
        override val label: String,
        override val description: String? = null,
        val value: Float,
        val onValueChange: (Float) -> Unit,
        val valueText: String,
        val defaultValue: Float,
        val valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
        val snapThreshold: Dp = 10.dp,
    ) : SettingsCardItem

    /**
     * 滑块组：label（+ 可选描述）下挂多行「左当前值 + 右滑条」，[lines] 逐行渲染；
     * 值文字槽与滑条按 1:3 分宽，多行滑条左缘对齐。参数语义同 [Slider]。
     */
    data class SliderGroup(
        override val label: String,
        override val description: String? = null,
        val lines: List<Line>,
    ) : SettingsCardItem {
        data class Line(
            val value: Float,
            val onValueChange: (Float) -> Unit,
            val valueText: String,
            val defaultValue: Float,
            val valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
            val snapThreshold: Dp = 10.dp,
        )
    }
}

/**
 * 设置卡片：白底（surface）+ [SettingsCardShape] 连续曲率圆角，内含一至多行 [SettingsCardItem]。
 * [rowSpacing] = 0（默认）时行与行无缝堆叠，整行按压反馈（放大 + 提亮）铺满行宽、溢出被
 * 卡片圆角裁剪（对齐设计稿「点击反馈」）；需要行间留白时传正值（间隙不可点，四周边距不受影响）。
 * [rowMinHeight] 为行高下限（默认 [SheetFieldHeight]）：多行内容卡要收紧行距时传小值，
 * 行高回落为自然高（上下内距 14×2 + 内容高），只影响传入该参数的卡片。
 * [contentVerticalPadding] 为卡内首行之前 / 末行之后的额外留白（默认 0）。
 *
 * 规格（对齐 [SheetTextField]）：
 * - 字体：label 走 fieldLabel（16sp SemiBold onSurface；[SettingsCardItem.Custom] 纯文字行
 *   标题例外，取 secondaryGrey 弱化层级），描述走 settingsDescription
 *   （14sp Normal、20sp 行高、secondaryText）；
 * - 间距：行高下限 [rowMinHeight]（默认 60dp = [SheetFieldHeight]）判在**整卡**而非单行，
 *   整卡内容不足时差额均摊给各行撑高（单行卡与旧「行高 min 60」规格渲染一致）；行内
 *   左右 16，内距画在交互区**内侧**；[SettingsCardItem.Custom] 行仍自带该下限兜底；
 * 颜色只取五处主题色：surface（卡底）、onSurface（label）、primary（箭头/开关轨道/行值，
 *   值经 fieldValue 角色）、classppColors.secondaryText（描述）、
 *   classppColors.secondaryGrey（纯文字行标题），深浅模式自动适配。
 *
 * [hazeState] 是选择行弹出菜单的磨砂采样源：交给 [PopupMenuPopup] 做背景模糊，须与菜单背后
 * 那层内容同源——浮层内的卡片传浮层的共享采样源（滚动内容即其采样源），设置页传页面自己的，
 * null（如 @Preview）退化为不透明白卡。其余行型用不到，传了也无副作用。
 */
@Composable
fun SettingsCard(
    items: List<SettingsCardItem>,
    modifier: Modifier = Modifier,
    // 行与行之间的额外间距：默认 0 = 无缝堆叠（各行点区相接），> 0 = 行间留白
    rowSpacing: Dp = 0.dp,
    // 行高下限：默认 60dp（对齐 SheetTextField）；多行卡收紧行距时传小值，
    // 行高回落为自然高（上下内距 14×2 + 内容），不传则其余卡片渲染不变
    rowMinHeight: Dp = SheetFieldHeight,
    // 卡内内容的上下内距：首行之前与末行之后各留一份（默认 0，卡外四周不受影响）
    contentVerticalPadding: Dp = 0.dp,
    // 选择行菜单的磨砂采样源（见 KDoc）：null = 菜单为不透明白卡
    hazeState: HazeState? = null,
) {
    // 行高下限判在整卡而非单行：先按内容自然高度测各行，整卡不足 [rowMinHeight]
    // 时把差额均摊给各行撑高——行变高后按压反馈仍铺满整卡、内容仍居中，单行卡与旧的
    // 「行高 min 60dp」规格渲染一致；内容超出下限后各行保持自然高度
    Layout(
        content = {
            items.forEach { item ->
                when (item) {
                    is SettingsCardItem.Custom -> CustomRow(item, rowMinHeight)
                    is SettingsCardItem.Slider -> SliderRow(item)
                    is SettingsCardItem.SliderGroup -> SliderGroupRow(item)
                    else -> SettingsCardRow(item, hazeState)
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .clip(SettingsCardShape)
            .background(MaterialTheme.colorScheme.surface),
    ) { measurables, constraints ->
        val spacingPx = rowSpacing.roundToPx()
        val minHeightPx = rowMinHeight.roundToPx()
        val insetPx = contentVerticalPadding.roundToPx()
        // 同一个 Measurable 一次布局只允许 measure() 一次：各行自然高度先用 intrinsic 查询
        // （不产生正式测量），再带着算好的下限一次性正式测量
        val width = constraints.maxWidth
        val naturals = measurables.map { it.minIntrinsicHeight(width) }
        val contentHeight = naturals.sum() + spacingPx * (naturals.size - 1).coerceAtLeast(0) +
            insetPx * 2
        // 空卡片不兜底；已达下限或超出的卡片各行维持自然高度
        val cardHeight = if (naturals.isEmpty()) 0 else
            contentHeight.coerceAtLeast(minHeightPx).coerceAtMost(constraints.maxHeight)
        // 整卡不足下限时把差额均摊给各行（余数按行序 +1px 补齐），行高 = max(自然高, 均摊份额)；
        // 行被撑高后按压反馈仍铺满整卡、内容仍居中，单行卡与旧「行高 min 60dp」规格渲染一致
        val extra = cardHeight - contentHeight
        val placeables = measurables.mapIndexed { index, measurable ->
            val share = if (extra > 0) {
                extra / measurables.size + if (index < extra % measurables.size) 1 else 0
            } else 0
            // 自然高可能超过父级给的最大高（如预览页面底部剩余空间不足），min 不得越过 max
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
}

/**
 * 单行渲染：padding 在按压反馈/点区内侧，放大提亮（或开关整行点区）铺满整行宽。
 * 行高随内容，不再自行兜底 60dp——下限由 [SettingsCard] 判在整卡上。
 * [hazeState] 只有选择行用得到（转给菜单弹层做磨砂），其余行型不消费。
 */
@Composable
private fun SettingsCardRow(item: SettingsCardItem, hazeState: HazeState?) {
    // 菜单开合只有选择行用得到，其余行型状态恒为 false 不产生行为
    var menuExpanded by remember { mutableStateOf(false) }
    // 菜单收起时刻：非 focusable 弹层的 ACTION_OUTSIDE 与落回行上的点击是同一个手势，
    // 收起后的这次点击不再把菜单重新打开（见 MenuReopenGuardMillis）
    var menuDismissedAtMillis by remember { mutableLongStateOf(0L) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // 选择行在输入中打开菜单：这次收键盘不回滚内容（被点的行保持原位，菜单锚点不漂）
    val imeVisible = rememberImeVisible()
    val imeRestoreGate = LocalImeRestoreGate.current
    // 行的按压反馈：Nav/Select 用 pressClickable 一行接入（内部自建按压源）；
    // Toggle 要与 toggleable 共享按压源，故手动配对（indication 置 null，涟漪由按压反馈取代）。
    // 浅色模式下白底行加色白提亮无感（白色无法更亮），改用微微压暗表达按压；深色模式仍提亮
    val rowShade = if (MaterialTheme.isDarkTheme) PressShade.Brighten else PressShade.Darken
    val togglePress = remember { MutableInteractionSource() }
    val interactionModifier = when (item) {
        is SettingsCardItem.Nav -> Modifier.pressClickable(
            RectangleShape,
            shade = rowShade,
            onClick = item.onClick,
        )
        is SettingsCardItem.Toggle -> if (item.enabled) Modifier
            // 按压反馈在点区之前：整行放大 + 明暗，行无自有 clip（溢出由卡片圆角壳兜住）
            .pressFeedback(togglePress, RectangleShape, shade = rowShade)
            .toggleable(
                value = item.checked,
                interactionSource = togglePress,
                indication = null,
                role = Role.Switch,
                onValueChange = item.onCheckedChange,
            )
        else Modifier
        is SettingsCardItem.Select -> Modifier.pressClickable(RectangleShape, shade = rowShade) {
            if (menuExpanded) {
                // 菜单开着：再点行 = 收起（退场动画照常播完）。点外部收起可能已在按下时
                // 先走 ACTION_OUTSIDE，这里兜住另一种到达顺序，并记下收起时刻供防重开用
                menuDismissedAtMillis = SystemClock.uptimeMillis()
                menuExpanded = false
            } else if (SystemClock.uptimeMillis() - menuDismissedAtMillis > MenuReopenGuardMillis) {
                // 打开菜单前先收起键盘与焦点：键盘若开着，菜单会被盖住、焦点还留在原输入框上
                // 键盘在场时登记「这次收起不回滚」：被点的行原地不动，向下展开的菜单不会跟着
                // 内容回滚滑走（见 ImeRestoreGate）
                if (imeVisible) imeRestoreGate?.suppressNextRestore()
                focusManager.clearFocus()
                keyboard?.hide()
                menuExpanded = true
            }
        }
        // 自定义行无整行点击（尾部控件自理）
        is SettingsCardItem.Custom -> Modifier
        // 滑块行/滑块组由各自渲染器渲染，走不到这里
        is SettingsCardItem.Slider, is SettingsCardItem.SliderGroup -> Modifier
    }
    val rowValue = when (item) {
        is SettingsCardItem.Nav -> item.value
        is SettingsCardItem.Select -> item.value
        is SettingsCardItem.Toggle,
        is SettingsCardItem.Custom,
        is SettingsCardItem.Slider,
        is SettingsCardItem.SliderGroup,
        -> null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(interactionModifier)
            .padding(horizontal = RowCardPaddingHorizontal, vertical = RowCardPaddingVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsRowText(label = item.label, description = item.description, modifier = Modifier.weight(1f))
        if (rowValue != null) {
            Text(
                text = rowValue,
                style = MaterialTheme.classppTextStyles.fieldValue,
            )
            Spacer(Modifier.width(6.dp))
        }
        when (item) {
            is SettingsCardItem.Nav -> Chevron()
            is SettingsCardItem.Toggle -> ClassppSwitch(
                checked = item.checked,
                // 点击自理（整行 toggleable），开关只做指示；enabled=false 时整行不可点、开关同步置灰
                onCheckedChange = null,
                enabled = item.enabled,
            )
            is SettingsCardItem.Select -> SelectMenu(
                item = item,
                expanded = menuExpanded,
                onDismiss = {
                    // 点外部 / 返回键收起也记时刻：同一手势落回行上的点击不再重开
                    menuDismissedAtMillis = SystemClock.uptimeMillis()
                    menuExpanded = false
                },
                hazeState = hazeState,
            )
            // 自定义行由 CustomRow 渲染，走不到这里
            is SettingsCardItem.Custom -> Unit
            // 滑块行/滑块组由各自渲染器渲染，走不到这里
            is SettingsCardItem.Slider, is SettingsCardItem.SliderGroup -> Unit
        }
    }
}

/** 自定义行渲染：文字块与尾部槽同行居中（trailing = null 时不渲染尾部槽），[SettingsCardItem.Custom.footer] 画在行下、占满行宽；行高下限随 [minHeight] */
@Composable
private fun CustomRow(item: SettingsCardItem.Custom, minHeight: Dp) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .padding(horizontal = RowCardPaddingHorizontal, vertical = RowCardPaddingVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 纯文字行（无尾部控件）：描述不留避让间隙、标题与描述撑满整行可用宽；
            // 标题取 secondaryGrey——纯说明行不是操作入口，弱化层级但保持可读
            SettingsRowText(
                label = item.label,
                description = item.description,
                modifier = Modifier.weight(1f),
                fillWidth = item.trailing == null,
                labelColor = if (item.trailing == null) {
                    MaterialTheme.classppColors.secondaryGrey
                } else {
                    Color.Unspecified
                },
            )
            item.trailing?.invoke(this)
        }
        item.footer?.invoke()
    }
}

/** 滑块行渲染：label + 右上当前值同行，下方整宽滑条；内距同普通行（16/14），label↔滑条 14 复用行纵向内距节奏 */
@Composable
private fun SliderRow(item: SettingsCardItem.Slider) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RowCardPaddingHorizontal, vertical = RowCardPaddingVertical),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsRowText(label = item.label, description = item.description, modifier = Modifier.weight(1f))
            Text(
                text = item.valueText,
                style = MaterialTheme.classppTextStyles.fieldValue,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        ClassppSlider(
            value = item.value,
            onValueChange = item.onValueChange,
            valueRange = item.valueRange,
            defaultValue = item.defaultValue,
            snapThreshold = item.snapThreshold,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 滑块组渲染：label（+ 描述）在上，下方逐行「左当前值 + 右滑条」；
 * 值槽 weight(1f) / 滑条 weight(3f) 使多行滑条左缘对齐，值↔滑条 6 复用 value→尾部间距，
 * 行距 14 复用行纵向内距节奏。
 */
@Composable
private fun SliderGroupRow(item: SettingsCardItem.SliderGroup) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RowCardPaddingHorizontal, vertical = RowCardPaddingVertical),
    ) {
        // 描述独占一行、右侧无控件：不留避让间隙，撑满整行可用宽
        SettingsRowText(label = item.label, description = item.description, fillWidth = true)
        Spacer(modifier = Modifier.height(14.dp))
        item.lines.forEachIndexed { index, line ->
            if (index > 0) Spacer(modifier = Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = line.valueText,
                    style = MaterialTheme.classppTextStyles.fieldValue,
                    modifier = Modifier.width(36.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                ClassppSlider(
                    value = line.value,
                    onValueChange = line.onValueChange,
                    valueRange = line.valueRange,
                    defaultValue = line.defaultValue,
                    snapThreshold = line.snapThreshold,
                    modifier = Modifier.weight(3f),
                )
            }
        }
    }
}

/**
 * label + 可选灰色描述：无描述时单行垂直居中，有描述时整块随行数长高、与尾部控件垂直居中
 * （internal：分组卡同规格复用）。[fillWidth] = true 表示行右侧无控件（纯文字块）：
 * 描述不再预留尾部 10dp 避让间隙，文字撑满整行可用宽；默认 false 维持原避让规格。
 * [labelColor] 覆盖标题颜色（默认 Unspecified = 随 LocalContentColor onSurface）：
 * 纯文字说明行传 classppColors.secondaryGrey 弱化层级（见 [CustomRow]）。
 */
@Composable
internal fun SettingsRowText(
    label: String,
    description: String?,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
    labelColor: Color = Color.Unspecified,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.classppTextStyles.fieldLabel,
            color = labelColor,
        )
        if (description != null) {
            Spacer(modifier = Modifier.size(3.dp))
            Text(
                text = description,
                // 描述可能多行换行：20sp 行高，多行时行间节奏与 14sp 字号匹配
                style = ClassppTextTokens.settingsDescription.copy(
                    color = MaterialTheme.classppColors.secondaryText,
                ),
                // 行右侧有控件时预留 10dp 避让间隙（换行不贴控件）；无控件时撑满可用宽
                modifier = if (fillWidth) Modifier else Modifier.padding(end = 10.dp),
            )
        }
    }
}

/** 尾部蓝色右箭头（20dp，primary 着色）（internal：分组卡 Nav 行同款复用） */
@Composable
internal fun Chevron() {
    Icon(
        painter = painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp),
    )
}

/**
 * 选择行尾部：上下箭头 + 锚在其上的弹出菜单（[PopupMenuPopup] 独立 Popup 窗口，
 * 不受卡片圆角裁剪；PopupMenuCard 白卡 + 柔影，与排序/重复/右键菜单同材质）。
 * 卡片顶边贴行卡顶边（对齐设计稿）。
 * [hazeState] 转给菜单做背景模糊（采样菜单背后那层内容），null = 不透明白卡。
 *
 * 弹层**非 focusable**：输入中的键盘由行点击显式收起一次，弹层不再抢窗口焦点——
 * focusable 弹层会在 IME 收起动画期间再切一次焦点，键盘表现为「闪一下才退出」
 * （README 记录的 popupSelectCard 已知坑）。返回键由 PopupMenuPopup 内的
 * [MenuBackHandler] 兜底；点外部仍由 ACTION_OUTSIDE 收起。代价是非 focusable 窗口
 * 不消费外部触摸：点开着的行时，ACTION_OUTSIDE 收起与落回行上的点击是同一个手势，
 * 行点击靠 [MenuReopenGuardMillis] 忽略收起后那一下，表现为「再点行收起（退场动画
 * 完整播完）、再点一次打开」，而不是收起动画播到一半又反向进场。
 *
 * 点选即收 + [SettingsCardItem.Select.onPick] 常随选值重写宿主内容列（被采样源：
 * 服务商预设回填 / 调课参数行增删 / 重复规则行显隐等）：重写统一延后到退场播完
 * （[PopupActionGate]，机制见 [PopupMenuPopup] KDoc）——防护内建于本组件，
 * 各宿主（AiProfileSheet / AdjustCourseSheet / RepeatSettingsSheet / NotificationScreen
 * 等）零接线；宿主自有的源侧退让（如 AiProfileSheet 的 hazeSuspended）照常生效，
 * 只是随动作延后到退场播完才进入窗口。
 */
@Composable
private fun SelectMenu(
    item: SettingsCardItem.Select,
    expanded: Boolean,
    onDismiss: () -> Unit,
    hazeState: HazeState?,
) {
    val density = LocalDensity.current
    val topAlignLiftPx = with(density) { MenuTopAlignLift.roundToPx() }
    val rewriteGate = rememberPopupActionGate()
    Box {
        Icon(
            painter = painterResource(R.drawable.ic_chevron_up_down),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        PopupMenuPopup(
            expanded = expanded,
            onDismiss = onDismiss,
            onExitFinished = { rewriteGate.flush() },
            sections = listOf(
                PopupMenuSection(
                    items = item.items.map { (id, label) ->
                        PopupMenuItem(
                            label = label,
                            checked = id == item.selectedId,
                            onClick = {
                                onDismiss()
                                if (id != item.selectedId) rewriteGate.defer { item.onPick(id) }
                            },
                        )
                    },
                ),
            ),
            hazeState = hazeState,
            // 卡片左缘对齐图标左缘（DropdownMenu 原位），上移量使卡顶边贴行顶边；
            // 行尾箭头贴屏幕右缘，菜单放不下会被钳到右缘 → 锚点取卡片右上角
            cardPosition = { anchorBounds, _, _ ->
                IntOffset(anchorBounds.left, anchorBounds.bottom - topAlignLiftPx)
            },
            menuGrowth = MenuGrowth.TopRight,
            // 非 focusable：行点击已经显式收过一次键盘，弹层不能再切窗口焦点，
            // 否则 IME 收起动画会被打断/重启，表现为键盘闪烁
            focusable = false,
        )
    }
}
