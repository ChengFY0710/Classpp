package com.fangyi.classpp.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.SoftwareKeyboardController

/**
 * 输入框「整行热区」：挂在输入框所在行 / 卡壳的容器上，且必须位于 `padding` **之前**，
 * 让图标、label、左右内距与行内空白点下去都聚焦输入框并拉起键盘。
 *
 * 用 pointerInput 而非 clickable——clickable 会把本行合并成单个可点语义节点，
 * 吞掉内部输入框的可编辑语义（TalkBack 只读得到一个按钮）。文本区与行内按钮的点击
 * 被子级先消费，不会走到这里；子级未消费的按压（含拖动）由父滚动容器接手。
 *
 * [focusRequester] 必须已挂在本行输入框上；[keyboard] 为宿主的作用域键盘控制器，
 * null（如 @Preview）时只聚焦不拉键盘。
 */
internal fun Modifier.fieldTapHotZone(
    focusRequester: FocusRequester,
    keyboard: SoftwareKeyboardController?,
): Modifier = pointerInput(focusRequester) {
    detectTapGestures {
        focusRequester.requestFocus()
        keyboard?.show()
    }
}
