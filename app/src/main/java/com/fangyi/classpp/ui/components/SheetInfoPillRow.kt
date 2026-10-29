package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.tabularNumbers

/** 单枚信息胶囊的内距：水平 15dp（约一个 14sp 字符宽）、垂直 8dp——14sp/20sp 行高下胶囊高 36dp */
private val InfoPillPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

/** 胶囊间距：横向相邻胶囊与换行后的行间距同值 */
private val InfoPillSpacing = 10.dp

/**
 * 信息行首相对浮层内容列（[PageHorizontalSpacing] 页边距）再内缩的量：
 * 胶囊左缘比卡片左缘多收 3dp，三条信息不跟卡片完全齐平。
 */
private val InfoPillRowStartInset = 3.dp

/**
 * 浮层顶部信息胶囊行：把原来「当前：周X｜第X节｜XX:XX - XX:XX」的一整行灰字
 * 拆成若干枚独立胶囊（星期 / 节次 / 起止时间各一枚），白底 [PillShape] 全圆角、
 * 文字走 [ClassppTextTokens.secondaryTextLarge] + `classppColors.secondaryText`。
 *
 * 横向边距：浮层滚动内容列已带 [PageHorizontalSpacing]，本行只补 [InfoPillRowStartInset]
 * 这一段——胶囊左缘因此比同列卡片再内缩 3dp（设计如此），调用方不要再传横向 padding。
 *
 * 用 [FlowRow] 而非 Row：英文文案（如 `Periods 2-3`）或系统大字号下几枚胶囊放不下时
 * 自动换行，不会把内容挤出屏幕。
 *
 * 非交互元素（只读信息，无点击/按压反馈），纯展示用。
 */
@Composable
fun SheetInfoPillRow(
    values: List<String>,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = InfoPillRowStartInset),
        horizontalArrangement = Arrangement.spacedBy(InfoPillSpacing),
        verticalArrangement = Arrangement.spacedBy(InfoPillSpacing),
    ) {
        values.forEach { value ->
            Text(
                text = value,
                modifier = Modifier
                    .clip(PillShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(InfoPillPadding),
                // 节次与时间会随编辑即时变化：等宽数字避免胶囊宽度抖动（中文不受影响）
                style = ClassppTextTokens.secondaryTextLarge.tabularNumbers(),
                color = MaterialTheme.classppColors.secondaryText,
            )
        }
    }
}
