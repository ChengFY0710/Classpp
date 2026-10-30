package com.fangyi.classpp.data.wakeup

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Wakeup 口令导入执行器：无头 WebView 跑云端适配脚本（wake_up.js），把脚本弹窗交互
 * 「程序化」后收口成 [WakeupSharePayload]。移植自指南（NexioSchedule 的 ScheduleImport），
 * 结合真实脚本源码有三处关键契约：
 *
 * 1. **baseURL 决定同源**：必须以 `https://api.wakeup.fun/` 为 baseURL 加载空白页，
 *    脚本里的 fetch 才是同源请求（无 CORS 预检）；
 * 2. **注入严格串行**：页面加载完成 → Promise 桥 → bootstrap（覆写 showPrompt 回填口令）
 *    → 脚本本体，每一步等上一步的 evaluateJavascript 回调；
 * 3. **脚本的 Promise 桥不传 promiseId**（`saveImportedCourses(json)` 单参）——id 由注入的
 *    桥 JS 内部生成后拼在原生调用末位，原生侧 resolve/reject 均经 `evaluateJavascript`
 *    兑现 `window._resolveAndroidPromise / _rejectAndroidPromise`。
 *
 * 失败信号：`onImportFailed` 显式失败；`showToast` 文案含失败关键词（脚本错误路径只靠
 * toast 传达）也视为失败。数据收口：脚本流程里课程是最后回传的数据块
 * （时间段/配置都已 await 完毕），`saveImportedCourses` 到达即 complete；
 * `notifyTaskCompletion` 作为兜底完成信号。
 */
object WakeupImportExecutor {

    /** 失败原因（UI 按此选本地化文案；[Failed] 直接透传脚本给出的可读文案） */
    enum class Reason { Timeout, PageLoad, NoCourses, Failed }

    /** 导入失败（[Reason.Failed] 的 message 为脚本给出的可读文案，可能是 null） */
    class WakeupImportException(val reason: Reason, message: String?) : Exception(message)

    /** 数据为空时的兜底文案（本地化在 UI 侧按 [Reason.NoCourses] 处理，这里仅作日志安全占位） */
    private const val NO_COURSES = "未解析到课程"

    /** 脚本运行全程预算：含两段式请求（antispam → getv2）与解析 */
    private const val TOTAL_TIMEOUT_MS = 45_000L

    /** 空白页加载兜底：onPageFinished 不来时提前失败，不干等总超时 */
    private const val PAGE_LOAD_TIMEOUT_MS = 20_000L

    /** 失败关键词：与指南 bootstrap 的 toast 兜底同源 */
    private val ERROR_KEYWORDS = Regex("失败|无效|无法|不能为空|未检测到")

    private const val BASE_URL = "https://api.wakeup.fun/"
    private const val BLANK_PAGE = "<html><body></body></html>"

    /**
     * 执行导入。WebView 相关操作全程主线程；协程被取消（用户关浮层/页面销毁）时
     * finally 仍销毁 WebView（NonCancellable），不泄漏。
     */
    suspend fun import(
        context: Context,
        shareKey: String,
        script: String,
        onProgress: (String) -> Unit,
    ): WakeupSharePayload = withContext(Dispatchers.Main) {
        try {
            withTimeout(TOTAL_TIMEOUT_MS) { runImport(context.applicationContext, shareKey, script, onProgress) }
        } catch (e: TimeoutCancellationException) {
            // 仅超时翻译成业务失败；外层协程自身的取消（CancellationException）原样上抛
            throw WakeupImportException(Reason.Timeout, null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runImport(
        appContext: Context,
        shareKey: String,
        script: String,
        onProgress: (String) -> Unit,
    ): WakeupSharePayload {
        val mainHandler = Handler(Looper.getMainLooper())
        val result = CompletableDeferred<WakeupSharePayload>()

        val webView = WebView(appContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            setBackgroundColor(Color.TRANSPARENT)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // 严格串行：Promise 桥 → bootstrap → 脚本本体（每步等上一步执行完）
                    view?.evaluateJavascript(SCRIPT_PROMISE_BRIDGE) {
                        view.evaluateJavascript(bootstrapJs(shareKey)) {
                            view.evaluateJavascript(script, null)
                        }
                    }
                }
            }
        }
        val bridge = Bridge(webView, mainHandler, shareKey, onProgress, result)

        // 桥在页面加载前注册：AndroidBridge 自页面首行 JS 起可用（脚本与 bootstrap 都直接引用）
        webView.addJavascriptInterface(bridge, "AndroidBridge")

        // 空白页加载看门狗：到点仍未收口（页面没加载/脚本没跑）就提前失败
        val pageTimeout = Runnable { bridge.failTimeoutPageLoad() }
        mainHandler.postDelayed(pageTimeout, PAGE_LOAD_TIMEOUT_MS)

        // baseURL 决定同源——关键！
        webView.loadDataWithBaseURL(BASE_URL, BLANK_PAGE, "text/html", "utf-8", null)

        try {
            return result.await()
        } finally {
            mainHandler.removeCallbacks(pageTimeout)
            withContext(NonCancellable) { bridge.destroy() }
        }
    }

    /**
     * JS 桥（脚本 ↔ 原生）。所有回调都先 post 到主线程再动收集态（@JavascriptInterface
     * 运行在 WebView 的 JavaBridge 线程）；promiseId 的兑现统一经 [evaluate] 注入，
     * 浮层关闭销毁 WebView 后不再注入。
     */
    private class Bridge(
        private val webView: WebView,
        private val mainHandler: Handler,
        private val shareKey: String,
        private val onProgress: (String) -> Unit,
        private val result: CompletableDeferred<WakeupSharePayload>,
    ) {
        // 收集态只在主线程读写（全部回调经 post 收敛），无需加锁
        private val courses = mutableListOf<WakeupCourse>()
        private val timeSlots = mutableListOf<WakeupTimeSlot>()
        private var config = WakeupCourseConfig()
        @Volatile private var destroyed = false

        /** 进度/错误 toast：含失败关键词即判失败（脚本错误路径只靠 toast 传达） */
        @JavascriptInterface
        fun showToast(message: String?) {
            val text = message.orEmpty()
            post {
                if (ERROR_KEYWORDS.containsMatchIn(text)) {
                    fail(Reason.Failed, text)
                } else {
                    onProgress(text)
                }
            }
        }

        /** bootstrap 对 showAlert 的覆写目标：显式失败信号 */
        @JavascriptInterface
        fun onImportFailed(message: String?) {
            val text = message?.takeIf { it.isNotBlank() } ?: "导入失败"
            post { fail(Reason.Failed, text) }
        }

        /** 脚本宣布流程结束（数据为空则判「未解析到课程」） */
        @JavascriptInterface
        fun notifyTaskCompletion() {
            post {
                if (courses.isEmpty()) fail(Reason.NoCourses, NO_COURSES) else complete()
            }
        }

        /** 脚本要口令的弹窗：bootstrap 已覆写直接回填，这里只兜底非覆写场景（回默认文本） */
        @JavascriptInterface
        fun showPrompt(title: String?, tip: String?, defaultText: String?, validatorJs: String?, promiseId: String?) {
            // JavaBridge 线程不能碰 WebView：兑现 Promise 一律 post 回主线程（下同）
            post { resolve(promiseId, jsString(defaultText?.takeIf { it.isNotBlank() } ?: shareKey)) }
        }

        /** 报错弹窗：转失败信号（文案透传 UI） */
        @JavascriptInterface
        fun showAlert(title: String?, content: String?, confirmText: String?, promiseId: String?) {
            val text = content?.takeIf { it.isNotBlank() } ?: title.orEmpty()
            post {
                fail(Reason.Failed, text.ifBlank { "导入失败" })
                resolve(promiseId, "true")
            }
        }

        /** 单选弹窗：无头环境返回默认选项 */
        @JavascriptInterface
        fun showSingleSelection(title: String?, itemsJson: String?, defaultIndex: String?, promiseId: String?) {
            post { resolve(promiseId, (defaultIndex?.toIntOrNull() ?: 0).toString()) }
        }

        @JavascriptInterface
        fun savePresetTimeSlots(timeSlotsJson: String?, promiseId: String?) {
            post {
                try {
                    val parsed = decodeList<WakeupTimeSlot>(timeSlotsJson)
                    timeSlots.clear()
                    timeSlots.addAll(parsed)
                    resolve(promiseId, "true")
                } catch (e: Exception) {
                    rejectAndFail(promiseId, e)
                }
            }
        }

        @JavascriptInterface
        fun saveCourseConfig(configJson: String?, promiseId: String?) {
            post {
                try {
                    config = configJson?.takeIf { it.isNotBlank() }
                        ?.let { WakeupScriptJson.decodeFromString<WakeupCourseConfig>(it) }
                        ?: WakeupCourseConfig()
                    resolve(promiseId, "true")
                } catch (e: Exception) {
                    rejectAndFail(promiseId, e)
                }
            }
        }

        /** 课程数据主通道：脚本流程的最后一块数据，到达即收口 */
        @JavascriptInterface
        fun saveImportedCourses(coursesJson: String?, promiseId: String?) {
            post {
                try {
                    val parsed = decodeList<WakeupCourse>(coursesJson)
                    courses.clear()
                    courses.addAll(parsed)
                    resolve(promiseId, "true")
                    if (courses.isEmpty()) fail(Reason.NoCourses, NO_COURSES) else complete()
                } catch (e: Exception) {
                    rejectAndFail(promiseId, e)
                }
            }
        }

        fun failTimeoutPageLoad() {
            post { fail(Reason.PageLoad, null) }
        }

        /** 销毁 WebView（必须在主线程调用）：标记后不再注入，挂起未决消息一并清掉 */
        fun destroy() {
            destroyed = true
            mainHandler.removeCallbacksAndMessages(null)
            webView.stopLoading()
            webView.destroy()
        }

        private fun complete() {
            result.complete(WakeupSharePayload(courses.toList(), timeSlots.toList(), config))
        }

        private fun fail(reason: Reason, message: String?) {
            result.completeExceptionally(WakeupImportException(reason, message))
        }

        /** 解析失败：先 reject（脚本 await 抛错走它的失败路径），再直接判失败（更快收口） */
        private fun rejectAndFail(promiseId: String?, error: Exception) {
            reject(promiseId, error.message)
            fail(Reason.Failed, error.message)
        }

        private fun resolve(promiseId: String?, resultExpr: String) {
            val id = promiseId ?: return
            evaluate("window._resolveAndroidPromise && window._resolveAndroidPromise('$id', $resultExpr);")
        }

        private fun reject(promiseId: String?, error: String?) {
            val id = promiseId ?: return
            evaluate("window._rejectAndroidPromise && window._rejectAndroidPromise('$id', ${jsString(error)});")
        }

        private fun evaluate(js: String) {
            if (destroyed) return
            try {
                webView.evaluateJavascript(js, null)
            } catch (_: Exception) {
                // destroy 竞态下的注入失败可安全忽略
            }
        }

        private fun post(block: () -> Unit) {
            mainHandler.post {
                if (!destroyed) block()
            }
        }

        private inline fun <reified T> decodeList(json: String?): List<T> =
            json?.takeIf { it.isNotBlank() }
                ?.let { WakeupScriptJson.decodeFromString<List<T>>(it) }
                .orEmpty()
    }

    //region 注入 JS

    /**
     * Promise 桥：把同步的 @JavascriptInterface 方法包装成返回 Promise 的函数。
     * 脚本调用 `shiguangBridgePromise.xxx(json)` 不带 id，桥内生成后拼在原生调用末位。
     */
    private const val SCRIPT_PROMISE_BRIDGE = """
        (function() {
            if (window._resolveAndroidPromise) return;
            window._androidPromiseResolvers = {};
            window._androidPromiseRejectors = {};
            window._resolveAndroidPromise = function(promiseId, result) {
                var resolve = window._androidPromiseResolvers[promiseId];
                if (resolve) {
                    resolve(result);
                    delete window._androidPromiseResolvers[promiseId];
                    delete window._androidPromiseRejectors[promiseId];
                }
            };
            window._rejectAndroidPromise = function(promiseId, error) {
                var reject = window._androidPromiseRejectors[promiseId];
                if (reject) {
                    reject(new Error(error));
                    delete window._androidPromiseResolvers[promiseId];
                    delete window._androidPromiseRejectors[promiseId];
                }
            };
            function makePromise(nativeCall) {
                return function() {
                    var args = Array.prototype.slice.call(arguments);
                    return new Promise(function(resolve, reject) {
                        var id = 'p_' + Date.now() + '_' + Math.floor(Math.random() * 1e9);
                        window._androidPromiseResolvers[id] = resolve;
                        window._androidPromiseRejectors[id] = reject;
                        nativeCall.apply(null, args.concat([id]));
                    });
                };
            }
            window.AndroidBridgePromise = {
                showAlert: makePromise(function(title, content, confirmText, id) {
                    window.AndroidBridge.showAlert(title, content, confirmText, id);
                }),
                showPrompt: makePromise(function(title, tip, defaultText, validatorJs, id) {
                    window.AndroidBridge.showPrompt(title, tip, defaultText, validatorJs, id);
                }),
                showSingleSelection: makePromise(function(title, itemsJson, defaultIndex, id) {
                    window.AndroidBridge.showSingleSelection(title, itemsJson, defaultIndex, id);
                }),
                savePresetTimeSlots: makePromise(function(timeSlotsJson, id) {
                    window.AndroidBridge.savePresetTimeSlots(timeSlotsJson, id);
                }),
                saveCourseConfig: makePromise(function(configJson, id) {
                    window.AndroidBridge.saveCourseConfig(configJson, id);
                }),
                saveImportedCourses: makePromise(function(coursesJson, id) {
                    window.AndroidBridge.saveImportedCourses(coursesJson, id);
                })
            };
            // 拾光脚本 v2 走 shiguangBridge 命名：别名兼容新旧脚本
            window.shiguangBridge = window.shiguangBridge || window.AndroidBridge;
            window.shiguangBridgePromise = window.shiguangBridgePromise || window.AndroidBridgePromise;
        })();
    """

    /**
     * bootstrap：把「给人用的脚本」变成「给程序用的」——口令直接经 showPrompt 回填（跳过
     * 弹窗），showAlert 报错转成原生失败信号；showToast 走原生关键词判定，无需覆写。
     */
    private fun bootstrapJs(shareKey: String): String = """
        (function() {
          window.shiguangBridge = window.AndroidBridge;
          window.shiguangBridgePromise = window.AndroidBridgePromise;
          var __key = ${jsString(shareKey)};
          if (window.AndroidBridgePromise) {
            window.AndroidBridgePromise.showPrompt = function() {
              return Promise.resolve(__key);
            };
            window.AndroidBridgePromise.showAlert = function(title, content) {
              if (window.AndroidBridge && window.AndroidBridge.onImportFailed) {
                window.AndroidBridge.onImportFailed(content || title || '导入失败');
              }
              return Promise.resolve(true);
            };
          }
        })();
    """.trimIndent()

    //endregion

    /**
     * 值 → JS 表达式（单引号字符串字面量）：转义反斜杠/引号/换行/控制符。
     * 口令、错误文案在进入注入脚本前都要过这一道，防注入也防语法破坏。
     */
    private fun jsString(value: String?): String {
        val safe = value.orEmpty()
        val builder = StringBuilder(safe.length + 8).append('\'')
        for (ch in safe) {
            when (ch) {
                '\\' -> builder.append("\\\\")
                '\'' -> builder.append("\\'")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (ch < ' ') builder.append("\\u%04x".format(ch.code)) else builder.append(ch)
            }
        }
        return builder.append('\'').toString()
    }
}
