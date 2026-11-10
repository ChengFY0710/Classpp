package com.fangyi.classpp.ui.components

import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.ui.text.input.ImeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [sheetTextFieldKeyboardActions]：不传回调时必须保持 `onNext` / `onDone` 为 null，让 Compose
 * 走默认动作（Next 跳焦点、Done 收起键盘）；只有显式传回调时才覆盖。
 *
 * 这是「登录用户名按下一项无反应 / AI 设置按完成不收键盘」的回归测试：恒定塞空 lambda
 * 会把两个默认动作都吞掉。
 */
class SheetTextFieldKeyboardActionsTest {

    private val scope = object : KeyboardActionScope {
        override fun defaultKeyboardAction(imeAction: ImeAction) = Unit
    }

    @Test
    fun `omitted callback keeps compose defaults`() {
        val actions = sheetTextFieldKeyboardActions(null)
        assertNull("onNext 必须为 null，Compose 才会把焦点移到下一项", actions.onNext)
        assertNull("onDone 必须为 null，Compose 才会收起键盘", actions.onDone)
    }

    @Test
    fun `provided callback overrides both ime actions`() {
        var calls = 0
        val actions = sheetTextFieldKeyboardActions { calls++ }
        assertNotNull(actions.onNext)
        assertNotNull(actions.onDone)
        actions.onNext?.invoke(scope)
        actions.onDone?.invoke(scope)
        assertEquals(2, calls)
    }
}
