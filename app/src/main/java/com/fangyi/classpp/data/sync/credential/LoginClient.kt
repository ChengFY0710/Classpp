package com.fangyi.classpp.data.sync.credential

/**
 * 原生登录结果（与既有登录链路分类一一同构）：
 * - [NeedCaptcha] / [InvalidCredentials]：认证失败一次即 BLOCKED 并删凭据，绝不自动重试；
 * - [ServiceBusy] / [NetworkError] / [Timeout]：瞬时错误，保持 ENABLED 但进入退避；
 * - [FormatChanged]：登录页结构变化，BLOCKED（需要适配者更新解析）。
 */
sealed interface LoginResult {
    data class Ok(val session: LoginSession) : LoginResult
    data object NeedCaptcha : LoginResult
    data object InvalidCredentials : LoginResult
    data object ServiceBusy : LoginResult
    data object NetworkError : LoginResult
    data object Timeout : LoginResult
    data object FormatChanged : LoginResult
}

/**
 * 一次成功登录拿到的会话凭据：
 * - [cookieHeader]：回传给业务取数的 `Cookie` 头（如 `session=S1; role_token=R1`）；
 * - [cookies]：name → value，用于镜像进 WebView（可选）。
 */
data class LoginSession(
    val cookieHeader: String,
    val cookies: Map<String, String> = emptyMap(),
)

/**
 * 原生登录客户端契约（学校实现）：只需负责「账号密码 → 会话凭据」这一段协议，
 * 单飞/失败即停/退避/持久化全部由 [AutoRenewManager] 统一编排。
 *
 * 预期实现使用 `data.sync.net.HttpTransport` 发请求；密码只提交给学校声明的身份主机，
 * 日志不带账号/密码/cookie/URL 参数（安全纪律见指导 §五.5）。
 */
fun interface LoginClient {
    suspend fun login(username: String, password: String): LoginResult
}
