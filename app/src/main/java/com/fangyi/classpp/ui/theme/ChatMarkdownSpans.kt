package com.fangyi.classpp.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.compose.extendedspans.RoundedCornerSpanPainter
import com.mikepenz.markdown.model.MarkdownExtendedSpans
import com.mikepenz.markdown.model.markdownExtendedSpans

/**
 * 行内代码 / `==高亮==` 的药丸圆角：4sp 与气泡 20dp 圆角同属“小圆角”观感，
 * 3sp / 1sp 是左右与上下内距——只影响 span 背景绘制，不改变文字排版。
 *
 * 放在 `ui/theme` 是因为业务代码禁止裸 `.sp`；这些值只服务 Markdown 扩展 span，
 * 不进入字号阶梯，也不作为全局 token 复用。
 */
private val InlineCodeCornerRadius = 4.sp
private val InlineCodePaddingHorizontal = 3.sp
private val InlineCodePaddingVertical = 1.sp

/**
 * Markdown 行内代码与高亮背景的圆角绘制器。
 *
 * 库只会在 `SpanStyle.background` 非空时绘制圆角背景，因此 [ClassppTextTokens.chatInlineCode]
 * 的 `SpanStyle` 必须保留 `background`（见 `ChatMarkdown` 的 annotator settings）。
 *
 * `remember` 必须放在 [markdownExtendedSpans] 的 lambda 内：库会为每个文本块调用该 lambda，
 * 若在整篇文档外层共享一个 [ExtendedSpans] 实例，各文本块的 `onTextLayout` 会互相覆盖
 * 绘制指令，圆角会画到错误位置。
 *
 * 已知差异：表格单元格走 `MarkdownTableBasicText`，不经过 ExtendedSpans，仍是直角背景。
 */
@Composable
internal fun chatMarkdownExtendedSpans(): MarkdownExtendedSpans = markdownExtendedSpans {
    remember {
        ExtendedSpans(
            RoundedCornerSpanPainter(
                cornerRadius = InlineCodeCornerRadius,
                padding = RoundedCornerSpanPainter.TextPaddingValues(
                    horizontal = InlineCodePaddingHorizontal,
                    vertical = InlineCodePaddingVertical,
                ),
            ),
        )
    }
}
