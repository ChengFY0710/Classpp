package com.fangyi.classpp.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.theme.SheetCardShape
import com.fangyi.classpp.ui.theme.classppTextStyles

/**
 * 浮层「大量文字输入框」（备注等长文本用）：一张白卡，文本顶左对齐、可换行。
 *
 * 与 [SheetTextField] 同族，白卡配方完全一致：同样的 [SheetCardShape]、
 * 同样的内距 [SheetCardPadding]（文本距白底四边 16 / 14）、同样的描边动画
 * （宽度与不透明度共走一条 0→1 进度，聚焦"生长+淡入"到 2dp 全显、失焦
 * "萎缩+淡出"，进度归 0 不挂 border）。
 *
 * 与 [SheetTextField] 的关键差异：
 * - 多行：允许换行，键盘回车即插入换行（不设 imeAction，故无 Next/Done 动作）；
 * - 无 label，占位顶左对齐；未聚焦且为空时显示灰色占位，聚焦时光标可见、不显占位；
 * - 空态最小 3 行高（minLines），内容超出后卡片随行数长高；
 * - 已输入文字与 [SheetTextField] 的值同款（fieldValue 角色样式：16sp Medium primary 蓝）。
 *
 * 同样做「点了就重新 show 一次键盘」：Compose 输入框已聚焦时再点不会拉起键盘，
 * 键盘一旦被收起就唤不回来（上机踩过）。
 *
 * 「聚焦后点空白取消聚焦（收起键盘）」与 [SheetTextField] 同款，由宿主容器的
 * `clearFocusOnTap` 提供（OverlaySheet 内容列已内置），字段自身无需处理。
 */
@Composable
fun SheetTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isError: Boolean = false,
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
    // 描边进度：宽度与透明度共用同一条 0→1 动画，与 SheetTextField 严格同款
    val strokeProgress by animateFloatAsState(
        targetValue = if (focused || isError) 1f else 0f,
        animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
        label = "sheetTextAreaStroke",
    )
    val strokeWidth = 2.dp * strokeProgress
    // 值区文字样式（角色样式）：空值不可见时要取它的色再置透明，故先取值（同 SheetTextField）
    val valueStyle = MaterialTheme.classppTextStyles.fieldValue

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = valueStyle.copy(
            // 空值本身不可见，占位由 decorationBox 负责
            color = if (value.isEmpty()) Color.Transparent else valueStyle.color,
        ),
        cursorBrush = SolidColor(
            if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        ),
        minLines = 3,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
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
            .padding(SheetCardPadding)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // 参与宿主的「键盘弹起把输入框滚到键盘上方」（OverlaySheet 的 ImeScrollTracker）：
            // 宿主没这项能力时此修饰符空转
            .imeFieldTracking()
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { innerTextField ->
            Box(modifier = Modifier.fillMaxWidth()) {
                // 未聚焦且为空时显示占位；聚焦时光标可见、不显占位
                if (value.isEmpty() && !focused && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.classppTextStyles.fieldPlaceholder,
                    )
                }
                innerTextField()
            }
        },
    )
}
