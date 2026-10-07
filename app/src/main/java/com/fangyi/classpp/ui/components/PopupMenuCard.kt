package com.fangyi.classpp.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.theme.MenuShape
import com.fangyi.classpp.ui.theme.classppTextStyles

/** 勾选图标边长；未勾选行保留同宽空槽，勾选/未勾选的文字左缘对齐（设计稿） */
private val CheckIconSize = 20.dp

/** 菜单行内边距（CourseContextMenu 同款 24/14 节奏） */
private val RowHorizontalPadding = 24.dp
private val RowVerticalPadding = 14.dp

/** 勾选图标与文字的间距 */
private val CheckLabelSpacing = 12.dp

/** 分割线内缩：左右各收 8dp，比行内容更贴边（设计稿分割线明显宽于文字区） */
private val DividerHorizontalInset = 8.dp

/** 分割线上下留白 */
private val DividerVerticalPadding = 8.dp

/** 弹出菜单项：文案 + 勾选态 + 点击动作（收起菜单由调用方与动作一起处理，这里只管渲染） */
data class PopupMenuItem(
    val label: String,
    val checked: Boolean = false,
    val onClick: () -> Unit = {},
)

/** 弹出菜单分组：纯视觉分段；[showDivider] = 与上一分组之间画分割线（可选，首组忽略） */
data class PopupMenuSection(
    val items: List<PopupMenuItem>,
    val showDivider: Boolean = false,
)

/**
 * 弹出选择菜单卡（设计稿：白圆角卡 + 左侧勾选行 + 按压涟漪 + 组间可选分割线）。
 *
 * - 行：fieldLabel（16sp SemiBold）；勾选行 = primary 色 + 左侧 ic_checkmark，
 *   未勾选行 = onSurface、勾选槽占位保文字对齐；点击走默认按压涟漪，
 *   [Surface] 裁剪到 [MenuShape]，越界涟漪不溢出卡片；
 * - 分组：[PopupMenuSection.showDivider] 控制与上一分组之间的 [HorizontalDivider]
 *   （outlineVariant）——单组菜单不传分割线即为设计稿中右两态的无缝形态；
 * - 宽度：取最宽行的内容宽（Surface wrap），与设计稿的窄卡一致。
 *
 * 只管卡片本体：锚定/挂载/收起由宿主负责（自定义锚定 Popup 或 DropdownMenu）。
 */
@Composable
fun PopupMenuCard(
    sections: List<PopupMenuSection>,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MenuShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = modifier,
    ) {
        Column {
            sections.forEachIndexed { index, section ->
                if (index > 0 && section.showDivider) {
                    HorizontalDivider(
                        modifier = Modifier.padding(
                            horizontal = DividerHorizontalInset,
                            vertical = DividerVerticalPadding,
                        ),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                section.items.forEach { item -> PopupMenuRow(item) }
            }
        }
    }
}

/** 菜单行：勾选槽（占位对齐）+ 档位名；勾选 = primary，未勾选 = onSurface */
@Composable
private fun PopupMenuRow(item: PopupMenuItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = item.onClick)
            .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(CheckIconSize), contentAlignment = Alignment.CenterStart) {
            if (item.checked) {
                Icon(
                    painter = painterResource(R.drawable.ic_checkmark),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(CheckIconSize),
                )
            }
        }
        Spacer(Modifier.width(CheckLabelSpacing))
        Text(
            text = item.label,
            style = MaterialTheme.classppTextStyles.fieldLabel,
            color = if (item.checked) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
