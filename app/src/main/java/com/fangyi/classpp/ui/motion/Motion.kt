package com.fangyi.classpp.ui.motion

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * 全局动效规格（时长 / 曲线 / 弹簧）的唯一定义点：动画调用点从这里取 token，
 * 调整节奏只动这一个文件。曲线名沿用 Material Motion 语义——[Standard] 标准
 * （先加速后减速）、[Decelerate] 减速（起点即全速、单调收慢，用于进场与微交互）、
 * [Accelerate] 加速（先慢后快，用于退场收场）；另有整页覆盖层与浮层共用的
 * [Overlay]（iOS 风格强非线性，前重后轻）。
 *
 * 仅供单一效果使用的参数（视差比例、手势阈值等）不在此列，留在各效果文件内。
 */
object Motion {
    /**
     * 微交互统一节奏：选中态、描边生长、开关、颜色渐变等小状态切换共用
     * （配合 [Decelerate]）。原先散落在多个组件文件里的 150ms 内联值收编于此。
     */
    const val FastMillis = 150

    /**
     * tab 转场时长：旧页居中缩小淡出、新页从相对方向整屏滑入，新旧页所有属性共用
     * [Overlay] 一条曲线、同帧启动。时长与 [PageEnterMillis] 同值——tab 切换与
     * 设置页覆盖层进场保持同一套整页手感。想让节奏更利落可下调（440ms 左右），曲线不变。
     */
    const val TabMillis = 520

    /**
     * tab 转场旧页缩小目标：居中缩到该倍率、同时淡出至透明。越小收缩感越强
     * （iOS 风格 0.9；想更含蓄可上调至 0.94 左右）。
     */
    const val TabShrinkScale = 0.9f

    /**
     * 编辑态过渡时长：顶栏 ↔ 编辑栏（ScheduleScreen 内 AnimatedContent）与底部导航栏
     * 的 AnimatedVisibility 共用同一规格（配合 [Standard]），同一个 editing 翻转同帧启动，
     * 两侧才能严格同步。想更快收场可下调（300ms 左右），曲线不变。
     */
    const val EditMillis = 360

    /**
     * 整页覆盖层（设置页、个性化子页）进场时长：整页右滑入，配合 [Overlay]。
     * 子页转场必须与设置页覆盖层本身同源，同源才有连续感。
     * [Overlay] 前重后轻，观感比总时长短；偏快再往上加，560ms 左右封顶。
     */
    const val PageEnterMillis = 520

    /** 整页覆盖层退场时长：同方向右滑出，比进场短一些，返回更利落。 */
    const val PageExitMillis = 380

    /**
     * 日程页台历三态转场（01→02→04 及反向、02 点卡重播）的吸附时长：
     * 三段转场与重播共用 [Overlay] 同一条曲线，松手后从当前进度收束到目标态。
     * 取值由设计稿节奏定，约 380ms；改这里全局同步。
     */
    const val AgendaSnapMillis = 380

    /** 浮层（OverlaySheet）入场时长：从屏幕底部滑入，配合 [Overlay]。 */
    const val SheetEnterMillis = 400

    /** 浮层出场时长：向下滑出，配合 [Overlay]。 */
    const val SheetExitMillis = 340

    /**
     * 遮罩弹窗（FadeOverlayDialog）淡入淡出时长：遮罩与卡片共用同一条透明度，
     * 进场 [Decelerate]、退场 [Accelerate]。
     */
    const val DialogMillis = 200

    /**
     * 弹出菜单内容层淡入时长（alpha 0→1），与进场果冻曲线同帧启动。
     * 淡入只作用于内容行——毛玻璃卡壳在本组件内不淡入（整卡只在进出场首尾一个
     * [PopupCardFadeMillis] 短窗内由宿主缩放层统一淡入淡出，中段恒满强度）：
     * 整卡长时间半透明会把锐利背景混进毛玻璃，模糊看起来「由弱变强」（6f7cae7 存档的
     * 已知问题，分层方案见 [com.fangyi.classpp.ui.components.PopupMenuCard]）。
     */
    const val PopupFadeMillis = 120

    /**
     * 弹出菜单退场时长：scale 1→0.8 与内容淡出共用，同步收尾（进场是 fade 先行、scale 收尾，
     * 退场若沿用不同时长会出现「alpha 已归零、scale 还在隐形缩小」的空窗）。
     * 配合 [Accelerate]，比进场短，收起更利落。
     */
    const val PopupExitMillis = 120

    /**
     * 弹层整卡（白底卡壳 + 柔影留白，含内容）进出场首尾的淡入淡出窗口时长：进场开头
     * 本窗口内整卡淡入、退场最后同样长的尾窗内整卡淡出，其余时段恒满强度。整卡长时间
     * 半透明会把锐利背景混进毛玻璃、模糊看起来「由弱变强」（6f7cae7 存档的已知问题），
     * 故淡入淡出压在首尾短窗内——只软化「凭空出现 / 凭空消失」的生硬切变，不给伪影
     * 留可见窗口。内容层的长淡入另见 [PopupFadeMillis]。
     */
    const val PopupCardFadeMillis = 100

    /** 弹出菜单进场起始缩放（退场缩回同值）；投影强度按 scale 距该值的进度渐入（PopupMenuPopup）。 */
    const val PopupEnterStartScale = 0.3f

    /**
     * 内容区展开/收起时长（Expandable 高度动画）：与配套的淡入淡出、头部 chevron
     * 旋转共用同一条时长，同一个展开开关翻转同帧启动、同帧收尾。
     * 进场配合 [Decelerate]、退场配合 [Accelerate]。
     */
    const val ExpandMillis = 300

    /**
     * 拖拽重排时长：条目避让位移（长按拖拽排序时其余条目让位）、拖拽浮层落位与缩放
     * 三条动画共用同一条时长——同一个松手事件触发的动画同帧启动、同帧收尾，
     * 浮层落点与其余条目就位才会严格对齐；配合 [Decelerate]。
     * 无效区域回弹例外：走 [Settle] 弹簧（无过冲），不走这条时长。
     */
    const val ReorderMillis = 220

    /** 标准曲线：先加速后减速，页面级进出场与弹出菜单的主曲线。 */
    val Standard: Easing = FastOutSlowInEasing

    /** 减速曲线：起点即全速、此后单调减速收尾（先快后慢），进场与微交互用。 */
    val Decelerate: Easing = LinearOutSlowInEasing

    /** 加速曲线：先慢后快，退场收场用，与 [Decelerate] 成「同族曲线对」。 */
    val Accelerate: Easing = FastOutLinearInEasing

    /**
     * 整页覆盖层转场与浮层（OverlaySheet）进出场的共用曲线：iOS 风格强非线性
     * cubic-bezier(0.32, 0.72, 0, 1)（UIKit 弹层/push 同款）——起步即接近全速、
     * 大部分位移集中在开头，长尾缓慢收住，比 [Standard] 更「前重后轻」。
     * 进场与退场同一条曲线（退场只是时长更短，同 iOS pop/dismiss），
     * 遮罩、视差等从同一进度派生，自动跟随这一条。
     */
    val Overlay: Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    /**
     * 松手回弹归位弹簧：无过冲（NoBouncy）、中低刚度，接近终点自然减速停住。
     * 当前用于浮层拖拽松手后的回位（跟手 snapTo 之后的收尾），
     * 以及待办顶栏分组条的极限橡皮筋回弹（Overscroll 效果的回弹 spec）。
     */
    val Settle: AnimationSpec<Float> = settle(Spring.DefaultDisplacementThreshold)

    /**
     * [Settle] 的带可见阈值变体。[visibilityThreshold] 是「距终点多近算到位」，它同时决定
     * 弹簧提前收站的时刻与**末帧吸附的幅度**——动画循环在估计时长处终止，终止帧直接落到
     * 终点，而上一帧还在以正常速度运动（距终点 ≈1.3~1.5 个阈值），这段差值就是一帧内的
     * 吸附跳变。以像素为单位动画时默认 0.01 只有 0.014px，不可见；但 OverlaySheet 的进度
     * 是「浮层高度比例」，0.01 ≈ 浮层高度的 1%（约 30px+），回弹尾段会肉眼可见地窜一下。
     * 归一化单位必须传「不足一像素」的比例值（如 1f / 浮层高度 px），吸附才隐形。
     */
    fun settle(visibilityThreshold: Float): AnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = visibilityThreshold,
    )
}
