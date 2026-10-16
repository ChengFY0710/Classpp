package com.fangyi.classpp.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.detectHorizontalSwipeGestures
import com.fangyi.classpp.ui.theme.NoteCardShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 展开态动作卡宽度（初次滑出停留时露出的按钮宽，含图标居中留白） */
private val SwipeActionWidth = 80.dp

/** 动作卡与待办主体卡的间距：拖拽全程恒定不变 */
private val SwipeActionGap = 10.dp

/** 呼出位/展开阈值：按钮完整露出（80+10）。首段拖拽松手的吸附落点，也是执行会话的填充起点 */
private val SwipeOpenOffset = SwipeActionWidth + SwipeActionGap

/** 拖拽上限/触发阈值：完全变色即钉住（两阶段的拖拽都钉在此处）；执行会话满色后松手才执行 */
private val SwipeMaxOffset = 216.dp

/** 填充跨度 = 上限 − 呼出位：执行会话越过呼出位后继续同向拖动的渐变变色行程（126dp） */
private val SwipeFillSpan = SwipeMaxOffset - SwipeOpenOffset

/** 触发阈值：与拖拽上限同值（满色即钉住） */
private val SwipeExecuteOffset = SwipeMaxOffset

/** 动作卡图标尺寸；滑出不足 [SwipeIconRevealSpan] 时图标按比例淡入，避免窄缝里生硬裁切 */
private val SwipeActionIconSize = 24.dp
private val SwipeIconRevealSpan = 36.dp

/** 删除飞出后等待落盘驱动的离场时长；超时仍在本组合（落盘失败）则自动滑回复位 */
private const val SwipeDeleteRestoreDelayMillis = 400L

/**
 * 侧滑动作：[NoteCardSwipe] 的单侧动作定义——图标、取色与业务回调。
 *
 * @property iconRes 图标资源（删除 ic_delete；置顶 ic_pin、取消置顶 ic_pin_off）
 * @property tint 未越过执行阈值时的图标色（theme 的 error / primary）
 * @property activeColor 越过执行阈值后背景填充的目标色（与 [tint] 同色）
 * @property onActiveColor 背景填充到位后的图标色——取 theme 的 onError / onPrimary，
 * 同 Overlay 顶栏删除/确认胶囊的 contentColor 处理（浅深主题各自成立，不硬编码白色）
 * @property contentDescription 动作卡的无障碍描述
 * @property onAction 松手越过执行阈值（或展开态直接点按动作卡）时执行的业务回调
 */
class SwipeAction(
    @DrawableRes val iconRes: Int,
    val tint: Color,
    val activeColor: Color,
    val onActiveColor: Color,
    val contentDescription: String,
    val onAction: () -> Unit,
)

/**
 * NoteCard 侧滑配置：互斥协调器 + 左右两侧动作（均可空，空侧不可拖出）。
 * [delete] 为左侧动作（卡片向右滑出时呼出，删除），[pin] 为右侧动作（向左滑出，置顶）。
 * [key] 用于互斥（同一时刻至多一张卡展开）与 Animatable 的按卡重建。
 */
class NoteCardSwipe(
    val key: Any,
    val coordinator: SwipeCoordinator,
    val delete: SwipeAction?,
    val pin: SwipeAction?,
)

/**
 * 侧滑互斥协调器：[activeKey] 为当前展开卡片的 key（null = 全部收起）。
 * 由页面层持有并下发——某卡展开时写入自己的 key，其余卡监听到变化自动收起；
 * 系统返回（页面层 BackHandler）置 null 即整体收起。
 */
class SwipeCoordinator {
    var activeKey: Any? by mutableStateOf(null)
}

/**
 * NoteCard 侧滑层：包住卡片内容，提供左滑/右滑呼出动作卡的手势与视觉反馈。
 *
 * 交互模型（两阶段按拖拽会话区分，位移符号约定：正 = 卡片右移呼出左侧动作、负 = 左移呼出右侧动作）：
 * - 拖拽跟手：动作卡宽度实时 = |位移| − [SwipeActionGap]（随拖拽变长，间距恒定），
 *   高度经 matchParentSize 撑满卡片实测高度，圆角与卡片一致（[NoteCardShape]）；
 *   拖拽上限 = [SwipeExecuteOffset]，到顶钉住；
 * - 第一阶段·呼出（从收起态起手的拖拽）：全程不变色、永不执行——拉到钉住点松手也只回
 *   [SwipeOpenOffset]（按钮露出状态）；[SwipeOpenOffset] 以下松手回弹收起。展开态下
 *   动作卡可直接点按执行、点卡片主体收起；
 * - 第二阶段·执行（从展开态起手的同向拖拽）：越过 [SwipeOpenOffset] 继续跟手位移并开始
 *   渐变变色——背景 surface → [SwipeAction.activeColor]、图标动作色 →
 *   [SwipeAction.onActiveColor]（[SwipeFillSpan] 内过渡到位）；拖到 [SwipeExecuteOffset]
 *   完全变色 = 触发阈值，触发一次长按触感（迟滞：回落到呼出位与阈值的中点以下才解除），
 *   此后松手执行——删除侧整卡飞出屏幕后回调（落盘失败仍在本组合时延时滑回复位），置顶侧
 *   先回弹归位再回调（卡片随后移组）；未满色松手回 [SwipeOpenOffset] 按钮露出状态；
 * - 反方向拖拽穿越零点不填充（按普通位移处理，松手按位移落点结算）。
 *
 * 手势用 [detectHorizontalSwipeGestures]（ui/motion 的方向仲裁增强版横滑检测）：
 * 累计位移过方向判定线且横向占比达标才认定横滑，认定后在 Initial pass 抢占消费、
 * 全程锁定——嵌在竖向滚动列表里起手带竖向分量的斜向轨迹不会被误判成滚动，
 * 中途倾斜也不切走；判定竖滑则立即让位，列表滚动/橡皮筋正常接管。位移经 lambda 式
 * offset 应用，拖拽全程卡片内容零重组。互斥经 [SwipeCoordinator]：起手即清掉他卡展开态、
 * 自身吸附展开时登记自己，页面层 BackHandler 置 null 时所有展开卡监听收起。
 *
 * [swipe] 为 null 时原样输出 [content]（不加层、不包 Box），未接入侧滑的使用处零影响。
 */
@Composable
fun NoteCardSwipeLayer(
    swipe: NoteCardSwipe?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (swipe == null) {
        Box(modifier) { content() }
        return
    }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // 手势回调生命周期长于单次组合（pointerInput 按 key 持久），经 State 读取最新配置
    val currentSwipe by rememberUpdatedState(swipe)

    val openPx = with(density) { SwipeOpenOffset.toPx() }
    val executePx = with(density) { SwipeExecuteOffset.toPx() }
    val maxPx = with(density) { SwipeMaxOffset.toPx() }
    val gapPx = with(density) { SwipeActionGap.toPx() }
    // 填充跨度：呼出位之上的位移距离（56dp），执行会话内映射颜色填充进度
    val fillSpanPx = maxPx - openPx
    val iconRevealPx = with(density) { SwipeIconRevealSpan.toPx() }

    // 位移（px）：正 = 右移呼出删除侧、负 = 左移呼出置顶侧；拖拽 snapTo 跟手、松手 animateTo 收场
    val offset = remember(swipe.key) { Animatable(0f) }
    // 拖拽会话状态（onDragStart 时定格）：起手是否已在展开态——决定本会话是「呼出」
    // （不变色、不执行）还是「执行」（可变色、满色触发）；起手侧符号锁定，反穿零点不填充
    var sessionFromOpen by remember(swipe.key) { mutableStateOf(false) }
    var sessionSign by remember(swipe.key) { mutableFloatStateOf(1f) }
    // 执行会话是否已拖到触发阈值（完全变色，到顶震一次；回落到填充中段以下才解除——
    // 钉住处手指抖动不反复触发触感）；呼出会话恒为 false
    var armed by remember(swipe.key) { mutableStateOf(false) }
    // 动作执行中（飞出/回弹 + 回调）：屏蔽协调器联动收起，避免执行途中被拽回
    var executing by remember(swipe.key) { mutableStateOf(false) }
    // 容器宽度：删除飞出的目标位移（整卡完全滑出屏幕）
    var layerWidthPx by remember { mutableIntStateOf(0) }
    val isOpen by remember(swipe.key) { derivedStateOf { abs(offset.value) > 0.5f } }

    // 收起：弹簧归位并让出互斥标记（他卡展开/系统返回引发的联动收起走 LaunchedEffect 分支）
    suspend fun collapse() {
        offset.animateTo(0f, Motion.Settle)
        val s = currentSwipe
        if (s.coordinator.activeKey == s.key) s.coordinator.activeKey = null
    }

    // 吸附展开：归位到按钮完整呼出的位置并登记互斥
    suspend fun openTo(sign: Float) {
        offset.animateTo(sign * openPx, Motion.Settle)
        val s = currentSwipe
        if (s.coordinator.activeKey != s.key) s.coordinator.activeKey = s.key
    }

    // 执行动作：删除侧先整卡飞出再回调（留延时兜底落盘失败的复位），置顶侧先回弹归位再回调
    suspend fun execute(action: SwipeAction, sign: Float) {
        executing = true
        try {
            if (sign > 0) {
                offset.animateTo(
                    maxOf(layerWidthPx, maxPx.roundToInt()).toFloat() * sign,
                    tween(Motion.FastMillis, easing = Motion.Accelerate),
                )
                val s = currentSwipe
                if (s.coordinator.activeKey == s.key) s.coordinator.activeKey = null
                action.onAction()
                // 正常路径：落盘成功 → 条目离场 → 本组合卸载、协程随之取消，下面不会执行；
                // 失败兜底：条目仍在场（Err 已 Toast），稍候整卡滑回复位
                delay(SwipeDeleteRestoreDelayMillis)
                offset.animateTo(0f, Motion.Settle)
            } else {
                offset.animateTo(0f, Motion.Settle)
                action.onAction()
            }
        } finally {
            executing = false
            // 执行即离场/归位：让出互斥标记，页面层 BackHandler 随之失效（删除路径已提前清，此处兜底）
            val s = currentSwipe
            if (s.coordinator.activeKey == s.key) s.coordinator.activeKey = null
        }
    }

    // 松手结算：执行会话满色（armed）才执行；否则按位移落点回按钮露出位（呼出会话从钉住点
    // 松手同样只是回 90dp，永不执行）或收起
    suspend fun settle() {
        val s = currentSwipe
        val value = offset.value
        val sign = if (value > 0f) 1f else -1f
        val action = if (sign > 0) s.delete else s.pin
        when {
            armed && action != null -> execute(action, sign)
            abs(value) >= openPx && action != null -> openTo(sign)
            else -> collapse()
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { layerWidthPx = it.width }
            .pointerInput(swipe.key) {
                detectHorizontalSwipeGestures(
                    onDragStart = {
                        // 互斥：另一张卡展开中先请它收起（清标记即触发其联动收起）
                        val s = currentSwipe
                        if (s.coordinator.activeKey != null && s.coordinator.activeKey != s.key) {
                            s.coordinator.activeKey = null
                        }
                        // 会话定型：起手在展开态 = 执行会话（可变色、满色触发），收起态 = 呼出
                        // 会话（全程不变色、不执行）；起手侧符号锁定，反穿零点不填充
                        sessionFromOpen = abs(offset.value) > 0.5f
                        sessionSign = if (offset.value >= 0f) 1f else -1f
                        armed = false
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        scope.launch {
                            val s = currentSwipe
                            val raw = offset.value + dragAmount
                            // 无动作的一侧不可拖出；绝对位移在拖拽上限处钳制（钉住）
                            val clamped = when {
                                raw > 0f && s.delete == null -> 0f
                                raw < 0f && s.pin == null -> 0f
                                else -> raw.coerceIn(-maxPx, maxPx)
                            }
                            // 触发阈值只在执行会话内生效（呼出会话拖到钉住点也不变色、不 armed）；
                            // 迟滞：回落到呼出位与阈值的中点以下才解除，钉住处抖动不反复震
                            val clampedSign = if (clamped >= 0f) 1f else -1f
                            if (sessionFromOpen && clampedSign == sessionSign && abs(clamped) >= executePx) {
                                if (!armed) {
                                    armed = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            } else if (abs(clamped) < openPx + fillSpanPx / 2f) {
                                armed = false
                            }
                            offset.snapTo(clamped)
                        }
                    },
                    onDragEnd = { scope.launch { settle() } },
                    onDragCancel = { scope.launch { settle() } },
                )
            },
    ) {
        // 待办主体卡：lambda 式 offset 平移（放置期生效），拖拽全程内容零重组
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }) {
            content()
        }
        // 展开态点卡片主体 = 收起（拦截卡片自身的点击/勾选）；夹在主体卡与动作卡之间，
        // 动作卡层在其上、点按不受影响。clickable 不消费移动事件，纵向滚动与续拖不受阻
        if (isOpen) {
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        scope.launch { collapse() }
                    },
            )
        }
        // 动作卡层：matchParentSize 撑满卡片实测尺寸（高度与主体卡完全相同），
        // 左右各按呼出宽度渲染；动作卡只可能与主体卡并排出现（宽度归零即不可见），层级无遮挡
        Box(Modifier.matchParentSize()) {
            val value = offset.value
            // 填充进度仅执行会话（展开态起手、未反穿零点）生效：呼出会话恒 0——surface 底 + 彩色图标
            swipe.delete?.let { action ->
                SwipeActionButton(
                    action = action,
                    revealedPx = value.coerceIn(0f, maxPx) - gapPx,
                    fillProgress = if (sessionFromOpen && sessionSign > 0f) {
                        ((value.coerceIn(0f, maxPx) - openPx) / fillSpanPx).coerceIn(0f, 1f)
                    } else {
                        0f
                    },
                    surfaceColor = MaterialTheme.colorScheme.surface,
                    iconRevealPx = iconRevealPx,
                    onClick = { scope.launch { execute(action, 1f) } },
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
            swipe.pin?.let { action ->
                SwipeActionButton(
                    action = action,
                    revealedPx = (-value).coerceIn(0f, maxPx) - gapPx,
                    fillProgress = if (sessionFromOpen && sessionSign < 0f) {
                        (((-value).coerceIn(0f, maxPx) - openPx) / fillSpanPx).coerceIn(0f, 1f)
                    } else {
                        0f
                    },
                    surfaceColor = MaterialTheme.colorScheme.surface,
                    iconRevealPx = iconRevealPx,
                    onClick = { scope.launch { execute(action, -1f) } },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }

    // 协调器联动：他卡展开（activeKey 变为他卡）或系统返回（置 null）时自动收起；
    // 动作执行中（executing）不介入，由执行流程自管收场
    val activeKey = swipe.coordinator.activeKey
    LaunchedEffect(activeKey) {
        if (activeKey != swipe.key && !executing && abs(offset.value) > 0.5f) {
            offset.animateTo(0f, Motion.Settle)
        }
    }
}

/**
 * 单个动作卡：宽度 = 当前呼出量（随拖拽变长、间距外恒定），高度撑满父层（与卡片同高）；
 * 圆角与待办卡片一致；[fillProgress] = 0 时 surface 底 + 彩色图标（呼出会话恒如此），
 * 执行会话内随进度渐变为动作色底 + on 色图标（拖到顶 = 完全变色，松手即执行）；图标随
 * 呼出宽度渐显（[SwipeIconRevealSpan] 内淡入）。整卡可点按直接执行动作（展开态下点按的入口）。
 */
@Composable
private fun SwipeActionButton(
    action: SwipeAction,
    revealedPx: Float,
    fillProgress: Float,
    surfaceColor: Color,
    iconRevealPx: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val backgroundColor = lerp(surfaceColor, action.activeColor, fillProgress)
    // 填充到位的图标色由调用方按容器色配 on 色（onError/onPrimary，同 Overlay 顶栏胶囊）
    val iconTint = lerp(action.tint, action.onActiveColor, fillProgress)
    val iconAlpha = (revealedPx / iconRevealPx).coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .width(with(density) { revealedPx.coerceAtLeast(0f).toDp() })
            .fillMaxHeight()
            .clip(NoteCardShape)
            .background(backgroundColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = action.contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(action.iconRes),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier
                .size(SwipeActionIconSize)
                .graphicsLayer(alpha = iconAlpha),
        )
    }
}
