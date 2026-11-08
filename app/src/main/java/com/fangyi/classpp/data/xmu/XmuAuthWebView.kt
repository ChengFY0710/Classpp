package com.fangyi.classpp.data.xmu

/**
 * 课程中心 WebView 的冻结常量（指导 §3.1）。
 *
 * WebView 的加固策略与会话驱动已由通用层接管
 * （`data/sync/webview/WebViewPolicy` + `WebSessionDriver`，行为与原实现逐行同构，
 * 由 `WebViewLoginSpec` 声明驱动）；本对象只保留登录链路与装配声明引用的协议常量：
 *
 * - [LNT_HOST]：课程中心业务域（原生取数 baseUrl、cookie 镜像域、头像域判定）；
 * - [ENTRY_URL]：`?version=v1` 与站内入口一致，绕开旧缓存；
 * - [DESKTOP_USER_AGENT]：lnt 按 UA 分流——移动 UA 会被 302 到 c-mobile 门户
 *   （不在白名单 → 导航被吞、登录页白屏），桌面 UA 才走统一认证链路
 *   （c-identity → ids.xmu.edu.cn → 回 lnt）；原生 OAuth 登录（[XmuLoginClient]）
 *   复用同一 UA。
 *
 * 白名单四域（lnt/ids/c-identity/pass）的运行时声明在
 * `schools/xmu/XmuSchoolProvider.XMU_ALLOWED_HOSTS`（按值复制并测试锁值），两处必须同改。
 */
internal object XmuWebViewPolicy {

    const val LNT_HOST = "lnt.xmu.edu.cn"

    // ?version=v1 与站内入口一致（指导 §3.1），绕开旧缓存
    const val ENTRY_URL = "https://$LNT_HOST/user/index?version=v1"

    internal const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
}
