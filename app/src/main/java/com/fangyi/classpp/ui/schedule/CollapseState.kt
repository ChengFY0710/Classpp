package com.fangyi.classpp.ui.schedule

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.min

/**
 * 头部折叠状态：collapseOffsetPx ∈ [0, maxCollapsePx]，0 = 完全展开，max = 完全折叠。
 *
 * 消费规则（跟手）：
 * - 上滑（delta < 0）优先折叠，剩余交给子列表；
 * - 下滑（delta > 0）优先展开，剩余交给子列表；
 * - 子列表滚到边缘后的剩余滚动继续按同规则消费；
 * - fling 剩余速度用 decay 惯性走完，Animatable 边界 clamp 越界自动停止（过顶回弹）。
 */
@Stable
class CollapseState(
    val maxCollapsePx: Float,
    private val decaySpec: DecayAnimationSpec<Float>,
) {
    var collapseOffsetPx: Float by mutableFloatStateOf(0f)
        private set

    /** 周数下拉菜单打开时暂停折叠消费，避免菜单锚点被滚动挪走 */
    var menuOpen: Boolean = false

    /** 每次拖动消费递增；fling 期间若用户重新触摸，让 decay 停止改写 offset */
    private var dragEpoch = 0

    val collapseFraction: Float
        get() = if (maxCollapsePx <= 0f) {
            0f
        } else {
            (collapseOffsetPx / maxCollapsePx).coerceIn(0f, 1f)
        }

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            consumeDelta(available.y)

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset = consumeDelta(available.y)

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            val velocity = available.y
            if (menuOpen || abs(velocity) < 1f || maxCollapsePx <= 0f) return Velocity.Zero
            val start = collapseOffsetPx
            // 向下甩动(velocity > 0)应展开（offset 减小），故衰减方向取反
            if ((velocity > 0f && start <= 0f) || (velocity < 0f && start >= maxCollapsePx)) {
                return Velocity.Zero
            }
            val epoch = dragEpoch
            val animatable = Animatable(start)
            animatable.updateBounds(0f, maxCollapsePx)
            animatable.animateDecay(initialVelocity = -velocity, animationSpec = decaySpec) {
                if (epoch == dragEpoch) collapseOffsetPx = value
            }
            return Velocity(0f, velocity)
        }
    }

    private fun consumeDelta(deltaY: Float): Offset {
        if (menuOpen || deltaY == 0f || maxCollapsePx <= 0f) return Offset.Zero
        val current = collapseOffsetPx
        val consumed = if (deltaY < 0f) {
            min(-deltaY, maxCollapsePx - current)
        } else {
            min(deltaY, current)
        }
        if (consumed <= 0f) return Offset.Zero
        dragEpoch++
        collapseOffsetPx = if (deltaY < 0f) current + consumed else current - consumed
        return Offset(0f, if (deltaY < 0f) -consumed else consumed)
    }
}

@Composable
fun rememberCollapseState(maxCollapsePx: Float): CollapseState {
    val decaySpec = remember { exponentialDecay<Float>() }
    return remember(maxCollapsePx, decaySpec) { CollapseState(maxCollapsePx, decaySpec) }
}
