package com.fangyi.classpp.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.ai.ChatMarkdown
import com.fangyi.classpp.ui.ai.ChatMarkdownMode
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SheetCard
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.theme.ClassppTextTokens

/**
 * 关于页「用前须知」的文档类型：浮层标题与正文 raw markdown 一一对应。
 *
 * 正文按语言由资源限定符解析（`res/raw` 中文、`res/raw-en` 英文），调用点不感知语言。
 */
internal enum class AboutDocument(
    @StringRes val titleRes: Int,
    @RawRes val contentRes: Int,
) {
    Disclaimer(R.string.about_disclaimer, R.raw.about_disclaimer),
    OpenSourceLicense(R.string.about_open_source_license, R.raw.about_open_source_license),
    PrivacyPolicy(R.string.about_privacy_policy, R.raw.about_privacy_policy),
}

/**
 * 关于页文档浮层：免责声明 / 开源协议 / 隐私与政策共用。
 *
 * 视觉对齐 AI 回答气泡：正文复用 [ChatMarkdown] 的 Markdown 渲染与
 * [ClassppTextTokens.chatBody] 等字号 token，外层是统一白卡 [SheetCard]
 * （默认 [com.fangyi.classpp.ui.components.SheetCardPadding]，左右 16 / 上下 14）。
 * 整篇文档放在一张卡内，由 [OverlaySheet] 统一滚动；浮层无输入框，键盘不必让位。
 */
@Composable
internal fun AboutDocumentSheet(
    document: AboutDocument,
    visible: Boolean,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
) {
    // 返回键关浮层（与其余浮层一致的两段式：visible 复位后播退场，播完才卸载）
    BackHandler(enabled = visible) {
        onDismiss()
    }

    OverlaySheet(
        title = stringResource(document.titleRes),
        rightAction = SheetTopAction(
            label = stringResource(R.string.switcher_close),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = onDismiss,
        ),
        onDismiss = onDismiss,
        visible = visible,
        onDismissed = onDismissed,
        // 纯展示文档，无输入框，键盘不必让位
        imeBehavior = SheetImeBehavior.IgnoreIme,
    ) {
        val markdown = rememberDocumentMarkdown(document.contentRes)
        SheetCard(modifier = Modifier.fillMaxWidth()) {
            // SheetCard 只铺白底，不提供内容色；这里显式对齐助理气泡的 onSurface，
            // ChatMarkdown 的行内文字、表格与代码块颜色都从 LocalContentColor 派生。
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurface,
            ) {
                if (markdown.isBlank()) {
                    Text(
                        text = stringResource(R.string.about_document_load_failed),
                        style = ClassppTextTokens.chatBody,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    SelectionContainer {
                        ChatMarkdown(
                            content = markdown,
                            modifier = Modifier.fillMaxWidth(),
                            mode = ChatMarkdownMode.Document,
                        )
                    }
                }
            }
        }
    }
}

/** 读取 raw markdown 正文（UTF-8）；读取失败返回空串，由调用点降级为错误文案。 */
@Composable
private fun rememberDocumentMarkdown(@RawRes resId: Int): String {
    val context = LocalContext.current
    return remember(context, resId) {
        runCatching {
            context.resources.openRawResource(resId)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText().trim() }
        }.getOrDefault("")
    }
}
