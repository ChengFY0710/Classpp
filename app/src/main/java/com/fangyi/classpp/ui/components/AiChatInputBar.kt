package com.fangyi.classpp.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.LiquidGlassStyle
import com.fangyi.classpp.ui.theme.RowCardPaddingHorizontal
import com.fangyi.classpp.ui.theme.RowCardPaddingVertical
import com.fangyi.classpp.ui.theme.SheetCardShape
import com.fangyi.classpp.ui.theme.SheetFieldHeight
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.glassPressFeedback
import com.fangyi.classpp.ui.theme.glassSurface
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.shadow.Shadow
import dev.chrisbanes.haze.HazeState
import java.io.File
import kotlin.math.roundToInt

/** 动作图标边长（发送 / 停止 / 添加，均为动作图标档 30dp） */
private val ActionIconSize = 30.dp

/** 单行文本的最小排版高度：与 + 号同高，保证单行时文字与 + 号垂直同心 */
private val InputTextMinHeight = ActionIconSize

/** 附件菜单与 + 号锚点的间距 */
private val AddMenuGap = 8.dp

/** 文本与 + 号之间的间距 */
private val InnerContentGap = 0.dp

/** 发送圆球按压缩放：同顶栏胶囊 1.10（玻璃 pressScale / 普通 pressFeedback 共用） */
private const val SendPressScale = 1.10f

/** 输入框最多显示的行数：超过后卡片不再长高，输入框内部上下滚动 */
private const val MaxInputLines = 12

/**
 * 发送圆球的玻璃外投影：drawBackdrop 默认 24dp / 黑 10%，对 60dp 圆球偏浓；
 * 降到 12dp / 下移 2dp / 黑 6%，保留轻微悬浮感。
 */
private val SendGlassShadow = Shadow(
    radius = 24.dp,
    offset = DpOffset(0.dp, 2.dp),
    color = Color.Black.copy(alpha = 0.03f),
)

private val TextSentSapcing = 10.dp

/**
 * 非 focusable 菜单收起后，极短窗口内忽略「+」的打开请求：点 + 收起菜单时，Popup 的
 * ACTION_OUTSIDE 与落到 + 上的点击是同一个事件（非 focusable 窗口不消费外部触摸），
 * 不拦的话菜单会「刚收起又立刻重开」。
 */
private const val MenuReopenGuardMillis = 200L

/** 输入卡在窗口里的坐标（菜单锚点）：布局回调逐帧写普通字段，不写 snapshot state */
private class MenuAnchorBounds {
    var value: Rect = Rect.Zero
}

private fun Rect.toIntRect(): IntRect = IntRect(
    left = left.roundToInt(),
    top = top.roundToInt(),
    right = right.roundToInt(),
    bottom = bottom.roundToInt(),
)

/**
 * AI 对话底部输入栏：左侧输入卡 + 右侧 60dp 发送圆球。
 *
 * 输入卡沿用 [SheetTextField] 的卡壳与聚焦规格：
 * - 高度下限 [SheetFieldHeight]（60dp），[SheetCardShape] 连续圆角，底色 `colorScheme.surface`；
 * - 未聚焦无描边；聚焦时 2dp `colorScheme.primary` 描边 + 主色光标，描边宽度与透明度
 *   共用一条 0→1 进度（[Motion.FastMillis] + [Motion.Decelerate]），进度为 0 时不挂 border；
 * - 内距 16 / 14，值文字 `fieldValue`（16sp Medium primary），占位 `fieldPlaceholder`，
 *   仅空值且未聚焦时显示（聚焦时光标可见、不显占位）；
 * - 点击已聚焦的输入框会重新 show 一次键盘（Compose 不会自动拉起，同 [SheetTextField]）。
 *
 * 多行：不设 `singleLine`、不过滤换行，长文自动换行、粘贴换行保留，卡片随行数长高，
 * 最多显示 12 行（[MaxInputLines]）——超过后卡片不再长高，输入框内部可上下滑动查看
 * 超出内容；单行时文本与 + 号在 60dp 内垂直居中，多行时 + 号与圆球都贴输入卡底边，
 * 圆球直径恒定 60dp。值区宽度固定为「卡宽 - 内距 - + 号 - 间距」，不会横向滚动。
 *
 * 发送圆球三态（[sending] 优先）：无文字=surface 底 + onSurface 箭头、不可点；
 * 有非空文字**或有待发送图片**=primary 底 + onPrimary 箭头、点击 [onSend]；发送中=
 * primary 底 + onPrimary 停止图标、点击 [onStop]。空白字符不算可发送（有图可发）。
 *
 * 待发送图片预览条（[pendingImages] 非空时）：输入卡内文本行上方一排 64dp 圆角缩略图
 * （Coil 本地加载），右上角小删除圆钮点击 [onRemoveImage]；纯图无文字也允许发送
 * （图片即提问）。
 *
 * + 号点击经 [PopupMenuPopup] 弹出 [PopupMenuCard]：菜单向上浮在输入卡外、右对齐 + 号，
 * 纯文字「拍照 / 添加图片 / 添加文件」；选中项只收起菜单并回调 [onTakePhoto] /
 * [onAddImage] / [onAddFile]（拍照与选图的系统授权流程由宿主接，本组件不管）。
 * [hazeState] 非空时菜单卡走 Haze 磨砂
 * （宿主传浮层的 haze 采样源，与顶栏同源），null 退化为不透明白卡。
 *
 * 菜单是**非 focusable** 弹层：打开不抢窗口焦点，正在输入的键盘与 Compose 焦点都保留
 * （focusable 弹层会让系统收起 IME，菜单锚点还会跟着 imePadding 抬升的输入栏下移）。
 * 锚点固定在点 + 那一刻，inset 变化时菜单也不漂移；返回键由 [MenuBackHandler] 兜底收起。
 * 文本状态由宿主提升，不会丢失。
 *
 * 输入框带 `imeFieldTracking()`：AI 浮层里输入栏位于钉底槽、不在内容列 ImeScrollTracker
 * 的作用域内，此修饰符空转（键盘弹起时消息列表的跟随由 AiChatOverlay 的 IME 补偿负责）；
 * 直排宿主里同样空转。
 *
 * 玻璃材质：宿主按「更高级的玻璃材质」开关下发 [backdrop]（null = 普通材质）。非空时
 * 输入卡与发送圆球改 [glassSurface] 出底（通用 Pill 玻璃参数，染色 70%），圆球按压走
 * [glassPressFeedback]——玻璃 + API 31+ 为 pressScale Q 弹放大，否则维持 pressFeedback；
 * 主色圆球的内容色随之改用 [classppColors.onPrimaryGlass]（颜色规范）；发送圆球单独
 * 用更轻的玻璃外投影（[SendGlassShadow]），输入卡沿用库默认投影。null 时底材与
 * 按压反馈完全回退旧实现，[backdrop] 的采样层由宿主提供（OverlaySheet 钉底槽用
 * [LocalOverlayBottomGlassBackdrop]，与顶栏胶囊同源）。
 */
@Composable
fun AiChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    sendingPlaceholder: String = placeholder,
    sending: Boolean = false,
    enabled: Boolean = true,
    onSend: () -> Unit = {},
    onStop: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onAddImage: () -> Unit = {},
    onAddFile: () -> Unit = {},
    /** 待发送图片（已导入的压缩副本文件）；非空时输入卡上方渲染预览条 */
    pendingImages: List<File> = emptyList(),
    onRemoveImage: (File) -> Unit = {},
    // 附件菜单的磨砂采样源：非空时 PopupMenuCard 走 Haze 磨砂，null 为不透明白卡
    hazeState: HazeState? = null,
    backdrop: Backdrop? = null,
) {
    // 卡级焦点：聚焦描边、占位显隐共用；输入框禁用（发送中）时自然回落 false
    var cardFocused by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    // 输入卡在窗口里的实时坐标：点 + 时快照成菜单锚点（见 frozenMenuAnchor）
    val anchorBounds = remember { MenuAnchorBounds() }
    // 菜单展开期间的固定锚点：非 focusable 菜单不会让键盘收起；但用户若按返回键收键盘、
    // 或系统改 inset，输入栏仍会下移——锚点固定在点 + 那一刻，菜单才不会跟着滑下去
    var frozenMenuAnchor by remember { mutableStateOf<IntRect?>(null) }
    // 非 focusable 菜单点「+」时，Popup 先收到 ACTION_OUTSIDE 收起、本次点击又落到 + 上：
    // 记录收起时刻，极短窗口内忽略这次「打开」，否则菜单会立刻重开
    var menuDismissedAtMillis by remember { mutableLongStateOf(0L) }
    val interactionSource = remember { MutableInteractionSource() }
    val sendInteractionSource = remember { MutableInteractionSource() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            // 已聚焦时再点输入框不会拉起键盘，键盘被收起后靠这里唤回（同 SheetTextField）
            if (interaction is PressInteraction.Release) keyboard?.show()
        }
    }

    // 描边进度：宽度与透明度同走一条动画，聚焦生长淡入、失焦萎缩淡出，严格同步
    val strokeProgress by animateFloatAsState(
        targetValue = if (cardFocused) 1f else 0f,
        animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
        label = "aiChatInputStroke",
    )
    val strokeWidth = 2.dp * strokeProgress

    // 有字或有图都可发送：纯图无文字 = 图片即提问
    val canSend = enabled && !sending && (value.isNotBlank() || pendingImages.isNotEmpty())
    val actionEnabled = sending || canSend
    val valueStyle = MaterialTheme.classppTextStyles.fieldValue
    val hint = if (sending) sendingPlaceholder else placeholder
    // 主色玻璃按钮的内容色：玻璃模式用 onPrimaryGlass（颜色规范），普通材质仍 onPrimary
    val activeSendContentColor = if (backdrop != null) {
        MaterialTheme.classppColors.onPrimaryGlass
    } else {
        MaterialTheme.colorScheme.onPrimary
    }
    val takePhotoLabel = stringResource(R.string.ai_take_photo)
    val addImageLabel = stringResource(R.string.ai_add_image)
    val addFileLabel = stringResource(R.string.ai_add_file)
    val removeImageLabel = stringResource(R.string.cd_ai_remove_pending_image)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TextSentSapcing),
        // 多行时发送圆球贴输入卡底边，直径维持 SheetFieldHeight
        verticalAlignment = Alignment.Bottom,
    ) {
        // 输入卡整体作为菜单锚点：菜单向上浮在输入栏外，多行时也不会压住正文
        Box(
            modifier = Modifier
                .weight(1f)
                // 只记普通字段、不写 snapshot state：坐标逐帧变化，写 state 会让整条输入栏逐帧重组
                .onGloballyPositioned { anchorBounds.value = it.boundsInWindow() },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SheetFieldHeight)
                    .clip(SheetCardShape)
                    // 玻璃开关开启时由 drawBackdrop 出底；backdrop = null 回退实心 surface
                    .glassSurface(
                        shape = SheetCardShape,
                        containerColor = MaterialTheme.colorScheme.surface,
                        backdrop = backdrop,
                        style = LiquidGlassStyle.Pill,
                    )
                    .then(
                        // 进度归 0 后不挂 border：0 宽描边会被 Skia 画成 1px 发丝线（SheetTextField 同款）
                        if (strokeWidth > 0.dp) {
                            Modifier.border(
                                width = strokeWidth,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = strokeProgress),
                                shape = SheetCardShape,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .onFocusChanged { cardFocused = it.hasFocus },
                // 单行时把 60dp 下限多出的高度上下均摊，文本与 + 号居中；多行无余量时不生效
                verticalArrangement = Arrangement.Center,
            ) {
                // 待发送图片预览条：64dp 圆角缩略图一排，右上角删除圆钮（悬出 4dp，
                // 行的上内距让出空间避免被卡壳裁掉）
                if (pendingImages.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = RowCardPaddingHorizontal, end = RowCardPaddingHorizontal, top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pendingImages.forEach { image ->
                            Box(modifier = Modifier.size(64.dp)) {
                                AsyncImage(
                                    model = image,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clip(RoundedCornerShape(10.dp)),
                                )
                                Surface(
                                    onClick = { onRemoveImage(image) },
                                    shape = CircleShape,
                                    color = Color.Black.copy(alpha = 0.55f),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 4.dp, y = (-4).dp)
                                        .size(20.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_dismiss),
                                            contentDescription = removeImageLabel,
                                            tint = Color.White,
                                            modifier = Modifier.size(12.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = RowCardPaddingHorizontal,
                            vertical = RowCardPaddingVertical,
                        ),
                    // 多行时 + 号贴底；单行时文本区与 + 号同高（InputTextMinHeight），自然同心
                    verticalAlignment = Alignment.Bottom,
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        // 发送中禁用输入框 = 产品取舍（为回答让出屏幕，2026-11-10 确认保留）：
                        // 失去焦点时键盘一并收起，回答结束后不自动恢复，连续发送需再点输入框
                        enabled = enabled && !sending,
                        textStyle = valueStyle.copy(
                            // 空值本身不可见，占位由 decorationBox 负责
                            color = if (value.isEmpty()) Color.Transparent else valueStyle.color,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        // 不设 singleLine：宽度固定、超宽自动换行；最多 12 行，超出后
                        // 文本域自身纵向滚动，卡片高度随之封顶
                        maxLines = MaxInputLines,
                        interactionSource = interactionSource,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = { if (canSend) onSend() },
                        ),
                        modifier = Modifier
                            .weight(1f)
                            // 参与宿主的「键盘弹起把输入框滚到键盘上方」；无 tracker 时空转
                            .imeFieldTracking(),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = InputTextMinHeight),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (value.isEmpty() && !cardFocused && hint.isNotEmpty()) {
                                    Text(
                                        text = hint,
                                        style = MaterialTheme.classppTextStyles.fieldPlaceholder,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                    Spacer(Modifier.width(InnerContentGap))
                    Box(
                        modifier = Modifier
                            .size(ActionIconSize)
                            .pressClickable(
                                shape = CircleShape,
                                enabled = enabled,
                                role = Role.Button,
                            ) {
                                // 见 menuDismissedAtMillis：本次点击若同时触发了「点外部收起」，
                                // 不要再把它当成一次新的打开
                                if (
                                    SystemClock.uptimeMillis() - menuDismissedAtMillis >
                                    MenuReopenGuardMillis
                                ) {
                                    frozenMenuAnchor = anchorBounds.value.toIntRect()
                                    menuExpanded = true
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = stringResource(R.string.cd_ai_add_attachment),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .size(ActionIconSize),
                        )
                    }
                }
            }
            val gapPx = with(LocalDensity.current) { AddMenuGap.roundToPx() }
            // 菜单右缘对齐 + 号右缘（卡的右内距即 + 号到卡右缘的距离）
            val addIconInsetPx = with(LocalDensity.current) { RowCardPaddingHorizontal.roundToPx() }
            PopupMenuPopup(
                expanded = menuExpanded,
                onDismiss = {
                    menuDismissedAtMillis = SystemClock.uptimeMillis()
                    menuExpanded = false
                },
                // 退场播完才清锚点：退场期间沿用固定位置，菜单不会在收起时跳一下
                onExitFinished = { frozenMenuAnchor = null },
                // 非 focusable：菜单不抢窗口焦点，键盘与输入框焦点都保留。
                // 配合上面的固定锚点，菜单既不会把键盘顶掉，也不会跟着输入栏下移
                focusable = false,
                sections = listOf(
                    PopupMenuSection(
                        items = listOf(
                            PopupMenuItem(
                                label = takePhotoLabel,
                                onClick = {
                                    menuExpanded = false
                                    onTakePhoto()
                                },
                            ),
                            PopupMenuItem(
                                label = addImageLabel,
                                onClick = {
                                    menuExpanded = false
                                    onAddImage()
                                },
                            ),
                            PopupMenuItem(
                                label = addFileLabel,
                                onClick = {
                                    menuExpanded = false
                                    onAddFile()
                                },
                            ),
                        ),
                    ),
                ),
                cardPosition = { anchor, _, card ->
                    // 优先用点 + 那一刻的快照：键盘/inset 变化导致输入栏移动时菜单原地不动
                    val anchorRect = frozenMenuAnchor ?: anchor
                    IntOffset(
                        x = anchorRect.right - addIconInsetPx - card.width,
                        y = anchorRect.top - gapPx - card.height,
                    )
                },
                // 生长锚点按最终落位 + 冻结锚点（+ 号）反推：输入栏钉底时菜单自卡片
                // 下缘向上长，钳位后也不会跑到左上角
                anchor = frozenMenuAnchor?.let {
                    Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
                },
                hazeState = hazeState,
            )
        }
        Box(
            modifier = Modifier
                .size(SheetFieldHeight)
                // 按压反馈接在底材之前：玻璃模式 pressScale Q 弹放大、普通材质
                // pressFeedback，缩放与提亮覆盖整颗圆球（同设置页玻璃圆钮）
                .glassPressFeedback(
                    interactionSource = sendInteractionSource,
                    shape = CircleShape,
                    backdrop = backdrop,
                    pressedScale = SendPressScale,
                )
                .glassSurface(
                    shape = CircleShape,
                    containerColor = if (actionEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    backdrop = backdrop,
                    style = LiquidGlassStyle.Pill,
                    shadow = SendGlassShadow,
                )
                .clickable(
                    interactionSource = sendInteractionSource,
                    indication = null,
                    enabled = actionEnabled,
                    role = Role.Button,
                ) {
                    if (sending) onStop() else onSend()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(if (sending) R.drawable.ic_stop else R.drawable.ic_arrow_up),
                contentDescription = stringResource(
                    if (sending) R.string.cd_ai_stop else R.string.cd_ai_send,
                ),
                tint = if (actionEnabled) {
                    activeSendContentColor
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.size(ActionIconSize),
            )
        }
    }
}

// ---------- 预览 ----------

/** 设计稿四态：空 / 有文字 / 多行 / AI 回答中（聚焦描边需实机输入，静态预览不展示） */
@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390)
@Composable
private fun AiChatInputBarPreview() = ClassppTheme {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AiChatInputBar(
            value = "",
            onValueChange = {},
            placeholder = "点击输入和AI对话",
        )
        AiChatInputBar(
            value = "帮我",
            onValueChange = {},
        )
        AiChatInputBar(
            value = "输入内容输入内容输入内容输入内容输入内容输入内容输入内容输入内容输入内容输入内容",
            onValueChange = {},
        )
        AiChatInputBar(
            value = "",
            onValueChange = {},
            placeholder = "点击输入和AI对话",
            sendingPlaceholder = "AI正在回答",
            sending = true,
        )
    }
}

/** 附件菜单卡内容（Popup 窗口在 Preview 中不渲染，这里直接铺卡） */
@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390)
@Composable
private fun AiChatInputBarMenuPreview() = ClassppTheme {
    PopupMenuCard(
        sections = listOf(
            PopupMenuSection(
                items = listOf(
                    PopupMenuItem(label = "拍照"),
                    PopupMenuItem(label = "添加图片"),
                    PopupMenuItem(label = "添加文件"),
                ),
            ),
        ),
        modifier = Modifier.padding(24.dp),
    )
}

/** 超过 12 行：卡片封顶 12 行高，多余内容在文本域内部滚动（静态 Preview 只展示前 12 行） */
@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390)
@Composable
private fun AiChatInputBarMaxLinesPreview() = ClassppTheme {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        AiChatInputBar(
            value = (1..15).joinToString("\n") { "第${it}行输入内容" },
            onValueChange = {},
        )
    }
}
