package com.fangyi.classpp.ui.schedule

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.components.hazeSources
import com.fangyi.classpp.ui.components.hazeTopBarStyle
import com.fangyi.classpp.ui.motion.LocalPageOverlayTransform
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.PageOverlayTransitionState
import com.fangyi.classpp.ui.motion.PopupGlassCard
import com.fangyi.classpp.ui.motion.popupMotionScale
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.motion.rememberPopupMotion
import com.fangyi.classpp.ui.motion.TabTransitionState
import com.fangyi.classpp.ui.motion.transitionFreeze
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.Correct
import com.fangyi.classpp.ui.theme.Error
import com.fangyi.classpp.ui.theme.LiquidGlassStyle
import com.fangyi.classpp.ui.theme.LocalNavigationBarHeight
import com.fangyi.classpp.ui.theme.LocalStatusBarHeight
import com.fangyi.classpp.ui.theme.LocalStatusBarInsets
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.glassPressFeedback
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.shapes.RoundedRectangle
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import java.util.Calendar
import java.util.Date
import kotlin.math.roundToInt

internal val TopBarHeight = 56.dp  //顶栏行自然高度，也是最大折叠量

private val WeekPillHeight = 40.dp
/** 周数胶囊尾部箭头旋转时长：打开/关闭同值 */
private const val WeekPillArrowRotateMillis = 360
private val HeaderEdgePadding = 33.dp

/**
 * 图标中心距屏幕边缘恒为 44dp（原实现 = 20dp 留白 + 48dp 按钮盒的一半），盒宽取其两倍、
 * IconButton 内容居中 → 图标原位不动，只有热区变大（40×40 → 64×40）。
 */
private val TopBarIconTouchWidth = 64.dp

/** 折叠态胶囊右缘距屏幕右侧的距离（与顶栏设置图标右缘 ~29dp 基本对齐） */
private val PillEndPadding = 30.dp

/** 周数选择器顶部与胶囊底部的固定垂直间距（展开/折叠态一致），也是高度预算里扣掉的间距。 */
internal val WeekPickerGap = 16.dp

/**
 * 高度预算的胶囊下缘预留：顶栏内垂直居中的胶囊底边（展开态 = 48dp，折叠态停在日期行中心、
 * 更低）。用它而不是整窗高，弹窗高度只看胶囊下方真正可用的空间。
 */
private val WeekPickerPillBottomReserve = (TopBarHeight + WeekPillHeight) / 2

internal val CardShadowPadding = 16.dp

/** 投影向下延伸最多，底部单独加大透明留白，防止被 Popup 窗口下缘裁切 */
internal val CardShadowBottomPadding = 48.dp

/**
 * 周数选择弹窗的投影留白上限（菜单族仍用 [CardShadowPadding] / [CardShadowBottomPadding]）：
 * 52dp 柔影比菜单族更重，左右/顶部最多留 50dp，底部坠得最远、单独用
 * [WeekPickerShadowBottomPadding]。顶部留白向上压进头部区域，**不占**高度预算；左右/底部
 * 只做上下限钳制，实际值由剩余空间决定，不再反过来挤压卡片。
 */
internal val WeekPickerShadowPadding = 50.dp

/** 周数选择弹窗专用的底部投影留白上限（其余三边用 [WeekPickerShadowPadding]）。 */
internal val WeekPickerShadowBottomPadding = 60.dp

/**
 * 投影留白的最低余量：高度不足时等比缩放会吃掉留白，但始终给投影留 8dp，
 * 避免卡片贴死在 Popup 窗口边缘（再不足就进入内部滚动，见 [WeekPickerGeometry]）。
 */
internal val WeekPickerShadowMinPadding = 8.dp

/** 左右最低留白：宽 384dp 时卡片到屏幕边缘恒为 17dp（1dp 边距 + 16dp 留白）。 */
internal val WeekPickerSideMinPadding = 16.dp

internal val WeekPickerCellSpace = 8.dp // 周数选择器里小方块间距

/** 弹窗边距：卡片居中后窗口左右各留 1dp，保证窗口收在屏幕内（384dp 时卡片到屏幕 17dp）。 */
internal val WeekPickerEdgeMargin = 1.dp

/**
 * 384dp 宽（正常手机）时的方格边长，也是宽度缩小的参考值：
 * 6×49 + 7×8 间距/内边距 + 2×16 留白 + 2×1 边距 = 384，卡片到屏幕两侧恒为 17dp。
 */
internal val WeekCellSizeBase = 49.dp

/**
 * 方格边长上限：450dp 宽时格子涨到 60dp（卡片 416dp、两侧仍 17dp），更宽（平板/横屏）
 * 不再放大，卡片 416dp 居中、投影留白自然变宽——正好也与平板的观感匹配。
 */
internal val WeekCellSizeMax = 60.dp

/**
 * 等比缩小的可读下限：28dp 方格约对应 13sp 字号。缩到这里仍放不下时不再压缩，
 * 改为卡片内部纵向滚动（[WeekPickerGeometry.scrollable]）。
 */
internal val WeekCellSizeMin = 28.dp

/** 卡片与方格的圆角参考值（[WeekPickerGeometry.visualScale] = 1），实际按它缩放。 */
internal val WeekPickerCardCorner = 20.dp
internal val WeekPickerCellCorner = 12.dp

/** 卡片投影高度参考值（[WeekPickerGeometry.visualScale] = 1），实际按它缩放。 */
internal val WeekPickerShadowElevation = 52.dp

/**
 * 周数选择弹窗的完整尺寸。宽度分三段：
 *
 * - ≤384dp：卡片占满「屏幕 − 两侧 17dp」，方格从 49dp 等比缩小，间距、字号、圆角、
 *   投影一起乘同一比例，窄窗不挤压；
 * - 384–450dp：卡片继续占满「屏幕 − 两侧 17dp」，只放大方格（49→60dp），
 *   间距 8dp、字号 23sp、圆角、投影保持基准值——弹窗随屏宽放大但字号不变；
 * - >450dp：卡片封顶 416dp（方格 60dp），居中显示，投影留白自然变宽，适合平板。
 *
 * 高度只受「胶囊下缘 → 窗口底部」的预算约束：卡片顶边固定在胶囊下方
 * [WeekPickerGap]，顶部投影留白向上压进头部区域，不占预算。行数参与反推，
 * 2 行的短学期不会被按 4 行缩。矮窗放不下时整张卡片（含字号、圆角、投影）按
 * 同一比例缩小。
 *
 * [scrollable] 为 true 时高度已缩到 [WeekCellSizeMin] 仍放不下：卡片高度锁在
 * [maxCardHeight]，网格在卡片内部滚动——宁可滚动，也不压扁底行或裁掉内容。
 *
 * [visualScale] 只作用于字号、圆角、投影高度：宽度放大段恒为 1（字号不变），
 * 窄窗/矮窗的等比缩小段才小于 1。
 */
internal data class WeekPickerGeometry(
    val visualScale: Float,
    val cellSize: Dp,
    val cellSpace: Dp,
    val sidePadding: Dp,
    val topPadding: Dp,
    val bottomPadding: Dp,
    val shadowElevation: Dp,
    val cardCorner: Dp,
    val cellCorner: Dp,
    val scrollable: Boolean,
    val maxCardHeight: Dp,
)

/**
 * 按可用空间计算周数弹窗尺寸。两个输入都未知（0，首帧之前/预览）时回落到参考设计。
 *
 * [availableHeightBelowPillDp] 是胶囊下缘到窗口可用底部的距离（组合期已扣除状态栏、
 * 顶栏预留与底部导航栏让位）；函数内部再扣 [WeekPickerGap] 与投影最低余量。
 *
 * 宽度本身连最低留白都放不下时允许跌破 [WeekCellSizeMin]（横向没有补救空间，
 * 宁可小也不能溢出）；只有高度不足才触发下限 + 滚动。
 */
internal fun weekPickerGeometry(
    availableWidthDp: Float,
    availableHeightBelowPillDp: Float,
    rows: Int,
): WeekPickerGeometry {
    val referenceCell = WeekCellSizeBase.value
    val maxCell = WeekCellSizeMax.value
    val referenceSpace = WeekPickerCellSpace.value
    val rowCount = rows.coerceAtLeast(1)
    val edge = WeekPickerEdgeMargin.value
    val sideMin = WeekPickerSideMinPadding.value
    val baseCardWidth = 6f * referenceCell + 7f * referenceSpace
    val maxCardWidth = 6f * maxCell + 7f * referenceSpace

    // 宽度：≤450dp 时卡片始终占满「屏幕 − 两侧 17dp」；>450dp 封顶 416dp
    val cardWidthTarget = if (availableWidthDp > 0f) {
        (availableWidthDp - 2f * edge - 2f * sideMin).coerceIn(0f, maxCardWidth)
    } else {
        baseCardWidth
    }
    val widthScale = cardWidthTarget / baseCardWidth

    // 384dp 以下：所有 token 等比缩小；384–450dp：只放大方格，间距与字号保持基准值
    val baseCell: Float
    val baseSpace: Float
    if (cardWidthTarget <= baseCardWidth) {
        baseCell = referenceCell * widthScale
        baseSpace = referenceSpace * widthScale
    } else {
        baseCell = (cardWidthTarget - 7f * referenceSpace) / 6f
        baseSpace = referenceSpace
    }
    val baseCardHeight = rowCount * baseCell + (rowCount + 1) * baseSpace

    // 高度：只受胶囊下方可用空间约束，行数参与反推；放不下时整卡等比缩小
    val cardSpace = if (availableHeightBelowPillDp > 0f) {
        (availableHeightBelowPillDp - WeekPickerGap.value - WeekPickerShadowMinPadding.value)
            .coerceAtLeast(0f)
    } else {
        Float.MAX_VALUE
    }
    var heightScale = if (baseCardHeight > cardSpace) {
        (cardSpace / baseCardHeight).coerceIn(0f, 1f)
    } else {
        1f
    }
    // 可读下限只约束高度方向：宽度本身放不下时宁可更小，也绝不让卡片横向溢出
    val floorScale = if (baseCell > 0f) WeekCellSizeMin.value / baseCell else 0f
    val scrollable = baseCell >= WeekCellSizeMin.value && heightScale < floorScale
    if (scrollable) heightScale = floorScale

    val cellSize = baseCell * heightScale
    val cellSpace = baseSpace * heightScale
    val cardHeight = rowCount * cellSize + (rowCount + 1) * cellSpace
    val frameHeight = if (scrollable) cardSpace else cardHeight
    // 宽度放大段字号/圆角/投影不跟着涨；窄窗与矮窗的缩小段才等比缩
    val visualScale = widthScale.coerceAtMost(1f) * heightScale

    return WeekPickerGeometry(
        visualScale = visualScale,
        cellSize = cellSize.dp,
        cellSpace = cellSpace.dp,
        sidePadding = if (availableWidthDp > 0f) {
            ((availableWidthDp - 6f * cellSize - 7f * cellSpace) / 2f - edge)
                .coerceIn(0f, WeekPickerShadowPadding.value).dp
        } else {
            WeekPickerSideMinPadding
        },
        topPadding = WeekPickerShadowPadding,
        bottomPadding = if (availableHeightBelowPillDp > 0f) {
            (availableHeightBelowPillDp - WeekPickerGap.value - frameHeight)
                .coerceIn(0f, WeekPickerShadowBottomPadding.value).dp
        } else {
            WeekPickerShadowBottomPadding
        },
        shadowElevation = WeekPickerShadowElevation * visualScale,
        cardCorner = WeekPickerCardCorner * visualScale,
        cellCorner = WeekPickerCellCorner * visualScale,
        scrollable = scrollable,
        maxCardHeight = if (scrollable) cardSpace.dp else 0.dp,
    )
}

/**
 * 折叠态（fraction = 1）周数胶囊的左缘坐标：胶囊「结束侧」边缘距屏幕同侧边缘恒为 [endPadPx]，
 * 由胶囊实测宽度反推左缘位置。
 *
 * 宽度必须参与计算：折叠终点不能只用固定偏移量判定，否则不同字宽的周数
 * （如「第 9 周」与「第 10 周」实测宽度不同）会停在距屏幕右侧不同的距离上，甚至整体滑出屏幕。
 * RTL 下结束侧在左，轴对称处理。
 */
internal fun collapsedPillX(
    widthPx: Int,
    pillWidthPx: Int,
    endPadPx: Int,
    isRtl: Boolean,
): Float = if (isRtl) {
    endPadPx.toFloat()
} else {
    (widthPx - pillWidthPx - endPadPx).toFloat()
}

/**
 * 可折叠课表头部：顶栏行（编辑 | 周数胶囊 | 设置）+ 日期行 + 星期行。
 *
 * [collapseFraction] ∈ [0,1]：0 完全展开，1 完全折叠——
 * 顶栏行高度收缩为 0，编辑/设置渐隐，日期行与星期行自然上移，
 * 周数胶囊从中央平移到日期行右端（与日期同一行右对齐）。
 * fraction 外置便于 @Preview 直接预览两态。
 *
 * 星期行按 [daysPerWeek] 等分（5 或 7 列），与网格列硬对齐；
 * 7 天视图下多出周六/周日两列，高亮列仍由顶栏日期推导。
 * [onDaysPerWeekToggle] 非空时整行可点：点一下在 5 天 / 7 天视图间切换
 * （把「每周上课天数」从设置深处搬到课表首屏——7 天课表的学期结束日是周日，
 * 不先改回周五就无法从设置页切回 5 天，这个开关是那条退路）。
 *
 * [blurProgress] ∈ [0,1]（与日期带渐隐同步）：>0 时头部背景改为
 * Haze 背景模糊（叠在 surface 兜底色之上），顶部最强、向下渐弱；
 * 同时驱动周数胶囊从扁平灰底过渡到半透明白 + 投影。
 * 传 [State] 而非 Float：兜底色 / 胶囊底材 / 投影 / 玻璃 alpha 与 Haze Style 全在
 * 绘制期（或 Style 解析时）读它，滚动不触发重组；null（如 @Preview）视作 0，
 * 保持不透明背景。State 身份必须由宿主 remember 稳定持有。
 * [hazeState] 为 null 时同样保持不透明背景。
 *
 * [tabTransition] 非 null 时启用转场冻结层（[transitionFreeze]）；此外，宿主通过
 * [LocalPageOverlayTransform] 注入设置页覆盖层视差状态（或经 [overlayTransitionState]
 * 传入本页自己的覆盖层状态）后，视差期间同样冻结。两种
 * transform 进行中整帧重放就位时录制的画面（含模糊输出），绕开 Haze 对转场中间态
 * 几何的重采样，杜绝快速切 tab / 开关设置页时顶栏闪出展开态的白/黑帧。
 *
 * [pillBackdrop] 非空（宿主经「更高级的玻璃材质」开关 + SDK 双门控下发）时周数胶囊
 * 折叠终态改液态玻璃底 + Q弹按压放大——玻璃随 [blurProgress] 淡入接替实心底渐变
 * （展开态的扁平灰底形态不变，见 WeekPill）；null（默认，如 @Preview / 玻璃开关
 * 关闭）维持原实心底 + pressFeedback。
 *
 * [termStartWeekdayIndex]/[termEndWeekdayIndex] 非空时星期行的对应列绿（Correct）/红（Error）：
 * 查看开学/结课所在周时由调用方传入该日期的星期下标（周一起始，与 [weekIndex] 同规则），
 * 与日期带的学期起止日标记上下对齐；起止日恰为「今天」时调用方传 null，
 * 让位给「今天/查看日」的蓝字高亮。
 */
@Composable
fun ScheduleHeader(
    collapseFraction: Float,
    date: Date,
    selectedWeek: Int,
    todayWeek: Int? = null,
    onWeekSelected: (Int) -> Unit,
    onEditClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    blurProgress: State<Float>? = null,
    hazeState: HazeState? = null,
    pillBackdrop: Backdrop? = null,
    weekRange: IntRange = 1..20,
    daysPerWeek: Int = 5,
    onDaysPerWeekToggle: (() -> Unit)? = null,
    onMenuExpandedChange: (Boolean) -> Unit = {},
    termStartWeekdayIndex: Int? = null,
    termEndWeekdayIndex: Int? = null,
    tabTransition: TabTransitionState? = null,
    // 本页自己的覆盖层转场状态（AI/教务网页的视差）：非空时优先于
    // [LocalPageOverlayTransform] 注入的外层状态，用于视差期间顶栏冻结
    overlayTransitionState: PageOverlayTransitionState? = null,
) {
    val fraction = collapseFraction.coerceIn(0f, 1f)
    val iconAlpha = 1f - fraction
    val iconsEnabled = fraction < 0.5f
    // 滚动进度只在绘制期读：兜底色、胶囊、Haze Style 块共用这一个读取口，组合期不读，
    // 渐隐窗口内滚动不重组（State 身份由宿主 remember 稳定持有）
    fun progress(): Float = (blurProgress?.value ?: 0f).coerceIn(0f, 1f)
    // Haze 效果只在进度跨过 0 时挂载 / 卸载：派生成布尔，组合期只在端点翻转一次
    val hazeActive by remember(blurProgress) { derivedStateOf { progress() > 0f } }
    // 兜底色两端：随主题在组合期取好，插值进度留到 draw 阶段读
    val fallbackSurface = MaterialTheme.colorScheme.surface
    val fallbackBackground = MaterialTheme.colorScheme.background

    val calendar = remember(date) { Calendar.getInstance().apply { time = date } }
    val monthDay = stringResource(
        R.string.month_day_format,
        calendar.get(Calendar.MONTH) + 1,
        calendar.get(Calendar.DAY_OF_MONTH),
    )
    // Calendar 以周日为 1，转成周一起始的下标（周一 = 0）
    val weekIndex = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val weekdayNames = stringArrayResource(R.array.weekdays)
    // 列数只认资源里真有的星期名：7 天视图展示周一~周日，5 天视图只到周五
    val days = daysPerWeek.coerceIn(1, weekdayNames.size)
    // 星期行的列标签用单字简称资源（中文「周X」→「X」，英文 Mon/Tue/…），勿再对全名做字符串假设
    val weekdays = stringArrayResource(R.array.weekdays_short).take(days)
    // 标题读的是**真实日期的真实星期**：周末在 5 天视图下没有对应列（weekIndex ≥ days），
    // 也不能对截断后的 weekdays 取模——那会把周六/周日回绕成周一/周二
    val dateTitle = stringResource(R.string.date_title_format, monthDay, weekdayNames[weekIndex])
    // 无障碍文案：点整行是切换视图，读屏用户看不到"行可点"这回事，故显式说明
    val daysToggleDescription = stringResource(
        if (days == 7) R.string.cd_switch_to_5_days else R.string.cd_switch_to_7_days,
    )

    // 「返回本周」= 回到今周：学期前今周是第 0 周（当今周），学期中是当前周，
    // 学期后没有今周页（不显示）。查看的周与今周相同则按钮隐藏
    val showBackToCurrent = todayWeek != null && selectedWeek != todayWeek

    // 转场冻结层：就位帧（含模糊输出）录制于此，tab 转场/设置页视差期间整帧重放。
    // overlayTransform 只在组合期取引用；isTransforming 在绘制期读，禁止在组合期读
    val transitionFreezeLayer = rememberGraphicsLayer()
    val overlayTransform = overlayTransitionState ?: LocalPageOverlayTransform.current
    val hasFreezeSource = tabTransition != null || overlayTransform != null

    Layout(
        content = {
            // 1) 顶栏行：高度随折叠收缩，图标渐隐
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TopBarHeight * (1f - fraction))
                    .clipToBounds()
                    // 水平留白必须在裁剪之内（clipToBounds 之后）：裁剪线随之外移到屏幕
                    // 边缘，按压放大 1.10 的左右外扩（64→70.4dp，各 3.2dp）才不会在按钮
                    // 外缘被裁成直边；垂直裁剪照旧——折叠时行高收缩，图标溢出部分仍被裁掉。
                    // 两种顺序的子项约束与本 Box 尺寸一致，按钮原位不动。
                    .padding(start = 12.dp, end = 12.dp),
            ) {
                // M3 按钮的涟漪在组件内部硬编码、调用侧置不了 null：本阶段只叠加按压反馈——
                // 按压源经 interactionSource 形参交给按钮，pressFeedback 追加在 modifier 链末尾
                // （最靠近按钮本体：缩放不裹挟外层 padding/alpha，还随折叠渐隐一起淡出）
                val editPress = remember { MutableInteractionSource() }
                IconButton(
                    onClick = onEditClick,
                    enabled = iconsEnabled,
                    interactionSource = editPress,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        // 盒子从屏幕左缘铺起并铺满行高，热区随之扩大；内容居中，图标原位不动
                        .fillMaxHeight()
                        .padding(top = 8.dp, bottom = 8.dp)
                        .width(TopBarIconTouchWidth)
                        .graphicsLayer { alpha = iconAlpha }
                        // 顶栏图标钮视觉尺寸小，按压放大单独调大到 1.10（不随全局默认）
                        .pressFeedback(editPress, CircleShape, scale = 1.10f),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_calendar_edit),
                        contentDescription = stringResource(R.string.cd_edit_schedule),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(30.dp)
                    )
                }
                val settingsPress = remember { MutableInteractionSource() }
                IconButton(
                    onClick = onSettingsClick,
                    enabled = iconsEnabled,
                    interactionSource = settingsPress,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        // 同编辑按钮：热区铺到屏幕右缘，图标居中原位
                        .fillMaxHeight()
                        .padding(top = 8.dp, bottom = 8.dp)
                        .width(TopBarIconTouchWidth)
                        .graphicsLayer { alpha = iconAlpha }
                        // 与编辑钮同规格：小按钮按压放大单独调大到 1.10（不随全局默认）
                        .pressFeedback(settingsPress, CircleShape, scale = 1.10f),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings),
                        contentDescription = stringResource(R.string.cd_settings),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            // 2) 日期行：左侧日期，右侧按需附带返回本周按钮（随图标一同渐隐）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = HeaderEdgePadding,
                        top = 12.dp,
                        bottom = 12.dp,
                        end = 27.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = dateTitle,
                    style = ClassppTextTokens.pageTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                // 箭头方向只在按钮可见时更新：跳回今周后 selectedWeek == todayWeek，
                // 渐隐退场若沿用实时方向会当场翻面（左箭头变右箭头）
                val backLookingForward = todayWeek != null && selectedWeek > todayWeek
                var backLookingForwardShown by remember { mutableStateOf(backLookingForward) }
                if (showBackToCurrent) {
                    backLookingForwardShown = backLookingForward
                }
                // 渐隐出现/渐隐消失（Motion 微交互节奏：进场减速、退场加速）
                AnimatedVisibility(
                    visible = showBackToCurrent,
                    enter = fadeIn(tween(Motion.FastMillis, easing = Motion.Decelerate)),
                    exit = fadeOut(tween(Motion.FastMillis, easing = Motion.Accelerate)),
                ) {
                    BackToCurrentButton(
                        // 箭头指向"回到本周"的方向：查看更后的周 → 左箭头往回，更前的周 → 右箭头往前
                        icon = if (backLookingForwardShown) {
                            R.drawable.ic_arrow_circle_left
                        } else {
                            R.drawable.ic_arrow_circle_right
                        },
                        onClick = { todayWeek?.let(onWeekSelected) },
                        enabled = iconsEnabled,
                        alpha = iconAlpha,
                    )
                }
            }

            // 3) 星期行：按 daysPerWeek 等分（5/7 列），今天高亮（周起始下标与日期行一致）；
            //    传了 onDaysPerWeekToggle 时整行可点 → 5 天 / 7 天视图互切
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 7.dp)
                    .then(
                        if (onDaysPerWeekToggle != null) {
                            // 条件挂载 → 按压源也只在条件内创建（同 SheetCard 的写法）：
                            // pressFeedback 与 clickable 共用它，按压放大提亮取代涟漪
                            val press = remember { MutableInteractionSource() }
                            Modifier
                                // 本行没有 clip：反馈原位放最外侧，整行按下都有响应
                                .pressFeedback(press, RectangleShape)
                                .clickable(
                                    interactionSource = press,
                                    indication = null,
                                    onClick = onDaysPerWeekToggle,
                                )
                                .semantics { contentDescription = daysToggleDescription }
                                .padding(bottom = 3.dp)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                for (i in weekdays.indices) {
                    Text(
                        text = weekdays[i],
                        modifier = Modifier.weight(1f),
                        style = ClassppTextTokens.calendarWeekday,
                        textAlign = TextAlign.Center,
                        // 学期起止日标记优先于「今天/查看日」高亮：与下方日期带的同日标记
                        // 同列同色（开始日绿、结束日红）；为今天时调用方已传 null，蓝字保留。
                        // 下标越界（5 天视图里开学/结课在周六日）时列不存在，自然不标
                        color = when {
                            i == termStartWeekdayIndex -> Correct
                            i == termEndWeekdayIndex -> Error
                            i == weekIndex -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }

            // 4) 周数胶囊：顶层 overlay，在两锚点间插值；模糊激活时材质同步变化
            // 始终可点（折叠态也要能打开周数菜单），图标仍随折叠禁用。
            // 「本周」高亮传今周：开学前今周是第 0 周，弹窗里没有 0 格 → 自然无高亮
            WeekPill(
                selectedWeek = selectedWeek,
                currentWeek = todayWeek,
                weekRange = weekRange,
                onWeekSelected = onWeekSelected,
                blurProgress = blurProgress,
                hazeState = hazeState,
                backdrop = pillBackdrop,
                onMenuExpandedChange = onMenuExpandedChange,
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (hasFreezeSource) {
                    Modifier.transitionFreeze(transitionFreezeLayer) {
                        tabTransition?.isTransitioning == true ||
                            overlayTransform?.isTransforming == true
                    }
                } else {
                    Modifier
                },
            )
            // 兜底色随模糊进度由 surface 渐向 background：progress≈0 时与原不透明背景逐帧一致；
            // 模糊激活后，模糊输出盖不到的区域（跨多周跳转整块平移网格时的覆盖缺口、
            // progressive 渐弱到 0 的顶栏底缘）露出的就是这层兜底——对齐到下方页面基础底色
            // background，缺口才不会以亮一档的 surface 闪出白块（深色主题同理）。
            // 稳态下源图层满屏、兜底色完全被模糊盖住，此渐变对正常观感无影响
            // 颜色在组合期取好，进度在 draw 阶段读——滚动不触发重组
            .drawBehind {
                drawRect(lerp(fallbackSurface, fallbackBackground, progress()))
            }
            .then(
                if (hazeState != null && hazeActive) {
                    // 背景模糊画在兜底色之上、内容之下；opacity 随进度渐入实现无缝衔接。
                    // Haze 2 由库内负责源失效与几何对齐（旧 forceInvalidateOnPreDraw 已移除），
                    // 原先「源重画后强制重采样、杜绝快速切 tab 首帧空白」的诉求由库接管；
                    // 转场期另有 transitionFreeze 整帧重放兜底。Style 块内读进度 State，
                    // 进度变化只让效果重解析 + 重绘，不重建 Style、不重组顶栏
                    Modifier.hazeBlur(
                        input = hazeSources(hazeState),
                        style = hazeTopBarStyle(radius = 25.dp, opacity = blurProgress),
                        performanceMode = HazePerformanceMode.Quality,
                    )
                } else {
                    Modifier
                },
            )
            .windowInsetsPadding(LocalStatusBarInsets.current),
    ) { measurables, constraints ->
        // 本 Layout 自身用了 fillMaxWidth → 收到的约束是 minWidth == maxWidth，
        // 若原样下发给子项，每个子项都会被撑满整屏（周数胶囊实测宽度也会变成屏宽，
        // 于是任何按「胶囊实际宽度」算出的定位全部失真）。故测量子项前清掉 minWidth：
        // 顶栏行/日期行/星期行各自声明了 fillMaxWidth，仍是满宽；只有胶囊按内容收窄。
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val topBarPlaceable = measurables[0].measure(childConstraints)
        val datePlaceable = measurables[1].measure(childConstraints)
        val weekdayPlaceable = measurables[2].measure(childConstraints)
        val pillPlaceable = measurables[3].measure(childConstraints)

        val width = constraints.maxWidth
        val totalHeight =
            topBarPlaceable.height + datePlaceable.height + weekdayPlaceable.height

        // 胶囊位置：x 居中 → 右对齐，y 顶栏中心 → 日期行中心
        val pillWidth = pillPlaceable.width
        val pillHeight = pillPlaceable.height
        val endPad = PillEndPadding.roundToPx()
        val centerX = (width - pillWidth) / 2f
        // 折叠终点交给 collapsedPillX：由胶囊实测宽度反推，右缘距屏幕右侧恒为 PillEndPadding
        val endX = collapsedPillX(
            widthPx = width,
            pillWidthPx = pillWidth,
            endPadPx = endPad,
            isRtl = layoutDirection == LayoutDirection.Rtl,
        )
        val pillX = centerX + (endX - centerX) * fraction
        val topBarCenterY = TopBarHeight.roundToPx() / 2f
        val dateCenterY = topBarPlaceable.height + datePlaceable.height / 2f
        val pillCenterY = topBarCenterY + (dateCenterY - topBarCenterY) * fraction

        layout(width, totalHeight) {
            var y = 0
            topBarPlaceable.placeRelative(0, y)
            y += topBarPlaceable.height
            datePlaceable.placeRelative(0, y)
            y += datePlaceable.height
            weekdayPlaceable.placeRelative(0, y)

            pillPlaceable.place(
                pillX.roundToInt(),
                (pillCenterY - pillHeight / 2f).roundToInt(),
            )
        }
    }
}

/**
 * 日期行右侧「返回本周」按钮：非本周时显示，点按触发 [onClick] 跳回本周。
 * [icon] 由调用方按方向给出（左箭头 = 从更后的周往回，右箭头 = 从更前的周往前）。
 * [alpha]/[enabled] 吃编辑/设置图标同款折叠渐隐值，上滑时随顶栏一同消失。
 */
@Composable
private fun BackToCurrentButton(
    icon: Int,
    onClick: () -> Unit,
    enabled: Boolean,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    // 圆角只声明一次：按压反馈与 clip 共用同一形状，反馈范围与裁剪完全对齐
    val shape = RoundedCornerShape(6.dp)
    // 按压态的唯一来源：pressFeedback 与 clickable 共用（涟漪由按压反馈取代，故 clickable 不再要 Indication）
    val press = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .graphicsLayer { this.alpha = alpha }
            // 按压反馈插在 alpha 图层与 clip 之间：缩放/提亮发生在裁剪之前（clip 不影响点击输入），
            // 外层 alpha 照常生效 → 反馈随折叠与按钮一同渐隐
            .pressFeedback(press, shape)
            .clip(shape)
            .clickable(
                interactionSource = press,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp, vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.back_to_current_week),
            style = ClassppTextTokens.calendarAction,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

@Composable
private fun WeekPill(
    selectedWeek: Int,
    currentWeek: Int?,
    weekRange: IntRange,
    onWeekSelected: (Int) -> Unit,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
    blurProgress: State<Float>? = null,
    backdrop: Backdrop? = null,
    onMenuExpandedChange: (Boolean) -> Unit = {},
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        var menuExpanded by remember { mutableStateOf(false) }
        // 点选周后不立刻换周：换周会驱动 Pager 换页 / 整页换入并重写 Haze 采样源结构，
        // 这一大帧会把 120ms 的退场动画整段吞掉。统一先把目标周记下来，退场播完再换周
        // （与 PopupActionGate「退场后再执行重写动作」同一原则），相邻周与非相邻周手感一致。
        var pendingJumpWeek by remember { mutableStateOf<Int?>(null) }
        val latestOnWeekSelected by rememberUpdatedState(onWeekSelected)
        // 尾部箭头随弹窗开合旋转：同一张 chevron 下箭头转 180° 当上箭头用，
        // 打开与关闭同走固定时长，转角读在 layer 内不触发重组
        val arrowRotation by animateFloatAsState(
            targetValue = if (menuExpanded) 180f else 0f,
            animationSpec = tween(WeekPillArrowRotateMillis, easing = Motion.Standard),
            label = "weekPillArrow",
        )
        // 进出场动画（果冻曲线 + 整卡首尾淡入淡出 + 分层淡入 + 尾淡兜底，与弹出菜单族
        // 同一套 motion）：收起动画期间 motion.shown 保持 Popup 在场
        val motion = rememberPopupMotion(
            expanded = menuExpanded,
            onExitFinished = {
                pendingJumpWeek?.let { week ->
                    pendingJumpWeek = null
                    latestOnWeekSelected(week)
                }
            },
            // 起始缩放 0.5：半尺寸小卡收在胶囊正下方，从胶囊中心「涌出」的读感才够强
            // （胶囊中心距卡片顶边仅 36dp，默认 0.8 的锚点差肉眼不可辨）
            enterStartScale = 0.5f,
        )

        fun closeMenu() {
            menuExpanded = false
            onMenuExpandedChange(false)
        }

        // 弹窗尺寸随宿主窗口自适应（见 weekPickerGeometry）：≤384dp 等比缩小；
        // 384–450dp 卡片铺满两侧 17dp、只放大方格；>450dp 封顶 416dp 卡片。
        // 高度预算只算「胶囊下缘 → 窗口底部」：扣掉状态栏让位、胶囊下缘预留
        // 与底部导航栏让位后，再交给几何函数扣固定间距；顶部投影留白向上压进头部区域，
        // 不占这份预算。多窗口/分屏下 containerDpSize 是当前窗口而非整块屏幕，与定位一致
        val windowSizeDp = LocalWindowInfo.current.containerDpSize
        val availableWidthDp = windowSizeDp.width.value
        val availableHeightBelowPillDp =
            (windowSizeDp.height - LocalStatusBarHeight.current - WeekPickerPillBottomReserve -
                LocalNavigationBarHeight.current).value
        val weekRows = ((weekRange.last - weekRange.first + 1 + 5) / 6).coerceAtLeast(1)
        val geometry = remember(availableWidthDp, availableHeightBelowPillDp, weekRows) {
            weekPickerGeometry(
                availableWidthDp = availableWidthDp,
                availableHeightBelowPillDp = availableHeightBelowPillDp,
                rows = weekRows,
            )
        }

        // 按压态的唯一来源：glassPressFeedback 与 clickable 共用（涟漪由按压反馈取代，故 clickable 不再要 Indication）
        val pressInteraction = remember { MutableInteractionSource() }
        // 玻璃双门控（开关 + SDK）已由宿主完成，此处只看非空
        val useGlass = backdrop != null && Build.VERSION.SDK_INT >= 31
        // 滚动进度只在绘制期读；null 视作 0（预览 / 无进度宿主）
        fun pillProgress(): Float = (blurProgress?.value ?: 0f).coerceIn(0f, 1f)
        // 底材两端色与玻璃染色随主题在组合期取好（DrawScope 读不了组合上下文），
        // 插值进度留到 draw 阶段
        val pillSurface = MaterialTheme.colorScheme.surface
        val pillBackground = MaterialTheme.colorScheme.background
        // 玻璃表面染色：叠原 surface 底色——白玻璃
        val surfaceTint = pillSurface.copy(alpha = LiquidGlassStyle.Pill.tintAlpha)
        // 胶囊 = 按压反馈 + 柔影 + 手势 + 内容；底材单独拆成底层子 Box——玻璃要随
        // blurProgress 淡入，只有独立子图层才能只淡玻璃底、不牵连文字内容
        //（graphicsLayer 若放在同一链上，alpha 会连子内容一起淡掉）
        Box(
            modifier = Modifier
                .height(WeekPillHeight)
                // 按压反馈：玻璃模式官方 Q弹按压放大（软弹簧果冻过冲 + 沿轮廓加色提亮），
                // 非玻璃维持 pressFeedback（封装见 ui.theme LiquidGlass）；两态同取 1.10。
                // 接在带 clip 的图层之前，投影随组件一起放大
                .glassPressFeedback(
                    interactionSource = pressInteraction,
                    shape = PillShape,
                    backdrop = backdrop,
                )
                // 投影随模糊进度长出；graphicsLayer 在 draw 阶段读进度 State，不引发重组
                .graphicsLayer {
                    shape = PillShape
                    clip = true
                    shadowElevation = 45.dp.toPx() * pillProgress()
                    spotShadowColor = Color.Black.copy(alpha = 0.2f)
                }
                .clickable(
                    interactionSource = pressInteraction,
                    indication = null,
                    onClick = {
                        // 新一轮交互作废上一次尚未落地的延后换周
                        pendingJumpWeek = null
                        menuExpanded = true
                        onMenuExpandedChange(true)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            // 底材层：扁平灰底 → surface 的原渐变原样保留（玻璃模式也不改终点）——
            // 玻璃输出本身不透明（采样层画了页面底色），直接在不透明底材上淡入即可：
            // 中段是两张不透明图像的干净叠化，不会有半透明底透出原始网格的发暗感。
            // 底材插值与玻璃 alpha 都在 draw 阶段读进度，滚动期间零重组
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        drawRect(lerp(pillBackground, pillSurface, pillProgress()))
                    }
                    .then(
                        if (useGlass && backdrop != null) {
                            Modifier
                                .graphicsLayer { alpha = pillProgress() }
                                .drawBackdrop(
                                    backdrop = backdrop,
                                    shape = { PillShape },
                                    effects = {
                                        blur(LiquidGlassStyle.Pill.blurRadius.toPx())
                                        if (Build.VERSION.SDK_INT >= 33) {
                                            lens(
                                                LiquidGlassStyle.Pill.lensHeight.toPx(),
                                                LiquidGlassStyle.Pill.lensDepth.toPx(),
                                            )
                                        }
                                    },
                                    onDrawSurface = { drawRect(surfaceTint) },
                                )
                        } else {
                            Modifier
                        },
                    ),
            )
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // 学期前有多页「第 0 周」（原始周号 ≤ 0：今周~开学前一周），胶囊一律显示「第 0 周」
                    text = stringResource(R.string.week_format, selectedWeek.coerceAtLeast(0)),
                    style = ClassppTextTokens.weekPill,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_down),
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = arrowRotation },
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // 屏幕水平居中 + 垂直固定：卡片顶部落在胶囊底部下方 WeekPickerGap（展开/折叠态一致）。
        // DropdownMenu 的位置策略锚定胶囊左缘无法满足居中，故用自定义定位器。
        // 窗口含投影留白（顶部/左右/底部都按可用空间分配），定位按窗口计算需将其扣除：
        // 卡片顶部仍落在胶囊底部下方 WeekPickerGap
        val density = LocalDensity.current
        val gapPx = with(density) { (WeekPickerGap - geometry.topPadding).roundToPx() }
        val sidePaddingPx = with(density) { geometry.sidePadding.toPx() }
        val topPaddingPx = with(density) { geometry.topPadding.toPx() }
        // 缩放锚点跟随胶囊实际位置：定位回调存下「弹层落位 + 胶囊实际中心」快照
        // （胶囊会随头部展开/折叠移动，常量推算的固定锚点会脱靶），锚点在 draw 阶段
        // 读快照实时跟随——胶囊动到哪，锚点跟到哪
        val anchorSnapshot = remember { mutableStateOf(PopupAnchorSnapshot.Zero) }
        val positionProvider = remember(gapPx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    // 尺寸预算已保证放得下；clamp 是 dp→px 取整 / insets 抖动的兜底，
                    // 避免窗口贴出屏幕底部后被系统裁切
                    val offset = IntOffset(
                        x = (windowSize.width - popupContentSize.width) / 2,
                        y = (anchorBounds.bottom + gapPx).coerceIn(
                            minimumValue = 0,
                            maximumValue = (windowSize.height - popupContentSize.height)
                                .coerceAtLeast(0),
                        ),
                    )
                    // anchorBounds 即胶囊自身的窗口坐标（本 Popup 挂在胶囊容器上）
                    anchorSnapshot.value = PopupAnchorSnapshot(
                        contentOffset = offset,
                        pillCenter = anchorBounds.center,
                    )
                    return offset
                }
            }
        }

        // 开关动画与菜单族同款（rememberPopupMotion）：果冻曲线 + 只淡内容行 + 尾淡兜底；
        // 缩放锚点跟随胶囊实际中心（见上方 anchorSnapshot），收起播完才移除 Popup
        if (motion.shown) {
            Popup(
                onDismissRequest = { closeMenu() },
                popupPositionProvider = positionProvider,
                // focusable：返回键收起；dismissOnClickOutside 默认开启
                properties = PopupProperties(focusable = true),
            ) {
                WeekPickerCardShell(
                    selectedWeek = selectedWeek,
                    currentWeek = currentWeek,
                    weekRange = weekRange,
                    hazeState = hazeState,
                    geometry = geometry,
                    blurOrigin = { size ->
                        // 胶囊中心在卡面坐标下的位置（扣一圈投影留白），随快照实时跟随
                        TransformOrigin(
                            pivotFractionX =
                                (anchorSnapshot.value.pillCenter.x -
                                    anchorSnapshot.value.contentOffset.x - sidePaddingPx) /
                                size.width.coerceAtLeast(1f),
                            pivotFractionY =
                                (anchorSnapshot.value.pillCenter.y -
                                    anchorSnapshot.value.contentOffset.y - topPaddingPx) /
                                size.height.coerceAtLeast(1f),
                        )
                    },
                    blurScale = motion::blurScale,
                    contentAlpha = { motion.contentAlpha.value },
                    blurProgress = motion::blurProgress,
                    // 排在外壳 padding/裁剪/投影/毛玻璃之外：整卡一起缩放（含退场尾淡），
                    // 缩放锚点 = 胶囊实际中心（在卡片上方、窗口外 → 负分数锚点），
                    // 卡片从胶囊中心向下「长」出来，胶囊移动时锚点随快照实时跟随
                    modifier = Modifier.popupMotionScale(
                        motion,
                        origin = { size ->
                            TransformOrigin(
                                pivotFractionX =
                                    (anchorSnapshot.value.pillCenter.x -
                                        anchorSnapshot.value.contentOffset.x) /
                                    size.width.coerceAtLeast(1f),
                                pivotFractionY =
                                    (anchorSnapshot.value.pillCenter.y -
                                        anchorSnapshot.value.contentOffset.y) /
                                    size.height.coerceAtLeast(1f),
                            )
                        },
                    ),
                    onWeekSelected = { week ->
                        // 所有周次都延后到退场播完（onExitFinished）再落地，避免换周帧
                        // 与退场同帧；点当前周也会先播完退场再写回同值（无副作用）
                        pendingJumpWeek = week
                        closeMenu()
                    },
                    // 投影留白区的点按 = 点卡片外：走与 dismissOnClickOutside 相同的收起路径
                    onDismiss = { closeMenu() },
                )
            }
        }
    }
}


@Composable
private fun WeekPickerCard(
    selectedWeek: Int,
    currentWeek: Int?,
    weekRange: IntRange,
    geometry: WeekPickerGeometry,
    onWeekSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val weeks = remember(weekRange) { weekRange.toList() }
    val cellShape = remember(geometry.cellCorner) { RoundedRectangle(geometry.cellCorner) }
    // 字号/行高仍只来自 token：等比缩放形态由 TextTokens 提供（业务代码不碰裸字号）
    val numberStyle = remember(geometry.visualScale) {
        ClassppTextTokens.weekGridNumberScaled(geometry.visualScale)
    }
    // 矮到连可读下限都放不下时：卡片高度锁在预算内、网格内部滚动，绝不靠压缩底行硬塞
    val scrollModifier = if (geometry.scrollable) {
        Modifier
            .heightIn(max = geometry.maxCardHeight)
            .verticalScroll(rememberScrollState())
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .then(scrollModifier)
            .padding(horizontal = geometry.cellSpace, vertical = geometry.cellSpace),
        verticalArrangement = Arrangement.spacedBy(geometry.cellSpace),
    ) {
        weeks.chunked(6).forEach { rowWeeks ->
            Row(horizontalArrangement = Arrangement.spacedBy(geometry.cellSpace)) {
                rowWeeks.forEach { week ->
                    WeekCell(
                        week = week,
                        selected = week == selectedWeek,
                        isCurrent = week == currentWeek,
                        size = geometry.cellSize,
                        shape = cellShape,
                        numberStyle = numberStyle,
                        onClick = { onWeekSelected(week) },
                    )
                }
            }
        }
    }
}

/**
 * 弹窗卡片外壳：[PopupGlassCard] 共享配方（投影 / 毛玻璃 / 分层动画契约都在那里，
 * 圆角、投影都随 [WeekPickerGeometry.visualScale] 等比缩放）+ 周数网格内容；四周透明留白
 * （顶部固定 [WeekPickerShadowPadding]，左右/底部按剩余空间分配）容纳投影，
 * 避免被 Popup 窗口边界裁剪。
 * [hazeState] 非空时在白底之上叠加 Haze 毛玻璃（采样课表网格 hazeSource，跨窗口生效）。
 */
@Composable
private fun WeekPickerCardShell(
    selectedWeek: Int,
    currentWeek: Int?,
    weekRange: IntRange,
    hazeState: HazeState?,
    geometry: WeekPickerGeometry,
    blurOrigin: (Size) -> TransformOrigin,
    blurScale: () -> Float,
    contentAlpha: () -> Float,
    blurProgress: () -> Float,
    onWeekSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardShape = remember(geometry.cardCorner) { RoundedRectangle(geometry.cardCorner) }
    Box(modifier = modifier) {
        // 留白层的点按语义等同「点卡片外」→ 收起弹窗：顶部 50dp 留白会盖住胶囊下半，
        // 不接住点击就是「点了没反应」的死区（窗口无法把点击穿透给下层窗口）
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        )
        PopupGlassCard(
            shape = cardShape,
            shadowElevation = geometry.shadowElevation,
            spotShadowColor = Color.Black.copy(alpha = 0.2f),
            hazeState = hazeState,
            // 投影留白垫在缩放图层之内、卡壳之外（裁切线 = Popup 窗口边缘）：顶部固定、
            // 左右/底部为剩余空间——投影高度也随 scale 缩，留白比例在任何尺寸下都成立；
            // 没有这层留白投影会整体画在窗口外被裁掉，卡片落位也会偏移（gapPx 按窗口含留白反推）
            modifier = Modifier
                .padding(
                    start = geometry.sidePadding,
                    top = geometry.topPadding,
                    end = geometry.sidePadding,
                    bottom = geometry.bottomPadding,
                )
                // 卡片区域兜住点击：卡内空白/格间距的点击被吞掉，不冒泡到留白层收起弹窗；
                // 方格点击仍由子级优先处理（子级 click 先消费 down，本层不会触发）
                .pointerInput(Unit) { detectTapGestures { /* 卡内空白点按：吞掉 */ } },
            blurOrigin = blurOrigin,
            blurScale = blurScale,
            contentAlpha = contentAlpha,
            blurProgress = blurProgress,
            // 周数网格按内容自适应宽度，不吃外壳最小约束（与原壳行为一致）
            propagateMinConstraints = false,
        ) {
            WeekPickerCard(
                selectedWeek = selectedWeek,
                currentWeek = currentWeek,
                weekRange = weekRange,
                geometry = geometry,
                onWeekSelected = onWeekSelected,
            )
        }
    }
}

/**
 * 弹层锚点快照：弹层内容的窗口落位 + 胶囊实际窗口中心（均窗口坐标）。
 * 由 Popup 定位回调在每次布局时写入，缩放锚点（含毛玻璃反向缩放）在 draw 阶段读取——
 * 胶囊随头部展开/折叠移动时，锚点与弹层同步跟随，零重组。
 */
private data class PopupAnchorSnapshot(
    val contentOffset: IntOffset,
    val pillCenter: IntOffset,
) {
    companion object {
        val Zero = PopupAnchorSnapshot(IntOffset.Zero, IntOffset.Zero)
    }
}

/** 周数网格中的一格：方形圆角（随 scale 缩放）；选中态主色底白字，本周（未选中）浅蓝底蓝字 */
@Composable
private fun WeekCell(
    week: Int,
    selected: Boolean,
    isCurrent: Boolean,
    size: Dp,
    shape: Shape,
    numberStyle: TextStyle,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.week_format, week)
    // 按压态的唯一来源：pressFeedback 与 clickable 共用（涟漪由按压反馈取代，故 clickable 不再要 Indication）
    val press = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(size)
            // 按压反馈接在 clip 之前：缩放/提亮作用于整格，圆角裁剪在其内侧
            .pressFeedback(press, shape)
            .clip(shape)
            .background(
                when {
                    selected -> colors.primary
                    isCurrent -> colors.primaryContainer
                    else -> MaterialTheme.colorScheme.onPrimaryContainer
                },
            )
            .semantics { contentDescription = description }
            .clickable(interactionSource = press, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = week.toString(),
            style = numberStyle.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = when {
                selected -> colors.onPrimary
                isCurrent -> colors.primary
                else -> colors.onSurfaceVariant
            },
        )
    }
}

@Preview(showBackground = true, name = "展开态")
@Composable
private fun ScheduleHeaderExpandedPreview() {
    ClassppTheme {
        ScheduleHeader(
            collapseFraction = 0f,
            date = previewDate(),
            selectedWeek = 2,
            onWeekSelected = {},
            onEditClick = {},
            onSettingsClick = {},
        )
    }
}

@Preview(showBackground = true, name = "折叠态")
@Composable
private fun ScheduleHeaderCollapsedPreview() {
    ClassppTheme {
        ScheduleHeader(
            collapseFraction = 1f,
            date = previewDate(),
            selectedWeek = 2,
            onWeekSelected = {},
            onEditClick = {},
            onSettingsClick = {},
        )
    }
}

/** 7 天视图：星期行七等分（含周六/周日），与 7 列网格对齐 */
@Preview(showBackground = true, name = "展开态 · 7 天", widthDp = 411)
@Composable
private fun ScheduleHeaderSevenDayPreview() {
    ClassppTheme {
        ScheduleHeader(
            collapseFraction = 0f,
            date = previewDate(),
            selectedWeek = 2,
            onWeekSelected = {},
            onEditClick = {},
            onSettingsClick = {},
            daysPerWeek = 7,
        )
    }
}

// 2026-03-10 周二
private fun previewDate(): Date =
    Calendar.getInstance().apply { set(2026, Calendar.MARCH, 10) }.time
