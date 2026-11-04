package com.fangyi.classpp.ui.ai

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StrongWeightAnnotator] 的行为契约：库默认把 `**…**` 渲染为 FontWeight.Bold，
 * 本项目禁用 Bold，统一改为 SemiBold；其余行内样式（如粗体内的行内代码）保持库默认。
 */
class ChatMarkdownStrongTest {

    @Test
    fun `strong renders with semibold weight instead of bold`() {
        val annotated = render("**注意**")

        assertEquals("注意", annotated.text)
        val weights = annotated.spanStyles.mapNotNull { it.item.fontWeight }.toSet()
        assertTrue("粗体应为 SemiBold，实际 span 字重：$weights", FontWeight.SemiBold in weights)
        assertFalse("不得出现 Bold，实际 span 字重：$weights", FontWeight.Bold in weights)
    }

    @Test
    fun `inline code inside strong keeps library default rendering`() {
        val annotated = render("**注意 `deadline` 字段**")

        // 行内代码内容保留（库会保留 code span 两侧空格，这里只校验内容与字重）
        assertTrue("实际文本：${annotated.text}", annotated.text.contains("deadline"))
        assertTrue(annotated.spanStyles.none { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `plain text has no injected weight span`() {
        val annotated = render("下周三有数学课")

        assertTrue(annotated.spanStyles.none { it.item.fontWeight != null })
    }

    private fun render(source: String): AnnotatedString {
        val strong = StrongWeightAnnotator()
        strong.settings = DefaultAnnotatorSettings(
            linkTextSpanStyle = TextLinkStyles(),
            codeSpanStyle = SpanStyle(),
            annotator = strong.annotator,
            referenceLinkHandler = ReferenceLinkHandlerImpl(),
            linkInteractionListener = null,
            highlightSpanStyle = SpanStyle(),
        )
        val tree = MarkdownParser(
            flavour = GFMFlavourDescriptor(),
            cancellationToken = CancellationToken.NonCancellable,
        ).buildMarkdownTreeFromString(source as CharSequence)
        return AnnotatedString.Builder()
            .apply { buildMarkdownAnnotatedString(source, tree, strong.settings) }
            .toAnnotatedString()
    }
}
