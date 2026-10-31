package com.fangyi.classpp.data

import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * WebView 安全销毁：加载中调用 stopLoading/destroy 时，Chromium 早已向主线程队列
 * 投递了 loading-state 等回调消息；同一帧里同步 destroy() 会让这些消息在销毁后才
 * 派发，触发系统警告「Application attempted to call on a destroyed WebView」。
 * 这里把真正的 destroy 推迟一轮消息循环，让已排队回调在实例仍存活时先跑完
 * （主线程队列 FIFO：晚于它们入队的 destroy 一定最后执行）。
 *
 * 用独立 Handler 而非 View.post：销毁常发生在视图已 detach 之后（浮层关闭、协程
 * finally 兜底），View.post 在 detach 态会进 run queue、到下次 attach 才执行，
 * 永不复挂载的 WebView 就此泄漏。
 */
private val destroyHandler by lazy { Handler(Looper.getMainLooper()) }

/** stopLoading 掐掉在途加载后，把 destroy() 推迟到下一轮主线程消息循环 */
internal fun WebView.destroySafely() {
    stopLoading()
    destroyHandler.post { destroy() }
}
