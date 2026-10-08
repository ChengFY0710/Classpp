package com.fangyi.classpp.ui.components

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.rubberBandHorizontalScroll
import com.fangyi.classpp.ui.theme.MenuShape
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.SheetCardShape
import com.fangyi.classpp.ui.theme.SheetFieldHeight
import com.fangyi.classpp.ui.theme.classppTextStyles

/** 标签选择卡里的一项：名字 + 颜色。
 *  用户自定义标签的颜色来自颜色选择器（接入前恒为主题 Primary）；课程标签的颜色
 *  由宿主从课程卡配色映射（ui.schedule.CourseColor.barColor），组件不依赖课表包。 */
@Immutable
data class TagItem(val name: String, val color: Color)

/** 标签文字：胶囊私有的小号正文（设计稿 13sp Normal），颜色由各胶囊按标签色注入 */
private val TagTextStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal)

/** 胶囊内标签图标尺寸：与 13sp 文字同高对齐设计稿 */
private val TagIconSize = 14.dp

/** 删除弹层与标签之间的缝隙 */
private val DeletePopupGap = 8.dp

/**
 * 标签选择卡：白卡内「输入添加自定义标签 + 横滑标签行 + 可展开的课程标签区」。
 *
 * 设计稿五态：
 * - 常态：占位「点击输入添加标签」+ 右侧颜色圆球（本次恒 Primary、非交互，颜色选择后续再接）；
 * - 已有标签：标签行横向排列，超宽横向滑动、多余裁切（不足一行也能拉出橡皮筋）；
 * - 聚焦：整卡 2dp 蓝色描边（SheetTextField 同款单动画源），光标可见；
 * - 提交：失焦（点空白/其他地方）或 IME Done 时把输入建成标签，重名静默忽略；
 * - 长按用户标签弹「删除」层；课程标签区默认收起，点头部行展开（本次无动画）。
 *
 * 描边宽度与不透明度同走一条 0→1 进度动画（单动画源，两条曲线严格同步），
 * 进度归 0 后干脆不挂 border——0 宽描边会被 Skia 画成 1px 发丝线（SheetTextField 踩过）。
 * 与 SheetTextField 的差异：描边挂整卡（聚焦时标签区也在框内），输入左对齐、单行。
 *
 * 「点击空白处或其他地方自动保存提交」拆成两半：
 * - 点空白：宿主容器的 clearFocusOnTap（OverlaySheet 内容列已内置）让输入框失焦，
 *   失焦即提交（下方 LaunchedEffect）；
 * - 点其他地方（标签胶囊、课程标签头部）：子级可点元素会消费点击、clearFocusOnTap
 *   收不到，故这些点击里主动 clearFocus——统一走失焦提交。
 *
 * IME Done 提交后保留焦点与键盘，方便连续录入多个标签，最后一条靠失焦提交。
 *
 * 选中态（设计稿）：点击胶囊切换选中——未选中「标签色 30% 底 + 标签色字」，
 * 选中「标签色实心底 + 白字」（白字含深色模式，按设计稿恒白）。选中键是标签名
 * 字符串，与 Todo.tags 的纯字符串模型一致，未来待办可直接携带。
 *
 * @param userTags 用户自定义标签（横滑行，支持长按删除）
 * @param courseTags 课程标签（宿主传激活课表按课程名去重后的映射结果，不可删）
 * @param selectedNames 选中的标签名集合
 * @param onAddTag 提交新标签（已 trim 非空）；颜色由宿主定（本次恒 Primary）
 * @param onDeleteTag 长按删除确认后回调（仅用户标签）
 * @param initialCourseExpanded 课程标签区初始展开态（预览/特殊宿主用，交互中仍可切换）
 */
@Composable
fun TagChoosingCard(
    userTags: List<TagItem>,
    courseTags: List<TagItem>,
    selectedNames: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onAddTag: (String) -> Unit,
    onDeleteTag: (String) -> Unit,
    modifier: Modifier = Modifier,
    inputPlaceholder: String = stringResource(R.string.tag_choosing_input_hint),
    courseSectionTitle: String = stringResource(R.string.tag_choosing_course_title),
    initialCourseExpanded: Boolean = false,
) {
    var text by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    // 长按待删除的用户标签名（非空 = 删除弹层打开），同时只开一个
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    // 课程标签区展开状态：默认收起；本次不做展开动画，直接切换显隐
    var courseExpanded by rememberSaveable { mutableStateOf(initialCourseExpanded) }

    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // 提交当前输入：trim 非空且不与现有标签（用户/课程）重名才回调，随后清空输入。
    // 重名静默忽略：标签本质是字符串（Todo.tags），同名再建一份没有意义。
    fun submitInput() {
        val name = text.trim()
        if (name.isEmpty()) return
        val duplicated = userTags.any { it.name == name } || courseTags.any { it.name == name }
        if (!duplicated) onAddTag(name)
        text = ""
    }

    // 失焦自动提交：「点空白/其他地方自动保存提交建立标签」的统一出口。首次组合时
    // focused 为 false 也会空跑一次，但输入恒为空，是干净空转。
    LaunchedEffect(focused) {
        if (!focused) submitInput()
    }

    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            // 已聚焦的字段再点不会拉键盘，键盘一旦被收起就唤不回来（SheetTextField 同款踩坑）
            if (interaction is PressInteraction.Release) keyboard?.show()
        }
    }

    // 点胶囊/头部行 = 「点其他地方」：先清焦让待提交的输入走失焦提交，再做本职动作
    val toggleSelection: (String) -> Unit = { name ->
        focusManager.clearFocus()
        onSelectionChange(if (name in selectedNames) selectedNames - name else selectedNames + name)
    }

    // 描边进度：宽度与透明度共用同一条 0→1 动画，聚焦「生长+淡入」、失焦「萎缩+淡出」严格同步
    val strokeProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
        label = "tagCardStroke",
    )
    val strokeWidth = 2.dp * strokeProgress
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(SheetCardShape)
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (strokeWidth > 0.dp) {
                    Modifier.border(
                        width = strokeWidth,
                        color = primaryColor.copy(alpha = primaryColor.alpha * strokeProgress),
                        shape = SheetCardShape,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        // —— 输入行：间距对齐 SheetTextField（16/14 内距、60dp 行高下限），左侧颜色圆球 ——
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SheetFieldHeight)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val valueStyle = MaterialTheme.classppTextStyles.fieldValue
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = valueStyle,
                // 标签名不需要换行：单行输入，粘贴带入的换行由 singleLine 自动滤掉
                singleLine = true,
                cursorBrush = SolidColor(primaryColor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                // Done 只提交不清焦：焦点键盘都留着，方便连续录入，最后一条靠失焦提交
                keyboardActions = KeyboardActions(onDone = { submitInput() }),
                interactionSource = interactionSource,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        // 未聚焦且为空时显示占位；聚焦时光标可见、不显占位（SheetTextField 同款）
                        if (text.isEmpty() && !focused && inputPlaceholder.isNotEmpty()) {
                            Text(
                                text = inputPlaceholder,
                                style = MaterialTheme.classppTextStyles.fieldPlaceholder,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            Spacer(Modifier.width(12.dp))
            // 颜色选择器占位：新标签的颜色圆球，本次恒 Primary、非交互
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(PillShape)
                    .background(primaryColor),
            )
        }

        // —— 用户自定义标签行：横向滑动 + 橡皮筋（不足一行也能拉出；多余随卡片裁切）——
        if (userTags.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .rubberBandHorizontalScroll(rememberScrollState())
                    // padding 在滚动之内 = 两端内容边距：静止时与输入文字左缘对齐，
                    // 滑到头也能停在 16dp 上；视觉裁切由外卡 clip 承担
                    .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = if (courseTags.isNotEmpty()) 0.dp else 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                userTags.forEach { tag ->
                    TagCapsule(
                        item = tag,
                        selected = tag.name in selectedNames,
                        onClick = { toggleSelection(tag.name) },
                        onLongClick = { deleteTarget = tag.name },
                        showDeletePopup = deleteTarget == tag.name,
                        onDismissDelete = { deleteTarget = null },
                        onDelete = {
                            onDeleteTag(tag.name)
                            deleteTarget = null
                        },
                    )
                }
            }
        }

        // —— 课程标签区：头部行可点展开/收起；展开后 FlowRow 换行，颜色=课程卡片配色 ——
        if (courseTags.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        focusManager.clearFocus()
                        courseExpanded = !courseExpanded
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = courseSectionTitle,
                    style = MaterialTheme.classppTextStyles.fieldLabel,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    painter = painterResource(
                        if (courseExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
                    ),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (courseExpanded) {
                FlowRow(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    courseTags.forEach { tag ->
                        TagCapsule(
                            item = tag,
                            selected = tag.name in selectedNames,
                            onClick = { toggleSelection(tag.name) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 标签胶囊：标签色 30% 透明度底 + 标签色字；选中为标签色实心底 + 白字（图标同白）。
 * 用户标签经 [onLongClick] 长按弹删除层；课程标签不可删，不传即无长按响应。
 */
@Composable
private fun TagCapsule(
    item: TagItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    showDeletePopup: Boolean = false,
    onDismissDelete: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val contentColor = if (selected) Color.White else item.color
    // Box 兼作删除弹层的锚：Popup 挂在它下面，anchorBounds 即胶囊的窗口坐标
    Box {
        Row(
            modifier = Modifier
                .clip(PillShape)
                .background(if (selected) item.color else item.color.copy(alpha = 0.3f))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_tag),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(TagIconSize),
            )
            Text(
                text = item.name,
                style = TagTextStyle.copy(color = contentColor),
            )
        }
        if (showDeletePopup) {
            TagDeletePopup(
                onDismiss = onDismissDelete,
                onDelete = onDelete,
            )
        }
    }
}

/**
 * 长按用户标签弹出的「删除」层（设计稿的白色圆角弹层：红垃圾桶 + 红字）。
 *
 * 定位沿用 CourseContextMenu 那套：自定义 [PopupPositionProvider] + focusable 弹窗
 * （返回键/点外部收起）。差异是贴标签**上方**（菜单语义是「对这个标签做动作」，
 * 压住标签本身会挡住长按的高亮），上方放不下（标签贴屏顶）再落到下方，横向与
 * 标签左缘对齐、越界钳进窗口。
 *
 * 进场同规格（Motion.PopupScale/PopupFadeMillis）：scale 0.8→1 + alpha 0→1。
 * 删除不做二次确认：设计稿只有一层弹层，点「删除」直接生效。
 */
@Composable
private fun TagDeletePopup(
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val visibleState = remember { MutableTransitionState(false) }
    // 首帧后置真：从 0.8/0 长到 1/1
    LaunchedEffect(Unit) { visibleState.targetState = true }

    val gap = with(LocalDensity.current) { DeletePopupGap.roundToPx() }
    val positionProvider = remember(gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = anchorBounds.left
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val y = if (anchorBounds.top - gap - popupContentSize.height >= 0) {
                    anchorBounds.top - gap - popupContentSize.height
                } else {
                    anchorBounds.bottom + gap
                }.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                return IntOffset(x, y)
            }
        }
    }

    val transition = rememberTransition(visibleState, label = "TagDeletePopup")
    val scale by transition.animateFloat(
        transitionSpec = { tween(Motion.PopupScaleMillis, easing = Motion.Standard) },
        label = "scale",
    ) { visible -> if (visible) 1f else 0.8f }
    val alpha by transition.animateFloat(
        transitionSpec = { tween(Motion.PopupFadeMillis, easing = Motion.Standard) },
        label = "alpha",
    ) { visible -> if (visible) 1f else 0f }

    Popup(
        onDismissRequest = onDismiss,
        popupPositionProvider = positionProvider,
        // focusable：返回键收起；dismissOnClickOutside 默认开启
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = MenuShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                // 从标签那一侧长出来
                transformOrigin = TransformOrigin(0.5f, 1f)
            },
        ) {
            Row(
                modifier = Modifier
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.edit_delete),
                    style = MaterialTheme.classppTextStyles.menuItem.copy(
                        color = MaterialTheme.colorScheme.error,
                    ),
                )
            }
        }
    }
}
