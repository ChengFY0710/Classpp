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

    /**
     * 适配脚本常把唯一一句原因放进 alert（"未检测到课表"）：命中失败关键词必须立刻收口，
     * 否则脚本 alert 完直接 return，原生侧要白等到 60s 超时，脚本文案也一起丢掉。
     * 具体 JS 运行时行为由 WebView 集成验证，这里只钉住垫片的结构约定。
     */
    @Test
    fun `fatal alert ends the import and the script message survives`() {
        val built = ShiguangScriptShim.build("adapterEntry();")

        assertTrue(built.contains("if (isFatalToast(text)) finish(false, text, 'SCRIPT_ERROR');"))
        // 脚本没给 error 时，失败信封回落到前面弹过的提示
        assertTrue(built.contains("error: String(error || state.lastError || '')"))
    }
}
