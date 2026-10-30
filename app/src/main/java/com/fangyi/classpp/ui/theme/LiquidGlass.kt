package com.fangyi.classpp.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.motion.pressScale
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens

/**
 * 液态玻璃样式：全 app 玻璃按钮（底部导航栏三胶囊、待办页顶栏胶囊、设置页顶栏返回
 * 按钮、课表页顶栏周数胶囊、浮层/选择卡顶栏按钮）共用的手感/观感参数。调观感改
 * [Pill] / [NavPill] 两处取值即可全局生效；按压倍率属动效不在此处（各调用点自定）。
 *
 * 玻璃 = drawBackdrop 出底：采样内容经 [blurRadius] 模糊、[lensHeight]/[lensDepth]
 * 折射裁进组件形状，表面再叠原底色 × [tintAlpha] 染色——色相不变、透出玻璃感；
 * lens 折射仅 API 33+，31-32 只留模糊+染色（玻璃整体启用需 API 31+，见
 * [isGlassStyleEnabled]）。配套接线：[rememberGlassBackdrop]（采样层）、
 * [glassPressFeedback] / [glassSurface]（按压与底材的非玻璃回退）。
 */
data class LiquidGlassStyle(
    val blurRadius: Dp,
    val lensHeight: Dp,
    val lensDepth: Dp,
    val tintAlpha: Float,
) {
    companion object {
        /**
         * 中小胶囊/按钮（40-47dp 高：顶栏胶囊、浮层按钮、返回圆钮）。官方玻璃
         * 指示器的折射量级（10/14dp），轻模糊够读。
         */
        val Pill: LiquidGlassStyle = LiquidGlassStyle(
            blurRadius = 4.dp,
            lensHeight = 10.dp,
            lensDepth = 14.dp,
            tintAlpha = 0.70f,
        )

        /**
         * 底部导航大胶囊（56dp 高）：胶囊更大、底后是渐变遮罩淡出的页面内容，
         * 折射取官方 Glass Bottom Bar 教程同款起始值（16/32dp）。
         */
        val NavPill: LiquidGlassStyle = LiquidGlassStyle(
            blurRadius = 4.dp,
            lensHeight = 16.dp,
            lensDepth = 32.dp,
            tintAlpha = 0.70f,
        )
    }
}

/**
 * 玻璃材质开关的组合本地下发：玻璃效果复用在多个共享组件，调用点分散，逐层传参
 * 会穿透一长串中间签名——组合根部提供、任意深处读取。MainActivity 在 ClassppTheme
 * 外层用「更高级的玻璃材质」开关的即时值提供（读 .value，翻转自根部重组，与
 * themeMode 同一模式）；未提供（预览等）时默认关闭 = 原实现。持久化见
 * GlassPreferences；启用判定统一走 [isGlassStyleEnabled]（含 SDK 双门控）。
 */
val LocalGlassStyle = staticCompositionLocalOf { false }

/**
 * 玻璃材质是否启用：「更高级的玻璃材质」开关 + API 31+（RenderEffect，blur 的门槛；
 * lens 折射另需 33+，由 [glassSurface] 内部降级）双门控。各玻璃调用点的宿主用它
 * 决定是否挂载采样层/下发采样层，关闭即全链路回退原实现、采样层零开销。
 */
@Composable
fun isGlassStyleEnabled(): Boolean = LocalGlassStyle.current && Build.VERSION.SDK_INT >= 31

/**
 * 创建「底色 + 内容」的玻璃采样层（[rememberLayerBackdrop]）：底色必须画进采样层，
 * 否则玻璃后漏透明像素（官方教程）；底色经 State 中转——draw 块读不了组合上下文，
 * 靠状态读取驱动主题切换重画。调用方把返回值经 Modifier.layerBackdrop 标记到本页/
 * 本卡的采样源内容节点上（与 Haze 磨砂的 hazeSource 可挂同一节点，两套采样互不
 * 干扰），玻璃按钮须在采样节点之外（读写同一层是 GPU 反馈环，玻璃会照见自己）。
 */
@Composable
fun rememberGlassBackdrop(backgroundColor: Color): LayerBackdrop {
    val bgColor = rememberUpdatedState(backgroundColor)
    return rememberLayerBackdrop {
        drawRect(bgColor.value)
        drawContent()
    }
}

/**
 * 玻璃按钮的按压反馈（玻璃/非玻璃二选一）：[backdrop] 非空且 API 31+ 走 Q弹按压
 * 放大（pressScale：官方软弹簧果冻过冲 + 沿轮廓加色提亮）；否则维持 pressFeedback
 * （硬节奏缩放提亮）。接在带 clip 的图层（柔影等）之前——投影随组件一起放大。
 * [pressedScale] 属动效由调用点自定（顶栏胶囊 1.10、导航胶囊 1.15），不进样式。
 */
@Composable
fun Modifier.glassPressFeedback(
    interactionSource: InteractionSource,
    shape: Shape,
    backdrop: Backdrop?,
    pressedScale: Float = 1.10f,
): Modifier = then(
    if (backdrop != null && Build.VERSION.SDK_INT >= 31) {
        Modifier.pressScale(
            interactionSource = interactionSource,
            shape = shape,
            pressedScale = pressedScale,
        )
    } else {
        Modifier.pressFeedback(
            interactionSource = interactionSource,
            shape = shape,
            scale = pressedScale,
        )
    },
)

/**
 * 玻璃按钮的底材（玻璃/非玻璃二选一，[glassPressFeedback] 的配套）：[backdrop] 非空
 * 且 API 31+ 由 drawBackdrop 出底——采样内容经 [style] 的模糊/折射裁进 [shape]，
 * 表面叠染色（[tint] 非空用原值，如导航胶囊选中蓝；null = [containerColor] ×
 * [LiquidGlassStyle.tintAlpha]，色相不变透出玻璃感）；否则维持实心底
 * [Modifier.background]。缩放会让采样跟着放大（无 layerBlock 补偿），blur + 染色 +
 * 1.1 左右的小倍率下不可感知，全 app 玻璃按钮同一取舍。
 */
fun Modifier.glassSurface(
    shape: Shape,
    containerColor: Color,
    backdrop: Backdrop?,
    style: LiquidGlassStyle = LiquidGlassStyle.Pill,
    tint: Color? = null,
): Modifier {
    val useGlass = backdrop != null && Build.VERSION.SDK_INT >= 31
    return then(
        if (useGlass && backdrop != null) {
            Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    blur(style.blurRadius.toPx())
                    if (Build.VERSION.SDK_INT >= 33) {
                        lens(style.lensHeight.toPx(), style.lensDepth.toPx())
                    }
                },
                onDrawSurface = {
                    drawRect(tint ?: containerColor.copy(alpha = style.tintAlpha))
                },
            )
        } else {
            Modifier.background(containerColor, shape)
        },
    )
}
