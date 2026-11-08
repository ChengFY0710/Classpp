package com.fangyi.classpp.data.sync.remote

import com.fangyi.classpp.data.sync.webview.WebViewTimetableJs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 固定脚本生成器：携带声明配置、写回约定变量、无远端可执行代码注入。 */
class RemoteTimetableScriptTest {

    @Test
    fun `script carries declared endpoints and result contract`() {
        val script = RemoteTimetableScript.script(demoTimetable())

        assertTrue(script.contains(WebViewTimetableJs.RESULT_VAR))
        assertTrue(script.contains("/wdkcb/querySchedule.do"))
        assertTrue(script.contains("/wdkcb/querySlots.do"))
        assertTrue(script.contains("semesterCode"))
        assertTrue(script.contains("JSON.parse"))
        assertTrue(script.contains("credentials: 'include'"))
        assertFalse(script.contains("addJavascriptInterface"))
    }

    @Test
    fun `single quoted config escapes backslash and quote`() {
        assertEquals("a\\'b\\\\c", RemoteTimetableScript.jsSingleQuoted("a'b\\c"))
    }
}
