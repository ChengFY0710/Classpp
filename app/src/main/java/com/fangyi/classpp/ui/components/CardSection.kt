package com.fangyi.classpp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.theme.CardSectionSpacing
import com.fangyi.classpp.ui.theme.CardSectionTitleSpacing
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.settingsRowMetrics

/**
 * 卡片分组：灰色小标题（卡片外）+ 下方一组卡片。组内两条纵向间距彼此独立：
 * 卡↔卡走 [spacing]，标题↔首卡走 [titleSpacing]（两者默认各取 ui.theme 的
 * CardSectionSpacing / CardSectionTitleSpacing，当前均为 10dp）。
 * 自设置页的 SettingsSection 迁入更名，设置页与各浮层（课表设置、加课面板等）共用，
 * 保持同一套标题样式与间距节奏；组件只管纵向排版，横向边距与卡片本体由调用方提供
 * （典型搭配：卡内放 SettingsCard / PopupSelectCard 等）。
 *
 * [title] 可空：null（默认）不渲染标题，组件退化为纯分组容器——组内仍走 [spacing]
 * 节奏（无题卡组与带题分组的纵向节奏因此一致）。
 *
 * **浮层（OverlaySheet）侧约定**：内容列的顶层块一律用它——成组的卡片列表与孤块
 * （输入框、单张卡片）都收进分组（无标题 = 不渲染标题行），唯二的例外是直接的文本内容
 * 与待办标签行；顶层块之间走 `SheetSectionSpacingBetween`（12dp），由调用方的内容列给出。
 */
@Composable
fun CardSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    // 卡间距统一走 ui.theme 的 CardSectionSpacing（10dp），调用点无需覆盖
    spacing: Dp = CardSectionSpacing,
    // 标题↔首卡独立于卡间距，默认 CardSectionTitleSpacing，需要时可单独覆盖
    titleSpacing: Dp = CardSectionTitleSpacing,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                modifier = Modifier.padding(start = 6.dp),
                style = MaterialTheme.classppTextStyles.sectionTitle.settingsRowMetrics(),
            )
            // 标题↔首卡用显式留白，好让下面 content 的 spacedBy 只管卡↔卡
            Spacer(Modifier.height(titleSpacing))
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            content()
        }
    }
}
