package com.fangyi.classpp.schools.xmu

import androidx.compose.runtime.Composable
import com.fangyi.classpp.data.sync.LoginRaw
import com.fangyi.classpp.data.sync.SyncError
import com.fangyi.classpp.data.xmu.XmuFetchResult
import com.fangyi.classpp.ui.schools.SchoolUi
import com.fangyi.classpp.ui.schools.WebViewLoginOutcome
import com.fangyi.classpp.ui.settings.XmuLoginSheet

/**
 * 厦大的 UI 绑定：网页登录浮层复用现有 [XmuLoginSheet]（WebView 行为零改动），
 * 只把学校的取数结果类型映射到通用 [WebViewLoginOutcome]。
 */
object XmuUi : SchoolUi {

    @Composable
    override fun WebViewLoginSheet(
        onDismiss: () -> Unit,
        onResult: (WebViewLoginOutcome) -> Unit,
    ) {
        XmuLoginSheet(
            onDismiss = onDismiss,
            onResult = { result -> onResult(result.toWebViewLoginOutcome()) },
        )
    }
}

/** 学校结果 → 通用结果（失败分类一一同构，不兜底合并） */
private fun XmuFetchResult.toWebViewLoginOutcome(): WebViewLoginOutcome = when (this) {
    is XmuFetchResult.Ok -> WebViewLoginOutcome.Ok(LoginRaw(profileRaw, todosRaw))
    XmuFetchResult.SessionExpired -> WebViewLoginOutcome.Failed(SyncError.SessionExpired)
    XmuFetchResult.ServiceBusy -> WebViewLoginOutcome.Failed(SyncError.ServiceBusy)
    XmuFetchResult.NetworkError -> WebViewLoginOutcome.Failed(SyncError.NetworkError)
    XmuFetchResult.Timeout -> WebViewLoginOutcome.Failed(SyncError.Timeout)
    XmuFetchResult.FormatChanged -> WebViewLoginOutcome.Failed(SyncError.FormatChanged)
}
