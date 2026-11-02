package com.fangyi.classpp.ui.motion

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.shapes.UnevenRoundedRectangle
import kotlinx.coroutines.delay

/**
 * 覆盖层转场进度（0=隐藏、1=就位）的可共享状态。
 *
 * 进度提升为外部状态后，宿主可以在不碰 [PageOverlayTransition] 内部的情况下读取它；
 * 页内 Haze 冻结层通过 [LocalPageOverlayTransform] 拿到它，在覆盖层视差期间重放
 * 就位帧。[isTransforming] 只在绘制期读取（Animatable 是快照状态），逐帧只失效绘制、
 * 不触发重组。
 */
@Stable
class PageOverlayTransitionState {
    val progress = Animatable(0f)

    /** 进度处于 (0,1) 开区间即视差转场进行中；隐藏(0)与就位(1)都返回 false。 */
    val isTransforming: Boolean
        get() = isOverlayTransforming(progress.value)
}

/**
 * 覆盖层进度是否处于视差转场中间态。开区间：端点（隐藏/完全就位）不冻结，
 * 避免在端点误判。
 */
internal fun isOverlayTransforming(progress: Float): Boolean =
    progress > 0f && progress < 1f

@Composable
fun rememberPageOverlayTransitionState(): PageOverlayTransitionState =
    remember { PageOverlayTransitionState() }

/**
 * 外层设置页覆盖层视差状态的只读注入点。
 *
 * 只在 MainActivity 最外层 [PageOverlayTransition] 之外显式 provide：它移动的是
 * tab 页面世界；嵌套子页覆盖层移动的是设置页内容，不能提供这个 local，否则
 * 「最近一层」语义会遮住真正在移动页面世界的那一层。无覆盖层的场景（预览/独立
 * 组件）为 null。
 */
val LocalPageOverlayTransform = compositionLocalOf<PageOverlayTransitionState?> { null }

/**
 * 整页覆盖层转场（设置页覆盖层与个性化子页共用，两层转场同源）：[overlay] 整页从右缘
 * 滑入盖住 [behind]。完整动效四件套同帧启动、同一节奏（[Motion.PageEnterMillis] /
 * [Motion.PageExitMillis] + [Motion.Overlay]）：
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
 * - [state]：转场进度状态。调用方可传入外部持有的实例（并另经 [LocalPageOverlayTransform]
 *   提供给页内冻结层读取）；默认各调用点新建一份，行为与旧实现一致。设置页内嵌套的
 *   子页覆盖层不要传外层的 state，也不要 provide 该 local；
 * - [visible] 翻转即启动完整转场，转场中途反向翻转时从当前值继续；
 * - [prewarm] 空闲预热帧：progress 不动，覆盖层临时停在 x=0、近透明（[PageOverlayPrewarmAlpha]）
 *   画一两帧，让页内 Haze 完成初始化并把带模糊的就位帧录进冻结层，随后由调用方关掉。
 *   预热期 behind 不视差、遮罩不出现、覆盖层子树不参与命中（Initial 阶段消费触摸），
 *   正式打开（[visible]=true）时立即让位给正常转场。
 */
@Composable
fun PageOverlayTransition(
    visible: Boolean,
    modifier: Modifier = Modifier,
    state: PageOverlayTransitionState = rememberPageOverlayTransitionState(),
    prewarm: Boolean = false,
    behind: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    // 转场进度 0..1（0=隐藏、1=就位）：Animatable 驱动而非 AnimatedVisibility。
    // targetState 翻转即启动，时长/曲线取 Motion token——与遮罩、视差天然同一条曲线
    val progress = state.progress
    // 空闲预热帧：仅在不处于正式打开时生效，visible 翻转即让位给正常转场
    val prewarmActive = prewarm && !visible
    LaunchedEffect(visible) {
        progress.animateTo(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(
                durationMillis = if (visible) Motion.PageEnterMillis else Motion.PageExitMillis,
                easing = Motion.Overlay,
            ),
        )
    }
    // 端点派生布尔：动画帧不重组，整段转场各只在跨过端点时翻转一次
    val hidden by remember { derivedStateOf { progress.value <= 0f } }
    val settled by remember { derivedStateOf { progress.value >= 1f } }

    // 左缘圆角开关：内容首帧必然不在场，初始即为开（圆角恒定全开、不随滑动插值收放）；
    // 完全就位后再保持一小段，画面彻底静止再恢复矩形贴边并摘掉裁剪。
    // 圆角走全局同款连续曲率形状（UnevenRoundedRectangle 默认 Continuous，圆滑圆角），
    // 转场期页面像一张圆滑卡片滑过背景，与全局卡片形状风格一致
    val leftCornerRadius = rememberDeviceCornerRadius()
    val leftCornerShape = remember(leftCornerRadius) {
        UnevenRoundedRectangle(topStart = leftCornerRadius, bottomStart = leftCornerRadius)
    }
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
        // 比例见 [PageOverlayParallaxFraction]）。位移挂在独立 layer 上，与各 tab 页
        // 转场层（MainActivity.tabLayer）各管一层互不干扰；转场中覆盖层页缘始终压住
        // 本容器右缘，不会露出底缝。预热帧期间不位移：不惊动用户正在看的页面世界
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = if (prewarmActive) {
                        0f
                    } else {
                        -progress.value * size.width * PageOverlayParallaxFraction
                    }
                },
        ) {
            behind()
        }
        // 压暗遮罩：透明度与页面滑移同一条曲线、同帧推进。只画不拦截点击（无指针处理，
        // 触控落回下层）；alpha=0（隐藏）时渲染线程整层跳过。预热帧不出现遮罩
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (prewarmActive) 0f else progress.value }
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
                // 预热帧停在屏内：子树仍可命中。在 Initial 阶段消费全部触摸（父级先于
                // 子级收到事件），既不让近透明的设置页吞掉点击，也不会触发其中任何按钮
                .then(
                    if (prewarmActive) {
                        Modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Initial)
                                        .changes.forEach { it.consume() }
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                )
                .graphicsLayer {
                    // 预热帧：停在 x=0 让 Haze 的源/效果节点落在屏内（屏外效果会触发
                    // RenderThread 递归）；整层压到不可辨的 1%，两帧后由调用方关掉
                    translationX = if (prewarmActive) 0f else (1f - progress.value) * size.width
                    alpha = if (prewarmActive) PageOverlayPrewarmAlpha else 1f
                    if (cornersOn) {
                        shape = leftCornerShape
                        clip = true
                    } else {
                        shape = RectangleShape
                        clip = false
                    }
                },
        ) {
            // 活跃状态发给覆盖层内容：隐藏后内容仍常驻组合，其中的返回键处理等
            // 交互据此关闭，避免吃掉系统返回。预热门控单独一个 local：只让 Haze/冻结
            // 录制在预热帧工作，不打开任何交互（BackHandler 仍按 active 判定）
            CompositionLocalProvider(
                LocalPageOverlayActive provides (visible || !hidden),
                LocalPageOverlayPrewarm provides prewarmActive,
            ) {
                overlay()
            }
        }
    }
}

/**
 * tab 转场状态的最小只读视图：持有与 ui.navigation 的 TabPageState（TabPager 状态机）
 * 同源的 x/scale 动画值，
 * 供页内需要在转场期间冻结 haze 模糊的组件（顶栏/编辑栏）在**绘制期**读取——Animatable
 * 值是快照状态，绘制块里读取只逐帧失效绘制、不触发重组。判定规则：x ≠ 0（滑入途中或
 * 屏外停靠）或 scale ≠ 1（缩小淡出/唤回途中）即转场进行中。
 */
class TabTransitionState(
    val x: Animatable<Float, AnimationVector1D>,
    val scale: Animatable<Float, AnimationVector1D>,
) {
    val isTransitioning: Boolean
        get() = x.value != 0f || scale.value != 1f
}

/**
 * 冻结帧的录制元数据：普通字段，只在绘制期读写，不参与快照与重组。
 * size 必须一起记：窗口尺寸/字体缩放/分屏变化但键未变时，旧帧尺寸已不对，必须重录。
 */
class FreezeFrame {
    var hasFrame = false
    var key: Any? = null
    var size: IntSize? = null
}

/**
 * 转场期间是否可以直接重放录制帧。
 *
 * - 图层尺寸无效（尚未绘制/已归零）→ 不重放；
 * - 无元数据（顶栏旧行为：每帧录制、不设键）→ 尺寸有效即重放；
 * - 有元数据 → 必须已录过帧、键相同、且录制尺寸与当前图层尺寸一致。
 */
internal fun shouldReplayFreeze(
    layerWidth: Int,
    layerHeight: Int,
    frame: FreezeFrame?,
    key: Any?,
): Boolean {
    if (layerWidth < 1 || layerHeight < 1) return false
    if (frame == null) return true
    return frame.hasFrame && frame.key == key && frame.size == IntSize(layerWidth, layerHeight)
}

/** 冻结修饰符本帧的动作：[Replay] 重放旧帧、[Record] 录制新帧、[Live] 直接实时绘制 */
internal enum class FreezeDrawAction { Replay, Record, Live }

/**
 * 冻结修饰符的绘制决策（纯函数，供 JVM 单测覆盖）：
 * - 转场中且帧有效 → 重放；转场中但帧失配/无帧 → 实时绘制（最坏等于改造前）；
 * - 非转场且允许录制 → 录制后重放；
 * - 非转场且 [recordWhen] 为假 → 实时绘制、**不录制**（覆盖层隐藏时 Haze 已卸载，
 *   录到的会是无模糊平底色，必须保留上一次活跃期的帧）。
 */
internal fun freezeDrawAction(
    isTransforming: Boolean,
    recordWhen: Boolean,
    canReplay: Boolean,
): FreezeDrawAction = when {
    isTransforming -> if (canReplay) FreezeDrawAction.Replay else FreezeDrawAction.Live
    recordWhen -> FreezeDrawAction.Record
    else -> FreezeDrawAction.Live
}

/**
 * 页面级 transform 的冻结修饰符：tab 转场与设置页覆盖层视差共用。
 *
 * - [isTransforming] 为 true（绘制期读取快照值，逐帧只失效绘制、不触发重组）：
 *   冻结层有帧、尺寸有效时整帧重放 [layer]（含 Haze 模糊输出），不再执行内部绘制链；
 *   若提供了 [frame] 还要求录制键与尺寸都匹配，否则回退 `drawContent()`——最坏情况
 *   等于改造前，绝不重放过期帧。
 * - [isTransforming] 为 false：每帧录制后重放。绝不按「键未变」跳过录制：背景图异步
 *   解码、Coil 换帧等变化不一定体现在键上，跳过录制会让旧帧永久盖住新内容。
 * - [recordWhen] 为 false（且不在转场）：只实时绘制、不录制，保留上一次的帧。覆盖层
 *   隐藏时 Haze 已卸载，继续录制会把「无模糊的平底色」覆盖掉有效的就位帧。
 *
 * 为什么需要：tab 转场用祖先 graphicsLayer 平移/缩放整页，设置页覆盖层则把 behind
 * 整层左移做视差；Compose 会在动画的每一帧向整棵子树派发 onGloballyPositioned
 * （图层位置属性一变即递归派发），Haze 的源/效果节点随之把滑入起点、屏外停靠位等
 * **中间态窗口坐标**写进记账状态并逐帧失效重采样——重采样按窗口坐标取几何、画布却
 * 已被祖先变换叠加，滑入起点/屏外停靠处效果层与屏幕交集为空、尺寸归零，模糊整层
 * 不画，只剩兜底色，即快速切 tab / 开关设置页时闪出的纯色帧。转场期间页面内容本就
 * 不变，重放就位帧与实时绘制逐像素一致，且转场期零模糊重采样开销。
 *
 * 停靠页（x = ±1.2 的屏外停靠位）只重放、不重录，其冻结帧由
 * TabPagerState.reRecordParkedTabs（MainActivity 的回前台/切主题/跨天时机触发）
 * 维持有效（进多任务时系统会丢弃不可见窗口的 RenderNode 显示列表）。
 *
 * 注意：录制后只 `drawLayer(layer)`，不要再 `drawContent()`——Haze 2 的效果节点在
 * 「同一帧内被绘制两次」时会构建自引用 RenderNode，渲染线程在 prepareTreeImpl 里
 * 无限递归、栈溢出崩溃。
 */
fun Modifier.transitionFreeze(
    layer: GraphicsLayer,
    frame: FreezeFrame? = null,
    key: () -> Any? = { Unit },
    recordWhen: () -> Boolean = { true },
    isTransforming: () -> Boolean,
): Modifier = drawWithContent {
    val transforming = isTransforming()
    // 只在转场期取键：静止期每帧录制时的键求值留在 record 之后，避免重复构造键对象
    val canReplay = transforming &&
        shouldReplayFreeze(layer.size.width, layer.size.height, frame, key())
    when (freezeDrawAction(transforming, recordWhen(), canReplay)) {
        FreezeDrawAction.Replay -> drawLayer(layer)
        FreezeDrawAction.Live -> drawContent()
        FreezeDrawAction.Record -> {
            // 只把子树绘制一次（录进 layer 后直接画 layer）：录制后 layer.size 才是本帧
            // 内容的真实尺寸，元数据在 record 之后取
            layer.record { this@drawWithContent.drawContent() }
            frame?.let {
                it.hasFrame = true
                it.key = key()
                it.size = layer.size
            }
            drawLayer(layer)
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
 * 最近一层覆盖层是否处于空闲预热帧（[PageOverlayTransition] 的 `prewarm`）。
 * 只作为页内 Haze 挂载与冻结录制的门控：预热两帧里页面停在 x=0 但近透明，Haze 需要
 * 真实挂载一次才能初始化并录下带模糊的就位帧。**不**并入 [LocalPageOverlayActive]——
 * 否则预热期会打开各页的 BackHandler 等交互逻辑。
 */
val LocalPageOverlayPrewarm = compositionLocalOf { false }

/**
 * 覆盖层预热帧的整层透明度。不取 0：alpha=0 的层会被渲染线程整层剔除，Haze 效果不一定
 * 真正出图；1% 在任何底色上都不可辨，且只在启动 idle 的两帧内出现。需要回滚时改这里。
 */
private const val PageOverlayPrewarmAlpha = 0.01f

/**
 * 覆盖层转场的背景视差位移比例：下层页面向左挪自身宽度的这个比例让位，滑出时滑回。
 * 量级「微微」即可——iOS push 是 1/3 宽，明显重于本效果；想更含蓄可下调至 0.06 左右
 */
private const val PageOverlayParallaxFraction = 0.1f

/**
 * 机身圆角半径：API 31+ 读系统真实 R 角、四角取最大，低版本退化 24dp 近似。
 * 两处共用：覆盖层转场的左缘圆角（本文件）与 tab 淡出页的整卡圆角（TabPager）。
 */
@Composable
fun rememberDeviceCornerRadius(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    return remember {
        val insets = view.rootWindowInsets
        fun corner(position: Int): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                insets.getRoundedCorner(position)?.radius ?: 0
            } else {
                0
            }
        maxOf(
            corner(RoundedCorner.POSITION_TOP_LEFT),
            corner(RoundedCorner.POSITION_TOP_RIGHT),
            corner(RoundedCorner.POSITION_BOTTOM_LEFT),
            corner(RoundedCorner.POSITION_BOTTOM_RIGHT),
        ).takeIf { it > 0 }
            ?.let { with(density) { it.toFloat().toDp() } }
            ?: 24.dp
    }
}
