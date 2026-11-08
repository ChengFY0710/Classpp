package com.fangyi.classpp.ui.schools

import com.fangyi.classpp.schools.xmu.XmuUi

/**
 * UI 绑定的唯一注册点（指导 §3.4）：学校 id → [SchoolUi]。
 *
 * 未注册返回 null（调用方隐藏网页登录入口）；Phase 4 的 `GenericSchoolUi` 会成为缺省
 * 绑定，新学校不写任何 UI 代码也能走通用 WebView 浮层。
 */
object SchoolUiBindings {

    fun of(schoolId: String): SchoolUi? = when (schoolId) {
        "xmu" -> XmuUi
        else -> null
    }
}
