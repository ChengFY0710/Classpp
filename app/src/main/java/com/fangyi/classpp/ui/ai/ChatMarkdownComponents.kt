package com.fangyi.classpp.ui.ai

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.classppColors
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownBlockQuote
import com.mikepenz.markdown.compose.elements.MarkdownHeader
import com.mikepenz.markdown.compose.elements.MarkdownOrderedList
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.compose.elements.listDepth
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.model.DefaultMarkdownColors
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * 自定义 Markdown 组件集合：只接管表格、标题与有序列表三处，其余沿用库默认。
 *
 * 表格必须自定义：
 * - 库的 `MarkdownTableHeader` 内部把表头字重写死为 Bold，项目禁用该档；
 * - 库的表格行默认 `maxLines = 1`，多行单元格会被省略。
 *
 * 标题自定义只负责加顶部呼吸间距；有序列表自定义只固定 marker 宽度，让 `1.` / `10.`
 * 的内容起点一致。
 *
 * 覆盖 `components` 时必须显式回填 M3 勾选框，否则任务列表会退回库的 core 默认勾选框。
 */
@Composable
internal fun rememberChatMarkdownComponents(mode: ChatMarkdownMode): MarkdownComponents {
    val majorHeadingTop = when (mode) {
        ChatMarkdownMode.Chat -> MarkdownChatMajorHeadingTop
        ChatMarkdownMode.Document -> MarkdownDocumentMajorHeadingTop
    }
    val minorHeadingTop = when (mode) {
        ChatMarkdownMode.Chat -> MarkdownChatMinorHeadingTop
        ChatMarkdownMode.Document -> MarkdownDocumentMinorHeadingTop
    }
    return remember(mode) {
        markdownComponents(
            checkbox = { model ->
                MarkdownCheckBox(model.content, model.node, model.typography.text)
            },
            blockQuote = { model -> ChatMarkdownBlockQuote(model) },
            table = { model -> ChatMarkdownTable(model) },
            heading1 = { model ->
                ChatMarkdownHeading(model, model.typography.h1, majorHeadingTop)
            },
            heading2 = { model ->
                ChatMarkdownHeading(model, model.typography.h2, majorHeadingTop)
            },
            heading3 = { model ->
                ChatMarkdownHeading(model, model.typography.h3, minorHeadingTop)
            },
            heading4 = { model ->
                ChatMarkdownHeading(model, model.typography.h4, minorHeadingTop)
            },
            heading5 = { model ->
                ChatMarkdownHeading(model, model.typography.h5, minorHeadingTop)
            },
            heading6 = { model ->
                ChatMarkdownHeading(model, model.typography.h6, minorHeadingTop)
            },
            setextHeading1 = { model ->
                ChatMarkdownHeading(model, model.typography.h1, majorHeadingTop)
            },
            setextHeading2 = { model ->
                ChatMarkdownHeading(model, model.typography.h2, majorHeadingTop)
            },
            orderedList = { model ->
                MarkdownOrderedList(
                    content = model.content,
                    node = model.node,
                    style = model.typography.ordered,
                    depth = model.listDepth,
                    markerModifier = { Modifier.width(MarkdownOrderedMarkerWidth) },
                    listModifier = { Modifier.weight(1f) },
                )
            },
        )
    }
}

/**
 * 引用块脱灰：库的 `MarkdownBlockQuote` 只把 `quote.color` 用于左侧 bar，正文子节点
 * （段落/列表/代码）仍回退 `LocalMarkdownColors.text`。这里在引用子树内把 `text`
 * 换成 `secondaryText`，让整段引用正文与 bar 同色；链接仍取 `primary`，
 * 行内代码/高亮背景沿用原槽位。
 */
@Composable
private fun ChatMarkdownBlockQuote(model: MarkdownComponentModel) {
    val colors = LocalMarkdownColors.current
    val quoteColors = DefaultMarkdownColors(
        text = MaterialTheme.classppColors.secondaryText,
        codeBackground = colors.codeBackground,
        inlineCodeBackground = colors.inlineCodeBackground,
        dividerColor = colors.dividerColor,
        tableBackground = colors.tableBackground,
        alert = colors.alert,
        highlightText = colors.highlightText,
        highlightBackground = colors.highlightBackground,
    )
    CompositionLocalProvider(LocalMarkdownColors provides quoteColors) {
        MarkdownBlockQuote(model.content, model.node, model.typography.quote)
    }
}

/**
 * 表格：正文走 [ClassppTextTokens.chatTable]，表头走 [ClassppTextTokens.chatTableHeader]。
 *
 * 表头不能复用库的 `MarkdownTableHeader`（内部强制 Bold），这里按 CELL 自行排版；
 * 行仍复用 `MarkdownTableRow`，只把 `maxLines` 放宽到 3、垂直对齐改为顶部。
 */
@Composable
private fun ChatMarkdownTable(model: MarkdownComponentModel) {
    MarkdownTable(
        model.content,
        model.node,
        model.typography.table,
        annotatorSettings(),
        headerBlock = { content, header, tableWidth, _ ->
            ChatMarkdownTableHeader(content, header, tableWidth)
        },
        rowBlock = { content, row, tableWidth, style ->
            MarkdownTableRow(
                content,
                row,
                tableWidth = tableWidth,
                style = style,
                maxLines = MarkdownTableCellMaxLines,
                overflow = TextOverflow.Ellipsis,
                verticalAlignment = Alignment.Top,
                annotatorSettings = annotatorSettings(),
            )
        },
    )
}

/**
 * 自定义表头：逐 CELL 使用 `chatTableHeader`（SemiBold），最多 3 行、超出省略。
 *
 * 布局与库的 `MarkdownTableHeader` 保持一致：Row 取 `tableWidth` 最小宽度、
 * 单元格 `weight(1f)` 等分列宽，内距取 `LocalMarkdownDimens.tableCellPadding`；
 * 仅替换表头字重（库内写死 Bold）并把 `maxLines` 放宽到 3。
 * 表头与表体之间的横向分割线由 `MarkdownTable` 在 `TABLE_SEPARATOR` 节点绘制。
 */
@Composable
private fun ChatMarkdownTableHeader(
    content: String,
    header: ASTNode,
    tableWidth: Dp,
) {
    val cellPadding = LocalMarkdownDimens.current.tableCellPadding
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(tableWidth)
            .height(IntrinsicSize.Max),
    ) {
        header.children.filter { it.type == GFMTokenTypes.CELL }.forEachIndexed { colIndex, cell ->
            Column(
                modifier = Modifier
                    .padding(cellPadding)
                    .weight(1f)
                    .semantics {
                        heading()
                        collectionItemInfo = CollectionItemInfo(
                            rowIndex = 0,
                            rowSpan = 1,
                            columnIndex = colIndex,
                            columnSpan = 1,
                        )
                    },
            ) {
                MarkdownTableBasicText(
                    content = content,
                    cell = cell,
                    style = ClassppTextTokens.chatTableHeader,
                    maxLines = MarkdownTableCellMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    annotatorSettings = annotatorSettings(),
                )
            }
        }
    }
}

/**
 * 标题外包一层顶部间距；`MarkdownHeader` 仍保留 `heading()` 语义。
 *
 * 第 4 个参数是 **内容子节点类型**，不是标题层级：ATX 标题（`#`）取
 * `ATX_CONTENT`，Setext 标题（下划线式）取 `SETEXT_CONTENT`。传错会让
 * `MarkdownText` 找不到可渲染的文本子节点，标题变成一条空白行。
 */
@Composable
private fun ChatMarkdownHeading(
    model: MarkdownComponentModel,
    style: TextStyle,
    topSpacing: Dp,
) {
    Box(Modifier.padding(top = topSpacing)) {
        MarkdownHeader(model.content, model.node, style, headingContentChildType(model.node))
    }
}

/**
 * 标题可渲染内容的子节点类型：Setext 标题与 ATX 标题的 AST 结构不同，必须分别取
 * `SETEXT_CONTENT` / `ATX_CONTENT`；直接传标题节点本身会渲染出空字符串。
 */
internal fun headingContentChildType(node: ASTNode): IElementType = when (node.type) {
    MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2 -> MarkdownTokenTypes.SETEXT_CONTENT
    else -> MarkdownTokenTypes.ATX_CONTENT
}

/** 表格单元格最多显示 3 行，超出省略；与列宽 120dp 配合，避免多行内容被直接截断。 */
private const val MarkdownTableCellMaxLines = 3

/** 有序列表序号标记的固定宽度：`1.` 与 `10.` 的内容起点对齐。 */
private val MarkdownOrderedMarkerWidth = 24.dp

/** Chat 模式 h1/h2 的额外顶部间距：块间距 4dp 之外再留 6dp。 */
private val MarkdownChatMajorHeadingTop = 6.dp

/** Chat 模式 h3~h6 的额外顶部间距：比大标题克制一档。 */
private val MarkdownChatMinorHeadingTop = 4.dp

/** Document 模式 h1/h2 的额外顶部间距：长文档章节之间需要更多呼吸。 */
private val MarkdownDocumentMajorHeadingTop = 8.dp

/** Document 模式 h3~h6 的额外顶部间距。 */
private val MarkdownDocumentMinorHeadingTop = 6.dp
