package com.fangyi.classpp.ui.motion

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 整页覆盖层转场（设置页覆盖层与个性化子页共用，两层转场同源）：[overlay] 整页从右缘
 * 滑入盖住 [behind]。完整动效四件套同帧启动、同一节奏（[Motion.PageEnterMillis] /
 * [Motion.PageExitMillis] + [Motion.Standard]）：
 * - 背后整页左移让位视差（比例 [PageOverlayParallaxFraction]，layer 平移，动画期间
 *   子树布局与绘制指令一概不动）；
 * - 压暗遮罩淡入淡出（Material scrim）：进场时压暗下层提供进深，退场时随滑出恢复；
 * - 页面右滑进出场，不叠淡入淡出；
 * - 转场期左缘圆角：滑入/滑出时裁出与机身 R 角一致的圆角，页面像一张卡片滑过背景，
 *   完全就位后恢复矩形贴边。
 *
 * 性能形态：[overlay] 内容**常驻组合**（不用 AnimatedVisibility 的「退场即销毁、进场
 * 现组合整页」）——转场只是同一个 layer 上的矩阵更新（平移与左缘圆角合并为一层），
 * 每帧零重组、零重排、零重录。隐藏态整页停在屏外：命中测试收不到、渲染线程整层剔除，
 * 而组合与绘制指令保持就绪，再次打开是纯矩阵动画。代价是冷启动多组合一次覆盖层内容、
 * 隐藏期间多驻留几块 layer，换来每次开合的顺滑。
 *
 * - [behind]：被盖住的下层内容（可多个、常驻组合），随视差层整体位移；
 * - [overlay]：盖上来的整页，须自身不透明——就位后完全盖住遮罩与下层。隐藏态下内容
 *   仍在组合中，其中的返回键处理等交互应读 [LocalPageOverlayActive] 自行关闭；
 * - [visible] 翻转即启动完整转场，转场中途反向翻转时从当前值继续。
 */
@Composable
fun PageOverlayTransition(
    visible: Boolean,
    modifier: Modifier = Modifier,
    behind: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    // 转场进度 0..1（0=隐藏、1=就位）：Animatable 驱动而非 AnimatedVisibility。
    // targetState 翻转即启动，时长/曲线取 Motion token——与遮罩、视差天然同一条曲线
    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        progress.animateTo(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(
                durationMillis = if (visible) Motion.PageEnterMillis else Motion.PageExitMillis,
                easing = Motion.Standard,
            ),
        )
    }
    // 端点派生布尔：动画帧不重组，整段转场各只在跨过端点时翻转一次
    val hidden by remember { derivedStateOf { progress.value <= 0f } }
    val settled by remember { derivedStateOf { progress.value >= 1f } }

    // 左缘圆角开关：内容首帧必然不在场，初始即为开（圆角恒定全开、不随滑动插值收放）；
    // 完全就位后再保持一小段，画面彻底静止再恢复矩形贴边并摘掉裁剪
    val leftCornerPx = rememberLeftCornerPx()
    var cornersOn by remember { mutableStateOf(true) }
    LaunchedEffect(settled) {
        if (settled) {
            delay(100)
            cornersOn = false
        } else {
            cornersOn = true
        }
    }

    Box(modifier) {
        // 下层整页容器：覆盖层滑入时整体向左微微滑出让位、滑出时向右滑回原位（视差，
        // 比例见 [PageOverlayParallaxFraction]）。位移挂在独立 layer 上，与 tabPage 的
        // layer 平移各管一层互不干扰；转场中覆盖层页缘始终压住本容器右缘，不会露出底缝
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = -progress.value * size.width * PageOverlayParallaxFraction
                },
        ) {
            behind()
        }
        // 压暗遮罩：透明度与页面滑移同一条曲线、同帧推进。只画不拦截点击（无指针处理，
        // 触控落回下层）；alpha=0（隐藏）时渲染线程整层跳过
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value }
                .background(MaterialTheme.colorScheme.scrim),
        )
        // 最后组合 ⇒ 绘制与命中测试覆盖下层全部内容（下层仅被覆盖、不重建）。
        // 整页平移与转场期左缘圆角合并在同一个 layer：动画每帧只更新这一个矩阵；
        // 隐藏态整页平移到屏外（命中测试收不到、渲染线程整层剔除），组合原样常驻
        Box(
            Modifier
                .fillMaxSize()
                // 隐藏态清空语义：屏外页面不该被无障碍/自动化视为可达内容
                .then(if (hidden) Modifier.clearAndSetSemantics { } else Modifier)
                .graphicsLayer {
                    translationX = (1f - progress.value) * size.width
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
                },
        ) {
            // 活跃状态发给覆盖层内容：隐藏后内容仍常驻组合，其中的返回键处理等
            // 交互据此关闭，避免吃掉系统返回
            CompositionLocalProvider(LocalPageOverlayActive provides (visible || !hidden)) {
                overlay()
            }
        }
    }
}

/**
 * 最近一层 [PageOverlayTransition] 的覆盖层是否「活跃」（可见或转场进行中）。
 * 覆盖层内容常驻组合：隐藏（转场彻底结束）后页面仍在组合里、只是停在屏外，其中的
 * 返回键处理等应据此关闭（如 BackHandler 的 enabled），否则会吃掉系统返回。
 * 无覆盖层的场景默认 true。
 */
val LocalPageOverlayActive = compositionLocalOf { true }

/**
 * 覆盖层转场的背景视差位移比例：下层页面向左挪自身宽度的这个比例让位，滑出时滑回。
 * 量级「微微」即可——iOS push 是 1/3 宽，明显重于本效果；想更含蓄可下调至 0.06 左右
 */
private const val PageOverlayParallaxFraction = 0.1f

/** 左缘圆角半径（px）：API 31+ 读系统真实 R 角，低版本退化 24dp 近似。 */
@Composable
private fun rememberLeftCornerPx(): Float {
    val view = LocalView.current
    val density = LocalDensity.current
    return remember {
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
}
