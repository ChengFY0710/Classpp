package com.fangyi.classpp.data.xmu

/**
 * 课程中心只读取数接口。一次会话 = 探活（/api/profile）+ 拉待办（/api/todos），
 * 在同一个 lnt.xmu.edu.cn 页面上下文里同源 fetch 完成（httpOnly cookie 由 WebView 自动携带）。
 *
 * MVP 实现：[XmuWebFetcher]（无头 WebView）；可见登录流程直接驱动用户正在操作的 WebView，
 * 不经此接口。测试注入 fake。
 */
interface XmuTodoApi {

    /** 拉一次完整会话快照；两个响应体都返回原文（解析与分类在同步仓库做，便于单测） */
    suspend fun fetchSession(): XmuFetchResult
}

/**
 * 会话结果分类（指导 §5.5/§6.1）：
 * - [SessionExpired]：被 302 到身份域 / 返回登录页 HTML / 401、403——提示重新登录，**不是**所有错误的兜底；
 * - [ServiceBusy]：5xx；[NetworkError]：请求抛异常（status 0）；[Timeout]：页面/轮询超时；
 * - [FormatChanged]：原生登录链路的结构/字段变化（WebView 路径不会返回它，页内原文
 *   由同步仓库的 `classifyBody` 判定）。
 */
sealed interface XmuFetchResult {
    data class Ok(val profileRaw: String, val todosRaw: String) : XmuFetchResult
    data object SessionExpired : XmuFetchResult
    data object ServiceBusy : XmuFetchResult
    data object NetworkError : XmuFetchResult
    data object Timeout : XmuFetchResult
    data object FormatChanged : XmuFetchResult
}
