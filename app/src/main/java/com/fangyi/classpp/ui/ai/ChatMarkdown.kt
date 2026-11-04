package com.fangyi.classpp.ui.ai

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.classppColors
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState

/**
 * AI 回复的 Markdown 渲染（GFM）：解析与块级布局交给
 * [mikepenz/multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer)，
 * 字号/字重全部映射到 [ClassppTextTokens]，颜色只取当前主题槽位。
 *
 * 流式适配：
 * - `retainState = true` 在增量重解析期间保留上一次成功结果，气泡不会先闪空再出字；
 * - `immediate = true` 让新消息首帧同步解析（聊天文本量级小），避免先露出原始 Markdown；
 * - 解析仍由库异步驱动（除首帧），后续增量的整段重解析在后台完成。
 *
 * 库对 material3 是 compileOnly，运行时用的是本项目已装配的 androidx material3；
 * 图片走库默认的 NoOp transformer（AI 文本回复不加载图片，不额外引入 coil 模块）。
 */
@Composable
internal fun ChatMarkdown(
    content: String,
    modifier: Modifier = Modifier,
) {
    val colors = markdownColor(
        // AI 气泡 Surface 的内容色 = onSurface，行内文字/表格/引用统一跟它走
        text = LocalContentColor.current,
        codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
        inlineCodeBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
        dividerColor = MaterialTheme.classppColors.onSurfaceCard,
        tableBackground = Color.Transparent,
    )
    val typography = markdownTypography(
        text = ClassppTextTokens.chatBody,
        code = ClassppTextTokens.chatCodeBlock,
        inlineCode = ClassppTextTokens.chatInlineCode,
        h1 = ClassppTextTokens.chatHeading1,
        h2 = ClassppTextTokens.chatHeading2,
        h3 = ClassppTextTokens.chatHeading3,
        h4 = ClassppTextTokens.chatHeading3,
        h5 = ClassppTextTokens.chatHeading3,
        h6 = ClassppTextTokens.chatHeading3,
        quote = ClassppTextTokens.chatBody,
        paragraph = ClassppTextTokens.chatBody,
        ordered = ClassppTextTokens.chatBody,
        bullet = ClassppTextTokens.chatBody,
        list = ClassppTextTokens.chatBody,
        table = ClassppTextTokens.chatBody,
        // 链接色不能进 token（token 不含颜色），在调用处按主题取 primary
        textLink = TextLinkStyles(
            style = SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
            ),
        ),
        alertTitle = ClassppTextTokens.chatHeading3,
    )
    Markdown(
        markdownState = rememberMarkdownState(
            content = content,
            retainState = true,
            immediate = true,
        ),
        colors = colors,
        typography = typography,
        // 不传库默认的 fillMaxSize：短回复的气泡宽度仍按内容自适应
        modifier = modifier,
        // 正常不会走到（immediate + retainState），万一 Loading 也先显示原文而不是空白
        loading = { loadingModifier ->
            Text(
                text = content,
                style = ClassppTextTokens.chatBody,
                modifier = loadingModifier,
            )
        },
        // 解析失败时退回原文：宁可露出标记，也不要让回复消失
        error = { errorModifier ->
            Text(
                text = content,
                style = ClassppTextTokens.chatBody,
                modifier = errorModifier,
            )
        },
    )
}
