package com.fangyi.classpp.ui.ai

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.rememberSystemAnimationsEnabled
import kotlinx.coroutines.launch

/**
 * 本次列表变化是否为「发送追加」：`next` 是 `prev` 严格前缀延长、且只多 1–2 条
 * （user 消息 + 仓库同帧追加的 assistant 占位）。
 *
 * 用户发送之外，消息列的其余变化都**不能**播入场动画——切换对话（id 全新）、
 * 删除对话（变短）、流式逐字（同一条消息内容变、id 不变）、历史恢复（首帧整列出现）。
 * 这些全靠这一条判定挡掉，不依赖调用点自己记状态。
 */
internal fun isPureAppend(prevIds: List<String>, nextIds: List<String>): Boolean {
    val growth = nextIds.size - prevIds.size
    if (growth !in 1..2) return false
    return prevIds.indices.all { prevIds[it] == nextIds[it] }
}

/**
 * 新气泡「抬升落位」：自下方 [riseDp] 上移到位，alpha 0→1。
 *
 * 两个属性各走一条 tween（[Motion.ChatBubbleRiseMillis] / [Motion.ChatBubbleFadeMillis]），
 * 同帧起播、alpha 先收尾——文字先可读，位移长尾只负责落稳。全程只读 `Animatable` 的
 * 快照值，逐帧只失效绘制、不触发重组，也不参与测量与布局（消息列在发送中会被逐帧
 * 钉底，任何尺寸动画都会与它抢同一段时间轴）。
 *
 * 用法：只在「本气泡需要入场」时挂上——不需要入场的消息由调用点直接传 `Modifier`，
 * 这里不会为它建图层；系统关闭动画时同样直接返回 `this`。判定（是否新消息）由调用点在
 * `key(message.id)` 作用域里用 `remember` 一次性捕获，之后清请求状态也不会撤图层或重播。
 */
@Composable
internal fun Modifier.chatBubbleEntrance(entranceKey: Any, riseDp: Dp): Modifier {
    // 系统关闭动画（无障碍「移除动画」/ 动画时长缩放归零）：不抬升、不淡入，直接呈现终态，
    // 连图层都不建——降级档必须落在「内容完整可见」上，不能停在 alpha 0 或偏移位置
    if (!rememberSystemAnimationsEnabled()) return this
    val rise = remember(entranceKey) { Animatable(0f) }
    val fade = remember(entranceKey) { Animatable(0f) }
    val risePx = with(LocalDensity.current) { riseDp.toPx() }
    LaunchedEffect(entranceKey) {
        launch {
            rise.animateTo(
                targetValue = 1f,
                animationSpec = tween(Motion.ChatBubbleRiseMillis, easing = Motion.Decelerate),
            )
        }
        launch {
            fade.animateTo(
                targetValue = 1f,
                animationSpec = tween(Motion.ChatBubbleFadeMillis, easing = Motion.Decelerate),
            )
        }
    }
    return graphicsLayer {
        translationY = (1f - rise.value) * risePx
        alpha = fade.value
    }
}
