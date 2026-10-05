package com.fangyi.classpp.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * 整页从右缘滑入盖住下层。设置页覆盖层与个性化子页共用同一组合，
 * 同规格才有连续感；不叠淡入淡出——进深感交给背后的压暗遮罩与下层让位视差。
 */
fun pageSlideIn(): EnterTransition =
    slideInHorizontally(
        // 从自身宽度右侧起步：整页右进
        animationSpec = tween(Motion.PageEnterMillis, easing = Motion.Standard),
    ) { it }

/** 整页向右缘滑出；退场略快于进场，收场更利落。 */
fun pageSlideOut(): ExitTransition =
    slideOutHorizontally(
        // 滑向自身宽度右侧：整页右出
        animationSpec = tween(Motion.PageExitMillis, easing = Motion.Standard),
    ) { it }
