package com.fangyi.classpp.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ImeRestoreGate]：菜单类弹层在输入中打开时登记「这次收起不回滚」。登记只能被消费一次，
 * 避免一次选择行点击吞掉之后多次键盘收起的正常回滚。
 */
class ImeRestoreGateTest {

    @Test
    fun `no registration does not suppress restore`() {
        val gate = ImeRestoreGate()
        assertFalse(gate.consumeSuppressed())
    }

    @Test
    fun `registration suppresses exactly one restore`() {
        val gate = ImeRestoreGate()
        gate.suppressNextRestore()
        assertTrue(gate.consumeSuppressed())
        assertFalse("登记消费后不应继续影响下一次收起", gate.consumeSuppressed())
    }
}
