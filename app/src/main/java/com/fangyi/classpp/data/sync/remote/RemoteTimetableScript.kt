package com.fangyi.classpp.data.sync.remote

import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.sync.webview.WebViewTimetableJs
import kotlinx.serialization.Serializable

/**
 * 根据声明生成**固定**的页内抓取脚本（ES5，页面变量回传 + 原生轮询）。
 *
 * 安全边界：远端只提供数据配置（路径/字段/模板），脚本本体由 App 生成；不接收远端 JS。
 * 请求一律 `base() + path` 同源发起并带 `credentials: 'include'`，[DeclarationTimetable]
 * 的校验保证 path 是 `/` 开头的相对路径、模板占位符受限。
 *
 * 结果写回 [WebViewTimetableJs.RESULT_VAR]：
 * `{ ok, semesterCode, semesterLabel, currentWeek, termStart, slots, courses }`；
 * 失败写 `{ ok:false, error:"semester|session|network|busy|format" }`。
 */
internal object RemoteTimetableScript {

    @Serializable
    private data class Config(
        val basePath: String? = null,
        val baseMarker: String? = null,
        val semester: Semester,
        val requests: Requests,
        val coursesRequest: String,
        val coursesListPath: String,
        val slotsRequest: String? = null,
        val slotsListPath: String? = null,
        val term: Term,
    )

    @Serializable
    private data class Semester(
        val selector: String? = null,
        val global: String? = null,
        val codePath: String? = null,
        val labelPath: String? = null,
    )

    @Serializable
    private data class Requests(
        val student: Request? = null,
        val schedule: Request,
        val slots: Request? = null,
        val week: Request? = null,
    )

    @Serializable
    private data class Request(
        val path: String,
        val method: String,
        val fields: Map<String, String>,
        val idPath: String? = null,
    )

    @Serializable
    private data class Term(
        val source: String,
        val request: String? = null,
        val path: String? = null,
    )

    fun script(timetable: DeclarationTimetable): String {
        val config = Config(
            basePath = timetable.basePath,
            baseMarker = timetable.baseMarker,
            semester = Semester(
                selector = timetable.semester.selector,
                global = timetable.semester.global,
                codePath = timetable.semester.codePath,
                labelPath = timetable.semester.labelPath,
            ),
            requests = Requests(
                student = timetable.requests.student?.toScriptRequest(),
                schedule = timetable.requests.schedule.toScriptRequest(),
                slots = timetable.requests.slots?.toScriptRequest(),
                week = timetable.requests.week?.toScriptRequest(),
            ),
            coursesRequest = timetable.courses.request,
            coursesListPath = timetable.courses.listPath,
            slotsRequest = timetable.slots?.request,
            slotsListPath = timetable.slots?.listPath,
            term = Term(
                source = timetable.term.source,
                request = timetable.term.request,
                path = timetable.term.path,
            ),
        )
        val json = jsSingleQuoted(ScheduleJson.encodeStorage(config))
        val resultVar = WebViewTimetableJs.RESULT_VAR
        return """
            (function () {
              $resultVar = '';
              var CONFIG = JSON.parse('$json');

              function fail(err) {
                $resultVar = JSON.stringify({ ok: false, error: err });
              }
              function done(payload) {
                $resultVar = JSON.stringify(payload);
              }
              function parseJson(text) {
                try { return JSON.parse(text); } catch (e) { return null; }
              }
              function isArray(value) {
                return Object.prototype.toString.call(value) === '[object Array]';
              }
              function at(root, path) {
                if (root === null || root === undefined || !path) return null;
                var parts = String(path).split('.');
                var current = root;
                for (var i = 0; i < parts.length; i++) {
                  if (current === null || current === undefined ||
                      typeof current !== 'object') {
                    return null;
                  }
                  current = current[parts[i]];
                }
                return current;
              }
              function str(root, path) {
                var value = at(root, path);
                if (value === null || value === undefined || typeof value === 'object') {
                  return '';
                }
                return String(value);
              }
              function base() {
                if (CONFIG.basePath) {
                  return CONFIG.basePath.replace(/\/$/, '');
                }
                if (CONFIG.baseMarker) {
                  var path = location.pathname;
                  var index = path.indexOf(CONFIG.baseMarker);
                  if (index >= 0) {
                    var derived = path.substring(0, index) + CONFIG.baseMarker;
                    return derived.charAt(derived.length - 1) === '/'
                      ? derived.substring(0, derived.length - 1)
                      : derived;
                  }
                }
                return '';
              }
              function readSemester() {
                var spec = CONFIG.semester;
                var code = '';
                var label = '';
                if (spec.selector) {
                  var element = document.querySelector(spec.selector);
                  if (element) {
                    code = element.value ? String(element.value) : '';
                    if (element.selectedIndex >= 0 &&
                        element.options && element.options[element.selectedIndex]) {
                      label = String(element.options[element.selectedIndex].text || '')
                        .replace(/^\s+|\s+$/g, '');
                    }
                  }
                }
                if (!code && spec.global) {
                  var source = window[spec.global];
                  if (source) {
                    if (spec.codePath) code = str(source, spec.codePath);
                    if (!label && spec.labelPath) label = str(source, spec.labelPath);
                  }
                }
                return { code: code, label: label };
              }
              function subst(value, values) {
                return String(value)
                  .replace(/\{semesterCode\}/g, values.semesterCode || '')
                  .replace(/\{semesterLabel\}/g, values.semesterLabel || '')
                  .replace(/\{studentId\}/g, values.studentId || '');
              }
              function encodeFields(fields, values) {
                var parts = [];
                for (var key in fields) {
                  if (Object.prototype.hasOwnProperty.call(fields, key)) {
                    parts.push(
                      encodeURIComponent(key) + '=' +
                      encodeURIComponent(subst(fields[key], values))
                    );
                  }
                }
                return parts.join('&');
              }
              function call(request, values) {
                var url = base() + request.path;
                var method = String(request.method || 'POST').toUpperCase();
                var encoded = encodeFields(request.fields || {}, values);
                var body = null;
                var headers = { 'Accept': 'application/json, text/plain, */*' };
                if (method === 'GET') {
                  if (encoded) {
                    url += (url.indexOf('?') >= 0 ? '&' : '?') + encoded;
                  }
                } else {
                  body = encoded;
                  headers['Content-Type'] =
                    'application/x-www-form-urlencoded; charset=UTF-8';
                }
                return fetch(url, {
                  method: method,
                  credentials: 'include',
                  headers: headers,
                  body: body
                }).then(function (response) {
                  return response.text().then(function (text) {
                    return { status: response.status, text: text };
                  });
                });
              }
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
              function afterStudent(studentJson, studentResponse) {
                var ids = ['schedule'];
                if (CONFIG.requests.slots) ids.push('slots');
                if (CONFIG.requests.week) ids.push('week');
                var jobs = [];
                for (var i = 0; i < ids.length; i++) {
                  jobs.push(call(CONFIG.requests[ids[i]], values));
                }
                return Promise.all(jobs).then(function (responses) {
                  if ($resultVar) return;
                  var byId = {};
                  var responsesById = {};
                  for (var i = 0; i < ids.length; i++) {
                    byId[ids[i]] = parseJson(responses[i].text);
                    responsesById[ids[i]] = responses[i];
                  }
                  if (studentJson !== null) {
                    byId['student'] = studentJson;
                    responsesById['student'] = studentResponse;
                  }
                  if (!check(responsesById['schedule'])) return;
                  var coursesResponse = responsesById[CONFIG.coursesRequest];
                  if (coursesResponse && !check(coursesResponse)) return;
                  var courses = at(byId[CONFIG.coursesRequest], CONFIG.coursesListPath);
                  if (!isArray(courses)) {
                    fail('format');
                    return;
                  }
                  var slots = [];
                  if (CONFIG.slotsRequest) {
                    var slotsResponse = responsesById[CONFIG.slotsRequest];
                    if (!slotsResponse || !check(slotsResponse)) return;
                    var slotArray = at(byId[CONFIG.slotsRequest], CONFIG.slotsListPath);
                    if (!isArray(slotArray)) {
                      fail('format');
                      return;
                    }
                    slots = slotArray;
                  }
                  var currentWeek = '';
                  var termStart = '';
                  if (CONFIG.term.request) {
                    var termResponse = responsesById[CONFIG.term.request];
                    if (termResponse && !check(termResponse)) return;
                    var termJson = byId[CONFIG.term.request];
                    if (CONFIG.term.request === 'student' && studentJson !== null) {
                      termJson = studentJson;
                    }
                    var raw = str(termJson, CONFIG.term.path);
                    if (CONFIG.term.source === 'CURRENT_WEEK') currentWeek = raw;
                    else termStart = raw;
                  }
                  done({
                    ok: true,
                    semesterCode: values.semesterCode,
                    semesterLabel: values.semesterLabel,
                    currentWeek: currentWeek,
                    termStart: termStart,
                    slots: slots,
                    courses: courses
                  });
                });
              }
              function start() {
                var semester;
                try {
                  semester = readSemester();
                } catch (e) {
                  fail('semester');
                  return;
                }
                if (!semester.code) {
                  fail('semester');
                  return;
                }
                values = {
                  semesterCode: semester.code,
                  semesterLabel: semester.label,
                  studentId: ''
                };
                if (CONFIG.requests.student) {
                  call(CONFIG.requests.student, values)
                    .then(function (response) {
                      if (!check(response)) return;
                      var studentJson = parseJson(response.text);
                      values.studentId =
                        str(studentJson, CONFIG.requests.student.idPath);
                      return afterStudent(studentJson, response);
                    })
                    .catch(onError);
                } else {
                  afterStudent(null, null).catch(onError);
                }
              }
              function onError() {
                if (!$resultVar) {
                  fail(navigator.onLine === false ? 'network' : 'session');
                }
              }

              var values = { semesterCode: '', semesterLabel: '', studentId: '' };
              start();
            })();
        """.trimIndent()
    }

    private fun DeclarationTimetableRequest.toScriptRequest(): Request = Request(
        path = path,
        method = method.uppercase(),
        fields = fields,
        idPath = idPath,
    )

    /** JSON 文本 → JS 单引号字符串字面量（JSON 已转义控制字符，这里补 \ 与 '） */
    internal fun jsSingleQuoted(json: String): String = buildString {
        for (ch in json) {
            when (ch) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
    }
}
