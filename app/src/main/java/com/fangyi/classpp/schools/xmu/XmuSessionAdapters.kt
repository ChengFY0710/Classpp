package com.fangyi.classpp.schools.xmu

import com.fangyi.classpp.data.sync.LoginRaw
import com.fangyi.classpp.data.sync.credential.LoginClient
import com.fangyi.classpp.data.sync.credential.LoginResult
import com.fangyi.classpp.data.sync.credential.LoginSession
import com.fangyi.classpp.data.sync.engine.FetchError
import com.fangyi.classpp.data.sync.engine.FetchOutcome
import com.fangyi.classpp.data.sync.engine.SessionApi
import com.fangyi.classpp.data.sync.net.HttpRequest
import com.fangyi.classpp.data.sync.net.HttpResponse
import com.fangyi.classpp.data.sync.net.SessionCookieHolder
import com.fangyi.classpp.data.xmu.XmuHttpRequest
import com.fangyi.classpp.data.xmu.XmuHttpResponse
import com.fangyi.classpp.data.xmu.XmuHttpTransport
import com.fangyi.classpp.data.xmu.XmuLoginClient
import com.fangyi.classpp.data.xmu.XmuLoginOutcome
import com.fangyi.classpp.data.xmu.XmuNativeTodoApi
import com.fangyi.classpp.data.xmu.XmuFetchResult
import com.fangyi.classpp.data.xmu.XmuSessionCookieHolder

/**
 * 厦大协议对象与通用契约之间的适配层（一一同构映射，不做兜底合并）：
 *
 * - [XmuTransportAdapter]：通用 [com.fangyi.classpp.data.sync.net.HttpTransport]（生产 =
 *   `OkHttpTransport`，DNS 策略由装配处注入）桥接给冻结的登录客户端与教务链路——
 *   它们只认 `XmuHttpRequest/XmuHttpResponse` 的冻结形态；
 * - [XmuNativeSessionApi]：冻结 [XmuNativeTodoApi]（带 cookie 直取 /api/profile + /api/todos）
 *   接到通用 [SessionApi]。冻结取数只认冻结 cookie holder，每次取数前把通用 holder 的
 *   会话头同步过去（[com.fangyi.classpp.data.sync.credential.AutoRenewManager] 是通用
 *   holder 的唯一写入方，单飞纪律保证同步期间无并发写）；
 * - [XmuNativeLoginClient]：冻结 OAuth/CAS 登录链路接到通用 [LoginClient]。
 */
internal class XmuTransportAdapter(
    private val delegate: com.fangyi.classpp.data.sync.net.HttpTransport,
) : XmuHttpTransport {

    override suspend fun execute(request: XmuHttpRequest): XmuHttpResponse {
        val response = delegate.execute(
            HttpRequest(
                method = request.method,
                url = request.url,
                headers = request.headers,
                formBody = request.formBody,
                jsonBody = request.jsonBody,
            ),
        )
        return XmuHttpResponse(
            status = response.status,
            location = response.location,
            setCookies = response.setCookies,
            body = response.body,
        )
    }
}

internal class XmuNativeSessionApi(
    transport: XmuHttpTransport,
    private val session: SessionCookieHolder,
) : SessionApi {

    private val frozenSession = XmuSessionCookieHolder()
    private val native = XmuNativeTodoApi(transport = transport, session = frozenSession)

    override suspend fun fetchSession(): FetchOutcome {
        frozenSession.header = session.header
        return when (val result = native.fetchSession()) {
            is XmuFetchResult.Ok -> FetchOutcome.Ok(LoginRaw(result.profileRaw, result.todosRaw))
            XmuFetchResult.SessionExpired -> FetchOutcome.Failed(FetchError.SessionExpired)
            XmuFetchResult.ServiceBusy -> FetchOutcome.Failed(FetchError.ServiceBusy)
            XmuFetchResult.NetworkError -> FetchOutcome.Failed(FetchError.NetworkError)
            XmuFetchResult.Timeout -> FetchOutcome.Failed(FetchError.Timeout)
            XmuFetchResult.FormatChanged -> FetchOutcome.Failed(FetchError.FormatChanged)
        }
    }
}

internal class XmuNativeLoginClient(
    private val delegate: XmuLoginClient,
) : LoginClient {

    override suspend fun login(username: String, password: String): LoginResult =
        when (val outcome = delegate.login(username, password)) {
            is XmuLoginOutcome.Ok ->
                LoginResult.Ok(LoginSession(cookieHeader = outcome.cookieHeader, cookies = outcome.cookies))
            XmuLoginOutcome.NeedCaptcha -> LoginResult.NeedCaptcha
            XmuLoginOutcome.InvalidCredentials -> LoginResult.InvalidCredentials
            XmuLoginOutcome.ServiceBusy -> LoginResult.ServiceBusy
            XmuLoginOutcome.NetworkError -> LoginResult.NetworkError
            XmuLoginOutcome.Timeout -> LoginResult.Timeout
            XmuLoginOutcome.FormatChanged -> LoginResult.FormatChanged
        }
}
