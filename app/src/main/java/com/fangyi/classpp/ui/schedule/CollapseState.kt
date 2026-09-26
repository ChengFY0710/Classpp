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
 * 消费规则（跟手，顺序刻意不对称）：
 * - 上滑（delta < 0）在 pre 阶段优先折叠，折叠满后子列表才滚动（图标渐隐/胶囊移位先于日期带渐隐）；
 * - 下滑（delta > 0）子列表先滚回顶部，列表到顶后的剩余才在 post 阶段展开（日期带先恢复，顶栏图标后恢复）；
 * - fling 逐帧同走 pre/post，剩余速度用 decay 收尾，Animatable 边界 clamp 越界自动停止（过顶回弹）。
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
            // 折叠先手：上滑在子列表消费前抢折叠；展开不在 pre 消费，让给下滑恢复顺序
            if (available.y < 0f) consumeDelta(available.y) else Offset.Zero

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset =
            // 展开后手：下滑先让子列表滚回顶部（日期带恢复），列表到顶后的剩余才用于展开
            if (available.y > 0f) consumeDelta(available.y) else Offset.Zero

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
