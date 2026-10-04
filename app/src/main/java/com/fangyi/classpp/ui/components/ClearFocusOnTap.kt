package com.fangyi.classpp.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager

/**
 * 「点空白取消聚焦」：点击容器内**未被子级消费**的位置（卡片间隙、留白、尾部余量）
 * 时清除输入焦点，键盘随之收起；子级可点元素（输入框、卡片、按钮）照常消费点击、不受影响。
 *
 * 这是给 [SheetTextField] / [SheetTextArea] 一族配套的宿主能力：字段只能看到自己
 * 范围内的点击，「聚焦后点空白收起键盘」只能由承载它们的容器实现——挂在这些字段
 * 所在滚动容器的根部（OverlaySheet 的内容列已内置；页面直排的表单需自行挂载）。
 */
@Composable
fun Modifier.clearFocusOnTap(): Modifier {
    val focusManager = LocalFocusManager.current
    // indication = null：纯点击接收器，无涟漪无视觉反馈
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interactionSource,
        indication = null,
    ) {
        focusManager.clearFocus()
    }
}
