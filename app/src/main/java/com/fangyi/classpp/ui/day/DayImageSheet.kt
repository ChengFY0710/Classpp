package com.fangyi.classpp.ui.day

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.PopupMenuItem
import com.fangyi.classpp.ui.components.PopupMenuPopup
import com.fangyi.classpp.ui.components.PopupMenuSection
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.components.menuGrowthForAnchor
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.DayImageCellShape
import com.fangyi.classpp.ui.theme.classppColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 网格内部规格（对照设计稿）：横纵间距 12dp、单元约 3:5、圆角 16dp
// （平滑圆角，收在 ui.theme 的 DayImageCellShape，与全局形状常量同源）；
// 自适应列数的最小单元宽——常见手机（≈390-430dp 宽）出 3 列
private val GridSpacing = 12.dp
private val MinCellWidth = 110.dp
private const val CellAspectRatio = 3f / 5f

// 一次最多导入张数（PickMultipleVisualMedia 要求 ≥ 2）
private const val MaxPickCount = 20

/**
 * 日程页「选择图片」浮层：网格展示已导入的图片，点一张即设为日程页背景并关闭；
 * 长按一张弹上下文菜单（单组单项「删除」），删除对应图片（文件与名单由日程页经
 * [onDelete] 落盘清理）。当前正在显示的背景（[currentImage]，随机命中或手动选中）
 * 不可删——菜单删除项置灰（negative 色）不可点，背景图被删的跳变与删除时序一并规避。
 *
 * 网格上方一张 [SettingsCard] 双行开关卡：「随机轮播」开启后每天从图库随机抽一张当
 * 背景——抽取逻辑（按当天日期做种子）在日程页，本浮层只管开关状态与落盘
 * （[onRandomChange]）；「壁纸视差」控制 01 态背景图随重力倾斜微移（[onParallaxChange]，
 * 逻辑在 ui.motion 的 TiltParallax）——两行都只管开关状态与落盘，效果本体不在浮层；
 * 无图可随机，空态不显示卡片。卡片与下方网格 16dp（对照设计稿）。
 *
 * 顶栏两颗胶囊走 OverlaySheet 自带规格：左上「导入」= 确认胶囊（同「新建」按钮的
 * 蓝底加号），唤起系统相册（Photo Picker，[ActivityResultContracts.PickVisualMedia.ImageOnly]
 * 从源头限制只能选图片，一次至多 [MaxPickCount] 张）；右上「关闭」= 动作胶囊（白底 ✕ 圆）。
 *
 * 相册 URI 是临时授权、跨重启失效，所以选择结果先拷贝进应用内部存储（[DayImageStore]，
 * IO 线程解码转正压缩）再入列——浮层只管选择流程，状态与落盘由日程页持有。
 *
 * 网格不写死列数：按内容区可用宽除以 [MinCellWidth] 动态求列数，单元宽扣除间隙均分
 * （FlowRow 而非 LazyVerticalGrid——OverlaySheet 的滚动内容列自带 verticalScroll，
 * 懒网格放进去会被无限高度约束测量直接崩溃）。
 *
 * 两段式关闭同既有浮层：[onDismiss]（关闭按钮 / 遮罩 / 下拉 / 点图选中）只翻转 visible，
 * 出场动画播完回调 [onDismissed]，调用方此刻才把浮层移出组合。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun DayImageSheet(
    visible: Boolean,
    images: List<String>,
    // 当前正在显示的背景图（随机轮播的当日命中或手动选中）：它的删除项置灰不可点——
    // 背景图被删后回退默认图属破坏性跳变，且删除它正是曾触发崩溃的最坏时序
    currentImage: String?,
    randomEnabled: Boolean,
    onRandomChange: (Boolean) -> Unit,
    parallaxEnabled: Boolean,
    onParallaxChange: (Boolean) -> Unit,
    onImported: (List<String>) -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(enabled = visible, onBack = onDismiss)

    // 长按菜单目标：文件名 + 该图当时的窗口坐标（菜单锚点）。收起只翻 menuVisible，
    // 锚点保留到退场播完（onExitFinished 清）——退场 150ms 里菜单内容稳定、动作仍可命中
    var menuTarget by remember { mutableStateOf<MenuTarget?>(null) }
    var menuVisible by remember { mutableStateOf(false) }
    // 长按菜单的毛玻璃——浮层滚动列是采样源，顶栏磨砂与菜单的背景模糊同源（同 TodoGroupsSheet
    // 的 sheetHazeState 接法：OverlaySheet 收到共享实例即复用，不再自建）。
    // 曾因跨窗口采样在「删除」时触发 RenderThread 栈溢出而短暂摘除，随「当前背景图不可删」
    // （[currentImage]，删除项置灰不可点）落地后恢复——删除不再驱动网格与菜单退场同帧重组
    val sheetHazeState = rememberHazeState()

    // 系统相册（Photo Picker）：ImageOnly 只出图片，视频等文件类型不可选
    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MaxPickCount),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val imported = uris.mapNotNull { DayImageStore.importImage(context, it) }
            withContext(Dispatchers.Main) {
                if (imported.isNotEmpty()) onImported(imported)
            }
        }
    }

    OverlaySheet(
        title = stringResource(R.string.day_image_sheet_title),
        confirmLabel = stringResource(R.string.day_image_import),
        confirmIcon = R.drawable.ic_add_circle,
        onConfirm = {
            pickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        rightAction = SheetTopAction(
            label = stringResource(R.string.day_image_close),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = onDismiss,
        ),
        onDismiss = onDismiss,
        visible = visible,
        onDismissed = onDismissed,
        // 纯网格浮层没有输入框，键盘不必让位（同待办分组浮层）
        imeBehavior = SheetImeBehavior.IgnoreIme,
        // 采样源交给容器（同 TodoGroupsSheet）：顶栏磨砂与叠在浮层上的长按菜单同源
        hazeState = sheetHazeState,
        modifier = modifier,
    ) {
        if (images.isEmpty()) {
            DayImageEmptyHint(modifier = Modifier.fillMaxWidth())
        } else {
            // 开关卡（与下方网格 16dp，对照设计稿）；卡片到顶栏的留白由
            // OverlaySheet 的 TopBarHeight 余量承担。整行可点切换，整行按压反馈铺满卡片
            SettingsCard(
                hazeState = sheetHazeState,
                items = listOf(
                    SettingsCardItem.Toggle(
                        label = stringResource(R.string.day_image_random_title),
                        description = stringResource(R.string.day_image_random_desc),
                        checked = randomEnabled,
                        onCheckedChange = onRandomChange,
                    ),
                    SettingsCardItem.Toggle(
                        label = stringResource(R.string.day_image_parallax_title),
                        description = stringResource(R.string.day_image_parallax_desc),
                        checked = parallaxEnabled,
                        onCheckedChange = onParallaxChange,
                    ),
                ),
            )
            Spacer(Modifier.height(16.dp))
            DayImageGrid(
                images = images,
                onSelect = onSelect,
                onLongClick = { name, anchor ->
                    menuTarget = MenuTarget(name, anchor)
                    menuVisible = true
                },
            )
        }
    }

    menuTarget?.let { target ->
        ImageMenuPopup(
            anchor = target.anchor,
            expanded = menuVisible,
            onDismiss = { menuVisible = false },
            onDelete = {
                menuVisible = false
                onDelete(target.name)
            },
            // 当前背景图不可删：删除项置灰（negative 色）不可点
            deleteEnabled = target.name != currentImage,
            hazeState = sheetHazeState,
            // 锚点保留到退场播完，同 ScheduleScreen 的 menuAnchor
            onExitFinished = { menuTarget = null },
        )
    }
}

/** 图片网格：按可用宽动态求列数，单元宽扣除 12dp 间隙均分，横纵间距一致；
 *  单击选中，长按弹上下文菜单（[onLongClick] 带该图窗口坐标作菜单锚点） */
@Composable
private fun DayImageGrid(
    images: List<String>,
    onSelect: (String) -> Unit,
    onLongClick: (String, Rect) -> Unit,
) {
    val context = LocalContext.current
    // 内容区自带页边距（OverlaySheet 的 PageHorizontalSpacing），这里只管网格内部规格
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = (maxWidth / MinCellWidth).toInt().coerceAtLeast(1)
        val cellWidth = (maxWidth - GridSpacing * (columns - 1)) / columns
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(GridSpacing),
            verticalArrangement = Arrangement.spacedBy(GridSpacing),
        ) {
            images.forEach { name ->
                val press = remember { MutableInteractionSource() }
                val bounds = remember(name) { BoundsHolder() }
                AsyncImage(
                    model = DayImageStore.imageFile(context, name),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    // 按压反馈手动配对（长按菜单要 combinedClickable，pressClickable 只接单击），
                    // 反馈与 clip 的相对位置同 pressClickable 惯例：反馈在外侧（放大不被裁掉）、
                    // 提亮轮廓与圆角对齐
                    modifier = Modifier
                        .width(cellWidth)
                        .aspectRatio(CellAspectRatio)
                        .onGloballyPositioned { bounds.value = it.boundsInWindow() }
                        .pressFeedback(press, DayImageCellShape)
                        .clip(DayImageCellShape)
                        .combinedClickable(
                            interactionSource = press,
                            indication = null,
                            onClick = { onSelect(name) },
                            onLongClick = { onLongClick(name, bounds.value) },
                        ),
                )
            }
        }
    }
}

/** 空列表态：网格没有内容时给一条引导文案，否则浮层只剩顶栏一片空白 */
@Composable
private fun DayImageEmptyHint(modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(top = 140.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            text = stringResource(R.string.day_image_empty_hint),
            style = ClassppTextTokens.emptyHintLarge,
            color = MaterialTheme.classppColors.secondaryText,
        )
    }
}

/** 菜单上缘压进图片底缘的量：菜单贴着图片长出来，而不是悬空在下方（同课表右键菜单） */
private val MenuOverlap = 12.dp

/** 菜单左缘相对图片左缘的内缩（同课表右键菜单） */
private val MenuLeftInset = 8.dp

/**
 * 长按图片弹出的上下文菜单（单组单项「删除」）：[PopupMenuPopup] 共享宿主
 * （同课表右键菜单的 ContextMenuPopup 范式）。
 *
 * 定位只认长按那张图的窗口坐标 [anchor]（菜单贴它的左下角长出，投影留白与越界钳制由
 * 宿主处理），生长角按锚点所在半屏现算（[menuGrowthForAnchor]）。
 * 生命周期走「锚点 + 可见性」两态（同 ScheduleScreen）：宿主收起只翻 [expanded]，锚点
 * 保留到退场播完（[onExitFinished] 后再清）——删除动作照旧与收起同帧执行，视觉移除
 * 交给退场动画；退场 150ms 里菜单内容稳定、行点击动作仍可命中。
 * [hazeState] 传浮层的共享采样源（滚动列）：菜单卡对背后的网格内容做毛玻璃
 * （跨 Popup 窗口采样，同周数弹窗/排序菜单材质）；null 退化为不透明白卡（@Preview 用）。
 * [deleteEnabled] = false（长按的是当前背景图）时删除项置灰（negative 色）不可点。
 */
@Composable
private fun ImageMenuPopup(
    anchor: Rect,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    deleteEnabled: Boolean,
    hazeState: HazeState?,
    onExitFinished: () -> Unit = {},
) {
    val density = LocalDensity.current
    val overlapPx = with(density) { MenuOverlap.roundToPx() }
    val insetPx = with(density) { MenuLeftInset.roundToPx() }
    val windowWidthPx = LocalWindowInfo.current.containerSize.width.takeIf { it > 0 }
        ?: LocalView.current.width
    PopupMenuPopup(
        expanded = expanded,
        onDismiss = onDismiss,
        sections = listOf(
            PopupMenuSection(
                items = listOf(
                    PopupMenuItem(
                        label = stringResource(R.string.day_image_delete),
                        icon = R.drawable.ic_delete,
                        enabled = deleteEnabled,
                        onClick = onDelete,
                    ),
                ),
            ),
        ),
        cardPosition = { _, _, _ ->
            IntOffset(
                x = anchor.left.roundToInt() + insetPx,
                y = anchor.bottom.roundToInt() - overlapPx,
            )
        },
        menuGrowth = menuGrowthForAnchor(anchor.center.x, windowWidthPx),
        hazeState = hazeState,
        onExitFinished = onExitFinished,
    )
}

/** 长按菜单的目标：图片文件名 + 长按那一刻该图的窗口坐标（菜单锚点） */
private class MenuTarget(val name: String, val anchor: Rect)

/**
 * 图片窗口坐标（长按弹菜单的锚点）：只在长按回调里读，故存普通字段而非 Compose state
 * ——写在 onGloballyPositioned 里也不会像 state 那样让布局阶段触发重组（CourseGrid 同款）。
 */
private class BoundsHolder {
    var value: Rect = Rect.Zero
}
