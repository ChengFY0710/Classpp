package com.fangyi.classpp.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.SettingsCardShape
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles

/**
 * 卡内上留白：24dp（大于下留白，内容整组略偏下）。两行说明时卡高为 159dp
 * （内容 115dp + 上下留白 44dp）；行数变化时卡片高度按每行 20dp 伸缩。
 */
private val InfoHeaderTopPadding = 24.dp

/** 卡内下留白：20dp（与上留白分开；说明距卡底更收紧）。 */
private val InfoHeaderBottomPadding = 20.dp

/** 图标尺寸（设计稿 40dp）。 */
private val InfoHeaderIconSize = 40.dp

/** 卡内左右留白：说明文字放得下约 20 字/行，设计稿文案恰好两行。 */
private val InfoHeaderHorizontalPadding = 32.dp

/** 图标与标题间距。 */
private val InfoHeaderIconTitleGap = 8.dp

/** 标题与说明间距（与 [SettingsRowText] 的 3dp 节奏对齐）。 */
private val InfoHeaderTitleDescriptionGap = 3.dp

/** 渐变终点透明度（同色 25%）。 */
private const val IconGradientEndAlpha = 0.25f

/**
 * 设置页信息头部卡：白底（surface）+ [SettingsCardShape] 连续曲率圆角，高度随内容
 * （图标 + 标题 + 说明）自适应，内容整组水平居中；两行说明时卡高 159dp（上留白 24、下留白 20）。
 *
 * 规格（对齐 [SettingsCard]）：
 * - 图标 [InfoHeaderIconSize]（[iconSize] 可调）：[iconColor] 为 45° 斜向渐变起点
 *   （100% 不透明 → 25% 同色，右下淡出），调用点可换色；图标本身的 fillColor 不参与
 *   着色，镂空处透出卡面底色；
 *   [tintIcon] = false 时图标保持原色（应用 Logo 等自带配色的资源），不做渐变着色；
 *   [iconShape] 非空时按该连续曲率形状裁剪（应用图标位图用）；
 *   [iconScale] 可把图标内容在槽位内再放大（超出部分被 [iconShape] 裁掉）；
 * - 标题：fieldLabel（16sp SemiBold onSurface），与设置卡行 label 同款；
 * - 说明：settingsDescription（14sp/20sp Normal secondaryText），不限行数、按文案自然换行，
 *   行数增减时卡片高度随之伸缩，说明距卡底的留白保持不变；
 * - 纯展示：无点击与按压反馈。
 */
@Composable
fun SettingsInfoHeaderCard(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    // false = 保留图标原色（应用 Logo 等品牌资源）；true = 走 [GradientTintedIcon] 渐变成色
    tintIcon: Boolean = true,
    // 图标边长（默认 40dp）；关于页应用图标可传更大值
    iconSize: Dp = InfoHeaderIconSize,
    // 可选裁剪形状（连续曲率圆角等）；null = 不裁剪（默认，既有调用点渲染不变）
    iconShape: Shape? = null,
    // 图标内容在槽位内的额外缩放（1f = 不缩放）；> 1 需同时传 [iconShape] 兜住溢出
    iconScale: Float = 1f,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(SettingsCardShape)
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(
                start = InfoHeaderHorizontalPadding,
                top = InfoHeaderTopPadding,
                end = InfoHeaderHorizontalPadding,
                bottom = InfoHeaderBottomPadding,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement =  Arrangement.Center,
        ) {
            val iconModifier = if (iconShape != null) {
                Modifier
                    .size(iconSize)
                    .clip(iconShape)
            } else {
                Modifier.size(iconSize)
            }.then(
                // 缩放放在裁剪层内侧：图标内容放大后仍被槽位形状裁掉，布局尺寸不变
                if (iconScale != 1f) {
                    Modifier.graphicsLayer {
                        // 以槽位中心为缩放原点，四周等量裁切
                        transformOrigin = TransformOrigin.Center
                        scaleX = iconScale
                        scaleY = iconScale
                    }
                } else {
                    Modifier
                },
            )
            if (tintIcon) {
                GradientTintedIcon(
                    icon = icon,
                    color = iconColor,
                    modifier = iconModifier,
                )
            } else {
                Image(
                    painter = painterResource(icon),
                    contentDescription = null,
                    // 位图图标铺满槽位（不做 letterbox），再由 [iconShape] 裁出圆滑轮廓
                    contentScale = ContentScale.Crop,
                    modifier = iconModifier,
                )
            }
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
internal fun GradientTintedIcon(
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
