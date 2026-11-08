package com.fangyi.classpp.data.sync.webview

/**
 * 教务课表页导入脚本的宿主约定（配合 `WebViewTimetableSpec.importScript`）。
 *
 * `evaluateJavascript` 不等待 Promise，所以采用「页面变量 + 原生轮询」：学校脚本在
 * 教务课表页内完成同源取数后，把原文（JSON 字符串）写回 [RESULT_VAR]；原生侧先调用
 * [resetResult] 清空旧值，再注入脚本，随后周期执行 [readResult]，读到非空串即收口。
 * 该变量的写入/读取两端都只经本对象拼表达式，变量名改动只需改这一处。
 */
object WebViewTimetableJs {

    const val RESULT_VAR: String = "window.__classppTimetable"

    /** 注入脚本前清空旧结果，避免把上一次抓取当成新结果 */
    fun resetResult(): String = "$RESULT_VAR = ''"

    /** 轮询读取表达式：没写回时返回空串 */
    fun readResult(): String = "($RESULT_VAR || '')"
}
