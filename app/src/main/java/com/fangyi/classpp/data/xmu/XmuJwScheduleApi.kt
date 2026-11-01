package com.fangyi.classpp.data.xmu

import com.fangyi.classpp.data.ScheduleJson
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 教务「我的课表」接口结果分类：口径与 [XmuFetchResult] 一致，但载荷类型随端点不同。
 * - [SessionExpired]：302 到身份域 / 401、403 / 200 + HTML——提示重新登录；
 * - [ServiceBusy]：5xx；[NetworkError]：请求抛异常；[Timeout]：超时；
 * - [FormatChanged]：状态码/JSON 结构不符合约定。
 */
internal sealed interface XmuJwApiResult<out T> {
    data class Ok<T>(val value: T) : XmuJwApiResult<T>
    data object SessionExpired : XmuJwApiResult<Nothing>
    data object ServiceBusy : XmuJwApiResult<Nothing>
    data object NetworkError : XmuJwApiResult<Nothing>
    data object Timeout : XmuJwApiResult<Nothing>
    data object FormatChanged : XmuJwApiResult<Nothing>
}

/** 学年学期：[code] 形如 `20261`（2026-2027 学年秋季学期），[label] 为教务展示文案 */
@Serializable
internal data class XmuJwSemesterDto(
    @SerialName("XNXQDM") val code: String? = null,
    @SerialName("XNXQDM_DISPLAY") val label: String? = null,
)

/** 上课节次：[code] 节次代码（1..11，另有 `12`=中午）、KSSJ/JSSJ 为 HHmm 整数（800=8:00） */
@Serializable
internal data class XmuJwSkjcDto(
    @SerialName("DM") val code: String? = null,
    @SerialName("MC") val name: String? = null,
    @SerialName("PX") val order: Int? = null,
    @SerialName("KSSJ") val startTime: Int? = null,
    @SerialName("JSSJ") val endTime: Int? = null,
)

/** 排课结果一行 = 一门课的一个时段占位；[weekBitmap] 第 i 位为 '1' 表示第 i+1 周上课 */
@Serializable
internal data class XmuJwPkjgDto(
    @SerialName("KCMC") val courseName: String? = null,
    @SerialName("KCDM") val courseCode: String? = null,
    @SerialName("JSXM") val teacher: String? = null,
    @SerialName("JASMC") val location: String? = null,
    @SerialName("XQ") val weekday: Int? = null,
    @SerialName("KSJCDM") val startSlotCode: Int? = null,
    @SerialName("JSJCDM") val endSlotCode: Int? = null,
    @SerialName("ZCBH") val weekBitmap: String? = null,
    @SerialName("KBBZ") val note: String? = null,
)

@Serializable
private data class XmuJwSemesterEnvelopeDto(
    val datas: XmuJwSemesterDatasDto? = null,
)

@Serializable
private data class XmuJwSemesterDatasDto(
    @SerialName("kfdxnxqcx") val semesters: XmuJwSemesterRowsDto? = null,
)

@Serializable
private data class XmuJwSemesterRowsDto(
    val rows: List<XmuJwSemesterDto> = emptyList(),
)

@Serializable
private data class XmuJwSkjcEnvelopeDto(
    val data: List<XmuJwSkjcDto> = emptyList(),
)

@Serializable
private data class XmuJwZcxxDto(
    @SerialName("currentZc") val currentZc: String? = null,
)

@Serializable
private data class XmuJwPkjgEnvelopeDto(
    @SerialName("pkjgList") val pkjgList: List<XmuJwPkjgDto> = emptyList(),
)

/**
 * XMU 全校统一作息（与教务网页端一致，上课时间全校固定）：作息接口未下发
 * （200 空应答/接口缺失/瞬时异常）时的兜底，交由 [XmuJwScheduleImporter] 选用。
 * 含「中午」占位行（DM=12），由映射器剔除。
 */
internal val XMU_JW_STANDARD_SLOTS: List<XmuJwSkjcDto> = listOf(
    XmuJwSkjcDto(code = "1", name = "第1节", order = 1, startTime = 800, endTime = 845),
    XmuJwSkjcDto(code = "2", name = "第2节", order = 2, startTime = 855, endTime = 940),
    XmuJwSkjcDto(code = "3", name = "第3节", order = 3, startTime = 1010, endTime = 1055),
    XmuJwSkjcDto(code = "4", name = "第4节", order = 4, startTime = 1105, endTime = 1150),
    XmuJwSkjcDto(code = "12", name = "中午", order = 12, startTime = 1200, endTime = 1400),
    XmuJwSkjcDto(code = "5", name = "第5节", order = 5, startTime = 1430, endTime = 1515),
    XmuJwSkjcDto(code = "6", name = "第6节", order = 6, startTime = 1525, endTime = 1610),
    XmuJwSkjcDto(code = "7", name = "第7节", order = 7, startTime = 1640, endTime = 1725),
    XmuJwSkjcDto(code = "8", name = "第8节", order = 8, startTime = 1735, endTime = 1820),
    XmuJwSkjcDto(code = "9", name = "第9节", order = 9, startTime = 1910, endTime = 1955),
    XmuJwSkjcDto(code = "10", name = "第10节", order = 10, startTime = 2005, endTime = 2050),
    XmuJwSkjcDto(code = "11", name = "第11节", order = 11, startTime = 2100, endTime = 2145),
)

/**
 * 教务「我的课表」原生取数（POST 表单 + jw 域会话 cookie），判定口径同
 * [XmuNativeTodoApi]：200 且正文 JSON → 有效；200 HTML / 跳身份域 / 401、403 →
 * 会话过期；5xx → 繁忙；异常按 Timeout / NetworkError / FormatChanged 分类。
 *
 * **会话 cookie 用 [XmuCookieJar] 维护并随响应演进**：教务接口前置负载均衡会下发
 * `route_amp` 之类的路由 cookie 并 302 回同一地址（相对 Location），必须带着新 cookie
 * 跟随重定向才会落到持有会话的节点——浏览器/XHR 天然如此，原生客户端必须自己实现。
 * 只有跳往**其他主机**（身份域 CAS 等）才判定为未登录。
 */
internal class XmuJwScheduleApi(
    private val transport: XmuHttpTransport,
    private val cookieJar: XmuCookieJar,
    private val baseUrl: String = "https://${XmuJwLoginClient.JW_HOST}",
) {

    /**
     * 业务接口全部挂在「我的课表」应用上下文内（`/gsapp/sys/wdkbapp/`，登录链路
     * 打开的正是该应用页）：教务按 EMAP 应用隔离接口，站点根路径下的同名接口已随
     * 旧版教务下线——根路径实测登录成功后仍 404，走应用路径才可达。
     *
     * 目录按实测分两族（与参考实现 democard/xmu_assistant 一致）：学期列表是 EMAP
     * 标准模块（`modules/xskcb/`），课表三件套挂在应用自定义目录 `wdkcb/`——放错目录
     * 会被 EMAP 分发器以 200 + `{"datas":null}` 一类的空应答顶回来，不报 404。
     */
    private val appBase = "gsapp/sys/wdkbapp"

    /** 学年学期列表（教务自身按新学期在前排序，原样保留） */
    suspend fun fetchSemesters(): XmuJwApiResult<List<XmuJwSemesterDto>> =
        post("$appBase/modules/xskcb/kfdxnxqcx.do", emptyMap()) { body ->
            val rows = ScheduleJson.decodeStorage<XmuJwSemesterEnvelopeDto>(body)
                .datas?.semesters?.rows
                ?: return@post null
            XmuJwApiResult.Ok(rows.filter { !it.code.isNullOrBlank() && !it.label.isNullOrBlank() })
        }

    /** 上课节次时间表（含「中午」行，由映射器剔除）；接口空应答时由调用方用内置作息兜底 */
    suspend fun fetchSlotTimes(xnxqdm: String): XmuJwApiResult<List<XmuJwSkjcDto>> =
        post("$appBase/wdkcb/queryXsskjc.do", mapOf("XNXQDM" to xnxqdm)) { body ->
            XmuJwApiResult.Ok(ScheduleJson.decodeStorage<XmuJwSkjcEnvelopeDto>(body).data)
        }

    /** 当前周次；教务在假期等场景可能给非预期值，交由调用方钳制 */
    suspend fun fetchCurrentWeek(xnxqdm: String): XmuJwApiResult<Int?> =
        post("$appBase/wdkcb/getZcxx.do", mapOf("XNXQDM" to xnxqdm)) { body ->
            XmuJwApiResult.Ok(
                ScheduleJson.decodeStorage<XmuJwZcxxDto>(body).currentZc?.trim()?.toIntOrNull(),
            )
        }

    /** 全学期排课结果（不传 ZC：服务端把 ZC 当过滤条件，网页端也不传，返回全学期全部记录） */
    suspend fun fetchSchedule(xnxqdm: String, studentId: String): XmuJwApiResult<List<XmuJwPkjgDto>> =
        post(
            "$appBase/wdkcb/queryXspkjg.do",
            mapOf("XNXQDM" to xnxqdm, "XH" to studentId),
        ) { body ->
            XmuJwApiResult.Ok(ScheduleJson.decodeStorage<XmuJwPkjgEnvelopeDto>(body).pkjgList)
        }

    private suspend fun <T> post(
        path: String,
        fields: Map<String, String>,
        parse: suspend (body: String) -> XmuJwApiResult<T>?,
    ): XmuJwApiResult<T> {
        val result = try {
            withTimeout(TOTAL_TIMEOUT_MS) { postOnce(path, fields, parse) }
        } catch (_: TimeoutCancellationException) {
            XmuJwApiResult.Timeout
        } catch (e: CancellationException) {
            throw e
        } catch (_: SocketTimeoutException) {
            XmuJwApiResult.Timeout
        } catch (_: IOException) {
            XmuJwApiResult.NetworkError
        } catch (_: Exception) {
            XmuJwApiResult.FormatChanged
        }
        XmuDebugLog.d("jwApi $path -> ${result::class.simpleName}")
        return result
    }

    private suspend fun <T> postOnce(
        path: String,
        fields: Map<String, String>,
        parse: suspend (body: String) -> XmuJwApiResult<T>?,
    ): XmuJwApiResult<T> {
        var url = "$baseUrl/$path"
        var method = "POST"
        var formBody: String? = fields.entries.joinToString("&") { (key, value) ->
            "${formEncode(key)}=${formEncode(value)}"
        }
        var hops = 0
        while (true) {
            val sent = cookieJar.headerFor(url)
            val response = transport.execute(
                XmuHttpRequest(
                    method = method,
                    url = url,
                    headers = buildMap {
                        // 请求头与参考实现一致：JSON Accept + 门户首页 Referer
                        put("Accept", "application/json, text/plain, */*")
                        put("Referer", XmuJwLoginClient.API_REFERER)
                        sent?.let { put("Cookie", it) }
                    },
                    formBody = formBody,
                ),
            )
            cookieJar.store(url, response.setCookies)
            // 诊断：状态码、跳转目标(仅 host/path/参数名)、响应形态、携带/下发的 cookie 名
            XmuDebugLog.d(
                "jwApiCall $path status=${response.status}" +
                    " sentCookieNames=[${headerCookieNames(sent)}]" +
                    " setCookies=[${cookieNames(response.setCookies)}]" +
                    " body=${bodyKind(response.body)}" +
                    (response.location?.let { " loc=${locationLabel(it, url)}" } ?: ""),
            )
            when {
                response.status in 500..599 -> return XmuJwApiResult.ServiceBusy
                response.status == 401 || response.status == 403 -> return XmuJwApiResult.SessionExpired
                response.status in 300..399 -> {
                    val location = response.location ?: return XmuJwApiResult.FormatChanged
                    val next = resolve(url, location) ?: return XmuJwApiResult.FormatChanged
                    // 跳往其他主机（身份域 CAS 等）= 未登录；同站重定向 = 负载均衡路由，
                    // 带着新下发的 cookie 跟随（浏览器/XHR 同款行为）
                    if (hostOf(next) != XmuJwLoginClient.JW_HOST) return XmuJwApiResult.SessionExpired
                    if (++hops > MAX_REDIRECT_HOPS) return XmuJwApiResult.FormatChanged
                    url = next
                    method = "GET"
                    formBody = null
                }
                response.status in 200..299 -> {
                    // 被重定向到登录页时有些链路返回 200 + HTML，按过期处理；
                    // 教务同族响应可能前置 BOM（参考实现实证），先剥离再判定/解析
                    val body = response.body.trimStart('\uFEFF')
                    if (!body.trimStart().startsWith("{")) {
                        return XmuJwApiResult.SessionExpired
                    } else {
                        return parse(body) ?: XmuJwApiResult.FormatChanged
                    }
                }
                else -> return XmuJwApiResult.FormatChanged
            }
        }
    }

    private fun hostOf(url: String): String? = try {
        java.net.URL(url).host?.lowercase()
    } catch (_: Exception) {
        null
    }

    private fun resolve(base: String, location: String): String? = try {
        java.net.URL(java.net.URL(base), location).toString()
    } catch (_: Exception) {
        null
    }

    private fun formEncode(value: String): String = try {
        java.net.URLEncoder.encode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }

    /** 响应形态：json / html / other(长度)——判断是否被登录页或网关拦截页替换 */
    private fun bodyKind(body: String): String {
        val trimmed = body.trimStart()
        return when {
            trimmed.startsWith("{") -> "json(${body.length})"
            trimmed.startsWith("<") -> "html(${body.length})"
            body.isEmpty() -> "empty"
            else -> "other(${body.length})"
        }
    }

    /** 跳转目标仅记 host/path/参数名（Location 可能含 ticket 等，不落值；可为相对路径） */
    private fun locationLabel(location: String, base: String): String {
        val resolved = resolve(base, location) ?: location
        val host = try {
            java.net.URL(resolved).host?.lowercase() ?: "?"
        } catch (_: Exception) {
            return "?"
        }
        val path = try {
            java.net.URL(resolved).path.orEmpty()
        } catch (_: Exception) {
            ""
        }
        val query = try {
            java.net.URL(resolved).query
        } catch (_: Exception) {
            null
        }
        val names = query?.split('&')
            ?.map { it.substringBefore('=') }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        return "$host$path" + if (names.isEmpty()) "" else "?${names.joinToString("&")}"
    }

    private fun cookieNames(setCookies: List<String>): String =
        setCookies.mapNotNull { raw ->
            raw.substringBefore('=').trim().takeIf { it.isNotEmpty() }
        }.joinToString(",").ifEmpty { "-" }

    /** Cookie 请求头里携带的 cookie 名（值不落日志） */
    private fun headerCookieNames(header: String?): String {
        if (header.isNullOrBlank()) return "-"
        return header.split(';')
            .mapNotNull { part ->
                part.substringBefore('=').trim().takeIf { it.isNotEmpty() }
            }
            .joinToString(",")
            .ifEmpty { "-" }
    }

    private companion object {
        const val TOTAL_TIMEOUT_MS = 30_000L

        /** 业务请求的同站路由重定向上限（LB 一般 1 跳），超过即视为异常 */
        const val MAX_REDIRECT_HOPS = 3
    }
}
