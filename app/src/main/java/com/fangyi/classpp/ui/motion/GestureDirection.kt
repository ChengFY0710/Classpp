// 横滑/竖滑方向仲裁：为嵌在竖向滚动容器（LazyColumn / 橡皮筋兜底层）里的横向手势
// （如卡片侧滑）提供「主方向优先 + 方向阈值 + 单次定轴全程锁定」的判定策略。
//
// 背景：Compose 的滚动检测按 touch slop 竞速仲裁（Main pass 冒泡、内层先于外层），
// 各检测器只累积自己轴的位移，谁先越过系统 slop（约 8dp）谁赢——斜向起手的轨迹
// 往往竖向分量先过线，横滑被整体让位，竖向的滚动/橡皮筋动画随之误触发。
// 本文件的检测器改在 Initial pass（同一事件里先于所有 Main pass 处理）观察并抢占：
// 横向一旦按占比阈值定轴立即消费事件，父级滚动检测见 change 已被消费即整段退出，
// 手势中途再倾斜也无法被夺走；判定为竖滑则立即让位，父级正常接管。
package com.fangyi.classpp.ui.motion

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.util.fastAny
import kotlin.math.abs

/**
 * 方向判定线系数：主方向判定位移下限 = 系统 touchSlop × 此值。
 * 取 0.8 略低于系统 slop——方向明确的手势抢先于父级滚动检测定轴；
 * 取得过大（≥1）会在与父级的同帧竞速中失去先机，过小则轻微抖动即误定轴。
 */
internal const val DirectionSlopFactor = 0.8f

/**
 * 主方向占比阈值：认定主方向要求 |主轴| ≥ 此值 × |交叉轴|。
 * 1.25 对应约 38.7° 锥角——横滑判定期问轨迹倾斜不超过它才算横滑；
 * 调大对竖向更宽容（横滑更难认定），调小对横滑更宽容。
 */
internal const val DirectionRatio = 1.25f

/** 单段手势的方向：未定轴 / 横向 / 竖向 */
internal enum class DragDirection {
    Undecided,
    Horizontal,
    Vertical,
}

/**
 * 单段手势的方向锁分类器：累积双轴位移逐帧判定，主方向过判定线且占比达标才定轴。
 *
 * - 未定轴前只累积不表态（调用方在此期间不得消费事件，点击不受影响）；
 * - **定轴后整段手势锁定、绝不重判**——这是消除「手势中途倾斜导致横滑/竖滑来回切换
 *   抖动」的关键：判定依据永远是定轴瞬间的完整累积轨迹，不随后续帧摆动；
 * - 近 45° 的纠结轨迹按占比条件可能长期无法满足，累计路径超过 2 × touchSlop 仍悬而
 *   未决时按主分量兜底定轴，避免手势悬死不响应。
 *
 * 实例与单段手势一对一（每次 down 新建），无跨手势状态。
 */
internal class DragDirectionLock(touchSlop: Float) {

    private val threshold = touchSlop * DirectionSlopFactor
    private val fallbackPathLength = touchSlop * 2f

    var direction: DragDirection = DragDirection.Undecided
        private set

    private var totalX = 0f
    private var totalY = 0f

    /** 逐帧喂入位移增量，返回当前方向（未定轴时继续观察） */
    fun onMove(dx: Float, dy: Float): DragDirection {
        if (direction != DragDirection.Undecided) return direction
        totalX += dx
        totalY += dy
        val absX = abs(totalX)
        val absY = abs(totalY)
        direction = when {
            absX >= threshold && absX >= DirectionRatio * absY -> DragDirection.Horizontal
            absY >= threshold && absY >= DirectionRatio * absX -> DragDirection.Vertical
            absX + absY >= fallbackPathLength ->
                if (absX >= absY) DragDirection.Horizontal else DragDirection.Vertical
            else -> DragDirection.Undecided
        }
        return direction
    }
}

/**
 * 带方向锁的横向拖拽检测：[androidx.compose.foundation.gestures.detectHorizontalDragGestures]
 * 的方向仲裁增强版，回调签名兼容（可直接替换调用点）。
 *
 * 与原版的差异（其余行为一致：单指针跟踪、松手收尾、取消回调、tap 不消费）：
 * - **方向阈值**：横向认定要求累计位移同时满足「|x| ≥ 0.8 × slop」与「|x| ≥ 1.25 × |y|」
 *   （见 [DirectionSlopFactor] / [DirectionRatio]），斜向起手不再靠系统 slop 竞速掷硬币；
 * - **Initial pass 抢占**：横向定轴当帧即在 Initial pass 消费事件——早于父级滚动检测的
 *   Main pass 处理，父级见 change 已被消费即整段退出；此后每帧持续消费，手势中途倾斜
 *   父级也无法接管（原有实现消费在 Main pass，斜向起手时会在竞速中整体让位）；
 * - **单次定轴**：横向认定后全程锁定（[DragDirectionLock]），竖向认定后立即让位
 *   （不消费、无回调，父级滚动/橡皮筋正常接管），均不再重判。
 *
 * 供嵌在竖向滚动容器内的横向手势使用（卡片侧滑、滑杆拖动等）——竖滑让位保证容器
 * 在滑杆上方照常滚动；真正独立于任何滚动容器的横滑用原版即可。
 */
suspend fun PointerInputScope.detectHorizontalSwipeGestures(
    onDragStart: (Offset) -> Unit = {},
    onHorizontalDrag: (change: PointerInputChange, dragAmount: Float) -> Unit,
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
) {
    val touchSlop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val lock = DragDirectionLock(touchSlop)
        var swipeStarted = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val changes = event.changes
            // 方向被他人抢定（父级滚动已接管并消费）：本段手势让位，已开拖则按取消收场
            if (changes.fastAny { it.isConsumed }) {
                if (swipeStarted) onDragCancel()
                break
            }
            val change = changes.firstOrNull { it.id == down.id }
            if (change == null) {
                if (swipeStarted) onDragCancel()
                break
            }
            val delta = change.positionChange()
            when (lock.onMove(delta.x, delta.y)) {
                DragDirection.Horizontal -> {
                    if (!swipeStarted) {
                        swipeStarted = true
                        onDragStart(change.position)
                    }
                    // Initial pass 消费：父级（Main pass）整段退出，本手势不可被夺走
                    change.consume()
                    onHorizontalDrag(change, delta.x)
                }
                // 认定竖滑：立即让位，不消费，父级滚动/橡皮筋正常接管
                DragDirection.Vertical -> break
                // 未定轴：只观察不消费，不影响点击
                DragDirection.Undecided -> Unit
            }
            if (!change.pressed) {
                if (swipeStarted) onDragEnd()
                break
            }
        }
    }
}
