package com.fangyi.classpp.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.classppColors

/** 空态图标尺寸：44dp。 */
private val EmptyStateIconSize = 44.dp

/** 空态上下留白：48dp，内容块在浮层滚动区里保持居中呼吸感。 */
private val EmptyStateVerticalPadding = 48.dp

/** 图标 ↔ 标题默认间距：8dp。 */
private val EmptyStateIconTitleSpacing = 8.dp

/** 标题 ↔ 描述默认间距：3dp。 */
private val EmptyStateTitleDescriptionSpacing = 3.dp

/** 描述 ↔ 操作默认间距：12dp。 */
private val EmptyStateDescriptionActionSpacing = 12.dp

/** 描述左右留白：24dp，长文案换行时不贴到浮层边缘。 */
private val EmptyStateDescriptionHorizontalPadding = 24.dp

/** 引导按钮左右内距：20dp。 */
private val EmptyStateActionHorizontalPadding = 20.dp

/** 引导按钮上下内距：10dp。 */
private val EmptyStateActionVerticalPadding = 10.dp

/**
 * 通用空态块：居中图标 + 标题 + 描述，可在末尾附加一个操作（如引导按钮）。
 *
 * 纯展示：无背景、无点击；文案与图标由调用方传入，组件不内置任何文案。
 * 排版固定为上下 48dp；图标 ↔ 标题、标题 ↔ 描述、描述 ↔ 操作三段间距分别由
 * [iconTitleSpacing]（默认 10dp）、[titleDescriptionSpacing]（默认 8dp）、
 * [descriptionActionSpacing]（默认 12dp）独立控制；描述多行时居中换行，左右各留 24dp。
 * [action] 为可选槽位，操作按钮的样式与点击逻辑都由调用方提供。
 */
@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    iconTitleSpacing: Dp = EmptyStateIconTitleSpacing,
    titleDescriptionSpacing: Dp = EmptyStateTitleDescriptionSpacing,
    descriptionActionSpacing: Dp = EmptyStateDescriptionActionSpacing,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = EmptyStateVerticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(EmptyStateIconSize),
            )
            Spacer(Modifier.height(iconTitleSpacing))
            Text(
                text = title,
                style = ClassppTextTokens.emptyTitle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(titleDescriptionSpacing))
            Text(
                text = description,
                style = ClassppTextTokens.emptyHint,
                color = MaterialTheme.classppColors.secondaryText,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = EmptyStateDescriptionHorizontalPadding),
            )
        }
        if (action != null) {
            Spacer(Modifier.height(descriptionActionSpacing))
            action()
        }
    }
}

/**
 * 空态引导按钮：纯色胶囊（无阴影、无描边），与 [EmptyState] 的 action 槽配套。
 *
 * 默认 primary 底 + onPrimary 字；[containerColor] / [contentColor] 可按场景替换。
 * 按压反馈走 [pressClickable]：按下整体放大并在轮廓内提亮，松手弹簧回位，涟漪由按压反馈取代。
 * 文案与点击逻辑由调用方提供。
 */
@Composable
fun EmptyStateActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Box(
        modifier = modifier
            .pressClickable(shape = PillShape, onClick = onClick)
            .clip(PillShape)
            .background(containerColor)
            .padding(
                horizontal = EmptyStateActionHorizontalPadding,
                vertical = EmptyStateActionVerticalPadding,
            ),
    ) {
        Text(
            text = label,
            style = ClassppTextTokens.buttonText,
            color = contentColor,
        )
    }
}
