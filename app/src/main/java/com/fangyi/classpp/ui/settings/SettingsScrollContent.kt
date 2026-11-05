package com.fangyi.classpp.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.motion.OffsetOverscrollEffect
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.SettingsSectionSpacing
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * 设置子页通用滚动正文（个性化 / 通知 / AI / 账号与同步共用），统一四件事：
 * - 整页铺开并挂 Haze 采样源（顶栏模糊背后始终有内容可采），可选玻璃返回按钮采样标记；
 * - 标准 verticalScroll + 页面自有 [OffsetOverscrollEffect]：内容可滚时边缘橡皮筋、
 *   顶栏标题渐显都读同一实例；
 * - 正文至少一屏高、并留 [OverScrollSlack] 可滚余量——内容偏短的子页（如移走连接表单后的
 *   AI 设置页）也能整页拖拽/过滚动：foundation 的 ScrollNode 只按「内容高度」占位，
 *   内容不足一屏时空白区域收不到手势（short-page 兜底手势层也只覆盖内容自身高度）；
 * - 页边距与分区间距固定走设置页全局 token，调用点只需给顶栏/底部让位。
 */
@Composable
internal fun SettingsScrollContent(
    scrollState: ScrollState,
    overscrollEffect: OffsetOverscrollEffect,
    hazeState: HazeState,
    glassBackdrop: LayerBackdrop?,
    topPadding: Dp,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                // 玻璃返回按钮开启时同一节点再挂液态玻璃采样标记
                .then(
                    if (glassBackdrop != null) {
                        Modifier.layerBackdrop(glassBackdrop)
                    } else {
                        Modifier
                    },
                )
                .verticalScroll(
                    scrollState,
                    overscrollEffect = overscrollEffect,
                )
                // 内容偏短时撑满一屏并留出可滚余量（见 KDoc）
                .heightIn(min = viewportHeight + OverScrollSlack)
                .padding(horizontal = PageHorizontalSpacing)
                .padding(top = topPadding, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(SettingsSectionSpacing),
            content = content,
        )
    }
}

/** 短内容页面额外留出的可滚余量：保证滚动手势与过滚动判定恒有非零量程 */
private val OverScrollSlack = 8.dp
