package com.fangyi.classpp.data.model

/**
 * 大课起止时间文本（`"8:00"` 形态，与现有 UI 显示串一致）的解析/格式化工具。
 *
 * - 合法格式：`^([01]?\d|2[0-3]):[0-5]\d$`（接受 `8:00` 与 `08:00`）
 * - 归一化：一律输出**无前导零小时**（`8:00`），存储/校验/课程卡显示沿用此形态
 * - 需要前导零的显示走 [formatDisplay]（`8:00` → `08:00`），目前仅设置页时间胶囊使用
 * - 比较用分钟数（`hour * 60 + minute`）
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

    /** 显示用：小时补前导零（`8:00` → `08:00`）；非法文本原样返回 */
    fun formatDisplay(text: String): String {
        val minutes = parseMinutes(text) ?: return text
        return "${(minutes / 60).toString().padStart(2, '0')}" +
            ":${(minutes % 60).toString().padStart(2, '0')}"
    }

    private val PATTERN = Regex("""([01]?\d|2[0-3]):[0-5]\d""")
}
