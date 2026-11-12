package com.fangyi.classpp.ui.ai

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.classppColors
import com.mikepenz.markdown.annotator.AnnotatorSettings
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.DefaultMarkdownAnimation
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.rememberMarkdownState
import org.intellij.markdown.MarkdownElementTypes

/**
 * 关闭库给每个文本块默认套的 `animateContentSize`：流式重解析时块尺寸持续变化，
 * 尺寸 spring 会和消息列的流式钉底抢同一段时间轴，气泡/思考卡会跟着上下抖。
 * 库的尺寸动画主要服务行内图片占位→实图的过渡，AI 文本回复没有行内图片，不需要。
 */
private val NoTextSizeAnimation = DefaultMarkdownAnimation(animateTextSize = { this })

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
        // ==高亮== 的默认色是未装配的 secondaryContainer（M3 紫色基线），改用已装配槽位
        highlightText = LocalContentColor.current,
        highlightBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
    val textLink = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        ),
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
        textLink = textLink,
        alertTitle = ClassppTextTokens.chatHeading3,
    )

    // 库把 **…**（STRONG）的字重硬编码为 Bold，项目禁用该档：
    // 用拦截器换成 SemiBold（见 [StrongWeightAnnotator]）。
    // settings 直接构造 DefaultAnnotatorSettings，不走 annotatorSettings()：后者的默认
    // 参数依赖 LocalMarkdownColors 等只在 Markdown 内部提供的局部量，在外层会抛
    // "No local MarkdownColors"。链接点击监听这里按 UriHandler 自建。
    val uriHandler = LocalUriHandler.current
    val strongAnnotator = remember { StrongWeightAnnotator() }
    strongAnnotator.settings = DefaultAnnotatorSettings(
        linkTextSpanStyle = textLink,
        codeSpanStyle = ClassppTextTokens.chatInlineCode.toSpanStyle().copy(
            background = MaterialTheme.colorScheme.surfaceContainerHigh,
            color = LocalContentColor.current,
        ),
        annotator = strongAnnotator.annotator,
        referenceLinkHandler = ReferenceLinkHandlerImpl(),
        linkInteractionListener = remember(uriHandler) {
            LinkInteractionListener { link ->
                val url = (link as? LinkAnnotation.Url)?.url
                if (url != null) runCatching { uriHandler.openUri(url) }
            }
        },
        // 高亮样式只影响 **…** 内部的 ==…==，这里不额外上色
        highlightSpanStyle = SpanStyle(),
    )
    Markdown(
        markdownState = rememberMarkdownState(
            content = content,
            retainState = true,
            immediate = true,
        ),
        colors = colors,
        typography = typography,
        annotator = strongAnnotator.annotator,
        // 流式期间不做块级尺寸动画（原因见 NoTextSizeAnimation）
        animations = NoTextSizeAnimation,
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

/**
 * STRONG（`**…**`）字重覆盖：库在行内注解里把 Bold 写死，项目禁用 Bold。
 *
 * 拦截 STRONG 节点：压入 [ClassppTextTokens.chatStrong]（SemiBold），再把子节点交回库的
 * 默认行内渲染；其余节点返回 false 走库的默认逻辑。[settings] 必须由调用方在构造后、
 * 首次解析前回填——annotator 与 AnnotatorSettings 互相引用（settings 持有 annotator，
 * annotator 递归子节点时又需要 settings）。
 */
internal class StrongWeightAnnotator {
    lateinit var settings: AnnotatorSettings

    val annotator: MarkdownAnnotator = markdownAnnotator(annotate = { markdown, node ->
        if (node.type == MarkdownElementTypes.STRONG) {
            pushStyle(ClassppTextTokens.chatStrong.toSpanStyle())
            buildMarkdownAnnotatedString(markdown, node.children, settings)
            pop()
            true
        } else {
            false
        }
    })
}
