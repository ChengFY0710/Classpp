package com.fangyi.classpp.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.theme.LiquidGlassStyle
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.RowCardPaddingHorizontal
import com.fangyi.classpp.ui.theme.RowCardPaddingVertical
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.glassPressFeedback
import com.fangyi.classpp.ui.theme.glassSurface
import com.kyant.backdrop.Backdrop
import dev.chrisbanes.haze.HazeState

/** 地址栏自然高度：上下 14dp 内距 + 24dp 图标（与 [SheetSearchField] 同规格） */
internal val WebAddressBarHeight = 52.dp

/** 地址栏右侧按钮的语义：空输入不显示 / 前往 / 更多 */
internal enum class WebAddressBarAction { None, Open, More }

/**
 * 地址栏右侧按钮的判定规则（纯函数，便于单测）：
 * - 输入为空（含纯空白）→ [WebAddressBarAction.None]，右侧不占位；
 * - 输入框未聚焦、且内容与 [openedUrl] 一致（忽略首尾空白）→ [WebAddressBarAction.More]，
 *   表示页面已停在该地址，右侧让位给 UA 切换菜单；
 * - 其余情况（正在输入 / 聚焦 / 内容与已打开地址不一致）→ [WebAddressBarAction.Open]，
 *   显示前往箭头。
 */
internal fun resolveWebAddressBarAction(
    inputFocused: Boolean,
    value: String,
    openedUrl: String?,
): WebAddressBarAction {
    val input = value.trim()
    if (input.isEmpty()) return WebAddressBarAction.None
    val opened = openedUrl?.trim().orEmpty()
    return if (!inputFocused && opened.isNotEmpty() && input == opened) {
        WebAddressBarAction.More
    } else {
        WebAddressBarAction.Open
    }
}

/**
 * 网页地址栏：视觉规格与 [SheetSearchField] 完全一致——白底胶囊卡（[PillShape]）、
 * 行内距水平 16 / 垂直 14、24dp 行首图标、12dp 间距、输入值 16sp Medium 蓝色
 * （fieldValue）、占位 16sp Medium 灰色（fieldPlaceholder）、聚焦 2dp 蓝描边与
 * SheetTextField 同走一条生长/萎缩动画；整条点击热区、键盘唤回与 glass 材质也沿用
 * 搜索框的取舍。
 *
 * 浏览器语义（与搜索框的差异）：
 * - 左侧恒显 ic_globe（24dp 灰，secondaryText）；[loading] 为 true 时原位换成
 *   primary 色加载圆环（24dp，2.5dp 描边），网页加载结束再换回 globe；
 * - 右侧动作由 [resolveWebAddressBarAction] 决定：输入中 / 与已打开地址不一致显示
 *   ic_arrow_circle_right_filled，点击（或键盘 Go）先收焦点再回调 [onOpen]，由宿主
 *   校验并加载；输入框未聚焦且内容等于 [openedUrl] 时显示 ic_more_circle，点击弹出
 *   UA 菜单（[PopupMenuPopup] + PopupMenuCard 勾选行：电脑版 / 手机版）；
 * - 加载圆环、前往箭头与更多按钮统一 primary 色；两个按钮的按压反馈直接落在
 *   24dp 图标圆上（玻璃模式 Q弹放大、普通模式硬节奏缩放提亮，同搜索框清空钮）；
 * - 输入框是 Uri 键盘 + Go 动作；singleLine 硬行并滤换行符。
 *
 * 状态完全由宿主持有：文字 [value] / [onValueChange]、当前 UA [desktopMode] /
 * [onDesktopModeChange]，以及「页面已打开到哪个地址」的 [openedUrl]。宿主在开始加载
 * 时把 openedUrl 更新为目标地址即可让右侧从箭头切到更多；跳转后的重定向 / 页内跳转
 * 由宿主同步 value 与 openedUrl。
 *
 * 玻璃材质：宿主按「更高级的玻璃材质」开关下发 [backdrop]（浮层经
 * LocalOverlayContentGlassBackdrop / LocalOverlayBottomGlassBackdrop 下发，null = 关闭）。
 * 非空时底材走 [glassSurface]（Pill 玻璃参数、surface 染色），按钮走
 * [glassPressFeedback]；null 时完全回退实心实现。聚焦蓝描边原样叠画在玻璃之上。
 *
 * 菜单与 Haze：菜单打开时把 [hazeState] 透传给 PopupMenuCard 做毛玻璃。切换 UA 会触发
 * 网页重载（采样源结构重写），因此菜单项的 [onDesktopModeChange] 经
 * [rememberPopupActionGate] 延后到弹层退场播完再执行，避免活采样期间同帧重写。
 *
 * 只读 / 状态形态：[statusText] 非空或 [readOnly] 为 true 时锁定编辑（网页冻结、读取 /
 * 识别期间）——不挂整条点击热区、不显示聚焦描边，输入区改为同字阶的只读 Text
 * （[statusText] 优先，否则显示 [value] 或占位），右侧固定为更多按钮但不可点。
 * 状态文案沿用 fieldValue 的 primary 色，与地址文字同视觉；不可点在识别/冻结期间会
 * 重载网页，误触会破坏抓取。
 */
@Composable
fun WebAddressBar(
    value: String,
    onValueChange: (String) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    /** 已打开页面的地址：null / 空串 = 尚未跳转；与输入值一致时右侧显示更多按钮 */
    openedUrl: String? = null,
    /** 网页加载中：左侧 globe 换成 primary 色加载圆环 */
    loading: Boolean = false,
    /** 只读状态文案：非空时隐藏输入框、显示该文案并锁定编辑（右侧固定为更多按钮） */
    statusText: String? = null,
    /** 只读但继续显示 [value]（网页冻结 / 识别期间锁定地址栏） */
    readOnly: Boolean = false,
    /** 输入框焦点变化（宿主持有「地址栏是否编辑中」时用） */
    onFocusChanged: ((Boolean) -> Unit)? = null,
    /** 当前 UA 模式（true = 电脑版），由宿主持有 */
    desktopMode: Boolean,
    onDesktopModeChange: (desktop: Boolean) -> Unit,
    /** 液态玻璃采样层：null = 普通实心材质（「更高级的玻璃材质」开关关闭或 API < 31） */
    backdrop: Backdrop? = null,
    /** 菜单毛玻璃采样态；未接入宿主时传 null（菜单退化为不透明白卡） */
    hazeState: HazeState? = null,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    // 文本区与右侧按钮各自持有交互源：右侧按钮每次切换图标时交互源不变，按压反馈连续
    val textInteraction = remember { MutableInteractionSource() }
    val actionInteraction = remember { MutableInteractionSource() }
    var inputFocused by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val menuActionGate = rememberPopupActionGate()

    // 已聚焦时再点文本区：Compose 不会重新拉起键盘，键盘一旦被收起就唤不回（上机踩过）；
    // 文本区以外的行内区域由下面的整条热区 pointerInput 兜住
    LaunchedEffect(textInteraction) {
        textInteraction.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) keyboard?.show()
        }
    }

    val locked = readOnly || statusText != null
    // 锁定瞬间输入框被移出组合：主动归位焦点形态、收起 UA 菜单，并通知宿主
    LaunchedEffect(locked) {
        if (locked) {
            inputFocused = false
            menuExpanded = false
            keyboard?.hide()
            onFocusChanged?.invoke(false)
        }
    }

    // 聚焦蓝描边：宽度与不透明度共用同一条 0→1 动画（与 SheetSearchField 同源同曲线）
    val strokeProgress by animateFloatAsState(
        targetValue = if (inputFocused && !locked) 1f else 0f,
        animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
        label = "webAddressBarStroke",
    )
    val strokeColor = MaterialTheme.colorScheme.primary
    val strokeWidth = 2.dp * strokeProgress
    // 锁定态固定为更多（不可点）；正常态按「聚焦 / 输入内容 vs 已打开地址」判定
    val action = if (locked) {
        if (statusText != null || value.isNotBlank()) {
            WebAddressBarAction.More
        } else {
            WebAddressBarAction.None
        }
    } else {
        resolveWebAddressBarAction(inputFocused, value, openedUrl)
    }
    val openLabel = stringResource(R.string.cd_web_address_open)
    val moreLabel = stringResource(R.string.cd_web_address_more)
    val loadingLabel = stringResource(R.string.cd_web_address_loading)

    fun openAddress() {
        if (value.isBlank()) return
        // 与浏览器一致：触发跳转即收起键盘，宿主持有 focus 时也能回到「更多」形态
        focusManager.clearFocus()
        onOpen()
    }

    // 外层 Box 只负责给 PopupMenuPopup 一个等于整条地址栏的锚点（Row 的坐标）
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PillShape)
                // 玻璃开启时由 drawBackdrop 出底；null 回退实心 surface
                .glassSurface(
                    shape = PillShape,
                    containerColor = MaterialTheme.colorScheme.surface,
                    backdrop = backdrop,
                    style = LiquidGlassStyle.Pill,
                )
                .then(
                    if (strokeWidth > 0.dp) {
                        Modifier.border(
                            width = strokeWidth,
                            color = strokeColor.copy(alpha = strokeColor.alpha * strokeProgress),
                            shape = PillShape,
                        )
                    } else {
                        Modifier
                    },
                )
                // 整条热区：图标、左右内距与行内空白点下去都聚焦输入框并拉起键盘。
                // 用 pointerInput 而非 clickable——clickable 会把本行合并成单个可点语义节点，
                // 吞掉内部输入框的可编辑语义；文本区与右侧按钮的点击被子级先消费。
                // 只读 / 状态态不挂热区：此时没有可聚焦的输入框
                .then(
                    if (!locked) {
                        Modifier.pointerInput(Unit) {
                            detectTapGestures {
                                focusRequester.requestFocus()
                                keyboard?.show()
                            }
                        }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = RowCardPaddingHorizontal, vertical = RowCardPaddingVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .semantics { contentDescription = loadingLabel },
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.5.dp,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_globe),
                    contentDescription = null,
                    tint = MaterialTheme.classppColors.secondaryText,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            when {
                // 识别 / 读取状态：同字阶的只读文案（primary），不显示光标与手写键盘
                statusText != null -> {
                    Text(
                        text = statusText,
                        style = MaterialTheme.classppTextStyles.fieldValue,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 冻结 / 回弹期间保留地址文字但禁止编辑
                readOnly -> {
                    Text(
                        text = value.ifEmpty { placeholder },
                        style = if (value.isEmpty()) {
                            MaterialTheme.classppTextStyles.fieldPlaceholder
                        } else {
                            MaterialTheme.classppTextStyles.fieldValue
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                else -> {
                    BasicTextField(
                        value = value,
                        // 键盘/粘贴带入的换行符一律滤掉：地址是逻辑单行
                        onValueChange = { onValueChange(it.filterNot { ch -> ch == '\n' || ch == '\r' }) },
                        textStyle = MaterialTheme.classppTextStyles.fieldValue,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        interactionSource = textInteraction,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = { openAddress() }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            // 只认输入框自身的焦点：右侧按钮（clickable 可聚焦）拿到焦点时
                            // 不应把描边 / 右侧形态带偏
                            .onFocusChanged {
                                inputFocused = it.isFocused
                                onFocusChanged?.invoke(it.isFocused)
                            }
                            // 参与宿主的「键盘弹起把输入框滚到键盘上方」：宿主没这项能力时此修饰符空转
                            .imeFieldTracking(),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                // 占位空值即显（聚焦也显示，光标停在占位文字首字前），同搜索框
                                if (value.isEmpty() && placeholder.isNotEmpty()) {
                                    Text(
                                        text = placeholder,
                                        style = MaterialTheme.classppTextStyles.fieldPlaceholder,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                }
            }
            when (action) {
                WebAddressBarAction.None -> Unit
                WebAddressBarAction.Open -> {
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_circle_right_filled),
                        contentDescription = openLabel,
                        tint = MaterialTheme.colorScheme.primary,
                        // 按压反馈直接落在 24dp 图标圆上：玻璃 + API 31+ 走 pressScale Q弹，
                        // 否则回退 pressFeedback 硬节奏缩放提亮（glassPressFeedback 内部二选一）
                        modifier = Modifier
                            .size(24.dp)
                            .glassPressFeedback(actionInteraction, CircleShape, backdrop)
                            .clickable(
                                interactionSource = actionInteraction,
                                indication = null,
                                onClick = { openAddress() },
                            ),
                    )
                }
                WebAddressBarAction.More -> {
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        painter = painterResource(R.drawable.ic_more_circle),
                        contentDescription = moreLabel,
                        tint = MaterialTheme.colorScheme.primary,
                        // 只读 / 状态态保留 more 图标但不可点：识别期间切 UA 会重载网页，
                        // 破坏冻结快照与抓取结果
                        modifier = Modifier
                            .size(24.dp)
                            .then(
                                if (locked) {
                                    Modifier
                                } else {
                                    Modifier
                                        .glassPressFeedback(actionInteraction, CircleShape, backdrop)
                                        .clickable(
                                            interactionSource = actionInteraction,
                                            indication = null,
                                            onClick = { menuExpanded = true },
                                        )
                                },
                            ),
                    )
                }
            }
        }

        PopupMenuPopup(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            // 退场播完再执行切 UA（会触发网页重载，属 Haze 采样源的结构重写）
            onExitFinished = { menuActionGate.flush() },
            sections = listOf(
                PopupMenuSection(
                    items = listOf(
                        PopupMenuItem(
                            label = stringResource(R.string.web_address_ua_desktop),
                            checked = desktopMode,
                            onClick = {
                                menuExpanded = false
                                if (!desktopMode) {
                                    menuActionGate.defer { onDesktopModeChange(true) }
                                }
                            },
                        ),
                        PopupMenuItem(
                            label = stringResource(R.string.web_address_ua_mobile),
                            checked = !desktopMode,
                            onClick = {
                                menuExpanded = false
                                if (desktopMode) {
                                    menuActionGate.defer { onDesktopModeChange(false) }
                                }
                            },
                        ),
                    ),
                ),
            ),
            // 菜单贴右侧 more 图标的右缘下方；宽锚点 + 窄菜单取右端生长，避免中心被钳到卡缘
            placement = MenuPlacement(
                gravity = MenuGravity.BottomEnd,
                inset = RowCardPaddingHorizontal,
            ),
            anchorFraction = Offset(1f, 0.5f),
            hazeState = hazeState,
        )
    }
}
