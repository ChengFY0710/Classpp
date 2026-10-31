package com.fangyi.classpp.data.xmu

/**
 * [XmuTodoApi] 的路由层：自动续期开启且凭据可用时走原生 HTTP 会话，否则原样委托
 * [XmuWebFetcher]（方案 A，行为与本次改动前完全一致）。
 *
 * 判断只看 [XmuAutoLoginManager.state] 的快照，不做任何网络探测——路由本身不能引入
 * 额外请求，也不能因为自动续期不可用而阻断原有 WebView 路径。
 */
internal class XmuTodoApiRouter(
    private val webFetcher: XmuTodoApi,
    private val autoLogin: XmuAutoLoginManager,
) : XmuTodoApi {

    override suspend fun fetchSession(): XmuFetchResult {
        val state = autoLogin.state.value
        return if (state.available &&
            state.policy == XmuAutoLoginPolicy.ENABLED &&
            state.hasCredentials
        ) {
            autoLogin.fetchSession()
        } else {
            webFetcher.fetchSession()
        }
    }
}
