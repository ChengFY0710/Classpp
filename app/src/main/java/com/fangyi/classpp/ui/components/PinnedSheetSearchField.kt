package com.fangyi.classpp.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing

/**
 * 浮层内钉顶搜索条：画在 [OverlaySheet] 的 contentOverlay 覆盖槽（滚动容器外、顶栏下）里，
 * 位置跟着列表里同高占位项的实测屏幕坐标走——占位项滚到哪儿条就到哪儿，被顶到顶栏下缘
 * 上方 [SheetSearchPinLift] 后钉住不再上移，列表标题与行从它下方滚过。始终组合、随卡进出场，
 * 焦点/光标/键盘状态不丢。
 *
 * 使用方式（两种浮层共用同一套搜索条形态）：
 * 1. `OverlaySheet` 切到 `lazyContent` 模式，在说明/刷新卡之后放一个占位 item：
 *    `Spacer(Modifier.height(searchFieldHeight).onGloballyPositioned { ... })`，
 *    高度取 [SheetSearchFieldDefaultHeight] 作首帧默认值，再经 [onHeightMeasured] 校准；
 * 2. `contentOverlay` 槽里调用本组件，把占位项的根坐标 [anchorRootY] 传进来；
 * 3. `imeBehavior` 用 [SheetImeBehavior.ContentScroll]：搜索框经 [SheetSearchField] 自带的
 *    `imeFieldTracking` 参与键盘让位。
 *
 * 覆盖槽默认画在顶栏之下；钉住位置比顶栏下缘高 5dp，抬高的那一截会压进顶栏的渐变底缘，
 * 被遮罩盖住——因此整层用 zIndex 抬到顶栏之上绘制，搜索条上缘保持完整可见。
 *
 * 玻璃材质经 [LocalOverlayContentGlassBackdrop] 下发：覆盖槽在采样层（卡片底色 +
 * 滚动内容）之外，可安全采样——钉住时折射从条下方滚过的行，不会读写同一层；
 * null 回退实心白底，聚焦蓝描边两种材质下原样叠加。
 *
 * @param anchorRootY 占位项在根坐标系里的 y（列表每帧回报，见调用点）；传状态对象而不是值，
 *   读它的就只有下面那个定位壳
 * @param onHeightMeasured 搜索框实测高度回传列表占位项，字体缩放等场景不与条脱节
 */
@Composable
internal fun BoxScope.PinnedSheetSearchField(
    anchorRootY: MutableFloatState,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    clearContentDescription: String,
    onHeightMeasured: (Dp) -> Unit,
) {
    val density = LocalDensity.current
    val backdrop = LocalOverlayContentGlassBackdrop.current
    // 覆盖槽自己量一次卡片顶端（同样用状态对象：读它的是定位壳的绘制块，不是本层）：
    // 锚点是根坐标，减掉它才是「相对浮层」的位移
    val containerTopRootY = remember { mutableFloatStateOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 抬到顶栏之上：钉住位置比顶栏下缘高 5dp，默认层级会被顶栏渐变遮罩盖住上缘
            .zIndex(1f)
            .onGloballyPositioned { containerTopRootY.floatValue = it.positionInRoot().y },
    ) {
        // 定位与内容分开：位移只让定位层的绘制块失效，搜索框走稳定插槽不被带着重组/重排
        PinnedSearchBar(anchorRootY = anchorRootY, containerTopRootY = containerTopRootY) {
            SheetSearchField(
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                clearContentDescription = clearContentDescription,
                backdrop = backdrop,
                modifier = Modifier
                    .fillMaxWidth()
                    // 实测高度回传列表占位项：首帧用默认值，量准后一次校准
                    .onSizeChanged { size -> with(density) { onHeightMeasured(size.height.toDp()) } },
            )
        }
    }
}

/**
 * 搜索条的定位层：把搜索条摆在锚点（占位项）当前所在的高度上。
 *
 * 位置**不从滚动状态推算，只认占位项的实测坐标**：覆盖槽里的节点不在滚动容器内，
 * 滚动不会让它重新放置，按滚动量算出来的位移实测会停在某一帧不动（条悬在半空、
 * 压住下面的行）。改为跟随占位项坐标后，内容滚多少、被橡皮筋拉多少，条就跟着走多少，
 * 折到顶栏下缘之后被 [sheetSearchPinOffsetPx] 钳在顶栏下缘上方 [SheetSearchPinLift]
 * 处贴住。
 *
 * 两个坐标都在这个绘制块里才读：读在上层会把整张浮层（连同列表 item provider）拖进
 * 逐帧重组；且坐标要到布局末尾才写入，组合阶段读到的值晚一帧，快速滑动时条会落后内容。
 * 绘制块与布局同帧、写入后立刻可见，位移只改图层平移，不触发重组与重排。
 * 搜索框经 [content] 插槽传入，滚动手势期间本层不重组，输入框状态不受影响。
 */
@Composable
private fun BoxScope.PinnedSearchBar(
    anchorRootY: MutableFloatState,
    containerTopRootY: MutableFloatState,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val topBarPx = with(density) { TopBarHeight.roundToPx() }
    // 钉住位置在顶栏下缘之上再抬 5dp：换算成 px 交给纯逻辑，规则本身在测试里锁住
    val pinLiftPx = with(density) { SheetSearchPinLift.roundToPx() }
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            // 横向与列表内容同边距（LazyColumn contentPadding 的水平侧）
            .padding(horizontal = PageHorizontalSpacing)
            // 位移在绘制阶段读取、只改图层平移：滚动时既不重组也不重排搜索条，
            // 又与占位项同帧取到最新坐标（读在组合阶段会晚一帧，滑动发顿）
            .graphicsLayer {
                translationY = sheetSearchPinOffsetPx(
                    anchorRootY = anchorRootY.floatValue,
                    containerTopRootY = containerTopRootY.floatValue,
                    topBarPx = topBarPx,
                    pinLiftPx = pinLiftPx,
                )
            },
    ) {
        content()
    }
}

/**
 * 搜索条相对浮层顶端（卡片顶边）的位移（px）：钉住时等于顶栏高减 [pinLiftPx]
 * （= 停在顶栏下缘上方 5dp），更大 = 还在说明卡下方、随内容一起走。
 *
 * 两个坐标都是根坐标系的实测值：[anchorRootY] 是列表里搜索条占位项的 y，
 * [containerTopRootY] 是浮层卡片的 y。两者相减就是占位项相对卡片的位置——也就是搜索条
 * 该画的位置；占位项被滚到钉住位置以上后钳成 `topBarPx - pinLiftPx`，条贴住钉住位置
 * 不再上移。
 *
 * 只做一次减法、不掺任何滚动状态的推算：这一项在屏幕上到哪儿，条就到哪儿。
 * 入参与返回值都用 Float（px）：占位项的位置可以是小数像素，取整会让快速滑动时
 * 逐帧出现 ±1px 的跳动。
 */
internal fun sheetSearchPinOffsetPx(
    anchorRootY: Float,
    containerTopRootY: Float,
    topBarPx: Int,
    pinLiftPx: Int,
): Float = (anchorRootY - containerTopRootY).coerceAtLeast((topBarPx - pinLiftPx).toFloat())

/** 外置搜索条的首帧默认高度：14×2 上下内距 + 24dp 图标（量准后由实测高度接管） */
internal val SheetSearchFieldDefaultHeight = 52.dp

/** 搜索框与其上方卡片的间距：浮层单独取 10dp，不跟随顶层块默认的 18dp */
internal val SheetSearchSectionSpacing = 10.dp

/** 搜索条钉住位置在顶栏下缘之上的抬升量：贴住后再上移 5dp */
private val SheetSearchPinLift = 5.dp
