package com.fangyi.classpp.data.model

/**
 * 大课起止时间文本（`"8:00"` 形态，与现有 UI 显示串一致）的解析/格式化工具。
 *
 * - 合法格式：`^([01]?\d|2[0-3]):[0-5]\d$`（接受 `8:00` 与 `08:00`）
 * - 归一化：一律输出**无前导零小时**（`8:00`）
 * - 比较用分钟数（`hour * 60 + minute`），本对象不承担显示逻辑
 */
object TimeText {

    fun isValid(text: String): Boolean = PATTERN.matches(text)

    /** 合法返回当天第几分钟（0..1439），非法返回 null */
    fun parseMinutes(text: String): Int? {
        if (!isValid(text)) return null
        val parts = text.split(':')
        val hour = parts[0].toInt()
        val minute = parts[1].toInt()
        return hour * 60 + minute
    }

    /** 分钟数（0..1439）→ 归一化文本；越界抛 [IllegalArgumentException] */
    fun format(minutes: Int): String {
        require(minutes in 0..1439) { "minutes out of range: $minutes" }
        return "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"
    }

    /** 合法则归一化（`08:00` → `8:00`），非法返回 null */
    fun normalize(text: String): String? = parseMinutes(text)?.let { format(it) }

    private val PATTERN = Regex("""([01]?\d|2[0-3]):[0-5]\d""")
}
