package com.fangyi.classpp.ui.schools

import androidx.compose.runtime.Composable
import com.fangyi.classpp.data.sync.LoginRaw
import com.fangyi.classpp.data.sync.SyncError

/**
 * WebView 登录浮层的学校绑定契约（指导 §3.4）：UI 层注册，避免 `data` 层依赖 Compose。
 *
 * 原生登录浮层（`CredentialLoginSheet`）全局通用，不需要按校绑定；只有 WebView 登录
 * 因各校页面结构不同需要学校自己的实现。未绑定自定义 UI 的纯声明学校由
 * [GenericSchoolUi] 兜底（Phase 4）：只要声明了 `webViewLogin`，不写任何 UI 代码也能用。
 */
interface SchoolUi {

    /**
     * 全屏 WebView 登录浮层：用户在学校认证页完成登录后，经 [onResult] 交还取数结果
     * （宿主负责关浮层）。浮层组合离场即释放 WebView。
     */
    @Composable
    fun WebViewLoginSheet(
        onDismiss: () -> Unit,
        onResult: (WebViewLoginOutcome) -> Unit,
    )
}

/**
 * WebView 登录取数结果：成功带两段响应原文（账号切换确认后还要复用），失败按
 * [SyncError] 分类交给宿主提示（**不把所有失败说成登录过期**，见指导 §2.4 第 8 条）。
 */
sealed interface WebViewLoginOutcome {

    data class Ok(val raw: LoginRaw) : WebViewLoginOutcome

    data class Failed(val error: SyncError) : WebViewLoginOutcome
}
