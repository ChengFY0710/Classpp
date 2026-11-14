package com.fangyi.classpp.ui.ai

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.annotator.AnnotatorSettings
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
 * [chatMarkdownAnnotatorSettings] 的行为契约：
 * - 行内代码保留背景（供 ExtendedSpans 绘制圆角药丸）；
 * - 普通与 `**==…==**` 嵌套高亮都带背景；
 * - 任何路径都不产生项目禁用的 Bold。
 */
class ChatMarkdownAnnotatorTest {

    private val inlineCodeStyle = SpanStyle(background = Color.LightGray)
    private val highlightStyle = SpanStyle(color = Color.Black, background = Color.Yellow)

    @Test
    fun `settings keep inline code and highlight backgrounds`() {
        val settings = settings()

        assertEquals(inlineCodeStyle.background, settings.codeSpanStyle.background)
        assertEquals(highlightStyle.background, settings.highlightSpanStyle.background)
    }

    @Test
    fun `nested highlight inside strong keeps highlight background`() {
        val annotated = render("**==注意==**")

        assertEquals("注意", annotated.text)
        assertTrue(
            "嵌套高亮应保留背景，实际 span：${annotated.spanStyles}",
            annotated.spanStyles.any { it.item.background == highlightStyle.background },
        )
        assertFalse(
            "不得出现 Bold，实际 span 字重：${annotated.spanStyles.mapNotNull { it.item.fontWeight }}",
            annotated.spanStyles.any { it.item.fontWeight == FontWeight.Bold },
        )
    }

    @Test
    fun `inline code keeps background instead of bold`() {
        val annotated = render("`deadline`")

        assertTrue("实际文本：${annotated.text}", annotated.text.contains("deadline"))
        assertTrue(
            "行内代码应保留背景，实际 span：${annotated.spanStyles}",
            annotated.spanStyles.any { it.item.background == inlineCodeStyle.background },
        )
        assertFalse(annotated.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    private fun render(source: String): AnnotatedString {
        val markdown = MarkdownParser(
            flavour = GFMFlavourDescriptor(),
            cancellationToken = CancellationToken.NonCancellable,
        ).buildMarkdownTreeFromString(source as CharSequence)
        return AnnotatedString.Builder()
            .apply { buildMarkdownAnnotatedString(source, markdown, settings()) }
            .toAnnotatedString()
    }

    private fun settings(): AnnotatorSettings {
        val strong = StrongWeightAnnotator()
        strong.settings = chatMarkdownAnnotatorSettings(
            inlineCodeStyle = inlineCodeStyle,
            linkTextStyle = TextLinkStyles(),
            highlightStyle = highlightStyle,
            annotator = strong.annotator,
            referenceLinkHandler = ReferenceLinkHandlerImpl(),
            linkInteractionListener = null,
        )
        return strong.settings
    }
}
