package com.fangyi.classpp.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.isActive

/** 回弹（放大回原页面）时长：只放大不回弹，用 [Motion.Overlay] 的非线性减速收尾 */
private const val CardRestoreMillis = 320

/**
 * 「网页冻结卡」的进出场进度句柄（[rememberCardShrinkMotion] 返回）：
 * 0 = 原始网页铺满内容区，1 = 目标居中缩小态，进场过冲时可大于 1。
 *
 * 只暴露进度，具体尺寸 / 圆角 / 位移由宿主按设计稿映射（见教务网页识别页的
 * `FrozenPageCard`），motion 层不绑定某种卡片的几何。
 */
@Stable
class CardShrinkMotionState internal constructor(
    val progress: State<Float>,
)

/**
 * 网页冻结卡的缩小 / 回弹时间轴（不改 [PopupMotion]，独立样式）：
 *
 * - 进场 0 → 1：直接复用弹层菜单的果冻曲线 [popupEnterJelly]（`startScale = 0f`，
 *   [PopupEnterDefaultTiming]），先缩过目标（进度峰值 ≈1.05）再回弹到 1，峰值时刻
 *   200ms、总时长 440ms；
 * - [onShrunk] 在进场动画完整播完（回到目标 1）后回调一次，宿主可据此在缩小完成后
 *   才开始耗时操作（如网页文本抓取）；进场被取消（提前 `active = false` / 离场）时不回调；
 * - 离场 1 → 0：`tween(320, Motion.Overlay)`，只非线性放大回铺满，不带过冲；
 * - [onRestored] 在离场播完后回调一次，宿主据此卸载快照、恢复 WebView 显示；
 *   首次组合即 `active = false` 时不回调。
 *
 * 与 [rememberPopupMotion] 的差别：后者是「由小长到大」且退场缩回起点，本样式是
 * 「由铺满缩成卡再放大回铺满」，因此单独成文件、不改弹层状态机。
 */
@Composable
fun rememberCardShrinkMotion(
    active: Boolean,
    onShrunk: () -> Unit = {},
    onRestored: () -> Unit = {},
): CardShrinkMotionState {
    val progress = remember { Animatable(0f) }
    val currentOnShrunk = rememberUpdatedState(onShrunk)
    val currentOnRestored = rememberUpdatedState(onRestored)
    // 只在「曾经进入过」之后才允许跑退场 / 回调，避免页面首次组合时误触发恢复
    var started by remember { mutableStateOf(false) }

    LaunchedEffect(active) {
        if (active) {
            started = true
            progress.animateTo(
                targetValue = 1f,
                animationSpec = popupEnterJelly(
                    startScale = 0f,
                    timing = PopupEnterDefaultTiming,
                ),
            )
            // 动画被取消（active 提前翻转 / 页面离场）时不要触发耗时操作
            if (isActive) currentOnShrunk.value()
        } else if (started) {
            if (progress.value != 0f) {
                progress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = CardRestoreMillis,
                        easing = Motion.Overlay,
                    ),
                )
            }
            started = false
            currentOnRestored.value()
        }
    }

    return remember(progress) { CardShrinkMotionState(progress.asState()) }
}
