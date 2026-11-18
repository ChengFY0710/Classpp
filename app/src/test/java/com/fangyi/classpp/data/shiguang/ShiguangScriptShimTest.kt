package com.fangyi.classpp.data.shiguang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 拾光脚本垫片的结构约定（JS 运行时行为由 WebView 集成验证） */
class ShiguangScriptShimTest {

    @Test
    fun `shim precedes adapter script and defines the bridge surface`() {
        val built = ShiguangScriptShim.build("adapterEntry();")

        assertTrue(built.startsWith("(function()"))
        assertTrue(built.trimEnd().endsWith("adapterEntry();"))
        assertTrue(built.indexOf("window.shiguangBridge") < built.indexOf("adapterEntry();"))
        assertTrue(built.contains("window.shiguangBridgePromise"))
        assertTrue(built.contains("window.AndroidBridgePromise"))
        assertTrue(built.contains("saveImportedCourses"))
        assertTrue(built.contains("savePresetTimeSlots"))
        assertTrue(built.contains("saveCourseConfig"))
        assertTrue(built.contains("saveComboSchedule"))
        assertTrue(built.contains("notifyTaskCompletion"))
        // 重新注入时用代号丢弃旧尝试的迟到结果
        assertTrue(built.contains("__classppShiguangGeneration"))
        // 部分脚本读取当前课表 id
        assertTrue(built.contains("window.currentTableId = 'classpp'"))
    }

    @Test
    fun `result variable helpers share one variable`() {
        assertEquals("window.__classppShiguang", ShiguangScriptShim.RESULT_VAR)
        assertEquals("window.__classppShiguang = '';", ShiguangScriptShim.resetJs())
        assertEquals("(window.__classppShiguang || '')", ShiguangScriptShim.readJs())
    }
}
