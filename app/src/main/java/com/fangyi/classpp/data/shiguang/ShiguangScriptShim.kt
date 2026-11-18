package com.fangyi.classpp.data.shiguang

/**
 * 拾光教务适配脚本的宿主垫片（纯 JS，注入到学校页面后立即生效）。
 *
 * 上游适配脚本只认 `window.shiguangBridge` / `window.shiguangBridgePromise`
 * （旧脚本还会用 `AndroidBridge*` 别名）：它们把课程、作息、学期配置与弹窗交互发回宿主。
 * Classpp 不需要实现完整的原生桥，只需要「收集数据 + 把弹窗自动应答」：
 *
 * - `saveImportedCourses` / `savePresetTimeSlots` / `saveCourseConfig` /
 *   `saveComboSchedule` 把脚本传入的 JSON（字符串或对象）存进内存并 resolve true；
 * - `showAlert` 自动确认，`showPrompt` 返回默认值，`showSingleSelection` 返回默认索引；
 * - `showToast` 含失败关键词时直接收口为失败（与 Wakeup 执行器同一启发式）；
 * - `notifyTaskCompletion` 把收集到的数据写成 [RESULT_VAR] 的 JSON 字符串；
 * - 未捕获异常 / unhandled rejection 也写成失败结果。
 *
 * 使用「页面变量 + 原生轮询」而不是 `addJavascriptInterface`：不向第三方页面暴露
 * 原生对象，与学校网页导入的安全口径一致。脚本里不使用箭头函数/模板字符串，
 * 保持 ES5 兼容（只依赖 Promise，Android WebView 早已支持）。
 */
internal object ShiguangScriptShim {

    /** 结果变量：shim 写入，原生侧 [resetJs] / [readJs] 轮询 */
    const val RESULT_VAR = "window.__classppShiguang"

    /** 注入前清空上一次结果 */
    fun resetJs(): String = "$RESULT_VAR = '';"

    /** 轮询读取表达式：未写回时返回空串 */
    fun readJs(): String = "($RESULT_VAR || '')"

    /** 垫片 + 学校适配脚本（垫片先定义桥，脚本随后执行） */
    fun build(adapterScript: String): String = SHIM + "\n;\n" + adapterScript

    private val SHIM = """
        (function() {
            // 每次重新注入都递增代号：上一次尝试注册的 error/unhandledrejection 监听器
            // 仍留在 window 上，靠代号丢弃旧尝试的迟到结果，避免覆盖本次结果
            var generation = (window.__classppShiguangGeneration || 0) + 1;
            window.__classppShiguangGeneration = generation;

            var state = {
                courses: null,
                timeSlots: null,
                config: null,
                combo: null,
                done: false,
                lastError: ''
            };

            function finish(ok, error, errorCode) {
                if (state.done) return;
                if (generation !== window.__classppShiguangGeneration) return;
                state.done = true;
                if (ok) {
                    window.__classppShiguang = JSON.stringify({
                        ok: true,
                        courses: state.courses || [],
                        timeSlots: state.timeSlots || [],
                        config: state.config || {}
                    });
                } else {
                    window.__classppShiguang = JSON.stringify({
                        ok: false,
                        errorCode: errorCode || 'SCRIPT_ERROR',
                        error: String(error || '')
                    });
                }
            }

            function parseData(value) {
                if (value == null) return null;
                if (typeof value === 'string') {
                    var text = value.replace(/^\s+|\s+$/g, '');
                    if (!text) return null;
                    try { return JSON.parse(text); } catch (e) { return null; }
                }
                return value;
            }

            function saveField(field) {
                return function(value) {
                    var parsed = parseData(value);
                    if (parsed == null) {
                        finish(false, '', 'INVALID_DATA');
                        return Promise.resolve(false);
                    }
                    if (field === 'courses' && !Object.prototype.toString.call(parsed).match(/Array/)) {
                        if (parsed && parsed.courses != null) parsed = parsed.courses;
                    }
                    if (field === 'courses' &&
                        !Object.prototype.toString.call(parsed).match(/Array/)) {
                        finish(false, '', 'INVALID_DATA');
                        return Promise.resolve(false);
                    }
                    state[field] = parsed;
                    return Promise.resolve(true);
                };
            }

            function isFatalToast(text) {
                return /失败|无效|无法|不能为空|未检测到|未登录|请先登录/.test(text);
            }

            window.shiguangBridge = {
                showToast: function(message) {
                    var text = String(message == null ? '' : message);
                    if (isFatalToast(text)) {
                        state.lastError = text;
                        finish(false, text, 'SCRIPT_ERROR');
                    }
                },
                notifyTaskCompletion: function() {
                    var isArray = Object.prototype.toString.call(state.courses).match(/Array/);
                    if (!isArray || state.courses.length === 0) {
                        finish(false, '', 'NO_COURSES');
                    } else {
                        finish(true, '', null);
                    }
                },
                onImportFailed: function(message) {
                    finish(false, message || '', 'SCRIPT_ERROR');
                }
            };
            window.AndroidBridge = window.shiguangBridge;

            window.shiguangBridgePromise = {
                showAlert: function(title, content, confirmText) {
                    var text = String(content || title || '');
                    if (text) state.lastError = text;
                    return Promise.resolve(true);
                },
                showPrompt: function(title, tip, defaultText, validatorJsFunction) {
                    return Promise.resolve(defaultText == null ? '' : String(defaultText));
                },
                showSingleSelection: function(title, itemsJsonString, defaultIndex) {
                    var index = Number(defaultIndex);
                    if (!isFinite(index) || index < 0) index = 0;
                    return Promise.resolve(index);
                },
                saveImportedCourses: saveField('courses'),
                savePresetTimeSlots: saveField('timeSlots'),
                saveCourseConfig: saveField('config'),
                saveComboSchedule: saveField('combo')
            };
            window.AndroidBridgePromise = window.shiguangBridgePromise;

            // 部分脚本会读取当前课表 id；Classpp 无此概念，给一个固定值避免 undefined 分支
            window.currentTableId = 'classpp';
            window._shiguangBridgeInjected = true;

            window.addEventListener('error', function(e) {
                var message = e && e.message ? e.message : '';
                finish(false, message, 'SCRIPT_ERROR');
            });
            window.addEventListener('unhandledrejection', function(e) {
                var reason = e && e.reason;
                var message = reason && reason.message ? reason.message : String(reason || '');
                finish(false, message, 'SCRIPT_ERROR');
            });
        })();
    """.trimIndent()
}
