package com.fangyi.classpp.data.xmu

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Schedule
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 教务导入的失败分类（UI 按类给文案） */
enum class XmuJwImportError {
    /** 未连接或未保存凭据：引导先去「账号与同步」登录 */
    NeedLogin,

    /** 教务系统要求验证码：无法无头登录，需稍后重试或先完成一次网页登录 */
    NeedCaptcha,

    /** 学号或密码验证失败：引导去「账号与同步」重新登录 */
    InvalidCredentials,

    /** 会话建立失败（登录后业务请求仍过期） */
    SessionExpired,
    ServiceBusy,
    NetworkError,
    Timeout,

    /** 教务登录链路形态异常（CAS 页面改版/意外跳转）：与接口异常分开提示 */
    LoginFormatChanged,

    /** 业务接口返回无法解析的内容 */
    FormatChanged,
}

/** 学期列表结果 */
sealed interface XmuJwSemestersResult {
    data class Ok(val semesters: List<XmuJwSemester>) : XmuJwSemestersResult
    data class Failed(val error: XmuJwImportError) : XmuJwSemestersResult
}

/** 课表导入结果：[Ok] 的课表已映射完毕，交由确认弹窗展示后走既有导入落库 */
sealed interface XmuJwScheduleResult {
    data class Ok(val schedule: Schedule) : XmuJwScheduleResult
    data object NoCourses : XmuJwScheduleResult
    data class Failed(val error: XmuJwImportError) : XmuJwScheduleResult
}

/** 可导入学期（教务学期代码 + 展示文案） */
data class XmuJwSemester(val code: String, val label: String)

/** 一学期课表的原始取数包（节次表 + 排课结果 + 当前周次）；多请求任一必需项失败即短路 */
private sealed interface XmuJwBundleResult {
    data class Ok(val bundle: XmuJwScheduleRaw) : XmuJwBundleResult
    data class Err(val error: XmuJwApiResult<*>) : XmuJwBundleResult
}

/**
 * 教务导入编排层：会话过期自动重登一次 + 瞬时失败退避，随后把取到的原始数据交给
 * [XmuJwScheduleMapper] 映射成课表。
 *
 * 与通用 AutoRenewManager 的边界：这里**只读**已存凭据（[credentialsProvider]），
 * 登录只对教务站（jw 域）建立会话——任何失败都**不触碰**课程中心的登录态与凭据策略
 * （不 BLOCKED、不删凭据），用户在课程中心的同步不受影响。
 *
 * 教务会话只在内存 [cookieJar]（与 [XmuJwScheduleApi] 共享同一实例）：登录成功后把
 * 凭据写入罐中，业务请求随响应的 Set-Cookie 继续演进（负载均衡路由 cookie 等）。
 */
class XmuJwScheduleImporter internal constructor(
    private val loginClient: XmuJwLoginClient,
    private val api: XmuJwScheduleApi,
    private val credentialsProvider: suspend () -> XmuCredentials?,
    private val cookieJar: XmuCookieJar,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /** 瞬时失败后的退避截止时刻（wall clock）与上次错误 */
    private var backoffUntilMillis = 0L
    private var backoffError: XmuJwImportError? = null

    /** 可导入学期（浮层打开时调用）；会话无效则登录一次后重试一次 */
    suspend fun fetchSemesters(): XmuJwSemestersResult = mutex.withLock {
        if (isBackingOff()) return@withLock XmuJwSemestersResult.Failed(backoffFailed())
        val credentials = credentialsProvider()
        if (credentials == null || credentials.username.isBlank() || credentials.password.isEmpty()) {
            return@withLock XmuJwSemestersResult.Failed(XmuJwImportError.NeedLogin)
        }
        when (val first = api.fetchSemesters()) {
            is XmuJwApiResult.Ok -> XmuJwSemestersResult.Ok(first.value.toSemesters())
            is XmuJwApiResult.SessionExpired -> {
                loginJw()?.let { return@withLock XmuJwSemestersResult.Failed(it) }
                when (val retried = api.fetchSemesters()) {
                    is XmuJwApiResult.Ok -> {
                        backoffUntilMillis = 0
                        XmuJwSemestersResult.Ok(retried.value.toSemesters())
                    }
                    else -> XmuJwSemestersResult.Failed(recordBackoff(retried))
                }
            }
            else -> XmuJwSemestersResult.Failed(recordBackoff(first))
        }
    }

    /**
     * 拉取选中学期的课表并映射为课表对象（作息 + 周次 + 排课结果同一会话）；会话无效
     * 则登录一次后重试一次。周次信息尽力而为——它只影响开学日推算，失败不阻断导入。
     */
    suspend fun fetchSchedule(
        xnxqdm: String,
        fallbackName: String,
        today: IsoDate = IsoDate.today(),
    ): XmuJwScheduleResult = mutex.withLock {
        if (isBackingOff()) return@withLock XmuJwScheduleResult.Failed(backoffFailed())
        val credentials = credentialsProvider()
        if (credentials == null || credentials.username.isBlank() || credentials.password.isEmpty()) {
            return@withLock XmuJwScheduleResult.Failed(XmuJwImportError.NeedLogin)
        }
        val studentId = credentials.username.trim()

        suspend fun finish(bundle: XmuJwScheduleRaw): XmuJwScheduleResult {
            val label = semesterLabelOf(xnxqdm).orEmpty()
            val schedule = XmuJwScheduleMapper.toScheduleOrNull(
                bundle.copy(semesterLabel = label, fallbackName = fallbackName, today = today),
            )
            return if (schedule != null) {
                backoffUntilMillis = 0
                XmuJwScheduleResult.Ok(schedule)
            } else {
                XmuJwScheduleResult.NoCourses
            }
        }

        when (val first = fetchBundle(xnxqdm, studentId)) {
            is XmuJwBundleResult.Ok -> finish(first.bundle)
            is XmuJwBundleResult.Err -> when (first.error) {
                is XmuJwApiResult.SessionExpired -> {
                    loginJw()?.let { return@withLock XmuJwScheduleResult.Failed(it) }
                    when (val retried = fetchBundle(xnxqdm, studentId)) {
                        is XmuJwBundleResult.Ok -> finish(retried.bundle)
                        is XmuJwBundleResult.Err ->
                            XmuJwScheduleResult.Failed(recordBackoff(retried.error))
                    }
                }
                else -> XmuJwScheduleResult.Failed(recordBackoff(first.error))
            }
        }
    }

    // ---------- 内部 ----------

    /**
     * 作息 + 周次 + 排课结果：周次只影响开学日推算，除会话过期外失败均静默降级。
     * 作息是全校固定的作息表——接口空应答（EMAP 对未知动作回 200 空数据）或瞬时异常
     * 时用内置作息兜底，不阻断导入；只有会话过期仍向上传播触发重登。
     */
    private suspend fun fetchBundle(xnxqdm: String, studentId: String): XmuJwBundleResult {
        val slots = when (val r = api.fetchSlotTimes(xnxqdm)) {
            is XmuJwApiResult.Ok -> r.value.ifEmpty { XMU_JW_STANDARD_SLOTS }
            is XmuJwApiResult.SessionExpired -> return XmuJwBundleResult.Err(r)
            else -> XMU_JW_STANDARD_SLOTS
        }
        val currentZc = when (val r = api.fetchCurrentWeek(xnxqdm)) {
            is XmuJwApiResult.Ok -> r.value
            is XmuJwApiResult.SessionExpired -> return XmuJwBundleResult.Err(r)
            else -> null
        }
        val courses = when (val r = api.fetchSchedule(xnxqdm, studentId)) {
            is XmuJwApiResult.Ok -> r.value
            else -> return XmuJwBundleResult.Err(r)
        }
        return XmuJwBundleResult.Ok(
            XmuJwScheduleRaw(
                semesterLabel = "",
                slots = slots,
                courses = courses,
                currentZc = currentZc,
                fallbackName = "",
            ),
        )
    }

    /** 学期文案（课表名）：主流程成功后的轻量补充，失败不阻断导入 */
    private suspend fun semesterLabelOf(xnxqdm: String): String? =
        when (val r = api.fetchSemesters()) {
            is XmuJwApiResult.Ok -> r.value.firstOrNull { it.code == xnxqdm }?.label
            else -> null
        }

    /**
     * 用已存凭据登录教务站一次并写入 jw 会话。只建立 jw 会话，不读改课程中心的
     * cookie 与策略；返回 null = 成功，否则返回对应失败分类（UI 按类给文案）。
     */
    private suspend fun loginJw(): XmuJwImportError? {
        val credentials = credentialsProvider()
        if (credentials == null || credentials.username.isBlank() || credentials.password.isEmpty()) {
            return XmuJwImportError.NeedLogin
        }
        return when (val outcome = loginClient.login(credentials.username.trim(), credentials.password)) {
            is XmuLoginOutcome.Ok -> {
                cookieJar.putCookies(XmuJwLoginClient.JW_HOST, outcome.cookies)
                null
            }
            XmuLoginOutcome.NeedCaptcha -> XmuJwImportError.NeedCaptcha
            XmuLoginOutcome.InvalidCredentials -> XmuJwImportError.InvalidCredentials
            XmuLoginOutcome.FormatChanged -> XmuJwImportError.LoginFormatChanged
            XmuLoginOutcome.ServiceBusy -> XmuJwImportError.ServiceBusy
            XmuLoginOutcome.NetworkError -> XmuJwImportError.NetworkError
            XmuLoginOutcome.Timeout -> XmuJwImportError.Timeout
        }
    }

    private fun List<XmuJwSemesterDto>.toSemesters(): List<XmuJwSemester> =
        map { XmuJwSemester(code = it.code.orEmpty(), label = it.label.orEmpty()) }

    private fun recordBackoff(error: XmuJwApiResult<*>): XmuJwImportError {
        val mapped = when (error) {
            is XmuJwApiResult.SessionExpired -> XmuJwImportError.SessionExpired
            is XmuJwApiResult.ServiceBusy -> XmuJwImportError.ServiceBusy
            is XmuJwApiResult.NetworkError -> XmuJwImportError.NetworkError
            is XmuJwApiResult.Timeout -> XmuJwImportError.Timeout
            else -> XmuJwImportError.FormatChanged
        }
        // 会话过期是「可立即重登」的确定状态，不退避；其余瞬时失败 60 秒内不打教务站
        if (mapped != XmuJwImportError.SessionExpired) {
            backoffUntilMillis = nowMillis() + BACKOFF_MS
            backoffError = mapped
        }
        return mapped
    }

    private fun backoffFailed(): XmuJwImportError =
        backoffError ?: XmuJwImportError.FormatChanged

    /**
     * 退避判定：wall clock 回拨后 `backoffUntil - now` 会远大于退避时长，
     * 此时必须视为已过期，否则用户会被「一直在退避」卡住（同通用 AutoRenewManager）。
     */
    private fun isBackingOff(): Boolean {
        val remaining = backoffUntilMillis - nowMillis()
        return remaining > 0 && remaining <= BACKOFF_MS
    }

    private companion object {
        /** 瞬时失败后的最小重试间隔（指导 §6.3 同款：60 秒内不打教务站） */
        const val BACKOFF_MS = 60_000L
    }
}
