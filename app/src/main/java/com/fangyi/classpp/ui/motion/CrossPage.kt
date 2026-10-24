// 来源：Cresto（Apache License 2.0）app 模块 feature/main/NavContainer.kt 的
// ManualAnimatedVisibility（导航栏切页的层级交叉过渡），经参数化与容器化整理后移植，
// 调参要点引自其仓库根目录 ANIMATION_GUIDE.md §2.2。按 Apache 2.0 保留本声明。
//
// 层级交叉切页：旧页原位淡出并缩到 0.95（慢起曲线，「沉下去」），新页延迟
// 100ms——等旧页先退一半，形成「退让」的层次——再淡入并从 0.95 放大回 1
// （EaseOutExpo 快收）。全部运动只有缩放 + 透明度两个通道，刻意不做左右推拉的
// 「空间导航」语言（运动层级，而非空间方位）；也因无平移，天然无 RTL 适配问题。
// 退场的实际寿命由 alpha 决定：alpha 归零内容即卸载，scale 的长尾只在隐形中收完。
//
// 相对原版的整理：
// - 时序参数化为 [CrossPageSpec]，默认值 = Cresto 原值；
// - 低层原语 [CrossPage] 的尺寸交给调用方 modifier（原版硬编码 fillMaxSize）；
// - 新增高层容器 [CrossPages]：key 驱动，内置 SaveableStateHolder 状态保活、
//   惰性挂载、退场后卸载与动态 z 序，等价于原版 NavContainer 的手写多页骨架；
// - 内容挂载门控由原版「组合期逐帧读 alpha.value」改为只在转场边界翻转的布尔
//   （alpha 归零那一刻）——转场期间容器体零重组，图层矩阵更新仍走 graphicsLayer
//   的逐帧延迟读取。
package com.fangyi.classpp.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 层级交叉切页的时序参数，[CrossPage] 与 [CrossPages] 共用。默认值即 Cresto 原版
 * 的调参结果。参数自包含于本文件：Motion.kt 只收全局共享节奏，单效果参数留在
 * 效果文件内（沿用全 app 惯例）。
 */
@Immutable
data class CrossPageSpec(
    /**
     * 进场延迟（毫秒）：灵魂参数。新页等旧页先退出一小段再进场，才有「退让」的
     * 层次感；调小（如 0）会退化成生硬的交叉淡化。
     */
    val enterDelayMillis: Int = 100,

    /** 进场淡入时长：与退场淡出同长，交叉段内新旧页同时可见。 */
    val enterAlphaMillis: Int = 200,

    /** 进场放大时长（[CrossPageSpec.hiddenScale]→1）：比淡入长一倍。 */
    val enterScaleMillis: Int = 400,

    /** 进场放大曲线：EaseOutExpo——前段极快、收尾轻盈的「浮起」感。 */
    val enterEasing: Easing = EaseOutExpo,

    /**
     * 退场淡出时长：**退场的实际寿命由它决定**——alpha 归零内容即卸载，
     * [exitScaleMillis] 的长尾只在隐形中收完。
     */
    val exitAlphaMillis: Int = 200,

    /** 退场缩小时长（1→[CrossPageSpec.hiddenScale]）：长于淡出，慢起曲线只在前段（可见部分）生效。 */
    val exitScaleMillis: Int = 600,

    /** 退场缩小曲线：CubicBezier(0.2, 0.2, 0, 1)——起步缓，有「沉下去」的意味。 */
    val exitEasing: Easing = CubicBezierEasing(0.2f, 0.2f, 0f, 1f),

    /** 页面隐藏态的缩放：进出场共同的幅度端点。越小层级位移感越强。 */
    val hiddenScale: Float = 0.95f,
)

/**
 * 层级交叉切页的低层原语：单页进出场，[visible] 翻转驱动（Cresto 原版
 * ManualAnimatedVisibility 的参数化移植）。
 *
 * 用法与 Cresto 的 NavContainer 同构：多个 [CrossPage] 叠放在同一 [Box] 中、每页
 * 一个实例，切换时把目标页置 [visible]、其余页置 false。内容在退场 alpha 归零后
 * 卸载；进场先挂载内容、再等 [CrossPageSpec.enterDelayMillis] 后播放动画（挂载
 * 成本被延迟窗口吸收，动画首帧不卡）。尺寸由 [modifier] 决定（铺满容器传
 * `Modifier.fillMaxSize()`）。
 *
 * 性能形态：双 Animatable + graphicsLayer 块内延迟读取，转场每帧只更新图层矩阵，
 * 子树布局与绘制指令不动；内容挂载门控只在转场边界翻转，门控本身不逐帧重组。
 * 转场中 [visible] 再次翻转时，重启的 effect 协程取消在途动画（含进场延迟窗），
 * 所有属性一律从当前值继续，任何翻转序列都无跳变。
 *
 * 多页叠放的容器化封装（状态保活、惰性挂载、z 序、卸载竞态防护）见 [CrossPages]。
 */
@Composable
fun CrossPage(
    visible: Boolean,
    modifier: Modifier = Modifier,
    spec: CrossPageSpec = CrossPageSpec(),
    content: @Composable BoxScope.() -> Unit,
) {
    // 初始可见性决定初值：首组合即可见时不播进场动画（与原版一致）。
    // 不以 spec 为 remember key——spec 变化只应影响后续动画的节奏，重建会丢在途状态。
    val anim = remember { CrossPageAnim(initiallyVisible = visible, hiddenScale = spec.hiddenScale) }

    LaunchedEffect(visible) {
        if (visible) anim.enter(spec) else anim.exit(spec)
    }

    if (anim.contentMounted) {
        Box(
            modifier = modifier.graphicsLayer {
                alpha = anim.alpha.value
                scaleX = anim.scale.value
                scaleY = anim.scale.value
            },
        ) {
            content()
        }
    }
}

/**
 * 层级交叉切页的高层容器：[selectedKey] 驱动的多页切换，等价于 Cresto NavContainer
 * 的手写骨架（Box 叠放 + 每页 ManualAnimatedVisibility + SaveableStateProvider），
 * 并补上原版没有的三件事：
 *
 * - **惰性挂载**：页面首次被选中才进组合；退场完整跑完（内容随 alpha 归零卸载、
 *   scale 隐形收尾）才移除记录。再选回时经 [SaveableStateProvider] 恢复状态——
 *   这正是切页动画「页面常驻」收益的来源（滚动位置、输入内容不丢）。
 * - **动态 z 序**：最新选中页 zIndex 递增取最高，永远盖住淡出中的旧页（原版靠
 *   静态组合顺序决定叠放，切回先组合的页时旧页压在新页上方）。
 * - **卸载竞态防护**：每页一条以目标可见性为 key 的驱动协程（与原版逐页
 *   LaunchedEffect(visible) 同构），快速连点时在途进场（含延迟窗）随协程重启
 *   取消——不会出现「已退场页的迟到进场把幽灵页拉回来」。
 *
 * K 须为可存入 Bundle 的稳定值（enum / String / 基本类型）：既是页面身份，也是
 * [SaveableStateProvider] 的状态保活 key。页面铺满容器；首组合的初始页直接就位、
 * 不播进场动画（与原版一致）；[selectedKey] 的持久化由调用方负责（如
 * rememberSaveable）。
 *
 * 性能形态同 [CrossPage]：转场每帧只更新图层矩阵，容器体零重组。
 */
@Composable
fun <K : Any> CrossPages(
    selectedKey: K,
    modifier: Modifier = Modifier,
    spec: CrossPageSpec = CrossPageSpec(),
    content: @Composable (K) -> Unit,
) {
    val holder = rememberSaveableStateHolder()

    // 已挂载页记录表：初始页随容器种子化（直接就位、不播动画），其余页在 select
    // 时惰性加入；退场完整跑完后由页自身的驱动协程移除。
    val pages = remember {
        mutableStateListOf(
            CrossPageRecord(key = selectedKey, initiallyVisible = true, hiddenScale = spec.hiddenScale),
        )
    }
    // 当前选中页的记录（select 的比较基准）：由转场协程写入，须是快照状态
    var current by remember { mutableStateOf(pages.first()) }
    var zIndexTick by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(selectedKey) {
        val existing = pages.firstOrNull { it.key == selectedKey }
        // 初组合（初始页已就位）或同页：不播动画
        if (existing === current) return@LaunchedEffect
        val target = existing ?: CrossPageRecord(
            key = selectedKey,
            initiallyVisible = false,
            hiddenScale = spec.hiddenScale,
        ).also { pages.add(it) }
        current.targetVisible = false
        current = target
        zIndexTick += 1f
        target.zIndex = zIndexTick
        target.targetVisible = true
    }

    Box(modifier = modifier) {
        pages.forEach { page ->
            key(page.key) {
                // 每页一条驱动协程、以目标可见性为 key：翻转即重启并取消在途动画
                //（含进场延迟窗）。连点序列下任何页都从当前值继续，无跳变、无幽灵页
                LaunchedEffect(page.targetVisible) {
                    if (page.targetVisible) {
                        page.anim.enter(spec)
                    } else {
                        page.anim.exit(spec)
                        // 走到这即退场完整跑完、未被重启取消：移除记录。内容早已
                        // 随 alpha 归零卸载，此处清掉的是渲染项与驱动协程本身
                        pages.remove(page)
                    }
                }
                if (page.anim.contentMounted) {
                    holder.SaveableStateProvider(page.key) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(page.zIndex)
                                .graphicsLayer {
                                    alpha = page.anim.alpha.value
                                    scaleX = page.anim.scale.value
                                    scaleY = page.anim.scale.value
                                },
                        ) {
                            content(page.key)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单页进出场驱动（[CrossPage] 与 [CrossPages] 的记录共用）：双 Animatable 承载
 * alpha/scale，[enter]/[exit] 按 [CrossPageSpec] 播放。挂载门控 [contentMounted]
 * 只在转场边界翻转——进场在动画开始前置位（内容先挂载、再等延迟窗），退场在
 * alpha 归零后置位（退场的实际寿命由 alpha 决定）；调用方据此控制内容进/出组合。
 *
 * 协程结构约定：enter/exit 内用 coroutineScope 并行两条属性动画、alpha 一侧留在
 * 当前协程——调用方协程重启（页面被再次驱动）时，取消会沿 coroutineScope 掐掉
 * 整段动画，置位语句执行不到（退场中的页被召回时 [contentMounted] 保持 true，
 * 内容不闪断）；Animatable 互斥保证被取代的属性动画一律从当前值继续。
 */
@Stable
private class CrossPageAnim(initiallyVisible: Boolean, hiddenScale: Float) {

    val alpha = Animatable(if (initiallyVisible) 1f else 0f, CrossPageVisibilityThreshold)

    val scale = Animatable(if (initiallyVisible) 1f else hiddenScale, CrossPageVisibilityThreshold)

    var contentMounted by mutableStateOf(initiallyVisible)
        private set

    /** 进场：先挂载内容，等 [CrossPageSpec.enterDelayMillis] 后并行淡入与放大。 */
    suspend fun enter(spec: CrossPageSpec) {
        contentMounted = true
        // 初始页直接就位（初值即全可见），不播动画
        if (alpha.value == 1f && scale.value == 1f) return
        delay(spec.enterDelayMillis.toLong())
        coroutineScope {
            launch { alpha.animateTo(1f, tween(spec.enterAlphaMillis)) }
            scale.animateTo(1f, tween(spec.enterScaleMillis, easing = spec.enterEasing))
        }
    }

    /**
     * 退场：并行淡出与缩小，alpha 归零即卸载内容（[contentMounted] 置 false），
     * scale 的长尾在隐形中收完——本函数在 scale 收尾后才返回，容器据此卸载记录。
     */
    suspend fun exit(spec: CrossPageSpec) {
        coroutineScope {
            launch {
                scale.animateTo(spec.hiddenScale, tween(spec.exitScaleMillis, easing = spec.exitEasing))
            }
            alpha.animateTo(0f, tween(spec.exitAlphaMillis))
            contentMounted = false
        }
    }
}

/**
 * 容器的单页记录：页面身份（[key]）+ 动画驱动（[anim]）+ 目标可见性 + 绘制层级。
 * [targetVisible] 是驱动协程的 key 与 select 的唯一写入面；[zIndex] 由 select
 * 递增分配，最新页恒在最上。
 */
private class CrossPageRecord<K : Any>(
    val key: K,
    initiallyVisible: Boolean,
    hiddenScale: Float,
) {
    val anim = CrossPageAnim(initiallyVisible, hiddenScale)

    var targetVisible by mutableStateOf(initiallyVisible)

    var zIndex by mutableFloatStateOf(0f)
}

/** Animatable 的可见阈值（Cresto 原值）：纯 tween 时序下不参与收敛判定，保持移植忠实。 */
private const val CrossPageVisibilityThreshold = 0.000001f
