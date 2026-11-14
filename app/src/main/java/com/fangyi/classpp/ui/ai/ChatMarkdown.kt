package com.fangyi.classpp.ui.ai

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.chatMarkdownExtendedSpans
import com.fangyi.classpp.ui.theme.classppColors
import com.mikepenz.markdown.annotator.AnnotatorSettings
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownA11yLabels
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.DefaultMarkdownAnimation
import com.mikepenz.markdown.model.MarkdownA11yLabels
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.ReferenceLinkHandler
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import org.intellij.markdown.MarkdownElementTypes

/**
 * 关闭库给每个文本块默认套的 `animateContentSize`：流式重解析时块尺寸持续变化，
 * 尺寸 spring 会和消息列的流式钉底抢同一段时间轴，气泡/思考卡会跟着上下抖。
 * 库的尺寸动画主要服务行内图片占位→实图的过渡，AI 文本回复没有行内图片，不需要。
 */
private val NoTextSizeAnimation = DefaultMarkdownAnimation(animateTextSize = { this })

/**
 * Markdown 渲染的两种排版上下文：
 * - [Chat]：AI 对话气泡，流式、短段落，块间距收紧；
 * - [Document]：关于页法律文档 / 更新日志，长文档，标题层级更重、块间距更松。
 */
internal enum class ChatMarkdownMode { Chat, Document }

/**
 * AI 回复 / 关于页文档的 Markdown 渲染（GFM）：解析与块级布局交给
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
 *
 * 表格、标题、有序列表由 [rememberChatMarkdownComponents] 接管（见该文件 KDoc）；
 * 行内代码 / `==高亮==` 的圆角药丸由 [chatMarkdownExtendedSpans] 绘制。
 */
@Composable
internal fun ChatMarkdown(
    content: String,
    modifier: Modifier = Modifier,
    mode: ChatMarkdownMode = ChatMarkdownMode.Chat,
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
        h1 = when (mode) {
            ChatMarkdownMode.Chat -> ClassppTextTokens.chatHeading1
            ChatMarkdownMode.Document -> ClassppTextTokens.detailTitle
        },
        h2 = when (mode) {
            ChatMarkdownMode.Chat -> ClassppTextTokens.chatHeading2
            ChatMarkdownMode.Document -> ClassppTextTokens.sheetTitle
        },
        h3 = when (mode) {
            ChatMarkdownMode.Chat -> ClassppTextTokens.chatHeading3
            // 文档模式的 ## 是顶层章节，### 提高一档，与正文拉开
            ChatMarkdownMode.Document -> ClassppTextTokens.chatHeading2
        },
        h4 = ClassppTextTokens.chatHeading4,
        h5 = ClassppTextTokens.chatHeading4,
        h6 = ClassppTextTokens.chatHeading4,
        // 库只用 quote.color 画引用 bar；引用正文由自定义 blockQuote 在子树内换成 secondaryText
        quote = ClassppTextTokens.chatBody.copy(
            color = MaterialTheme.classppColors.secondaryText,
        ),
        paragraph = ClassppTextTokens.chatBody,
        ordered = ClassppTextTokens.chatBody,
        bullet = ClassppTextTokens.chatBody,
        list = ClassppTextTokens.chatBody,
        table = ClassppTextTokens.chatTable,
        // 链接色不能进 token（token 不含颜色），在调用处按主题取 primary
        textLink = textLink,
        alertTitle = ClassppTextTokens.chatHeading3,
    )
    val padding = markdownPadding(
        block = when (mode) {
            ChatMarkdownMode.Chat -> MarkdownChatBlockSpacing
            ChatMarkdownMode.Document -> MarkdownDocumentBlockSpacing
        },
        listIndent = MarkdownListIndent,
    )
    val dimens = markdownDimens(
        blockQuoteThickness = MarkdownQuoteBarThickness,
        tableCellWidth = MarkdownTableCellWidth,
        tableCellPadding = MarkdownTableCellPadding,
    )
    val components = rememberChatMarkdownComponents(mode)

    // 库把 **…**（STRONG）的字重硬编码为 Bold，项目禁用该档：
    // 用拦截器换成 SemiBold（见 [StrongWeightAnnotator]）。
    // settings 直接构造 DefaultAnnotatorSettings，不走 annotatorSettings()：后者的默认
    // 参数依赖 LocalMarkdownColors 等只在 Markdown 内部提供的局部量，在外层会抛
    // "No local MarkdownColors"。链接点击监听这里按 UriHandler 自建。
    val uriHandler = LocalUriHandler.current
    // 引用式链接处理器同时交给 markdownState 与 annotator settings：两处若各建一个实例，
    // **…[ref]** 这类“粗体内部的引用式链接”会解析不到。
    val referenceLinks = remember { ReferenceLinkHandlerImpl() }
    val strongAnnotator = remember { StrongWeightAnnotator() }
    strongAnnotator.settings = chatMarkdownAnnotatorSettings(
        inlineCodeStyle = ClassppTextTokens.chatInlineCode.toSpanStyle().copy(
            background = MaterialTheme.colorScheme.surfaceContainerHigh,
            color = LocalContentColor.current,
        ),
        linkTextStyle = textLink,
        // 普通 ==…== 由库默认 annotatorSettings() 处理；这里补上 STRONG 内部嵌套高亮的背景
        highlightStyle = SpanStyle(
            color = colors.highlightText,
            background = colors.highlightBackground,
        ),
        annotator = strongAnnotator.annotator,
        referenceLinkHandler = referenceLinks,
        linkInteractionListener = remember(uriHandler) {
            LinkInteractionListener { link ->
                val url = (link as? LinkAnnotation.Url)?.url
                if (url != null) runCatching { uriHandler.openUri(url) }
            }
        },
    )
    CompositionLocalProvider(LocalMarkdownA11yLabels provides markdownA11yLabels()) {
        Markdown(
            markdownState = rememberMarkdownState(
                content = content,
                retainState = true,
                immediate = true,
                referenceLinkHandler = referenceLinks,
            ),
            colors = colors,
            typography = typography,
            padding = padding,
            dimens = dimens,
            components = components,
            annotator = strongAnnotator.annotator,
            extendedSpans = chatMarkdownExtendedSpans(),
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
}

/**
 * Markdown 无障碍标签中文化：库默认是英文（读屏会中英混读）。
 *
 * [MarkdownA11yLabels] 的函数字段不是 `@Composable`，所以先取 `Context.resources`，
 * 再在 lambda 里 `getString`；字符串标签直接走 `stringResource` 参与配置变更重建。
 *
 * 已知限制：M3 Checkbox 的 “Checked/Unchecked” 是库内硬编码，无法通过本组标签覆盖。
 */
@Composable
private fun markdownA11yLabels(): MarkdownA11yLabels {
    val resources = LocalContext.current.resources
    return MarkdownA11yLabels(
        blockquote = stringResource(R.string.markdown_a11y_quote),
        codeBlock = stringResource(R.string.markdown_a11y_code_block),
        codeBlockWithLanguage = {
            resources.getString(R.string.markdown_a11y_code_block_with_language, it)
        },
        codeLanguage = { resources.getString(R.string.markdown_a11y_code_language, it) },
        codeFallbackLanguage = stringResource(R.string.markdown_a11y_code_fallback),
        copyCode = stringResource(R.string.markdown_a11y_copy_code),
        alert = { resources.getString(R.string.markdown_a11y_alert, it) },
    )
}

/**
 * 构造 Markdown 行内注解设置；独立成纯函数便于 JVM 单测校验高亮 / 行内代码样式。
 *
 * [highlightStyle] 同时覆盖普通 `==…==`（库默认设置）与 `**==…==**` 的嵌套路径：
 * 后者走 [StrongWeightAnnotator.settings]，此前是空 [SpanStyle]，嵌套高亮因此没有底色。
 */
internal fun chatMarkdownAnnotatorSettings(
    inlineCodeStyle: SpanStyle,
    linkTextStyle: TextLinkStyles,
    highlightStyle: SpanStyle,
    annotator: MarkdownAnnotator,
    referenceLinkHandler: ReferenceLinkHandler,
    linkInteractionListener: LinkInteractionListener?,
): AnnotatorSettings = DefaultAnnotatorSettings(
    linkTextSpanStyle = linkTextStyle,
    codeSpanStyle = inlineCodeStyle,
    annotator = annotator,
    referenceLinkHandler = referenceLinkHandler,
    linkInteractionListener = linkInteractionListener,
    highlightSpanStyle = highlightStyle,
)

/** Chat 模式块间距：4dp，段落/代码块/表格之间不再黏连。 */
private val MarkdownChatBlockSpacing = 4.dp

/** Document 模式块间距：8dp，长文档章节之间需要更多呼吸。 */
private val MarkdownDocumentBlockSpacing = 8.dp

/** 列表缩进：16dp，嵌套列表层级更清楚（库默认 8dp 偏浅）。 */
private val MarkdownListIndent = 16.dp

/** 引用 bar 厚度：3dp，从“近黑细线”变成可识别的引用标记。 */
private val MarkdownQuoteBarThickness = 3.dp

/** 表格列宽：120dp，两列表格可在手机内容区内放下；三列仍由库横向滚动兜底。 */
private val MarkdownTableCellWidth = 120.dp

/** 表格单元格内距：8dp，表格高度比库默认 16dp 明显收紧。 */
private val MarkdownTableCellPadding = 8.dp

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
