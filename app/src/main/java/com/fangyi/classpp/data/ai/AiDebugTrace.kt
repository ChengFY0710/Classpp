package com.fangyi.classpp.data.ai

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * 一次工具调用的规模与耗时。**不含**参数与结果正文（红线：不落待办标题、备注、课表内容）。
 */
data class AiToolTrace(
    val name: String,
    val ok: Boolean,
    /** 失败时的 `ok:false` 错误码（invalid_arguments / not_found / limit_exceeded …），成功为 null */
    val errorCode: String?,
    val resultChars: Int,
    val elapsedMillis: Long,
)

/**
 * 一个用户轮次（一次发送）的诊断记录：上下文规模、首字延迟、工具调用与预算。
 *
 * 「工具误用率」= 调用里 [AiToolTrace.ok] 为 false 的占比（invalid_arguments / not_found /
 * unknown_tool 这些正是模型用错工具或编错 id 的信号）。
 */
data class AiTurnTrace(
    val atMillis: Long,
    /**
     * system prompt + 历史 + 本轮 user 的总字符数。**不含** tools schema——那部分只随
     * 工具数量变化，与「上下文精简」无关，放在一起会让前后对比失真。
     */
    val promptChars: Int,
    /** 按 [AiDebugTrace.estimateTokens] 的粗估 token（中日韩字符 ≈ 1 token、其余 ≈ 0.25；同样不含 schema） */
    val promptTokens: Int,
    /** 本次请求的消息条数（system + 历史 + 本轮） */
    val turnCount: Int,
    val toolsAttached: Boolean,
    /** 首个增量（正文或思考）的耗时；null = 本轮没有任何增量（失败/取消） */
    val firstDeltaMillis: Long?,
    /** 实际发出的请求次数（工具循环的轮数） */
    val requestCount: Int,
    val calls: List<AiToolTrace>,
    /** 工具循环因预算停止 */
    val capped: Boolean,
    /** 非空 = 本轮以该错误结束（AiError 名 / cancelled） */
    val error: String?,
) {
    val toolCallCount: Int get() = calls.size
    val toolErrorCount: Int get() = calls.count { !it.ok }
}

/**
 * AI 工具链路诊断（设计稿 §13 P3「观察 token、首字延迟、工具误用率」）。
 *
 * - **仅 debug 构建**：release（非 debuggable）不记录、不打印，也不分配任何数据；
 * - 只记规模与耗时，不记正文、工具参数、待办标题；
 * - 内存环形缓冲（最近 [MAX_TRACES] 轮）+ logcat 一行摘要，供 debug 构建的
 *   AI 设置页「调试」入口查看；
 * - 未 [init]（JVM 单测默认）→ 静默；[Log] 在 JVM 单测会抛 Stub，runCatching 兜底。
 */
internal object AiDebugTrace {
    private const val TAG = "AiToolTrace"

    /** 环形缓冲长度：够看最近几次对话，又不至于占内存 */
    const val MAX_TRACES = 20

    private val traces = ArrayDeque<AiTurnTrace>()

    @Volatile
    var enabled: Boolean = false
        private set

    fun init(context: Context) {
        enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /** 装配/单测直接给判定：true = 记录并打印 */
    fun init(debuggable: Boolean) {
        enabled = debuggable
    }

    fun record(trace: AiTurnTrace) {
        if (!enabled) return
        synchronized(traces) {
            traces.addLast(trace)
            while (traces.size > MAX_TRACES) traces.removeFirst()
        }
        runCatching {
            Log.d(
                TAG,
                "turn chars=${trace.promptChars} tokens≈${trace.promptTokens} turns=${trace.turnCount} " +
                    "tools=${trace.toolsAttached} firstDelta=${trace.firstDeltaMillis ?: "-"}ms " +
                    "requests=${trace.requestCount} calls=${trace.toolCallCount} " +
                    "errors=${trace.toolErrorCount} capped=${trace.capped} err=${trace.error ?: "-"}",
            )
        }
    }

    /** 最近一轮（新→旧）：调试面板直接渲染 */
    fun recent(): List<AiTurnTrace> = synchronized(traces) { traces.toList().asReversed() }

    fun reset() {
        synchronized(traces) { traces.clear() }
    }

    /**
     * 粗估 token：中日韩字符记 1、其余字符每 4 个记 1。
     *
     * 只用于看趋势（精简前后、换模型前后），不作为计费口径——各服务商 tokenizer 不同，
     * 真实用量以账单为准。
     */
    fun estimateTokens(text: String): Int {
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (isCjk(ch)) cjk++ else other++
        }
        return cjk + other / 4
    }

    private fun isCjk(ch: Char): Boolean = when (ch.code) {
        in 0x3000..0x303F -> true // CJK 标点
        in 0x3040..0x30FF -> true // 假名
        in 0x3400..0x4DBF -> true // 扩展 A
        in 0x4E00..0x9FFF -> true // 基本区
        in 0xFF00..0xFFEF -> true // 全角
        else -> false
    }
}
