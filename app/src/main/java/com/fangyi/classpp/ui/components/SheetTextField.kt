package com.fangyi.classpp.ui.components

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.theme.SheetCardShape
import com.fangyi.classpp.ui.theme.classppTextStyles

/**
 * 浮层「行卡」统一行高：上下内距 14×2 + 文字行高 24 = 52dp。
 * 输入框与浮层选择卡片都按它取 `heightIn(min)`，保证两张卡永远等高；
 * 系统字体放大时两者行高同步增长，等高关系依旧成立。
 * 例外：输入值超宽自动换行时输入行卡按行数长高（上下内距不变），选择卡维持此高度。
 */
val SheetFieldHeight = 60.dp

/**
 * 浮层内全新输入框：白卡片 + 左侧黑色粗体 label + 右对齐可编辑值。
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
) {
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) keyboard?.show()
        }
    }
    val strokeColor = if (isError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    // 描边进度：宽度与透明度共用同一条 0→1 动画，聚焦"生长+淡入"、失焦"萎缩+淡出"严格同步
    val strokeProgress by animateFloatAsState(
        targetValue = if (focused || isError) 1f else 0f,
        animationSpec = tween(durationMillis = 150, easing = LinearOutSlowInEasing),
        label = "sheetFieldStroke",
    )
    val strokeWidth = 2.dp * strokeProgress
    // 值区文字样式（角色样式）：空值不可见时要取它的色再置透明，故先取值
    val valueStyle = MaterialTheme.classppTextStyles.fieldValue

    Row(
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.classppTextStyles.fieldLabel,
        )
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = value,
            // 多行输入会引入换行符；值是逻辑单行，滤掉保证只有视觉换行
            onValueChange = { onValueChange(it.filterNot { ch -> ch == '\n' || ch == '\r' }) },
            textStyle = valueStyle.copy(
                // 空值本身不可见，占位由 decorationBox 负责
                color = if (value.isEmpty()) Color.Transparent else valueStyle.color,
                textAlign = TextAlign.End,
            ),
            cursorBrush = SolidColor(
                if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            ),
            // 不挂 singleLine：值区宽度固定（weight 占满 label 外剩余空间），超宽自动换行而非横向滚动
            interactionSource = interactionSource,
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onNext = { onImeAction() },
                onDone = { onImeAction() },
            ),
            modifier = Modifier
                .weight(1f)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    // 未聚焦且为空时显示范例占位；聚焦时光标可见、不显占位
                    if (value.isEmpty() && !focused && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
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
