package com.fangyi.classpp.schools.xmu

import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.sync.ImportError
import com.fangyi.classpp.data.sync.ImportOutcome
import com.fangyi.classpp.data.sync.webview.WebViewTimetableJs
import com.fangyi.classpp.data.xmu.XMU_JW_STANDARD_SLOTS
import com.fangyi.classpp.data.xmu.XmuJwPkjgDto
import com.fangyi.classpp.data.xmu.XmuJwScheduleMapper
import com.fangyi.classpp.data.xmu.XmuJwScheduleRaw
import com.fangyi.classpp.data.xmu.XmuJwSkjcDto
import kotlinx.serialization.Serializable

/**
 * 厦大「我的课表」网页导入适配（WebView 登录模式）：
 *
 * 1. [script] 在已登录的教务课表页上下文执行——读取页面当前选中的学年学期
 *    （`#myXnxqSelect` / `currentXnxq`），同源 POST 四个教务接口
 *    （initXsxx / queryXspkjg / queryXsskjc / getZcxx），把结果写回
 *    [WebViewTimetableJs.RESULT_VAR]；原生侧轮询取回后交给 [toImportOutcome]。
 * 2. [toImportOutcome] 把原文还原成 [XmuJwScheduleRaw]，复用 [XmuJwScheduleMapper]
 *    的合并/周次/作息/开学日推算，再走既有导入确认与落库路径。
 *
 * 接口与原生导入（`XmuJwScheduleApi`）同源同形：`queryXspkjg` 不传 ZC（服务端把它当
 * 过滤条件，网页端查看全学期时也不传），`queryXsskjc` 空应答用内置作息兜底。
 * 脚本里所有请求都带 `credentials: 'include'`，复用页面已登录的 jw 会话 cookie。
 *
 * 脚本约定：用 ES5（var/function，不用模板字符串），适配老 WebView；
 * `evaluateJavascript` 不等待 Promise，所以只写页面变量、不返回值。
 */
internal object XmuWebTimetableImport {

    /** 网页抓取原文的信封（脚本自定字段，全部为 JSON 基本类型/数组） */
    @Serializable
    private data class Envelope(
        val ok: Boolean = false,
        val error: String? = null,
        val semesterCode: String? = null,
        val semesterLabel: String? = null,
        val currentZc: String? = null,
        val slots: List<XmuJwSkjcDto> = emptyList(),
        val courses: List<XmuJwPkjgDto> = emptyList(),
    )

    /** 页面内抓取脚本：完成后写回 [WebViewTimetableJs.RESULT_VAR] */
    fun script(): String {
        val resultVar = WebViewTimetableJs.RESULT_VAR
        return """
            (function () {
              $resultVar = '';
              function fail(err) {
                $resultVar = JSON.stringify({ ok: false, error: err });
              }
              function done(payload) {
                $resultVar = JSON.stringify(payload);
              }
              function parseJson(text) {
                try { return JSON.parse(text); } catch (e) { return null; }
              }
              function appBase() {
                var path = location.pathname;
                var marker = '/sys/wdkbapp/';
                var index = path.indexOf(marker);
                return index < 0
                  ? '/gsapp/sys/wdkbapp'
                  : path.substring(0, index) + '/sys/wdkbapp';
              }
              function post(path, data) {
                var body = [];
                if (data) {
                  for (var key in data) {
                    if (Object.prototype.hasOwnProperty.call(data, key)) {
                      body.push(
                        encodeURIComponent(key) + '=' + encodeURIComponent(data[key])
                      );
                    }
                  }
                }
                return fetch(appBase() + path, {
                  method: 'POST',
                  credentials: 'include',
                  headers: {
                    'Accept': 'application/json, text/plain, */*',
                    'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8'
                  },
                  body: body.join('&')
                }).then(function (response) {
                  return response.text().then(function (text) {
                    return { status: response.status, text: text };
                  });
                });
              }
              // 200 + HTML（被重定向到统一认证登录页）按会话过期处理
              function check(response) {
                if (response.status === 401 || response.status === 403) {
                  fail('session');
                  return false;
                }
                if (response.status >= 500) {
                  fail('busy');
                  return false;
                }
                if (response.status < 200 || response.status >= 300) {
                  fail('format');
                  return false;
                }
                if (/^\s*</.test(response.text)) {
                  fail('session');
                  return false;
                }
                return true;
              }

              // 当前选中的学年学期：以页面下拉框为准（与用户眼前课表一致），
              // 再回退页面状态变量
              var select = document.getElementById('myXnxqSelect');
              var current = window.currentXnxq;
              var code = select && select.value
                ? String(select.value)
                : (current && current.XNXQDM ? String(current.XNXQDM) : '');
              var label = '';
              if (select && select.selectedIndex >= 0 && select.options[select.selectedIndex]) {
                label = String(select.options[select.selectedIndex].text || '')
                  .replace(/^\s+|\s+$/g, '');
              }
              if (!label && current && current.XNXQDM_DISPLAY) {
                label = String(current.XNXQDM_DISPLAY);
              }
              if (!code) {
                fail('semester');
                return;
              }

              post('/wdkcb/initXsxx.do', { XH: '' })
                .then(function (studentResponse) {
                  if (!check(studentResponse)) return null;
                  var student = parseJson(studentResponse.text);
                  var studentId = student && student.data && student.data.length &&
                    student.data[0].XH ? String(student.data[0].XH) : '';
                  return Promise.all([
                    post('/wdkcb/queryXspkjg.do', {
                      XNXQDM: code,
                      XH: studentId
                    }),
                    post('/wdkcb/queryXsskjc.do', { XNXQDM: code }),
                    post('/wdkcb/getZcxx.do', { XNXQDM: code })
                  ]);
                })
                .then(function (responses) {
                  if (!responses) return;
                  var scheduleResponse = responses[0];
                  if (!check(scheduleResponse)) return;
                  var schedule = parseJson(scheduleResponse.text);
                  // 空数组是合法结果（无课），交给映射器报「未解析到课程」
                  if (!schedule || schedule.success === false || !schedule.pkjgList) {
                    fail('format');
                    return;
                  }
                  var slots = parseJson(responses[1].text);
                  var week = parseJson(responses[2].text);
                  done({
                    ok: true,
                    semesterCode: code,
                    semesterLabel: label,
                    currentZc: week && week.currentZc !== undefined &&
                      week.currentZc !== null ? String(week.currentZc) : '',
                    slots: slots && slots.data ? slots.data : [],
                    courses: schedule.pkjgList
                  });
                })
                .catch(function () {
                  // 已登录页面里的同源请求被拒通常是 302 去身份域被 CORS 拦下；
                  // 真正离线时 navigator.onLine 为 false，两者区分提示
                  fail(navigator.onLine === false ? 'network' : 'session');
                });
            })();
        """.trimIndent()
    }

    /**
     * 网页抓取原文 → 课表：复用原生导入的映射器（同课拆行合并、周次位图、作息、
     * 超长课拆分、开学日推算全部一致）；落库仍由 UI 走既有确认 + 仓库导入。
     */
    fun toImportOutcome(
        raw: String,
        fallbackName: String,
        today: IsoDate = IsoDate.today(),
    ): ImportOutcome {
        val envelope = try {
            ScheduleJson.decodeStorage<Envelope>(raw)
        } catch (_: Exception) {
            null
        } ?: return ImportOutcome.Failed(ImportError.FormatChanged)

        if (!envelope.ok) {
            return ImportOutcome.Failed(envelope.error.toImportError())
        }
        if (envelope.semesterCode.isNullOrBlank()) {
            return ImportOutcome.Failed(ImportError.FormatChanged)
        }
        val schedule = XmuJwScheduleMapper.toScheduleOrNull(
            XmuJwScheduleRaw(
                semesterLabel = envelope.semesterLabel.orEmpty(),
                // 作息接口空应答/失败：与原生导入同一兜底（全校固定作息）
                slots = envelope.slots.ifEmpty { XMU_JW_STANDARD_SLOTS },
                courses = envelope.courses,
                currentZc = envelope.currentZc?.trim()?.toIntOrNull(),
                fallbackName = fallbackName,
                today = today,
            ),
        ) ?: return ImportOutcome.NoCourses
        return ImportOutcome.Ok(schedule)
    }

    private fun String?.toImportError(): ImportError = when (this) {
        "session" -> ImportError.SessionExpired
        "network" -> ImportError.NetworkError
        "busy" -> ImportError.ServiceBusy
        "semester", "format" -> ImportError.FormatChanged
        else -> ImportError.FormatChanged
    }
}
