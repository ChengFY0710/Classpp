package com.fangyi.classpp.ui.motion

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

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

/**
 * 整页覆盖层转场（设置页覆盖层与个性化子页共用，两层转场同源）：[overlay] 整页从右缘
 * 滑入盖住 [behind]。完整动效四件套同帧启动、同一节奏（[Motion.PageEnterMillis] /
 * [Motion.PageExitMillis] + [Motion.Standard]）：
 * - 背后整页左移让位视差（比例 [PageOverlayParallaxFraction]，layer 平移，动画期间
 *   子树布局与绘制指令一概不动）；
 * - 压暗遮罩淡入淡出（Material scrim）：进场时压暗下层提供进深，退场时随滑出恢复；
 * - 页面右滑进出场（[pageSlideIn]/[pageSlideOut]，不叠淡入淡出）；
 * - 转场期左缘圆角：滑入/滑出时裁出与机身 R 角一致的圆角，页面像一张卡片滑过背景，
 *   完全就位后恢复矩形贴边。
 *
 * - [behind]：被盖住的下层内容（可多个、常驻组合），随视差层整体位移；
 * - [overlay]：盖上来的整页，须自身不透明——就位后完全盖住遮罩与下层；
 * - [visible] 翻转即启动完整转场；退场期间 [overlay] 仍保持组合，返回键/返回按钮可幂等关闭。
 */
@Composable
fun PageOverlayTransition(
    visible: Boolean,
    modifier: Modifier = Modifier,
    behind: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    // 让位视差进度：与滑入/滑出同规格、同帧启动、同曲线推进——滑入时下层向左微微让位，
    // 滑出时向右滑回原位。只在 layer 阶段被读取：动画期间不重组、不重排（同 tabPage）
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (visible) Motion.PageEnterMillis else Motion.PageExitMillis,
            easing = Motion.Standard,
        ),
        label = "pageOverlayProgress",
    )
    Box(modifier) {
        // 下层整页容器：位移挂在独立 layer 上，与 tabPage 的 layer 平移各管一层互不干扰；
        // 转场中覆盖层页缘始终压住本容器右缘，不会露出底缝
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = -progress * size.width * PageOverlayParallaxFraction
                },
        ) {
            behind()
        }
        // 压暗遮罩：只画不拦截点击——覆盖层全可见时被完全盖住，仅转场期间透出
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(Motion.PageEnterMillis, easing = Motion.Standard)),
            exit = fadeOut(tween(Motion.PageExitMillis, easing = Motion.Standard)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim),
            )
        }
        // 最后组合 ⇒ 绘制与命中测试覆盖下层全部内容（下层仅被覆盖、不重建）；
        // 右缘滑入 + 左上角返回箭头共同构成子页语义
        AnimatedVisibility(
            visible = visible,
            enter = pageSlideIn(),
            exit = pageSlideOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            // 完全就位（当前态与目标态都是 Visible）即转场结束；退场开始立刻翻回 false，
            // 圆角赶在滑出之前恢复
            val settled = transition.currentState == EnterExitState.Visible &&
                transition.targetState == EnterExitState.Visible
            Box(
                Modifier
                    .fillMaxSize()
                    .pageTransitionLeftCorners(settled),
            ) {
                overlay()
            }
        }
    }
}

/**
 * 覆盖层转场的背景视差位移比例：下层页面向左挪自身宽度的这个比例让位，滑出时滑回。
 * 量级「微微」即可——iOS push 是 1/3 宽，明显重于本效果；想更含蓄可下调至 0.06 左右
 */
private const val PageOverlayParallaxFraction = 0.1f

/**
 * 转场期左缘圆角：滑入/滑出时左上/左下裁出与机身 R 角一致的圆角（API 31+ 读系统真实
 * 半径，低版本退化 24dp 近似），配合压暗遮罩让页面像一张卡片滑过背景；完全就位
 * （[settled]）且画面彻底静止后恢复矩形贴边、零裁剪开销。
 */
@Composable
private fun Modifier.pageTransitionLeftCorners(settled: Boolean): Modifier {
    val view = LocalView.current
    val density = LocalDensity.current
    val leftCornerPx = remember {
        val insets = view.rootWindowInsets
        val tl = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
        } else {
            0
        }
        val bl = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
        } else {
            0
        }
        maxOf(tl, bl).takeIf { it > 0 }?.toFloat() ?: with(density) { 24.dp.toPx() }
    }
    // 圆角开关：内容首帧必然处于转场中，初始即为开（圆角恒定全开、不随滑动插值收放）
    var cornersOn by remember { mutableStateOf(true) }
    LaunchedEffect(settled) {
        if (settled) {
            // 完全就位后再保持一小段，画面彻底静止再摘掉裁剪层
            delay(100)
            cornersOn = false
        } else {
            cornersOn = true
        }
    }
    return graphicsLayer {
        if (cornersOn) {
            shape = RoundedCornerShape(
                topStart = leftCornerPx,
                bottomStart = leftCornerPx,
            )
            clip = true
        } else {
            shape = RectangleShape
            clip = false
        }
    }
}
