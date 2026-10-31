package com.fangyi.classpp.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.theme.RowCardPaddingHorizontal
import com.fangyi.classpp.ui.theme.RowCardPaddingVertical
import com.fangyi.classpp.ui.theme.SheetCardShape
import com.fangyi.classpp.ui.theme.SheetFieldHeight
import com.fangyi.classpp.ui.theme.classppTextStyles

/**
 * 浮层内输入框（单行便捷入口）：白卡片 + 左侧黑色粗体 label + 右对齐可编辑值。
 * 多行同类输入合并到一张卡时用 [SheetTextFieldGroup]，两者共用同一套卡壳与行实现——
 * 本入口的渲染与改造前逐像素一致（单行卡仍为 60dp 高）。
 *
 * 四态（对齐设计稿）：
 * - 未输入：右侧灰色范例占位文字；
 * - 已输入：右侧蓝色粗体值；
 * - 输入中：2dp 蓝色描边 + 蓝色光标；
 * - 报错（如课程名为空）：2dp 红色描边 + 红色光标，压过聚焦态。
 *
 * 描边的宽度与不透明度同走一条 0→1 进度动画（单动画源，两条曲线严格同步）：
 * 聚焦从 0 生长并淡入到 2dp 全显，失焦从当前值萎缩并淡出（报错同走该动画，仅颜色压过聚焦蓝）。
 * 颜色恒取目标色、浓度由进度控制——若中途切透明，收回途中描边会先隐身，萎缩过程看不见；
 * 进度归 0 后干脆不挂 border（0 宽描边会画成 1px 发丝线）。
 *
 * 除键盘动作外还做两件事：
 * - **点了就重新 show 一次键盘**——Compose 输入框已聚焦时再点不会拉起键盘，
 *   键盘一旦被收起就唤不回来（上机踩过）；
 * - **值区宽度固定 + 超宽自动换行**——值区宽度恒为 label 之外的剩余宽度，
 *   不随内容伸缩；内容超过此宽度走视觉换行而非横向滚动，行卡随行数长高，
 *   文本与白底上下边缘的 14dp 间距不变。值仍是逻辑单行：键盘/粘贴带入的
 *   换行符一律滤掉。
 *
 * 「聚焦后点空白取消聚焦（收起键盘）」由宿主容器提供（`clearFocusOnTap`，
 * OverlaySheet 内容列与设置页根列已内置），字段自身无需处理。
 */
@Composable
fun SheetTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isError: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    // 单行 = 只有一行的多行卡：卡壳、行实现与描边全部共用，行为与改造前一致
    SheetTextFieldGroup(
        fields = listOf(
            SheetTextFieldItem(
                label = label,
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                isError = isError,
                imeAction = imeAction,
                onImeAction = onImeAction,
                focusRequester = focusRequester,
                keyboardType = keyboardType,
                visualTransformation = visualTransformation,
            ),
        ),
        modifier = modifier,
    )
}

/**
 * 多行输入卡中的一行：参数与单行 [SheetTextField] 一一对应。
 * [modifier] 挂在行容器上（观察该行焦点的事件，如 onFocusEvent 失焦提交，挂这里）。
 */
data class SheetTextFieldItem(
    val label: String,
    val value: String,
    val onValueChange: (String) -> Unit,
    val modifier: Modifier = Modifier,
    val placeholder: String = "",
    val isError: Boolean = false,
    val imeAction: ImeAction = ImeAction.Next,
    val onImeAction: () -> Unit = {},
    val focusRequester: FocusRequester? = null,
    /** 密码框等场景：[KeyboardType.Password] + [VisualTransformation] */
    val keyboardType: KeyboardType = KeyboardType.Text,
    val visualTransformation: VisualTransformation = VisualTransformation.None,
)

/**
 * 多行输入卡：一张白卡内纵向堆叠多行 [SheetTextFieldItem]，行与行无缝、无分割线。
 *
 * 规格对齐 SettingsCard：
 * - 高度：[SheetFieldHeight]（60dp）下限判在**整卡**——单行卡仍为 60dp；多行时各行按
 *   自然高（16sp 行高 24 + 上下内距 14×2 ≈ 52dp）堆叠，整卡随行数长高；值超宽换行时
 *   该行照常长高，上下内距不变；
 * - 间距：行内距水平 16 / 垂直 14，行间 0（与 SettingsCard 默认 rowSpacing 相同）；
 * - 描边：整卡 2dp——任一字段聚焦时蓝色、任一字段 [SheetTextFieldItem.isError] 时
 *   红色（红压过蓝），动画与单行版同源。卡级 hasFocus 聚合子节点，行间切换焦点
 *   描边不会闪断。
 *
 * 逐行能力与单行版一致：占位、蓝色值、聚焦光标、点击重新唤起键盘、逻辑单行（过滤换行符）、
 * 逐行 `imeFieldTracking()` 把正在编辑的输入框回报给宿主的键盘让位。
 */
@Composable
fun SheetTextFieldGroup(
    fields: List<SheetTextFieldItem>,
    modifier: Modifier = Modifier,
) {
    if (fields.isEmpty()) return
    // 卡级焦点：任一输入行聚焦即整卡描边；父子结构天然聚合，行间切换不会出现先 false 后 true 的断帧
    var cardFocused by remember { mutableStateOf(false) }
    val anyError = fields.any { it.isError }
    val strokeColor = if (anyError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    // 描边进度：宽度与透明度共用同一条 0→1 动画，聚焦"生长+淡入"、失焦"萎缩+淡出"严格同步
    val strokeProgress by animateFloatAsState(
        targetValue = if (cardFocused || anyError) 1f else 0f,
        animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
        label = "sheetFieldStroke",
    )
    val strokeWidth = 2.dp * strokeProgress

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SheetFieldHeight)
            .clip(SheetCardShape)
            .background(MaterialTheme.colorScheme.surface)
            .then(
                // 进度归 0 后干脆不挂 border：0 宽描边会被 Skia 画成 1px 发丝线而非不可见（上机踩过）
                if (strokeWidth > 0.dp) {
                    Modifier.border(
                        width = strokeWidth,
                        color = strokeColor.copy(alpha = strokeColor.alpha * strokeProgress),
                        shape = SheetCardShape,
                    )
                } else {
                    Modifier
                },
            )
            .onFocusChanged { cardFocused = it.hasFocus },
        // 单行时把 60dp 下限多出的高度上下均摊，内容位置与旧单卡居中逐像素一致；多行无余量时不生效
        verticalArrangement = Arrangement.Center,
    ) {
        fields.forEach { item -> SheetTextFieldRow(item) }
    }
}

/** 单行渲染：行内距画在这里（卡壳不带纵向留白），行与行因此无缝衔接。 */
@Composable
private fun SheetTextFieldRow(item: SheetTextFieldItem) {
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) keyboard?.show()
        }
    }    // 值区文字样式（角色样式）：空值不可见时要取它的色再置透明，故先取值
    val valueStyle = MaterialTheme.classppTextStyles.fieldValue

    Row(
        modifier = item.modifier
            .fillMaxWidth()
            .padding(horizontal = RowCardPaddingHorizontal, vertical = RowCardPaddingVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.label,
            style = MaterialTheme.classppTextStyles.fieldLabel,
        )
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = item.value,
            // 多行输入会引入换行符；值是逻辑单行，滤掉保证只有视觉换行
            onValueChange = { item.onValueChange(it.filterNot { ch -> ch == '\n' || ch == '\r' }) },
            textStyle = valueStyle.copy(
                // 空值本身不可见，占位由 decorationBox 负责
                color = if (item.value.isEmpty()) Color.Transparent else valueStyle.color,
                textAlign = TextAlign.End,
            ),
            cursorBrush = SolidColor(
                if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            ),
            // 不挂 singleLine：值区宽度固定（weight 占满 label 外剩余空间），超宽自动换行而非横向滚动
            interactionSource = interactionSource,
            keyboardOptions = KeyboardOptions(
                keyboardType = item.keyboardType,
                imeAction = item.imeAction,
            ),
            visualTransformation = item.visualTransformation,
            keyboardActions = KeyboardActions(
                onNext = { item.onImeAction() },
                onDone = { item.onImeAction() },
            ),
            modifier = Modifier
                .weight(1f)
                .then(
                    if (item.focusRequester != null) {
                        Modifier.focusRequester(item.focusRequester)
                    } else {
                        Modifier
                    },
                )
                // 参与宿主的「键盘弹起把输入框滚到键盘上方」（OverlaySheet 的 ImeScrollTracker）：
                // 宿主没这项能力时（设置页等直排表单）此修饰符空转
                .imeFieldTracking()
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    // 未聚焦且为空时显示范例占位；聚焦时光标可见、不显占位
                    if (item.value.isEmpty() && !focused && item.placeholder.isNotEmpty()) {
                        Text(
                            text = item.placeholder,
                            style = MaterialTheme.classppTextStyles.fieldPlaceholder,
                            textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}
