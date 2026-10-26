package com.fangyi.classpp.data.wakeup

/**
 * Wakeup 分享文案解析（纯函数，无依赖）。
 *
 * 完整分享文案形如
 * `【Wakeup课表】张三的课表，分享口令为「AbCdEf123456」…`；用户也可能只粘贴裸口令。
 * 提取不到合法口令时返回空串，由调用方拦截——垃圾输入不进无头 WebView。
 */
object WakeupShareText {

    /** 口令合法形态：字母/数字/下划线/连字符，长度 8..200（与 wake_up.js validateKey 一致） */
    private val KEY_PATTERN = Regex("^[A-Za-z0-9_-]{8,200}$")

    /** ①「分享口令」标签后取第一段（容忍 为/:/：/空白/中英引号） */
    private val LABELLED = Regex(
        "分享口令(?:为|:|：)?\\s*[「“\"']?\\s*([A-Za-z0-9_-]{1,200})\\s*[」”\"']?",
    )

    /** ② 被引号包裹的候选串 */
    private val QUOTED = Regex("[「“\"']\\s*([A-Za-z0-9_-]{8,200})\\s*[」”\"']")

    /** ③ 兜底候选：文本中任一 8~200 位字母数字串 */
    private val CANDIDATE = Regex("[A-Za-z0-9_-]{8,200}")

    /** 分享文案头「XXX的课表」的主人名（用于给新课表起名） */
    private val OWNER = Regex("】\\s*(.{1,30}?)的课表")

    /** [text] 是否是可直接使用的口令 */
    fun isValidKey(text: String): Boolean = KEY_PATTERN.matches(text)

    /**
     * 三级兜底提取口令：「分享口令」标签 → 引号包裹 → 最后一段候选串。
     * 提取不到或候选不足 8 位返回空串。
     */
    fun extractShareKey(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        // 标签命中但不足 8 位（如截图截断）时继续往下兜底，而非直接失败
        LABELLED.find(trimmed)?.groupValues?.get(1)?.takeIf { it.length >= 8 }?.let { return it }
        QUOTED.find(trimmed)?.groupValues?.get(1)?.let { return it }
        return CANDIDATE.findAll(trimmed).map { it.value }.lastOrNull() ?: ""
    }

    /** 从分享文案提取课表名（「XXX的课表」），提取不到返回空串（调用方用本地化缺省名） */
    fun extractScheduleName(text: String): String {
        val owner = OWNER.find(text.trim())?.groupValues?.get(1)?.trim().orEmpty()
        return if (owner.isEmpty()) "" else "${owner}的课表"
    }
}
