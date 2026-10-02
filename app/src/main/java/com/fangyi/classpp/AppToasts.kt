package com.fangyi.classpp

import android.content.Context
import android.widget.Toast

/**
 * app 统一的系统 Toast 出口：新提示出现前先取消上一条——校验类错误连点触发时
 * 只刷新同一条气泡，不会排队逐条刷屏（android.widget.Toast 默认行为是排队）。
 * 全部调用都发生在主线程（Compose 回调 / rememberCoroutineScope），无需额外调度。
 */
object AppToasts {
    private var current: Toast? = null

    fun show(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        current?.cancel()
        current = Toast.makeText(context, message, duration).also { it.show() }
    }
}
