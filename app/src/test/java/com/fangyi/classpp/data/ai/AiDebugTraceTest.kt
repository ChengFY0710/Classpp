package com.fangyi.classpp.data.ai

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 轮次诊断缓冲（P3 观察口径）：只记规模与耗时、只在 debug 记录、环形覆盖。
 *
 * 单例状态必须复位：这些用例与其它 AI 用例共享进程，残留记录会让「数量」断言飘。
 */
class AiDebugTraceTest {

    @Before
    fun setUp() {
        AiDebugTrace.reset()
        AiDebugTrace.init(debuggable = true)
    }

    @After
    fun tearDown() {
        AiDebugTrace.reset()
        AiDebugTrace.init(debuggable = false)
    }

    private fun trace(at: Long, calls: List<AiToolTrace> = emptyList()) = AiTurnTrace(
        atMillis = at,
        promptChars = 100,
        promptTokens = 60,
        turnCount = 3,
        toolsAttached = true,
        firstDeltaMillis = 800,
        requestCount = 2,
        calls = calls,
        capped = false,
        error = null,
    )

    @Test
    fun `recent returns newest first and caps the buffer`() {
        repeat(AiDebugTrace.MAX_TRACES + 3) { index ->
            AiDebugTrace.record(trace(at = index.toLong()))
        }

        val recent = AiDebugTrace.recent()

        assertEquals(AiDebugTrace.MAX_TRACES, recent.size)
        // 新→旧：最后写入的那轮在最前
        assertEquals((AiDebugTrace.MAX_TRACES + 2).toLong(), recent.first().atMillis)
        assertEquals(3L, recent.last().atMillis)
    }

    @Test
    fun `disabled trace records nothing`() {
        AiDebugTrace.init(debuggable = false)

        AiDebugTrace.record(trace(at = 1))

        assertFalse(AiDebugTrace.enabled)
        assertTrue(AiDebugTrace.recent().isEmpty())
    }

    @Test
    fun `reset clears buffered traces`() {
        AiDebugTrace.record(trace(at = 1))

        AiDebugTrace.reset()

        assertTrue(AiDebugTrace.recent().isEmpty())
    }

    @Test
    fun `turn trace counts tool errors as misuse signal`() {
        val trace = trace(
            at = 1,
            calls = listOf(
                AiToolTrace("search_todos", true, null, 412, 12),
                AiToolTrace("get_todo", false, "not_found", 96, 3),
                AiToolTrace("update_todos", false, "invalid_arguments", 120, 4),
            ),
        )

        assertEquals(3, trace.toolCallCount)
        assertEquals(2, trace.toolErrorCount)
    }

    @Test
    fun `estimateTokens treats cjk as one and ascii as a quarter`() {
        // 6 个汉字 + 7 个 ASCII（7/4 = 1）
        assertEquals(7, AiDebugTrace.estimateTokens("今天有什么课ab cdef"))
        assertEquals(0, AiDebugTrace.estimateTokens(""))
        assertTrue(AiDebugTrace.estimateTokens("今天有什么课") > AiDebugTrace.estimateTokens("abcdefgh"))
    }
}
