package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.theme.SheetCardShape

/** 白卡片默认内距：左右 16、上下 14（约 52dp 行高，对齐设计稿）。 */
val SheetCardPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)

/**
 * 浮层卡片基座——贯穿整个 app 的设计元素：白底 + 统一圆角 + 统一内距。
 * [onClick] 非空时整卡可点（自带涟漪）；null 为静态卡（卡内控件自行处理点按）。
 * 需要描边的变体（输入框聚焦/报错）由调用方在 modifier 上叠加 border。
 */
@Composable
fun SheetCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = SheetCardPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = if (onClick != null) {
        remember { MutableInteractionSource() }
    } else {
        null
    }
    Row(
        modifier = modifier
            .clip(SheetCardShape)
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (onClick != null && interactionSource != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = androidx.compose.foundation.LocalIndication.current,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
