package com.fangyi.classpp.ui.schools

import com.fangyi.classpp.data.sync.SchoolSession
import com.fangyi.classpp.schools.xmu.XmuUi

/**
 * UI 绑定的唯一注册点（指导 §3.4）：学校 id → [SchoolUi]。
 *
 * - 已注册自定义 UI 的学校（厦大）沿用自家浮层，WebView 行为零改动；
 * - 其余声明了 `webViewLogin` 的纯声明学校走 [GenericSchoolUi]：新学校不写任何
 *   UI 代码即可获得完整网页登录流程；
 * - 没有 WebView 登录能力的学校返回 null（调用方隐藏网页登录入口）。
 *
 * `data` 层不依赖 Compose：绑定只发生在 UI 层，通用会话契约保持不变。
 */
object SchoolUiBindings {

    /** 会话感知的绑定：优先自定义 UI，缺省为通用 WebView 浮层 */
    fun of(session: SchoolSession): SchoolUi? = when (session.spec.id) {
        "xmu" -> XmuUi
        else -> session.webViewLogin?.let { webView ->
            GenericSchoolUi(
                spec = webView,
                titleRes = session.spec.loginSheetTitleRes,
                profileValidator = session.webViewProfileValidator,
            )
        }
    }

    /**
     * 仅按 id 取自定义绑定：未注册返回 null。
     * 新代码应使用 [of]（会话感知），以便纯声明学校获得通用浮层兜底。
     */
    fun of(schoolId: String): SchoolUi? = when (schoolId) {
        "xmu" -> XmuUi
        else -> null
    }
}
