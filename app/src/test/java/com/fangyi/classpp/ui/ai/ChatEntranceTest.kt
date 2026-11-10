package com.fangyi.classpp.ui.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发送追加判定：只有「原列表原样延长 1–2 条」才算用户发送。
 *
 * 这条判定是气泡落位与滚动接住的唯一闸门——切对话、删对话、流式逐字、历史恢复都必须
 * 被挡在外面，否则整列会莫名其妙地播入场。
 */
class ChatEntranceTest {

    @Test
    fun `appending one message is a pure append`() {
        assertTrue(isPureAppend(listOf("a", "b"), listOf("a", "b", "c")))
    }

    @Test
    fun `appending user message and placeholder together is a pure append`() {
        assertTrue(isPureAppend(listOf("a", "b"), listOf("a", "b", "c", "d")))
    }

    @Test
    fun `appending to an empty conversation is a pure append`() {
        assertTrue(isPureAppend(emptyList(), listOf("c", "d")))
    }

    @Test
    fun `switching conversation is not a pure append`() {
        assertFalse(isPureAppend(listOf("a", "b"), listOf("x", "y")))
        // 条数恰好增长但前缀不同，同样不是追加
        assertFalse(isPureAppend(listOf("a", "b"), listOf("a", "x", "y")))
    }

    @Test
    fun `shrinking list is not a pure append`() {
        assertFalse(isPureAppend(listOf("a", "b", "c"), listOf("a", "b")))
    }

    @Test
    fun `unchanged list is not a pure append`() {
        assertFalse(isPureAppend(listOf("a", "b"), listOf("a", "b")))
    }

    @Test
    fun `growing by more than two is not a pure append`() {
        assertFalse(isPureAppend(listOf("a"), listOf("a", "b", "c", "d")))
    }

    @Test
    fun `restoring history from empty is a pure append only within two items`() {
        // 历史恢复：一次涌入整列，不算发送
        assertFalse(isPureAppend(emptyList(), listOf("a", "b", "c")))
    }
}
