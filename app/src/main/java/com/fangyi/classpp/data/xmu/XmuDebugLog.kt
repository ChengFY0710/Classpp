package com.fangyi.classpp.data.xmu

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * 课程中心链路诊断日志（仅 debug 构建）。
 *
 * 红线（指导 §8.1）：只记阶段名、HTTP 状态码、host/path、错误分类、条数；
 * **绝不记**学号、密码、cookie/token 值、URL 查询串、待办标题。
 *
 * - 未 [init]（JVM 单测）→ 静默；
 * - release（非 debuggable）→ 静默；
 * - [Log] 在 JVM 单测会抛 Stub，runCatching 兜底。
 */
internal object XmuDebugLog {
    private const val TAG = "XmuSync"

    @Volatile
    private var enabled: Boolean = false

    fun init(context: Context) {
        enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    fun d(message: String) {
        if (enabled) runCatching { Log.d(TAG, message) }
    }
}
