package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.tabularNumbers
import com.kyant.shapes.RoundedRectangle

/** 图标尺寸：20dp，约为单行条高的一半，小于行卡 24dp 图标槽。 */
private val InfoBannerIconSize = 20.dp

/** 图标与文字间距：8dp。 */
private val InfoBannerIconGap = 8.dp

/** 内容左右内距：文案超长换行时不会贴到圆角。 */
private val InfoBannerHorizontalPadding = 16.dp

/**
 * 内容上下内距：10dp。单行（文字行高 20dp）时条高 40dp，
 * 换行后条高按行数自然增长，不再撑破固定高度。
 */
private val InfoBannerVerticalPadding = 10.dp

/**
 * 容器形状：20dp 连续圆角（与浮层内卡片同规格）。换行后条高变高，
 * 若仍用全圆角胶囊会把图标与首行文字顶到弧边上。
 */
private val InfoBannerShape = RoundedRectangle(20.dp)

/**
 * 浮层信息提示条：白底 [InfoBannerShape] 圆角条，左侧信息图标 + 说明文字，
 * 单行时整组水平垂直居中。底色走 [MaterialTheme.colorScheme.surface]（浮层画布为
 * background 浅灰，白条可读），字号走 [ClassppTextTokens.secondaryTextLarge]。
 * 常规信息态图标与文字取 secondaryText；[isError] = true（校验错误/不可继续的提示）
 * 时两者统一换成 Error 红，与底部错误提示语义一致。
 *
 * 纯展示：无点击、按压反馈与描边；文案由调用方传入，组件不内置任何文案。
 * 文字支持换行：超宽时自动折行，行首左对齐、各行起始位置一致，图标随文字块垂直居中，
 * 条高按行数增长（不截断、不省略）。
 */
@Composable
fun SheetInfoBanner(
    text: String,
    modifier: Modifier = Modifier,
    // true = 校验错误态：图标与文字都用 colorScheme.error
    isError: Boolean = false,
) {
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.classppColors.secondaryText
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(InfoBannerShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(
                horizontal = InfoBannerHorizontalPadding,
                vertical = InfoBannerVerticalPadding,
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_info_regular),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(InfoBannerIconSize),
        )
        Spacer(Modifier.width(InfoBannerIconGap))
        Text(
            text = text,
            // 文案含随编辑变化的节次数字：等宽避免整行居中时左右抖动
            style = ClassppTextTokens.secondaryTextLarge.tabularNumbers(),
            color = contentColor,
        )
    }
}
