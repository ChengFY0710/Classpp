package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.tabularNumbers

/** 提示条高度：设计稿定值 45dp，图标 20dp 上下各留 12.5dp。 */
private val InfoBannerHeight = 45.dp

/** 图标尺寸：20dp，约为条高的 44%（与截图比例一致），小于行卡 24dp 图标槽。 */
private val InfoBannerIconSize = 20.dp

/** 图标与文字间距：8dp。 */
private val InfoBannerIconGap = 8.dp

/** 内容左右内距：文案超长被截断时不会贴到胶囊圆角。 */
private val InfoBannerHorizontalPadding = 16.dp

/**
 * 浮层信息提示条：45dp 高的白底 [PillShape] 胶囊，左侧信息图标 + 单行说明文字，
 * 整组水平垂直居中。底色走 [MaterialTheme.colorScheme.surface]（浮层画布为
 * background 浅灰，白条可读），图标与文字统一 primary 色，字号走
 * [ClassppTextTokens.secondaryTextLarge]。
 *
 * 纯展示：无点击、按压反馈与描边；文案由调用方传入，组件不内置任何文案。
 * 单行截断（[TextOverflow.Ellipsis]），长文案不会撑高 45dp 的条高。
 */
@Composable
fun SheetInfoBanner(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(InfoBannerHeight)
            .clip(PillShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = InfoBannerHorizontalPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_info_regular),
            contentDescription = null,
            tint = MaterialTheme.classppColors.secondaryText,
            modifier = Modifier.size(InfoBannerIconSize),
        )
        Spacer(Modifier.width(InfoBannerIconGap))
        Text(
            text = text,
            // 文案含随编辑变化的节次数字：等宽避免整行居中时左右抖动
            style = ClassppTextTokens.secondaryTextLarge.tabularNumbers(),
            color = MaterialTheme.classppColors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
