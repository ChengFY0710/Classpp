package com.fangyi.classpp.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 「抓起卡片自由拖拽 + Q 弹落位」的完整动效层（单文件，自包含）。
 *
 * 复刻自 NexioSchedule 的课程卡调课手感，物理参数与其一致，只保留通用能力、
 * 不认识任何业务模型：谁被拖、落在哪个格子、写不写库，全在宿主。
 *
 * ## 六段动画（时序即手感）
 * 1. **按压下潜** 1 → 0.94，[PressSinkSpec]：短促、几乎不过冲，只做「按到了」的回执；
 * 2. **长按拾起** 停 [LongPressMillis] 后原卡手势主动退出，把指针交给容器层
 *    （[scheduleDragTracker]）——这是整套架构的关键：卡片不自己跟手，才不会被列表
 *    回收 / 翻页重建打断；
 * 3. **抓起放大** 0.94 → 1.08，[GrabMillis]。从下潜值起步而不是从 1 起步，视觉上
 *    是「被捏住提起来」，不是「重新出现一次」；
 * 4. **跟手 1:1**，位移只写 [ScheduleDragMotionState.offsetX] / [offsetY]（float state），
 *    只在 `graphicsLayer` 的**绘制期**读——进组合就是每帧重组一整页；
 * 5. **松手吸附 = Q 弹本体**：[SettlePosition] 的 `dampingRatio = 0.58`（欠阻尼，必然
 *    冲过头再弹回）+ 把松手瞬间的速度喂进 `initialVelocity`。只做前者像橡皮筋，
 *    只做后者像平移，两者同时成立才有「甩出去被接住」的手感；
 * 6. **落地涟漪**：落点为中心，周围卡片按距离延迟 0~400ms 各弹一次 1 → 1.08 → 1。
 *    这是「重量感」的来源——卡片落地，桌面被震了一下。
 *
 * ## 与 [com.fangyi.classpp.ui.components.GroupCardDragState] 的分工
 * 那套是**列表重排**：纵向跟手、邻居让位、落位走 [Motion.ReorderMillis] 的 tween
 * （无过冲，因为要让位位移和浮层同时收尾）。本文件是**自由拖拽**：二维跟手、落点由
 * 宿主判定、落位走欠阻尼弹簧（就是要过冲）。两者语义不同，不要互相替换。
 *
 * ## 典型接法
 * ```
 * val motion = rememberScheduleDragMotion()
 * val scope = rememberCoroutineScope()
 *
 * Box(Modifier.scheduleDragTracker(
 *     motion = motion,
 *     onDragEnd = {
 *         // 用浮层中心的 root 坐标去问宿主：落在哪个槽？
 *         val slot = hitTest(motion.dropCenter(anchor, size))
 *         if (slot != null) motion.slamTo(slot.offset, slot.center) { commit(slot) }
 *         else motion.slamTo(Offset.Zero, null)
 *     },
 * )) {
 *     CourseCard(Modifier.scheduleDragSource(
 *         motion = motion,
 *         key = course.id,
 *         onGrab = { center, size -> anchor = center; cardSize = size },
 *     ))
 * }
 *
 * if (motion.visible) {
 *     Box(Modifier.offset { anchor.toIntOffset() }.size(cardSize).scheduleDragFloat(motion)) {
 *         CourseCard(course)   // 浮层，同一张卡
 *     }
 * }
 * ```
 */

// ── 本效果专属参数：只服务这一套拖拽，按 [Motion] 的约定留在本文件内 ──────────

/** 按压下沉目标缩放：只做「按到了」的回执，幅度大了会像点击失败。 */
private const val PressSinkScale = 0.94f

/**
 * 按压下沉弹簧：刚度 600 配阻尼 0.8 —— 收敛快（约 100ms 内到位）、只有一丝过冲。
 * 用 [Motion.Settle] 那种无过冲弹簧会显得「软」，用欠阻尼又会抢走落位的手感。
 */
private val PressSinkSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 600f)

/** 长按拾起阈值。与系统长按（500ms）错开：拖拽要更跟手，500ms 会让人觉得「按不动」。 */
private const val LongPressMillis = 320L

/**
 * 横滑让位阈值：位移超过它且横向占主导时，判定为用户在翻页 / 横滑，主动放弃拾起。
 * 没有这条，横向拖拽永远抢不过 pager。
 */
private val HorizontalSlop = 5.dp

/** 抓起放大目标：1.08 是「提起来」的最小可感知幅度，1.15 以上会显得浮层脱框。 */
private const val GrabScale = 1.08f

/** 抓起放大时长：短促上冲，与长按触感同步。 */
private const val GrabMillis = 120

/**
 * 落位位置弹簧：**Q 弹的核心**。
 *
 * `dampingRatio = 0.58` < 1 → 欠阻尼，一定冲过目标再回来，通常 1~2 次可见回摆；
 * `StiffnessMediumLow` 让整段回摆持续 300~500ms，肉眼能看清。
 *
 * 不要把阻尼调到 0.8 以上「求稳」——那样只剩一点点过冲，就完全不 Q 了；
 * 也不要低于 0.45，会来回荡三次像水球。
 */
private val SettlePosition = spring<Float>(
    dampingRatio = 0.58f,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * 落位缩放弹簧：比位置稍硬、阻尼稍高（0.7），让 1.08 → 1 比位移先收住。
 * 缩放和位移同时过冲会显得「整张卡在抖」，错开才有「卡先落稳、惯性再收」的层次。
 */
private val SettleScale = spring<Float>(
    dampingRatio = 0.7f,
    stiffness = Spring.StiffnessMedium,
)

/** 落地涟漪的触发延迟：吸附起步后一小段才震，先有位移再有冲击，否则像原地抖了一下。 */
private const val LandRippleDelayMillis = 120L

/** 涟漪峰值缩放与升降时长：上冲快（80ms）、回落慢（320ms），才像一次衰减的震动。 */
private const val RipplePeakScale = 1.08f
private const val RippleRiseMillis = 80
private const val RippleFallMillis = 320

/** 涟漪免延迟半径：落点附近的卡片立刻震，不排队。 */
private const val RippleFreeRadiusPx = 90f

/** 涟漪距离衰减：每超出 1px 延迟 0.3ms，封顶 400ms——再远就不该有反应了。 */
private const val RippleMillisPerPx = 0.3f
private const val RippleMaxDelayMillis = 400f

/**
 * 速度低通系数：新采样占 0.6、旧值占 0.4。
 *
 * 单帧的 `delta / dt` 会被事件抖动放大成几千 px/s，直接喂给弹簧会把卡片甩飞。
 * 一阶低通把尖峰压平，同时保留「甩」的方向感。
 */
private const val VelocitySmoothing = 0.6f

/** 速度钳制（px/s）：防止异常帧把落位动画甩出屏幕。 */
private const val MaxVelocity = 12000f

/** 无动画收场时的缩放回归时长。 */
private const val DismissMillis = 180

/** 落地冲击波：中心（root 坐标）+ token。token 自增，卡片据此只响应新的一次落地。 */
data class LandRippleSpec(
    val center: Offset = Offset.Zero,
    val token: Int = 0,
)

/**
 * 拖拽动效的状态所有者：位移、缩放、速度采样、涟漪广播都在这里。
 *
 * 刻意做成页面级 holder（挂在 pager / 列表之上），而不是放在卡片里：
 * 卡片会被列表回收、翻页重建，拖到一半重建就把状态丢了。卡片的
 * [scheduleDragSource] 只负责「判定长按」，判定完就退出，不做后续跟手。
 *
 * 位移用 [mutableFloatStateOf] 而非 `mutableStateOf`：它每帧都变，进组合就是每帧
 * 重组整页；只让 `graphicsLayer` 的绘制块读，则只失效这一层。
 */
@Stable
class ScheduleDragMotionState internal constructor(
    private val scope: CoroutineScope,
) {
    /** 跟手位移（px，相对拾起点）。只在 graphicsLayer 绘制期读。 */
    val offsetX = mutableFloatStateOf(0f)
    val offsetY = mutableFloatStateOf(0f)

    /** 浮层缩放：0.94（下潜）→ 1.08（抓起）→ 1（落位）。 */
    val scale = Animatable(1f)

    /** 浮层是否在屏上。宿主据此决定是否组合浮层。 */
    var visible by mutableStateOf(false)
        private set

    /** 当前被拾起的业务标识（通常是卡片 id）；null = 没有卡片被拾起。 */
    var liftedKey: Any? by mutableStateOf(null)
        private set

    /** 是否已真正拖动（位移超过 touchSlop）。松手时据此区分「落位」与「原地松手」。 */
    var dragging by mutableStateOf(false)
        private set

    /** 正在跟手的指针。宿主若有自己的 pager / 滚动容器，可读它主动让路。 */
    var holdingPointerId: PointerId? by mutableStateOf(null)
        private set

    /** 最近一次落地涟漪；卡片读它的 token 决定是否播一次震动。 */
    var landRipple by mutableStateOf(LandRippleSpec())
        private set

    private var velocityX = 0f
    private var velocityY = 0f
    private var lastSampleMs = 0L
    private var travelled = 0f

    /**
     * 拾起：登记指针、重置位移与速度，浮层从下潜值弹到 [GrabScale]。
     *
     * 由 [scheduleDragSource] 在长按到点时调用，宿主一般不直接调。
     */
    internal fun lift(key: Any?, pointerId: PointerId) {
        // 兜底：上一次拖拽若因系统取消 / 宿主提前卸载而没走到收尾，这里强制清掉。
        // 不清就会永久占着指针，之后任何卡片都拾不起来。
        if (holdingPointerId != null) release()
        liftedKey = key
        holdingPointerId = pointerId
        visible = true
        dragging = false
        travelled = 0f
        velocityX = 0f
        velocityY = 0f
        lastSampleMs = 0L
        offsetX.floatValue = 0f
        offsetY.floatValue = 0f
        scope.launch {
            // 从下潜值起步，不是从 1 起步：视觉上是「被捏住提起来」
            scale.snapTo(PressSinkScale)
            scale.animateTo(GrabScale, tween(GrabMillis))
        }
    }

    /**
     * 跟手：位移按增量累加，同时采样速度。
     *
     * 用增量而不是「当前坐标 - 按下坐标」是刻意的：增量不依赖坐标空间转换，
     * 容器挂在哪个层级都不会算错，浮层锚点也可以固定在卡片原位。
     *
     * @return true 表示本次调用让 [dragging] 从 false 变为 true（刚刚越过 touchSlop），
     *   宿主据此回调一次「开始拖拽」。
     */
    fun dragBy(delta: Offset, atMs: Long, slopPx: Float): Boolean {
        if (lastSampleMs != 0L) {
            val dt = (atMs - lastSampleMs).coerceAtLeast(1L)
            val rawX = (delta.x / dt * 1000f).coerceIn(-MaxVelocity, MaxVelocity)
            val rawY = (delta.y / dt * 1000f).coerceIn(-MaxVelocity, MaxVelocity)
            velocityX += (rawX - velocityX) * VelocitySmoothing
            velocityY += (rawY - velocityY) * VelocitySmoothing
        }
        lastSampleMs = atMs
        offsetX.floatValue += delta.x
        offsetY.floatValue += delta.y

        if (dragging) return false
        travelled += sqrt(delta.x * delta.x + delta.y * delta.y)
        if (travelled <= slopPx) return false
        dragging = true
        return true
    }

    /** 指针离开但没拖起来：不播落位，直接收场。 */
    fun release() {
        holdingPointerId = null
        dragging = false
    }

    /**
     * Q 弹落位：位移与缩放三条动画**并发**跑完，位置带松手初速度。
     *
     * 三条必须并发。串行 await 会变成「先缩回 1、再滑过去」，卡片像两段拼接的动画；
     * 并发才是「一张卡被同时拉回位置和尺寸」。
     *
     * @param targetOffset 目标位移（相对拾起点；吸回原位传 [Offset.Zero]）
     * @param rippleCenter 落点中心（root 坐标）；null = 不震
     * @param onSettled 动画完整播完后回调一次（宿主在这里写库 / 收浮层）
     */
    fun slamTo(
        targetOffset: Offset,
        rippleCenter: Offset? = null,
        onSettled: () -> Unit = {},
    ) {
        scope.launch {
            if (rippleCenter != null) {
                launch {
                    delay(LandRippleDelayMillis)
                    landRipple = LandRippleSpec(rippleCenter, landRipple.token + 1)
                }
            }
            coroutineScope {
                launch {
                    animate(
                        initialValue = offsetX.floatValue,
                        targetValue = targetOffset.x,
                        initialVelocity = velocityX,
                        animationSpec = SettlePosition,
                    ) { value, _ -> offsetX.floatValue = value }
                }
                launch {
                    animate(
                        initialValue = offsetY.floatValue,
                        targetValue = targetOffset.y,
                        initialVelocity = velocityY,
                        animationSpec = SettlePosition,
                    ) { value, _ -> offsetY.floatValue = value }
                }
                launch { scale.animateTo(1f, SettleScale) }
            }
            finish()
            onSettled()
        }
    }

    /**
     * 无动画弹回后收场（返回键 / 点空白）：先让缩放平滑回 1，再清状态。
     *
     * 直接清状态会让原卡 alpha 从 0 硬切回 1，看起来是「闪一下」。
     */
    fun dismiss() {
        scope.launch {
            scale.animateTo(1f, tween(DismissMillis))
            finish()
        }
    }

    /**
     * 浮层中心（root 坐标）——宿主的落点判定入口。
     *
     * @param anchor 浮层左上角（root 坐标）
     * @param size 浮层尺寸
     */
    fun floatCenter(anchor: Offset, size: Size): Offset =
        Offset(anchor.x + size.width / 2f + offsetX.floatValue, anchor.y + size.height / 2f + offsetY.floatValue)

    /** 收尾的唯一入口：任何一条路径漏一个字段，都会留下「卡片没了但原卡还隐着」的幽灵态。 */
    private fun finish() {
        visible = false
        liftedKey = null
        holdingPointerId = null
        dragging = false
        travelled = 0f
        velocityX = 0f
        velocityY = 0f
        lastSampleMs = 0L
        offsetX.floatValue = 0f
        offsetY.floatValue = 0f
        scope.launch { scale.snapTo(1f) }
    }
}

/**
 * 创建并记住一份拖拽动效状态。
 *
 * 位置要在**承载拖拽的那个容器**上（通常是页面根 Box，跨列表 / 跨页都不重建），
 * 不要在每张卡片里 remember —— 那样卡片一被回收状态就没了。
 */
@Composable
fun rememberScheduleDragMotion(): ScheduleDragMotionState {
    val scope = rememberCoroutineScope()
    return remember(scope) { ScheduleDragMotionState(scope) }
}

/**
 * 源卡片：只做「按压回执 + 长按交接 + 落地涟漪」，做完就退出，不跟手。
 *
 * 手势会在长按到点时主动结束，把后续移动让给父层的 [scheduleDragTracker]。
 * 不这么做就得在卡片里自己处理跨格、翻周、列表回收，全是坑。
 *
 * @param key 业务标识（卡片 id）。用于判断「这张卡是否正被拾起」——被拾起的那张
 *   原卡 alpha 归 0，由浮层顶替，避免双影。
 * @param onTap 轻点回调（320ms 内抬手且位移小于 touchSlop）
 * @param onGrab 长按到点回调，给出卡片中心与尺寸（root 坐标），宿主据此摆浮层
 */
@Composable
fun Modifier.scheduleDragSource(
    motion: ScheduleDragMotionState,
    key: Any?,
    enabled: Boolean = true,
    onTap: () -> Unit = {},
    onGrab: (center: Offset, size: Size) -> Unit = { _, _ -> },
): Modifier {
    val scope = rememberCoroutineScope()
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val sinkScale = remember { Animatable(1f) }
    val rippleScale = remember { Animatable(1f) }
    var pressed by remember { mutableStateOf(false) }
    var sinkActive by remember { mutableStateOf(false) }
    var rippling by remember { mutableStateOf(false) }

    // 首帧即非按压态时直接收摊，否则每张卡会白跑一次弹簧 + 两次重组
    LaunchedEffect(pressed) {
        if (!pressed && !sinkActive) return@LaunchedEffect
        sinkActive = true
        sinkScale.animateTo(
            targetValue = if (pressed) PressSinkScale else 1f,
            animationSpec = PressSinkSpec,
        )
        if (!pressed) sinkActive = false
    }

    // 落地涟漪：同一份 motion 的所有卡片都读这个 token，按距离各延迟一段
    val landRipple = motion.landRipple
    LaunchedEffect(landRipple.token) {
        if (landRipple.token == 0) return@LaunchedEffect
        val self = coords[0]
        if (self == null || !self.isAttached) return@LaunchedEffect
        val center = self.localToRoot(Offset(self.size.width / 2f, self.size.height / 2f))
        val distance = hypot(center.x - landRipple.center.x, center.y - landRipple.center.y)
        val delayMs = if (distance <= RippleFreeRadiusPx) {
            0L
        } else {
            ((distance - RippleFreeRadiusPx) * RippleMillisPerPx)
                .coerceAtMost(RippleMaxDelayMillis)
                .toLong()
        }
        if (delayMs > 0) delay(delayMs)
        rippling = true
        try {
            rippleScale.animateTo(RipplePeakScale, tween(RippleRiseMillis, easing = Motion.Standard))
            rippleScale.animateTo(1f, tween(RippleFallMillis, easing = Motion.Standard))
        } finally {
            rippling = false
        }
    }

    val isLifted = motion.liftedKey == key
    // 静止卡不挂变换层：几十张常驻合成层是列表滑动的 draw 热点
    val needLayer = pressed || sinkActive || rippling || isLifted

    return this
        // 只存坐标：布局期不做逐卡 localToRoot，消费点自己算
        .onGloballyPositioned { coords[0] = it }
        .then(
            if (needLayer) {
                Modifier.graphicsLayer {
                    val s = sinkScale.value * rippleScale.value
                    scaleX = s
                    scaleY = s
                    // 被浮层顶替：立即隐去，不淡出，否则与浮层叠出双影
                    alpha = if (isLifted) 0f else 1f
                }
            } else {
                Modifier
            }
        )
        .pointerInput(motion, key, enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                pressed = true
                val slop = viewConfiguration.touchSlop
                val horizontalSlop = HorizontalSlop.toPx()
                var settled = false
                var tap = false

                // 计时只做一件事：确认长按并把指针交给上层 tracker
                val timer = scope.launch {
                    delay(LongPressMillis)
                    if (settled || motion.holdingPointerId != null) return@launch
                    settled = true
                    pressed = false
                    val self = coords[0]
                    val center = if (self != null && self.isAttached) {
                        self.localToRoot(Offset(self.size.width / 2f, self.size.height / 2f))
                    } else {
                        Offset.Zero
                    }
                    motion.lift(key, down.id)
                    onGrab(center, Size(size.width.toFloat(), size.height.toFloat()))
                }
                try {
                    // 320ms 内抬手 = 轻点；横滑主导 / 位移超容差 = 让给翻页与滚动
                    while (!settled) {
                        val change = awaitPointerEvent(PointerEventPass.Final)
                            .changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            tap = (change.position - down.position).getDistance() < slop
                            break
                        }
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (abs(dx) > horizontalSlop && abs(dx) > abs(dy) * 1.2f) break
                        if (hypot(dx, dy) > slop) break
                    }
                    if (!settled) {
                        settled = true
                        pressed = false
                        if (tap) onTap()
                    }
                } finally {
                    timer.cancel()
                }
            }
        }
}

/**
 * 容器层 tracker：挂在承载拖拽的容器（页面根 Box / 列表外层）上，接管长按之后的指针。
 *
 * 与源卡的手势是**两个独立的 pointerInput**：卡片判定完长按就退出，这里从那一刻起
 * 才算数（靠 [ScheduleDragMotionState.holdingPointerId] 认领）。
 *
 * 这里在 [PointerEventPass.Main] 消费事件。若宿主自带 pager / 横向滚动容器，
 * 可读 [ScheduleDragMotionState.holdingPointerId] 让它主动让路——比调 pass 顺序可靠。
 *
 * @param onDragStart 位移首次超过 touchSlop 时回调一次
 * @param onDragEnd 拖动过之后抬手：宿主在这里判定落点并调 [ScheduleDragMotionState.slamTo]
 * @param onDragCancel 长按拾起但没拖动就抬手 / 指针丢失：宿主收浮层
 */
fun Modifier.scheduleDragTracker(
    motion: ScheduleDragMotionState,
    enabled: Boolean = true,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
): Modifier = pointerInput(motion, enabled) {
    if (!enabled) return@pointerInput
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val pointerId = motion.holdingPointerId ?: continue
            val change = event.changes.firstOrNull { it.id == pointerId }
            if (change == null) {
                // 跟手指针整批消失（系统取消 / 源卡被回收）：按取消收场，
                // 不能只 continue——那样 holdingPointerId 会一直挂着。
                motion.release()
                onDragCancel()
                continue
            }

            if (!change.pressed) {
                val wasDragging = motion.dragging
                motion.release()
                if (wasDragging) onDragEnd() else onDragCancel()
                continue
            }

            val delta = change.position - change.previousPosition
            if (delta == Offset.Zero) continue
            val justStarted = motion.dragBy(delta, change.uptimeMillis, viewConfiguration.touchSlop)
            // 拾起之后就该独占这个指针：不消费会被父级滚动 / 翻页抢走
            change.consume()
            if (justStarted) onDragStart()
        }
    }
}

/**
 * 浮层：把 [ScheduleDragMotionState] 的位移与缩放接到绘制层。
 *
 * 宿主自己负责浮层的锚点（`offset { }`）与尺寸（`size`），这里只管叠加位移——
 * 锚点固定在卡片原位，位移就是手指位移，不需要每帧重算坐标。
 *
 * 内部读的是 float state 与 [Animatable]，全部发生在 `graphicsLayer` 的绘制期，
 * 只失效这一层，不触发重组。
 */
fun Modifier.scheduleDragFloat(motion: ScheduleDragMotionState): Modifier =
    graphicsLayer {
        translationX = motion.offsetX.floatValue
        translationY = motion.offsetY.floatValue
        scaleX = motion.scale.value
        scaleY = motion.scale.value
    }
