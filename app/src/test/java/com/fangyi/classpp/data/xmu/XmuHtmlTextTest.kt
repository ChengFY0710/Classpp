package com.fangyi.classpp.data.xmu

import org.junit.Assert.assertEquals
import org.junit.Test

/** 作业要求 HTML → 备注纯文本（无 Android 依赖，JVM 可测） */
class XmuHtmlTextTest {

    @Test
    fun `paragraphs and breaks become new lines`() {
        assertEquals(
            "第一段\n第二段\n第三行",
            XmuHtmlText.toPlainText("<p>第一段</p><p>第二段<br>第三行</p>"),
        )
    }

    @Test
    fun `list items become bullets`() {
        assertEquals(
            "• 第一项\n• 第二项",
            XmuHtmlText.toPlainText("<ul><li>第一项</li><li>第二项</li></ul>"),
        )
    }

    @Test
    fun `named and numeric entities are decoded`() {
        assertEquals(
            "A&B <x> '😀",
            XmuHtmlText.toPlainText("A&amp;B&nbsp;&lt;x&gt;&nbsp;&#39;&#x1F600;"),
        )
    }

    @Test
    fun `script style and comments are removed`() {
        assertEquals(
            "正文",
            XmuHtmlText.toPlainText(
                "<style>.a{color:red}</style><!-- hidden --><p>正文</p><script>alert(1)</script>",
            ),
        )
    }

    @Test
    fun `inline image leaves a placeholder`() {
        assertEquals(
            "见图[图片]",
            XmuHtmlText.toPlainText("""<p>见图<img src="/api/uploads/1/modified-image"></p>"""),
        )
    }

    @Test
    fun `blank and null input return empty`() {
        assertEquals("", XmuHtmlText.toPlainText(null))
        assertEquals("", XmuHtmlText.toPlainText(""))
        assertEquals("", XmuHtmlText.toPlainText("  \n\t "))
    }

    @Test
    fun `indentation is trimmed and empty lines dropped`() {
        assertEquals(
            "第一行\n第二行",
            XmuHtmlText.toPlainText("<div>\n  第一行\n\n  第二行\n</div>"),
        )
    }
}
