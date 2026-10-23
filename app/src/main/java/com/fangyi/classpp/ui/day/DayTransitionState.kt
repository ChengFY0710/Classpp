package com.fangyi.classpp.ui.day

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fangyi.classpp.ui.motion.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 日程页三态转场状态机（01 台历 / 02 缩小卡 / 04 收起）。
 *
 * 两个进度 [open]（01↔02）与 [collapse]（02↔04）是整个画面的唯一派生源：
 * 图片卡、日期组、模糊带、白卡、顶栏全部从同一组进度取几何。拖拽直接写这两个
 * `mutableFloatStateOf`（nested scroll 回调是同步的，不能 suspend snapTo），松手后用
 * 协程里的 [Animatable] 驱动 0→1 并在每帧写回，曲线/时长统一取 [Motion.Overlay] +
 * [Motion.AgendaSnapMillis]。
 *
 * [stage] 只表示可停留的 01/02/04，绝不出现 03；转场中的中间帧只是进度。重播回 01
 * 是纯视觉动画：结束时 [stage] 变 Calendar，但不触发 [onCommit]，已开启标记保持不变。
 *
 * [isAnimating] 覆盖吸附与重播，调用方据此屏蔽所有手势（重播期间尤其必须屏蔽）。
 * 例外是 02↔04 吸附：[canTakeOverSnap] 期间新手势可在竖向定轴后调用 [takeOverSnap]
 * 取消动画、从当前进度继续拖拽；01↔02 吸附与重播不可接管。
 */
@Stable
internal class DayTransitionState(
    initialStage: DayStage,
    private val scope: CoroutineScope,
    private val onCommit: (DayStage) -> Unit,
) {
    /** 01→02 进度：0 = 全屏台历，1 = 02 图片卡。 */
    var open by mutableFloatStateOf(if (initialStage == DayStage.Calendar) 0f else 1f)
        private set

    /** 02→04 进度：0 = 02，1 = 04。 */
    var collapse by mutableFloatStateOf(if (initialStage == DayStage.Schedule) 1f else 0f)
        private set

    /** 当前可停留状态（不会出现 03）。 */
    var stage by mutableStateOf(initialStage)
        private set

    /** 吸附/重播动画进行中；true 时全部手势入口关闭（[canTakeOverSnap] 除外）。 */
    var isAnimating by mutableStateOf(false)
        private set

    /** 进行中的 02↔04 吸附可被新手势接管（[takeOverSnap]）；01↔02 吸附与重播不可。 */
    var canTakeOverSnap by mutableStateOf(false)
        private set

    /**
     * 01 静止态的机身圆角系数：0 = 图片满幅直角（静止默认），1 = 机身圆角。
     * 02→01 展开动画落定后从 1 渐变到 0，让圆角收成直角；只要 open > 0，
     * 几何就按滑动中的机身圆角取值，不读这个系数。
     */
    var deviceCornerAlpha by mutableFloatStateOf(0f)
        private set

    private var animationJob: Job? = null

    /** 独立的收角动画：不参与 [isAnimating]，展开落定后手势立即可用。 */
    private var cornerFadeJob: Job? = null

    /**
     * 动画代号：新旧动画交替时，旧协程的 finally 不得把新动画的 isAnimating 清掉。
     * 跨天重置也会递增它，让作废的动画彻底失去写状态资格。
     */
    private var animationGeneration = 0

    /**
     * 01→02 跟手：只改 [open]。deltaY 为手指位移（正 = 下滑，负 = 上滑）。
     * 02 端禁止下滑回 01（不注册该手势）；01 端下滑无效果。
     * @return 实际消费的像素（0 表示这一段没有可消费量，交给内容滚动/橡皮筋）。
     */
    fun dragOpenBy(deltaY: Float, distancePx: Float): Float {
        if (isAnimating || distancePx <= 0f || deltaY == 0f) return 0f
        val current = open
        // 02 禁止下滑；01 端下滑无位移。两个端点的 clamp 都返回 0，避免吞掉内容手势。
        if (deltaY > 0f && current <= 0f) return 0f
        if (deltaY > 0f && current >= 1f) return 0f
        val next = (current - deltaY / distancePx).coerceIn(0f, 1f)
        val consumedProgress = next - current
        if (consumedProgress == 0f) return 0f
        open = next
        return -consumedProgress * distancePx
    }

    /**
     * 02→04 跟手：只改 [collapse]。上滑增加、下滑减少。
     * 02 端下滑回 02 本身（不回到 01）；04 端上滑放行给内容/橡皮筋。
     * @return 实际消费的像素（0 = 放行给内容滚动）。
     */
    fun dragCollapseBy(deltaY: Float, distancePx: Float): Float {
        if (isAnimating || distancePx <= 0f || deltaY == 0f || open < 1f) return 0f
        val current = collapse
        if (deltaY > 0f && current <= 0f) return 0f
        if (deltaY < 0f && current >= 1f) return 0f
        val next = (current - deltaY / distancePx).coerceIn(0f, 1f)
        val consumedProgress = next - current
        if (consumedProgress == 0f) return 0f
        collapse = next
        return -consumedProgress * distancePx
    }

    /**
     * 松手吸附：按当前进度所在段 + 松手速度决定目标，全部用
     * [Motion.Overlay] / [Motion.AgendaSnapMillis]。
     * 04→02 与 02→04 共用 [DaySnapThreshold]，且只看松手时的最终进度：
     * 04 下滑越过往 02 的阈值（或快速下滑一甩）才提交 02，途中越过又拉回来的
     * 不算；04 仍在 1 时上滑只做过滚动，不启动吸附打断橡皮筋。
     * collapse 段的吸附可被新手势接管（[takeOverSnap]），01↔02 段不可。
     *
     * @param velocityY px/s，正 = 下滑，负 = 上滑
     * @param flingThresholdPx 快速甩动阈值（px/s）
     */
    fun settle(velocityY: Float, flingThresholdPx: Float) {
        if (isAnimating) return
        when {
            open < 1f -> {
                val forward = snapForward(open, velocityY, flingThresholdPx)
                when {
                    forward -> animateTo(1f, 0f, DayStage.Compact, commit = true)
                    open > 0f -> animateTo(0f, 0f, DayStage.Calendar, commit = false)
                    // 已在 01 且没有越过：只确保状态正确，不启动空动画
                    else -> stage = DayStage.Calendar
                }
            }
            else -> {
                // 02/03/04 都由 collapse 段收束；04→02 也按松手时的最终进度判阈值，
                // 下滑后上滑回来（未越过）回弹 04，越过才提交 02。04 仍在 1 时
                // forward 为 true，只对齐状态、不起动画。
                val forward = snapCollapseForward(stage, collapse, velocityY, flingThresholdPx)
                when {
                    forward && collapse < 1f ->
                        animateTo(
                            1f, 1f, DayStage.Schedule, commit = true, takeOverable = true,
                        )
                    forward -> commitCurrentEndpoint()
                    collapse > 0f -> animateTo(
                        1f, 0f, DayStage.Compact, commit = true, takeOverable = true,
                    )
                    else -> commitCurrentEndpoint()
                }
            }
        }
    }

    /**
     * 新手势接管进行中的 02↔04 吸附：取消动画、停在当前进度，随后的
     * [dragCollapseBy] 立即跟手。返回是否真的取消了在途吸附（动画已结束/不可接管时为 false）。
     */
    fun takeOverSnap(): Boolean {
        if (!canTakeOverSnap) return false
        // 先递增代号再 cancel：旧协程的 finally 不得再清掉接管后的状态。
        animationGeneration++
        animationJob?.cancel()
        animationJob = null
        isAnimating = false
        canTakeOverSnap = false
        return true
    }

    /** 02 点图片卡：同一曲线原路反向播放 02→01，纯视觉，不写任何持久化。 */
    fun replayToCalendar() {
        if (isAnimating || stage != DayStage.Compact || open < 1f || collapse > 0f) return
        animateTo(0f, 0f, DayStage.Calendar, commit = false)
    }

    /**
     * 跨天重置：取消在途动画、立即回 01，不写状态。midnight/onResume 检测到日期变化时
     * 调用；不播放补间，避免用户在 04 停留时看到反向动画。
     */
    fun resetForNewDay() {
        animationJob?.cancel()
        cornerFadeJob?.cancel()
        cornerFadeJob = null
        animationGeneration++
        open = 0f
        collapse = 0f
        stage = DayStage.Calendar
        isAnimating = false
        canTakeOverSnap = false
        // 跨天直接回 01：直角立即生效，不播放收角动画。
        deviceCornerAlpha = 0f
    }

    /**
     * 无中间进度时（拖到端点直接松手、点击等）把 stage 与持久化对齐。
     * open=1 统一按 collapse 判 02/04；open=0 为 01。
     */
    private fun commitCurrentEndpoint() {
        when {
            open >= 1f && collapse >= 1f -> commit(DayStage.Schedule)
            open >= 1f -> commit(DayStage.Compact)
            else -> stage = DayStage.Calendar
        }
    }

    private fun commit(target: DayStage) {
        stage = target
        if (target != DayStage.Calendar) onCommit(target)
    }

    /**
     * 共用的吸附动画：从当前值 tween 到目标值，每帧只写两个 float state；
     * 归位后提交 stage/持久化。取消（重播被新动作/跨天打断/手势接管）时不会提交。
     *
     * @param takeOverable 02↔04 段的吸附传 true：允许新手势中途 [takeOverSnap]；
     *   01↔02 与重播传 false，动画期间整段屏蔽手势。
     */
    private fun animateTo(
        targetOpen: Float,
        targetCollapse: Float,
        targetStage: DayStage,
        commit: Boolean,
        takeOverable: Boolean = false,
    ) {
        animationJob?.cancel()
        // 新转场开始时先停掉 01 的收角尾巴：若之后又落回 01，会在落定时重新开始。
        cornerFadeJob?.cancel()
        cornerFadeJob = null
        val generation = ++animationGeneration
        isAnimating = true
        canTakeOverSnap = takeOverable
        animationJob = scope.launch {
            val startOpen = open
            val startCollapse = collapse
            val driver = Animatable(0f)
            try {
                driver.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = Motion.AgendaSnapMillis,
                        easing = Motion.Overlay,
                    ),
                ) {
                    open = startOpen + (targetOpen - startOpen) * value
                    collapse = startCollapse + (targetCollapse - startCollapse) * value
                }
                // 末帧落到精确目标，避免浮点误差让 Geometry 停在差一帧的位置
                open = targetOpen
                collapse = targetCollapse
                stage = targetStage
                if (commit && targetStage != DayStage.Calendar) {
                    onCommit(targetStage)
                }
                // 02→01 展开到位（图片已铺满全屏）后，机身圆角再单独渐进收成直角。
                if (targetOpen == 0f && targetStage == DayStage.Calendar) {
                    startCornerFade()
                }
            } finally {
                if (generation == animationGeneration) {
                    isAnimating = false
                    canTakeOverSnap = false
                }
            }
        }
    }

    /**
     * 展开落定后的收角：先把系数钉回 1——此刻 open 刚落到 0，几何取满幅的
     * 机身圆角，与展开动画末帧连续——再淡出到 0 收成直角。
     * 独立于 [isAnimating]，期间手势立即可用；再次上滑时 open > 0 的几何
     * 会直接取机身圆角，这条尾巴的残余值不再被读取。
     */
    private fun startCornerFade() {
        cornerFadeJob?.cancel()
        deviceCornerAlpha = 1f
        cornerFadeJob = scope.launch {
            val driver = Animatable(1f)
            driver.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = Motion.AgendaCornerFadeMillis,
                    easing = Motion.Decelerate,
                ),
            ) {
                deviceCornerAlpha = value
            }
        }
    }
}
