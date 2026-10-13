package com.fangyi.classpp.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.theme.SheetCardShape
import com.fangyi.classpp.ui.theme.SheetFieldHeight
import com.fangyi.classpp.ui.theme.classppTextStyles
import dev.chrisbanes.haze.HazeState

/**
 * 底部输入弹卡：单行文本输入的底部弹出卡，框架为 [BottomSelectSheet]——
 * 顶栏（左「取消」+ 居中标题 + 右「确认」蓝色胶囊）、遮罩、进出场两段式动画、
 * 返回键、**键盘让位**（弹起时整卡抬到键盘上方不遮挡、收起后缓回）、**卡片磨砂**
 * （[hazeState] 非空时，同 [BottomSelectSheet] 的背景模糊）全部由框架内置。
 *
 * 输入框为私有化的 [SheetTextField] 副本 [BottomCardTextField]（见其 KDoc 的三处差异：
 * 背景取 background、无 label、占位与值左对齐）。挂载即自动聚焦并拉起键盘；退场
 * （visible = false）时收键盘，键盘随卡片一起回落。键盘「完成」等价点「确认」
 * （同样受 [confirmEnabled] 实时校验闸门）。
 *
 * [value] / [onValueChange] 为受控接口：草稿与校验都由调用方持有（如「重名/空名时
 * 确认钮置灰」），本组件不持有文本状态，挂载/卸载不残留。取消失败均只请求关闭
 * （[onDismiss]），退场播完才回调 [onDismissed]（两段式，同其余 Bottom* 弹卡）。
 */
@Composable
fun BottomTextFieldCard(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    // 确认胶囊可用态：false 时点击/按压失效、胶囊置灰（如空名或重名时）
    confirmEnabled: Boolean = true,
    shadowAlpha: Float = 0.3f,
    // 卡片磨砂的采样源（透传 [BottomSelectSheet]）：宿主把自己浮层的 [HazeState] 交给
    // OverlaySheet 作采样源后，必须把**同一个状态**传进来，卡片才是磨砂玻璃质感；
    // null = 平底卡（与日期轮等其他 Bottom* 弹卡的观感不一致）。宿主漏传是这套接法
    // 最容易踩的坑——组件自身拿不到采样源，无法自建
    hazeState: HazeState? = null,
    onDismissed: () -> Unit = {},
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // 挂载即聚焦弹键盘（输入弹卡的默认交互）；退场时收键盘——键盘收起过程里卡片
    // 经框架的键盘让位一同回落，观感是「随键盘一起走」
    LaunchedEffect(visible) {
        if (visible) {
            focusRequester.requestFocus()
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }
    BottomSelectSheet(
        title = title,
        onDismiss = onDismiss,
        modifier = modifier,
        visible = visible,
        onDismissed = onDismissed,
        confirmEnabled = confirmEnabled,
        shadowAlpha = shadowAlpha,
        hazeState = hazeState,
        // 键盘「完成」与顶栏「确认」同路；确认闸门在框架内已由 confirmEnabled 把住
        onConfirm = onConfirm,
    ) {
        Box(modifier = Modifier
            .fillMaxWidth()
            .padding(start = 15.dp, end = 15.dp)) {
            BottomCardTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                imeAction = ImeAction.Done,
                onImeAction = onConfirm,
                focusRequester = focusRequester,
            )
        }
    }
}

/**
 * 底部输入卡的输入框：[SheetTextField] 的私有副本（仅本文件的输入弹卡使用），
 * 与原件共规格、有三处差异：
 * 1. 背景取 [MaterialTheme.colorScheme.background]（灰底，衬在卡内 surface 白底上）；
 * 2. 无 label 行（去掉左侧标签与间距，输入区占满整卡宽）；
 * 3. 占位与已输入文本**左对齐**（原件为右对齐）。
 *
 * 其余全同：四态（占位灰字 / 蓝色值 / 聚焦 2dp 蓝描边 + 光标 / 报错红描边压过聚焦）、
 * 描边宽度与透明度同走一条 0→1 进度动画、点了就重新 show 一次键盘、值逻辑单行
 * （换行符滤掉）、超宽视觉换行、`imeFieldTracking` 在无宿主能力时空转。
 */
@Composable
private fun BottomCardTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isError: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
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
        animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
        label = "bottomCardFieldStroke",
    )
    val strokeWidth = 2.dp * strokeProgress
    val valueStyle = MaterialTheme.classppTextStyles.fieldValue

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SheetFieldHeight)
            .clip(SheetCardShape)
            // 与 SheetTextField 的唯一底色差异：背景取 background（灰底衬白卡）
            .background(MaterialTheme.colorScheme.background)
            .then(
                // 进度归 0 后干脆不挂 border：0 宽描边会被 Skia 画成 1px 发丝线而非不可见（原件同款）
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
        // 内容垂直居中（原件的外层 Row 用 CenterVertically，Row 改 Box 后同义补齐）：
        // 单行在 SheetFieldHeight 最小高度内居中；多行内容撑高卡片后自然以整块居中
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            // 多行输入会引入换行符；值是逻辑单行，滤掉保证只有视觉换行
            onValueChange = { onValueChange(it.filterNot { ch -> ch == '\n' || ch == '\r' }) },
            textStyle = valueStyle.copy(
                // 空值本身不可见，占位由 decorationBox 负责
                color = if (value.isEmpty()) Color.Transparent else valueStyle.color,
                textAlign = TextAlign.Start,
            ),
            cursorBrush = SolidColor(
                if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            ),
            // 不挂 singleLine：输入区宽度固定（占满卡片内宽），超宽自动换行而非横向滚动
            interactionSource = interactionSource,
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onNext = { onImeAction() },
                onDone = { onImeAction() },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                // 参与宿主的「键盘弹起把输入框滚到键盘上方」（OverlaySheet 的 ImeScrollTracker）：
                // 宿主没这项能力时（如本弹卡自身）此修饰符空转
                .imeFieldTracking()
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    // 未聚焦且为空时显示范例占位；聚焦时光标可见、不显占位
                    if (value.isEmpty() && !focused && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.classppTextStyles.fieldPlaceholder,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}
