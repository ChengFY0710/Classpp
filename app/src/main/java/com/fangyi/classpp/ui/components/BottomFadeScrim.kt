package com.fangyi.classpp.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.navigation.GradientOverhang
import com.fangyi.classpp.ui.navigation.NavReserve

/** 遮罩总高：系统 inset + 导航胶囊区 + 上沿延伸（几何与 BottomNavBar 同源，见其常量） */
@Composable
private fun scrimHeight(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        NavReserve + GradientOverhang

/**
 * 底部渐变遮罩：全宽垂直渐变（底部 Background → 向上透明），盖住滚进导航栏区域的
 * 页面内容。原为 BottomNavBar 内置层，现下沉为各页面自绘——切 tab 转场中遮罩随页面
 * 一起滑动，页面也因此能自带不同的遮罩。调用方负责对齐
 * （如 Modifier.align(Alignment.BottomCenter)）与压在滚动内容之上、页面浮层之下的层序。
 *
 * [visible] 为假时随导航栏一同下移出屏（编辑态/全屏浮层在场）。显隐动画与 MainActivity
 * 的导航栏 AnimatedVisibility 共用 [Motion.EditMillis] 规格，同一状态翻转同帧启动，
 * 两侧严格同步。
 */
@Composable
fun BottomFadeScrim(
    visible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            animationSpec = tween(Motion.EditMillis, easing = Motion.Standard),
        ) { it } + fadeIn(tween(Motion.EditMillis)),
        exit = slideOutVertically(
            animationSpec = tween(Motion.EditMillis, easing = Motion.Standard),
        ) { it } + fadeOut(tween(Motion.EditMillis)),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(scrimHeight())
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to MaterialTheme.colorScheme.background,
                    ),
                ),
        )
    }
}
