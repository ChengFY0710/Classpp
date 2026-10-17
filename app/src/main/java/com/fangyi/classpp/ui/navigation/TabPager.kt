package com.fangyi.classpp.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.TabTransitionState
import com.fangyi.classpp.ui.motion.rememberDeviceCornerRadius
import com.kyant.shapes.UnevenRoundedRectangle
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * tab 页转场状态机：惰性挂载 + 常驻不销毁的页面仓库与切页动画驱动（原内联在
 * MainActivity，纯搬移）。持有 [selectedTab]（Saver 持久化，旋转/进程重建后保持）、
 * 每页一份 [TabPageState]，并提供 [selectTab]/[reRecordParkedTabs] 两个动作入口；
 * 渲染由 [TabPager] 完成，页面内容经 content lambda 由调用方装配。
 *
 * scope 经 [rememberTabPagerState] 以 SideEffect 注入（不进 Saver——协程作用域不可
 * 序列化）。[selectTab] 刻意**不**做 suspend 由调用方提供 scope：通知落页的
 * LaunchedEffect 消费 pendingNotificationTab 后置空触发自身重启，若动画协程是该
 * effect 的子协程会被中途取消——长生命周期 scope（组合作用域）正是动画连贯的关键。
 */
@Stable
class TabPagerState internal constructor(initialTab: AppTab) {

    /** 当前选中页；写入一律经 [selectTab]（先动 z 序再启动动画，与挂载同帧） */
    var selectedTab by mutableStateOf(initialTab)
        private set

    /**
     * 惰性挂载：启动只组合默认页，其余页首次选中才进组合，挂载成本推迟到
     * 第一次访问。挂载后常驻（切走时停靠屏外、渲染层剔除，不销毁）——按 key
     * 重建课表页会首帧 headerHeight 跳闪，常驻保住状态，惰性省掉启动开销
     */
    private val mountedTabs = mutableStateListOf(initialTab)

    /** 每页一份的转场状态（首选取值即原位）：x 页宽分数（0=原位、±1=相邻屏外）、
     * 透明度、缩放，动画全走 Animatable、每帧只更新 layer 矩阵 */
    private val pageStates = mutableMapOf<AppTab, TabPageState>()
        .apply { put(initialTab, TabPageState(0f)) }

    /** 递增 z 序：最新选中的页取最高值，永远盖住正在淡出的旧页；
     * 底部导航栏由调用方另用极大值压在所有页面之上 */
    private var zIndexTick by mutableFloatStateOf(0f)

    /** 动画协程作用域（组合作用域）；rememberTabPagerState 每次组合后 SideEffect 注入 */
    private var scope: CoroutineScope? = null

    internal fun attachScope(scope: CoroutineScope) {
        this.scope = scope
    }

    private val animationScope: CoroutineScope
        get() = requireNotNull(scope) { "TabPagerState 未经 rememberTabPagerState() 创建（scope 未注入）" }

    /**
     * 切 tab：旧页居中缩小淡出、新页从相对方向整屏滑入（无压暗、无圆角裁切，
     * 那是设置页覆盖层的专属效果）。方向由序号差决定；所有属性同一条 spec
     * （Motion.TabMillis + Motion.Overlay，与设置页进场同一节奏）同帧启动
     */
    fun selectTab(tab: AppTab) {
        if (tab == selectedTab) return
        val from = selectedTab
        // 新页在右则从右缘滑入，在左则从左缘滑入
        val dir = if (tab.ordinal > from.ordinal) 1f else -1f
        selectedTab = tab
        if (tab !in mountedTabs) {
            mountedTabs.add(tab)
            // 与组合同帧生效：初始 x 即滑入起点，首帧就在屏外、不会闪现原位
            pageStates[tab] = TabPageState(dir)
        }
        zIndexTick += 1f
        pageStates.getValue(tab).zIndex = zIndexTick
        val spec = tween<Float>(durationMillis = Motion.TabMillis, easing = Motion.Overlay)
        val incoming = pageStates.getValue(tab)
        animationScope.launch {
            val gen = ++incoming.generation
            if (incoming.alpha.value <= 0f) {
                // 隐藏页停靠屏外：先把三属性摆到滑入起点（此刻不可见，无跳变），
                // 再整屏滑入——滑入全程不透明，不叠淡入淡出。转场全程只有图层
                // 矩阵在动，页面显示列表（含顶栏已画好的模糊）原样复用，不重画
                incoming.x.snapTo(dir)
                incoming.alpha.snapTo(1f)
                incoming.scale.snapTo(1f)
                incoming.x.animateTo(0f, spec)
            } else {
                // 转场中途被再次选中：从当前值就地淡回原位，不重新定位
                launch { incoming.x.animateTo(0f, spec) }
                launch { incoming.alpha.animateTo(1f, spec) }
                incoming.scale.animateTo(1f, spec)
            }
        }
        pageStates.getValue(from).let { outgoing ->
            animationScope.launch {
                val gen = ++outgoing.generation
                // 旧页原位不动（x 归 0 兜住中途被切换的残位）、居中缩小淡出，
                // 与新页滑入同 spec 同帧；Animatable 互斥保证连点时各属性
                // 一律从当前值继续，任何连点序列都无跳变
                launch { outgoing.x.animateTo(0f, spec) }
                launch { outgoing.scale.animateTo(Motion.TabShrinkScale, spec) }
                // 末位的 alpha 直接挂起等待（不可包 launch）：外层协程要等淡出彻底完成
                // 才走停靠；中途被再次选中时 Animatable 互斥取消的正是此刻挂起的外层协程，
                // generation 守卫与「停靠在淡出之后」都靠这一行的挂起语义成立
                outgoing.alpha.animateTo(0f, spec)
                if (outgoing.generation == gen) {
                    // 彻底淡出后停靠屏外：命中测试收不到、渲染线程整层剔除。
                    // 停靠期间顶栏冻结层只重放、不重录；进多任务等场景系统
                    // 会丢弃不可见窗口的 RenderNode 显示列表，故回前台时由
                    // 调用方（MainActivity 的回前台/切主题 LaunchedEffect 经
                    // reRecordParkedTabs）回位一帧重录，冻结层跨后台保持有效。
                    // generation 守卫：若淡出途中该页又被选中，本协程已随
                    // Animatable 互斥被取消，不会走到这里
                    outgoing.x.snapTo(dir * TabParkFraction)
                    outgoing.scale.snapTo(1f)
                }
            }
        }
    }

    /**
     * 停靠页顶栏冻结帧回位重录（回前台与深浅主题切换共用，时机由调用方的
     * LaunchedEffect 触发）：给停靠页造一帧原位绘制时机——x 归零（alpha=0 且压在
     * 当前页之下，不可见、无实际命中风险）→ 位置回调把 haze 几何刷回就位值 →
     * 该帧绘制照常走录制分支、冻结帧重录 → 再弹回停靠位。两次 withFrameNanos：
     * 第一次恢复在下一帧的动画相位（早于其绘制），第二次恢复时中间那帧已完成
     * 绘制并重录完毕，回弹才安全。就位页（被选中）逐帧自录、淡出中页（alpha>0）
     * 由转场协程管理，均跳过
     */
    fun reRecordParkedTabs() {
        mountedTabs.forEach { tab ->
            if (tab == selectedTab) return@forEach
            val page = pageStates.getValue(tab)
            if (page.alpha.value > 0f) return@forEach
            animationScope.launch {
                val parkX = page.x.value
                page.x.snapTo(0f)
                withFrameNanos { }
                withFrameNanos { }
                // 这一帧内该页被再次选中（转场已接管 x、alpha=1）时不再回弹；
                // 此时冻结帧刚录好，该次转场正好直接受保护
                if (page.alpha.value <= 0f && page.x.value == 0f) {
                    page.x.snapTo(parkX)
                }
            }
        }
    }

    /**
     * 页的转场只读视图（供页内顶栏的转场冻结层在绘制期判定，见 ui.motion 的
     * tabTransitionFreeze）；未挂载的页返回 null（调用方屏内默认值兜底）。
     */
    fun transitionFor(tab: AppTab): TabTransitionState? = pageStates[tab]?.transition

    /** 供同文件的 [TabPager] 遍历挂载页；不对外暴露可变集合（写入一律经 selectTab） */
    internal val mountedPages: List<AppTab> get() = mountedTabs

    /** 页的转场状态实例（挂载后恒定同一实例）；供 [TabPager] 承载转场层 */
    internal fun pageState(tab: AppTab): TabPageState = pageStates.getValue(tab)

    companion object {
        /**
         * 只持久化 [selectedTab]：挂载集合与转场状态是派生态，恢复时以选中页为
         * 初始页重建（与冷启动「mountedTabs 从默认页起步」同一形态，切到历史页
         * 会重新走一遍滑入动画，无状态错位）。
         */
        val Saver: Saver<TabPagerState, Any> = listSaver(
            save = { listOf(it.selectedTab.name) },
            restore = { saved -> TabPagerState(AppTab.valueOf(saved[0])) },
        )
    }
}

/**
 * 记住一份 [TabPagerState]（旋转/进程重建经 Saver 恢复选中页）并注入动画作用域：
 * SideEffect 在每次成功组合后把组合作用域挂上——恢复重建后 state 是新实例、scope
 * 是新作用域，两者在同一次组合内配对，不会错挂。
 */
@Composable
fun rememberTabPagerState(initialTab: AppTab = AppTab.Timetable): TabPagerState {
    val scope = rememberCoroutineScope()
    val state = rememberSaveable(saver = TabPagerState.Saver) { TabPagerState(initialTab) }
    SideEffect { state.attachScope(scope) }
    return state
}

/**
 * 已挂载页面层的渲染器：按 [TabPagerState] 的挂载集合逐页包一层 Box 承载转场变换，
 * key 保证新增页面插队时已有页面的节点与状态原样保留（页面内容仅组合一次、之后
 * 常驻）。非选中页清空语义：屏外/淡出中的页面不该被无障碍、自动化视为可达内容。
 *
 * 页面内容经 [content] 由调用方装配（tab 分派与各屏参数在调用处），转场只读视图
 * 一并给出——Timetable/Todo 页透传给顶栏启用转场冻结层。
 */
@Composable
fun TabPager(
    state: TabPagerState,
    content: @Composable BoxScope.(tab: AppTab, transition: TabTransitionState?) -> Unit,
) {
    // tab 页转场圆角：滑入途中裁前缘两角、卡片化（缩小淡出）裁四角，全部取
    // 机身 R 角（shapes 库连续曲率，与设置页覆盖层转场同款）。圆角定义在
    // layer 本地坐标，随页面缩放等比变小——满屏时正好贴合机身 R 角
    val deviceCornerRadius = rememberDeviceCornerRadius()
    val tabShapes = remember(deviceCornerRadius) { TabShapes(deviceCornerRadius) }
    state.mountedPages.forEach { tab ->
        key(tab) {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (tab == state.selectedTab) Modifier
                        else Modifier.clearAndSetSemantics { },
                    )
                    .tabLayer(state.pageState(tab), tabShapes),
            ) {
                content(tab, state.transitionFor(tab))
            }
        }
    }
}

/**
 * tab 页的转场状态（每页一份，见 [TabPagerState.selectTab]）：动画全走 Animatable，在
 * [tabLayer] 的 graphicsLayer 块内读取——每帧只更新变换矩阵，子树布局、绘制指令、
 * 文字排版一概不动，与 PageOverlayTransition 同一性能形态。
 */
internal class TabPageState(initialX: Float) {
    /** 页宽分数：0=原位、±1=相邻一屏外、淡出后停靠 ±[TabParkFraction]。 */
    val x = Animatable(initialX)

    val alpha = Animatable(1f)

    val scale = Animatable(1f)

    /** 绘制层级：每次选中递增（[TabPagerState.selectTab]），最新页盖住正在淡出的旧页。 */
    var zIndex by mutableFloatStateOf(0f)

    /** 转场协程代号：淡出协程收尾（停靠屏外）前校验，被新协程取代即放弃。 */
    var generation = 0

    /**
     * 转场状态只读视图：课表页顶栏的转场冻结层据此在绘制期判定转场是否进行中
     * （转场期间重放就位帧、绕开 haze 的中间态几何重采样，见 ScheduleHeader）。
     */
    val transition = TabTransitionState(x, scale)
}

/** 旧页淡出后的屏外停靠位：多留 0.2 屏余量，任何缩放与像素圆整下都不会露边。 */
private const val TabParkFraction = 1.2f

/**
 * tab 页转场层：z 序 + 转场矩阵（平移/透明度/缩放）合并进同一个 graphicsLayer，
 * 动画每帧只更新这一个矩阵；隐藏页停靠屏外，渲染线程整层剔除。
 * translationX 取整到整像素：动画期间文字不糊，收尾正好归 0 与原位重合。
 *
 * 圆角裁剪（[TabShapes]，机身 R 角连续曲率），按优先级取用：
 * - 缩放 < 1（卡片化：淡出中、或被中途唤回长回全屏途中）→ 裁四角；
 * - 缩放 = 1 且 x ≠ 0（滑入途中，前缘露在屏内、后缘贴着屏外）→ 裁前缘两角，
 *   x > 0 从右滑入裁左缘、x < 0 从左滑入裁右缘；
 * - 就位（x = 0、缩放 = 1）→ 恢复矩形：前缘两角此时正贴机身 R 角，摘掉裁剪无跳变；
 * 停靠屏外的隐藏页也命中"前缘两角"分支，但 alpha=0 不可见，无碍。
 */
private fun Modifier.tabLayer(state: TabPageState, shapes: TabShapes): Modifier = this
    .zIndex(state.zIndex)
    .graphicsLayer {
        translationX = (state.x.value * size.width).roundToInt().toFloat()
        alpha = state.alpha.value
        val shrink = state.scale.value
        scaleX = shrink
        scaleY = shrink
        when {
            shrink < 1f -> {
                shape = shapes.card
                clip = true
            }
            state.x.value != 0f -> {
                shape = if (state.x.value > 0f) shapes.leftCorners else shapes.rightCorners
                clip = true
            }
            else -> {
                shape = RectangleShape
                clip = false
            }
        }
    }

/**
 * tab 页转场期的圆角裁剪形状：全部取机身 R 角、连续曲率（shapes 库
 * [UnevenRoundedRectangle]，与设置页覆盖层转场同款），只按裁剪位置区分。
 */
private class TabShapes(corner: Dp) {
    /** 左缘两角圆滑：页面停在右侧屏外或从右滑入时，露出的前缘是左缘。 */
    val leftCorners = UnevenRoundedRectangle(topStart = corner, bottomStart = corner)

    /** 右缘两角圆滑：从左滑入时露出的前缘是右缘。 */
    val rightCorners = UnevenRoundedRectangle(topEnd = corner, bottomEnd = corner)

    /** 四角全圆：缩小淡出的卡片，以及被中途唤回后长回全屏的途中。 */
    val card = UnevenRoundedRectangle(
        topStart = corner,
        topEnd = corner,
        bottomStart = corner,
        bottomEnd = corner,
    )
}
