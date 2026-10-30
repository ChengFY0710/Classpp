package com.fangyi.classpp.data.wakeup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeupShareTextTest {

    @Test
    fun `extracts key from full share text`() {
        val text = "【Wakeup课表】张三的课表，分享口令为「AbCdEf123456」，快来一起使用吧！"
        assertEquals("AbCdEf123456", WakeupShareText.extractShareKey(text))
    }

    @Test
    fun `extracts key with labelled form variants`() {
        assertEquals("abc12345", WakeupShareText.extractShareKey("分享口令为abc12345"))
        assertEquals("abc12345", WakeupShareText.extractShareKey("分享口令: abc12345"))
        assertEquals("abc12345", WakeupShareText.extractShareKey("分享口令：abc12345"))
        assertEquals("abc12345", WakeupShareText.extractShareKey("分享口令 \"abc12345\""))
    }

    @Test
    fun `extracts bare key`() {
        assertEquals("AbCdEf123456", WakeupShareText.extractShareKey("AbCdEf123456"))
        assertEquals("AbCdEf123456", WakeupShareText.extractShareKey("  AbCdEf123456  "))
    }

    @Test
    fun `extracts quoted key without label`() {
        assertEquals("xyz_98765", WakeupShareText.extractShareKey("口令「xyz_98765」见帖"))
    }

    @Test
    fun `falls back to last candidate token`() {
        // 无标签、无引号：取文本中最后一段 8~200 位字母数字串
        assertEquals("lasttoken1", WakeupShareText.extractShareKey("firsttoken1 中间文字 lasttoken1"))
    }

    @Test
    fun `rejects garbage and short keys`() {
        assertEquals("", WakeupShareText.extractShareKey(""))
        assertEquals("", WakeupShareText.extractShareKey("   "))
        // 标签后不足 8 位、又无其他候选 → 拒绝（垃圾输入不进 WebView）
        assertEquals("", WakeupShareText.extractShareKey("分享口令为abc"))
        assertEquals("", WakeupShareText.extractShareKey("完全没有任何口令的中文文案"))
    }

    @Test
    fun `validates key charset and length`() {
        assertTrue(WakeupShareText.isValidKey("abc12345"))
        assertTrue(WakeupShareText.isValidKey("a".repeat(200)))
        assertTrue(WakeupShareText.isValidKey("has_underscore-and-dash"))
        assertFalse(WakeupShareText.isValidKey("short7_"))
        assertFalse(WakeupShareText.isValidKey("a".repeat(201)))
        assertFalse(WakeupShareText.isValidKey("有空格 abc12345"))
        assertFalse(WakeupShareText.isValidKey(""))
    }

    @Test
    fun `extracts schedule name from share text`() {
        val text = "【Wakeup课表】张三的课表，分享口令为「AbCdEf123456」"
        assertEquals("张三的课表", WakeupShareText.extractScheduleName(text))
    }

    @Test
    fun `schedule name falls back to empty when absent`() {
        assertEquals("", WakeupShareText.extractScheduleName("分享口令为「AbCdEf123456」"))
    }
}
