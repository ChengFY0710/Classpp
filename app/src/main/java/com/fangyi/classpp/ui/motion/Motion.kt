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
     * 编辑态过渡时长：整段转场共用同一时长——顶栏 ↔ 编辑栏（ScheduleScreen 内
     * AnimatedContent，配 [Standard]：起步更缓，避免顶栏的大位移在 [Overlay] 前段
     * 一口气窜完）、课程网格收拢（编辑进度 Animatable）、底部渐变遮罩与底部导航栏
     * 的 AnimatedVisibility（三者配 [Overlay]）。同一个 editing 翻转同帧启动，
     * 各处才能严格同步。想更快收场可下调（300ms 左右），两套曲线都不变。
     */
    const val EditMillis = 450

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
    const val AgendaSnapMillis = 450

    /**
     * 01 落定后的收角时长：02→01 展开动画结束时图片已铺满全屏，机身圆角再单独
     * 渐收成直角，作为展开动画的短尾；配合 [Decelerate]，不阻塞手势。
     */
    const val AgendaCornerFadeMillis = 320

    /** 浮层（OverlaySheet）入场时长：从屏幕底部滑入，配合 [Overlay]。 */
    const val SheetEnterMillis = 400

    /** 浮层出场时长：向下滑出，配合 [Overlay]。 */
    const val SheetExitMillis = 360

    /**
     * 底部弹出选择卡（BottomSelectSheet）入场时长：从屏幕底部滑入，配合 [Overlay]。
     * 与浮层 [SheetEnterMillis] 同值起步，但独立成 token——选择卡族调节奏不动浮层，
     * 反之亦然。
     */
    const val BottomSheetEnterMillis = 400

    /**
     * 底部弹出选择卡（BottomSelectSheet）出场时长：向下滑出，配合 [Overlay]。
     * 独立于浮层 [SheetExitMillis]；比入场短一拍的收场节奏与浮层保持一致。
     */
    const val BottomSheetExitMillis = 340

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

    /**
     * 待办顶栏特殊胶囊（特殊分组页/标签页胶囊）的邻粒避让时长：胶囊进出场与邻粒移动
     * 分两段先后播放（TodoTopBar）——进场先让位（宽度槽在胶囊隐形时张开，邻粒移到位）、
     * 退场末尾再收拢（胶囊淡出完毕，宽度槽塌掉、邻粒合拢），本值是让位/收拢这一段的
     * 时长，让位配 [Decelerate]、收拢配 [Accelerate]。
     */
    const val TodoPillPlaceMillis = 220

    /**
     * 待办顶栏特殊胶囊自身的淡入时长：邻粒让位完成后，胶囊才在就位的槽位里淡入——
     * 「先腾地方、再现身」。配合 [Decelerate]。
     */
    const val TodoPillEnterMillis = 220

    /**
     * 待办顶栏特殊胶囊自身的淡出时长：胶囊先在原位谢幕，淡出完毕邻粒才开始收拢——
     * 「先谢幕、再收桌」。配合 [Accelerate]；比淡入短一拍，与 Sheet/Popup 族进出场
     * 节奏一致。
     */
    const val TodoPillExitMillis = 180

    /**
     * 待办顶栏分组条滚动收束时长：特殊分组页/标签页打开时分组条从当前滚动位滚回首位
     * （animateScrollTo(0)），配合 [Overlay]——前重后轻，与台历吸附（[AgendaSnapMillis]
     * 配 [Overlay]）同一手感。这是点击触发的导航性位移而非松手回弹，故用 tween 而非
     * [Settle] 弹簧：节奏确定，且与全 app 转场时长语义对齐。
     */
    const val TodoPillRowSnapMillis = 400

    /**
     * 待办列表卡片进场时长：列表项淡入（LazyColumn animateItem 的 fadeInSpec，TodoList）。
     * 配合 [Decelerate]——起步即全速。animateItem 的进出场只动透明度、不动尺寸，占位
     * 由邻居的避让动画让出，两条动画同帧启动才有「让位到位、内容渐显」的整体感。
     */
    const val TodoCardEnterMillis = 260

    /**
     * 待办列表卡片退场时长：列表项淡出（animateItem 的 fadeOutSpec）。配合 [Accelerate]
     * ——与 [Decelerate] 成同族曲线对。条目移除时占位立即释放、其余项由避让动画滑合
     * 缺口，淡出中的旧卡与滑上的邻居会有一瞬交叠——比进场短一拍，压短交叠窗口。
     */
    const val TodoCardExitMillis = 220

    /**
     * 待办列表卡片避让收拢时长：增删/跨组移动后，其余卡片与分区滑合缺口的节奏
     * （animateItem 的 placementSpec）。配合 [Decelerate]——起步即全速、贴着上一帧的
     * 内容位置跟手让位。与 [ReorderMillis] 同档（同为「条目避让位移」语义）但独立成
     * token——那是分组管理页拖拽重排的节奏，跨屏共用会互相牵动。
     */
    const val TodoCardPlaceMillis = 220

    /**
     * 待办列表卡片进场的延迟：animateItem 的 fadeInSpec 用带 delay 的 tween（TodoList）
     * ——退场卡先谢幕一拍，同位置补位的新卡才淡入。重复待办完成一期后窗口前移，「下一期」
     * 卡与刚退场的卡内容几乎相同、又大概率落在同一位置，若与退场淡出交叉同跑，观感就是
     * 同一张卡闪了一下；延迟让两段动画错开（退场 [TodoCardExitMillis] 先走完大半），完成
     * 读作「勾上的卡退场、下一天再出现」。折叠展开、恢复删除等其余进场同样吃到这一拍，
     * 统一为「先腾位、再现身」的节奏（与 [TodoPillEnterMillis] 的胶囊编排同语义）。
     */
    const val TodoCardEnterDelayMillis = 120

    /**
     * 待办勾选完成的「停一拍」：勾选手势先在 UI 层乐观生效（TodoScreen 的 completionEcho
     * 回显覆盖卡片完成态，勾选与划线动画当帧起播），延后本值再落库——仓库回流触发的
     * 列表重排（退场/避让/补位进场）等完成反馈播完才开始，反馈不被重排动画截断。
     * 取值盖过 Checkbox 勾选与 NoteCard 划线过渡（[FastMillis]）的时长即可。
     */
    const val TodoCardCompleteSettleMillis = 180

    /**
     * 待办倒计时完成退场时长：卡片左区倒计时标签「左移淡出」的节奏（NoteCard 的
     * CountdownExitSlot，勾选完成当帧起播）。配合 [Accelerate]——退场收场，与划线
     * 过渡（[FastMillis]）几乎同时起播、同时档收束。
     */
    const val TodoCountdownExitMillis = 160

    /**
     * 待办倒计时退场时右区内容跟随的启动延迟：标签先走、内容随后跟上——倒计时左移
     * 淡出先播一拍，槽位收拢（内容左移让位）延后本值再启动，「先…跟随」的两段错拍。
     * 取消完成的入场（标签淡入回归、内容右移让位）用同一拍镜像。
     */
    const val TodoCountdownFollowDelayMillis = 60

    /**
     * 待办倒计时让位后右区内容跟随左移的时长：倒计时槽位宽度从自然宽收缩到 0（或反向
     * 张回）的节奏，内容由布局自动跟随。配合 [Decelerate]——与 [TodoCardPlaceMillis]
     * 同语义（条目内让位位移）但独立成 token：那是列表级避让收拢，这是卡内让位，
     * 跨效果共用会互相牵动。
     */
    const val TodoCountdownFollowMillis = 180

    /**
     * 开倒计时的待办勾选完成的「停一拍」：[TodoCardCompleteSettleMillis] 的倒计时版。
     * 取值盖过倒计时退场与内容跟随的总长（[TodoCountdownExitMillis] 与
     * [TodoCountdownFollowDelayMillis] + [TodoCountdownFollowMillis] 取大者），倒计时
     * 待办的完成反馈比普通待办多一拍，播完再落库、列表重排才开始。
     */
    const val TodoCountdownCompleteSettleMillis = 260

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
