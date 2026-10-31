package com.fangyi.classpp.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.SettingsCardShape
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles

/** 卡片固定高度（设计稿 150dp）。 */
private val InfoHeaderCardHeight = 150.dp

/** 图标尺寸（设计稿 40dp）。 */
private val InfoHeaderIconSize = 40.dp

/** 卡内左右留白：说明文字放得下约 20 字/行，设计稿文案恰好两行。 */
private val InfoHeaderHorizontalPadding = 32.dp

/** 图标与标题间距。 */
private val InfoHeaderIconTitleGap = 8.dp

/** 标题与说明间距（与 [SettingsRowText] 的 3dp 节奏对齐）。 */
private val InfoHeaderTitleDescriptionGap = 3.dp

/** 说明最多行数：卡高固定，超出省略，避免长文案顶出卡面。 */
private const val InfoHeaderDescriptionMaxLines = 2

/** 渐变终点透明度（同色 25%）。 */
private const val IconGradientEndAlpha = 0.25f

/**
 * 设置页信息头部卡：白底（surface）+ [SettingsCardShape] 连续曲率圆角，固定高度 150dp，
 * 内容整组（渐变图标 + 标题 + 次要说明）水平、垂直居中。
 *
 * 规格（对齐 [SettingsCard]）：
 * - 图标 40dp：[iconColor] 为 45° 斜向渐变起点（100% 不透明 → 25% 同色，右下淡出），
 *   调用点可换色；图标本身的 fillColor 不参与着色，镂空处透出卡面底色；
 * - 标题：fieldLabel（16sp SemiBold onSurface），与设置卡行 label 同款；
 * - 说明：settingsDescription（14sp/20sp Normal secondaryText），最多 2 行、超出省略；
 * - 纯展示：无点击与按压反馈。
 */
@Composable
fun SettingsInfoHeaderCard(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    iconColor: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(InfoHeaderCardHeight)
            .clip(SettingsCardShape)
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = InfoHeaderHorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement =  Arrangement.Center,
        ) {
            GradientTintedIcon(
                icon = icon,
                color = iconColor,
                modifier = Modifier.size(InfoHeaderIconSize),
            )
            Spacer(Modifier.height(InfoHeaderIconTitleGap))
            Text(
                text = title,
                style = MaterialTheme.classppTextStyles.fieldLabel,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(InfoHeaderTitleDescriptionGap))
            Text(
                text = description,
                style = ClassppTextTokens.settingsDescription.copy(
                    color = MaterialTheme.classppColors.secondaryText,
                ),
                textAlign = TextAlign.Center,
                maxLines = InfoHeaderDescriptionMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 渐变着色图标：把 [icon] 的 alpha 当作遮罩，用 [color] → 25% 同色的 45° 斜向渐变填充。
 *
 * Icon 的 tint 只支持纯色，因此用 [BlendMode.SrcIn] 替换图标填充色：saveLayer 把合成
 * 限制在图标自身范围（卡面不受影响），图标镂空处保持透明，透出下层卡面底色。
 */
@Composable
private fun GradientTintedIcon(
    @DrawableRes icon: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val painter = painterResource(icon)
    Spacer(
        modifier = modifier.drawWithCache {
            // 45° 斜向（左上 → 右下）：同色 100% → 25%
            val brush = Brush.linearGradient(
                colors = listOf(color, color.copy(alpha = IconGradientEndAlpha)),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
            val layerPaint = Paint()
            onDrawBehind {
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(Rect(Offset.Zero, size), layerPaint)
                    with(painter) { draw(size) }
                    drawRect(brush = brush, blendMode = BlendMode.SrcIn)
                    canvas.restore()
                }
            }
        },
    )
}
