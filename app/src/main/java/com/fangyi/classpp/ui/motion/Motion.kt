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
 * [Accelerate] 加速（先慢后快，用于退场收场）；另有整页覆盖层专用的
 * [PageTransition]（iOS 风格强非线性，前重后轻）。
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
     * tab 平移时长：先快后慢的减速曲线下，位移的大部分集中在开头，末尾只是缓慢收住。
     * 想让节奏更利落可下调（300ms 左右），曲线不变。
     */
    const val TabMillis = 400

    /**
     * 编辑态过渡时长：顶栏 ↔ 编辑栏（ScheduleScreen 内 AnimatedContent）与底部导航栏
     * 的 AnimatedVisibility 共用同一规格（配合 [Standard]），同一个 editing 翻转同帧启动，
     * 两侧才能严格同步。想更快收场可下调（300ms 左右），曲线不变。
     */
    const val EditMillis = 360

    /**
     * 整页覆盖层（设置页、个性化子页）进场时长：整页右滑入，配合 [PageTransition]。
     * 子页转场必须与设置页覆盖层本身同源，同源才有连续感。
     */
    const val PageEnterMillis = 440

    /** 整页覆盖层退场时长：同方向右滑出，比进场短一些，返回更利落。 */
    const val PageExitMillis = 320

    /** 浮层（OverlaySheet）入场时长：从屏幕底部滑入，配合 [Decelerate]。 */
    const val SheetEnterMillis = 320

    /** 浮层出场时长：向下滑出，配合 [Accelerate]。 */
    const val SheetExitMillis = 280

    /**
     * 遮罩弹窗（FadeOverlayDialog）淡入淡出时长：遮罩与卡片共用同一条透明度，
     * 进场 [Decelerate]、退场 [Accelerate]。
     */
    const val DialogMillis = 200

    /**
     * 弹出菜单 scale 时长（M3 DropdownMenu 同款：scale 0.8→1），周数弹窗与
     * 课程长按菜单共用，配合 [Standard]。
     */
    const val PopupScaleMillis = 200

    /** 弹出菜单 alpha 时长（alpha 0→1），与 [PopupScaleMillis] 同帧启动、同曲线。 */
    const val PopupFadeMillis = 120

    /** 标准曲线：先加速后减速，页面级进出场与弹出菜单的主曲线。 */
    val Standard: Easing = FastOutSlowInEasing

    /** 减速曲线：起点即全速、此后单调减速收尾（先快后慢），进场与微交互用。 */
    val Decelerate: Easing = LinearOutSlowInEasing

    /** 加速曲线：先慢后快，退场收场用，与 [Decelerate] 成「同族曲线对」。 */
    val Accelerate: Easing = FastOutLinearInEasing

    /**
     * 整页覆盖层转场曲线（设置页、个性化子页共用）：iOS 风格强非线性
     * cubic-bezier(0.32, 0.72, 0, 1)（UIKit 弹层/push 同款）——起步即接近全速、
     * 大部分位移集中在开头，长尾缓慢收住，比 [Standard] 更「前重后轻」。
     * 进场与退场同一条曲线（退场只是时长更短，同 iOS pop），下层视差与压暗遮罩
     * 随同一条进度推进。
     */
    val PageTransition: Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    /**
     * 松手回弹归位弹簧：无过冲（NoBouncy）、中低刚度，接近终点自然减速停住。
     * 当前用于浮层拖拽松手后的回位（跟手 snapTo 之后的收尾）。
     */
    val Settle: AnimationSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
}
