package com.fangyi.classpp.ui.schools

import com.fangyi.classpp.data.sync.SchoolSession

/**
 * WebView 登录浮层的通用绑定（指导 §3.4）：所有声明了 `webViewLogin` 的学校统一走
 * [GenericSchoolUi]——入口/白名单/端点/文案全部由 spec 驱动，不写任何学校 UI 代码。
 * 学校需要特殊页面结构/交互时才在这里注册自己的 [SchoolUi] 实现（当前无注册项）。
 *
 * 没有 WebView 登录能力的学校返回 null（调用方隐藏网页登录入口）。
 *
 * `data` 层不依赖 Compose：绑定只发生在 UI 层，通用会话契约保持不变。
 */
object SchoolUiBindings {

    /** 会话感知绑定：声明了 webViewLogin 的学校一律走通用浮层（spec 驱动，零学校分支） */
    fun of(session: SchoolSession): SchoolUi? = session.webViewLogin?.let { webView ->
        GenericSchoolUi(
            spec = webView,
            titleRes = session.spec.loginSheetTitleRes,
            profileValidator = session.webViewProfileValidator,
        )
    }
}
