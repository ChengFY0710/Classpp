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
 * 教务「我的课表」原生取数（POST 表单 + jw 域会话 cookie），判定口径同
 * [XmuNativeTodoApi]：200 且正文 JSON → 有效；200 HTML / 3xx 到身份域 / 401、403 →
 * 会话过期；5xx → 繁忙；异常按 Timeout / NetworkError / FormatChanged 分类。
 */
internal class XmuJwScheduleApi(
    private val transport: XmuHttpTransport,
    private val session: XmuSessionCookieHolder,
    private val baseUrl: String = "https://${XmuJwLoginClient.JW_HOST}",
) {

    /** 学年学期列表（教务自身按新学期在前排序，原样保留） */
    suspend fun fetchSemesters(): XmuJwApiResult<List<XmuJwSemesterDto>> =
        post("modules/xskcb/kfdxnxqcx.do", emptyMap()) { body ->
            val rows = ScheduleJson.decodeStorage<XmuJwSemesterEnvelopeDto>(body)
                .datas?.semesters?.rows
                ?: return@post null
            XmuJwApiResult.Ok(rows.filter { !it.code.isNullOrBlank() && !it.label.isNullOrBlank() })
        }

    /** 上课节次时间表（含「中午」行，由映射器剔除） */
    suspend fun fetchSlotTimes(xnxqdm: String): XmuJwApiResult<List<XmuJwSkjcDto>> =
        post("wdkcb/queryXsskjc.do", mapOf("XNXQDM" to xnxqdm)) { body ->
            XmuJwApiResult.Ok(ScheduleJson.decodeStorage<XmuJwSkjcEnvelopeDto>(body).data)
        }

    /** 当前周次；教务在假期等场景可能给非预期值，交由调用方钳制 */
    suspend fun fetchCurrentWeek(xnxqdm: String): XmuJwApiResult<Int?> =
        post("wdkcb/getZcxx.do", mapOf("XNXQDM" to xnxqdm)) { body ->
            XmuJwApiResult.Ok(
                ScheduleJson.decodeStorage<XmuJwZcxxDto>(body).currentZc?.trim()?.toIntOrNull(),
            )
        }

    /** 全学期排课结果（ZC 传空 = 不限周次） */
    suspend fun fetchSchedule(xnxqdm: String, studentId: String): XmuJwApiResult<List<XmuJwPkjgDto>> =
        post(
            "wdkcb/queryXspkjg.do",
            mapOf("XNXQDM" to xnxqdm, "XH" to studentId, "ZC" to ""),
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
        val url = "$baseUrl/$path"
        val response = transport.execute(
            XmuHttpRequest(
                method = "POST",
                url = url,
                headers = buildMap {
                    put("Accept", "application/json")
                    session.header?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
                },
                formBody = fields.entries.joinToString("&") { (key, value) ->
                    "${formEncode(key)}=${formEncode(value)}"
                },
            ),
        )
        return when {
            response.status in 500..599 -> XmuJwApiResult.ServiceBusy
            response.status == 401 || response.status == 403 -> XmuJwApiResult.SessionExpired
            response.status in 300..399 ->
                if (isIdentityLocation(response.location)) {
                    XmuJwApiResult.SessionExpired
                } else {
                    XmuJwApiResult.FormatChanged
                }
            response.status in 200..299 ->
                // 被重定向到登录页时有些链路返回 200 + HTML，按过期处理
                if (!response.body.trimStart().startsWith("{")) {
                    XmuJwApiResult.SessionExpired
                } else {
                    parse(response.body) ?: XmuJwApiResult.FormatChanged
                }
            else -> XmuJwApiResult.FormatChanged
        }
    }

    /** 过期后业务接口是 302 到身份域（不是 401），Location 也可能只是相对 /login */
    private fun isIdentityLocation(location: String?): Boolean {
        val value = location ?: return false
        return value.contains("c-identity.xmu.edu.cn", ignoreCase = true) ||
            value.contains("ids.xmu.edu.cn", ignoreCase = true) ||
            value.contains("/login", ignoreCase = true)
    }

    private fun formEncode(value: String): String = try {
        java.net.URLEncoder.encode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }

    private companion object {
        const val TOTAL_TIMEOUT_MS = 30_000L
    }
}
