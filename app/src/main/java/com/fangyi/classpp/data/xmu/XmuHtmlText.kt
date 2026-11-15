package com.fangyi.classpp.data.xmu

/**
 * 课程中心作业要求正文（`data.description`）是 HTML，待办备注只能存纯文本。
 *
 * 约束：运行在 JVM 单测环境，不能依赖 `android.text.Html`；实现必须无异常（远端内容不可信），
 * 转换失败最差也只是内容不完整，不能影响同步。处理范围：
 * - `<br>`/`<hr>` 与常见块级标签 → 换行，`<li>` → 「• 」；
 * - `<img>` → 「[图片]」（正文里只有图片时至少留下可见占位）；
 * - 剥离 `<script>`/`<style>`/注释与其余标签，解码常用命名实体与数字实体；
 * - 逐行 trim 并丢弃空行，避免 HTML 缩进在备注里留下大片空白。
 */
object XmuHtmlText {

    fun toPlainText(html: String?): String {
        if (html.isNullOrBlank()) return ""
        var text = html.replace("\r\n", "\n").replace('\r', '\n')
        text = SCRIPT_STYLE.replace(text, " ")
        text = COMMENT.replace(text, " ")
        text = BR.replace(text, "\n")
        text = IMG.replace(text, "[图片]")
        text = LI_OPEN.replace(text, "\n• ")
        text = BLOCK_CLOSE.replace(text, "\n")
        text = CELL_CLOSE.replace(text, " ")
        text = TAG.replace(text, "")
        text = ENTITY.replace(text) { match -> decodeEntity(match) }
        return text.lineSequence()
            .map { it.replace('\u00A0', ' ').trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun decodeEntity(match: MatchResult): String {
        val body = match.groupValues[1]
        val named = NAMED_ENTITIES[body.lowercase()]
        if (named != null) return named
        val codePoint = when {
            body.startsWith("#x", ignoreCase = true) -> body.substring(2).toIntOrNull(16)
            body.startsWith('#') -> body.substring(1).toIntOrNull()
            else -> null
        } ?: return match.value
        if (codePoint !in 0..MAX_CODE_POINT || codePoint in SURROGATE_RANGE) return match.value
        return try {
            String(Character.toChars(codePoint))
        } catch (_: IllegalArgumentException) {
            match.value
        }
    }

    private val SCRIPT_STYLE = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>")
    private val COMMENT = Regex("(?s)<!--.*?-->")
    private val BR = Regex("(?i)<(br|hr)\\b[^>]*>")
    private val IMG = Regex("(?i)<img\\b[^>]*>")
    private val LI_OPEN = Regex("(?i)<li\\b[^>]*>")
    private val BLOCK_CLOSE =
        Regex("(?i)</(p|div|li|ul|ol|h[1-6]|tr|table|section|article|blockquote)\\s*>")
    private val CELL_CLOSE = Regex("(?i)</t[dh]\\s*>")
    private val TAG = Regex("(?s)<[^>]+>")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);")

    private const val MAX_CODE_POINT = 0x10FFFF
    private val SURROGATE_RANGE = 0xD800..0xDFFF

    private val NAMED_ENTITIES = mapOf(
        "nbsp" to " ",
        "ensp" to " ",
        "emsp" to " ",
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "middot" to "·",
        "hellip" to "…",
        "mdash" to "—",
        "ndash" to "–",
        "ldquo" to "“",
        "rdquo" to "”",
        "lsquo" to "‘",
        "rsquo" to "’",
        "copy" to "©",
        "reg" to "®",
        "times" to "×",
        "divide" to "÷",
    )
}
