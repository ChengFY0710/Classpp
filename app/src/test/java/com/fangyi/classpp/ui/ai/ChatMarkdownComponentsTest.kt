package com.fangyi.classpp.ui.ai

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.markdownAnnotator
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自定义标题组件的回归测试：`MarkdownHeader` 的第 4 个参数是内容子节点类型
 * （`ATX_CONTENT` / `SETEXT_CONTENT`），传成标题层级会让标题渲染为空行。
 */
class ChatMarkdownComponentsTest {

    @Test
    fun `atx heading resolves to atx content and renders text`() {
        val source = "## 1. 项目性质"
        val heading = topLevelNode(source, MarkdownElementTypes.ATX_2)

        assertEquals(MarkdownTokenTypes.ATX_CONTENT, headingContentChildType(heading))
        assertEquals("1. 项目性质", renderHeadingText(source, heading))
    }

    @Test
    fun `setext heading resolves to setext content and renders text`() {
        val source = "项目性质\n===="
        val heading = topLevelNode(source, MarkdownElementTypes.SETEXT_1)

        assertEquals(MarkdownTokenTypes.SETEXT_CONTENT, headingContentChildType(heading))
        assertEquals("项目性质", renderHeadingText(source, heading))
    }

    private fun renderHeadingText(source: String, heading: ASTNode): String {
        val contentNode = requireNotNull(
            heading.findChildOfType(headingContentChildType(heading)),
        ) { "标题节点缺少内容子节点：${heading.type}" }
        return AnnotatedString.Builder()
            .apply { buildMarkdownAnnotatedString(source, contentNode, settings()) }
            .toAnnotatedString()
            .text
    }

    private fun topLevelNode(source: String, type: IElementType): ASTNode =
        MarkdownParser(
            flavour = GFMFlavourDescriptor(),
            cancellationToken = CancellationToken.NonCancellable,
        ).buildMarkdownTreeFromString(source as CharSequence)
            .children
            .first { it.type == type }

    private fun settings() = DefaultAnnotatorSettings(
        linkTextSpanStyle = TextLinkStyles(),
        codeSpanStyle = SpanStyle(),
        annotator = markdownAnnotator(),
        referenceLinkHandler = ReferenceLinkHandlerImpl(),
        linkInteractionListener = null,
        highlightSpanStyle = SpanStyle(),
    )
}
