package com.fangyi.classpp.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * 返回键优先收键盘：键盘在场时只收起 IME，键盘已收起时才执行 [onBack]。
 *
 * 这是表单浮层「返回先收键盘」的统一约定（原以 `imeInsets.getBottom(density) > 0` 判断的
 * 五个调用点已收敛到这里）。判断改用 [rememberImeVisible]（`isImeVisible`）：导航栏的
 * 输入法切换条、状态栏的光标控制条在部分机型上也会计入 `ime`，按高度判断会误判。
 */
@Composable
internal fun BackHandlerHidesKeyboard(
    enabled: Boolean = true,
    onBack: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = rememberImeVisible()
    BackHandler(enabled = enabled) {
        if (imeVisible) keyboard?.hide() else onBack()
    }
}
