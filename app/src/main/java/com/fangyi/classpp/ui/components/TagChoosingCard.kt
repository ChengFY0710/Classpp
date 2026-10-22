package com.fangyi.classpp.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.motion.Expandable
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.motion.rubberBandHorizontalScroll
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.SheetCardShape
import com.fangyi.classpp.ui.theme.SheetFieldHeight
import com.fangyi.classpp.ui.theme.classppTextStyles
import dev.chrisbanes.haze.HazeState
import kotlin.math.roundToInt

/** 标签选择卡里的一项：名字 + 颜色。
 *  用户自定义标签的颜色来自颜色选择器（未挑色时跟随主题 Primary）；课程标签的颜色
 *  由宿主从课程卡配色映射（ui.schedule.CourseColor.barColor），组件不依赖课表包。 */
@Immutable
data class TagItem(val name: String, val color: Color)

/** 标签文字：胶囊私有的小号正文（设计稿 13sp Normal），颜色由各胶囊按标签色注入 */
private val TagTextStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal)

/** 胶囊内标签图标尺寸：与 13sp 文字同高对齐设计稿 */
private val TagIconSize = 14.dp

/** 删除菜单与标签之间的缝隙 */
private val DeletePopupGap = 8.dp

/** 未选中胶囊的底色透明度：用户标签 10%；课程标签 20%（底稍实一点区分来源） */
private const val UserTagAlpha = 0.1f
private const val CourseTagAlpha = 0.2f

/**
 * 标签选择卡：白卡内「输入添加自定义标签 + 横滑标签行 + 可展开的课程标签区」。
 *
 * 设计稿五态：
 * - 常态：占位「点击输入添加标签」+ 右侧颜色圆球（显示新标签的颜色草稿，点按唤起
 *   宿主的选色弹卡 [BottomColorSelectCard]）；
 * - 已有标签：标签行横向排列，超宽横向滑动、多余裁切（不足一行也能拉出橡皮筋）；
 *   删空后整行经 Expandable 折叠收起；
 * - 聚焦：整卡 2dp 蓝色描边（SheetTextField 同款单动画源），光标可见；
 * - 提交：失焦（点空白/其他地方）或 IME Done 时把输入建成标签，重名静默忽略；
 * - 长按用户标签弹「删除」菜单（[PopupMenuPopup] 共享弹层）；课程标签区默认收起，
 *   点头部行展开——高度动画复用 motion 层 [Expandable]（展开/收起时下方内容实时让位），
 *   chevron 同步 180° 旋转。
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
 * 选中态（设计稿）：点击胶囊切换选中——未选中「标签色 10% 底 + 标签色字」，
 * 选中「标签色实心底 + 白字」（白字含深色模式，按设计稿恒白）。选中键是标签名
 * 字符串，与 Todo.tags 的纯字符串模型一致，未来待办可直接携带。
 *
 * @param userTags 用户自定义标签（横滑行，支持长按删除）
 * @param courseTags 课程标签（宿主传激活课表按课程名去重后的映射结果，不可删）
 * @param selectedNames 选中的标签名集合
 * @param onAddTag 提交新标签（已 trim 非空）与其颜色（[newTagColorArgb]，null = 跟随主题
 *   Primary）；宿主落库 `TagRepository.addTag(name, colorArgb)`
 * @param onDeleteTag 长按删除确认后回调（仅用户标签）
 * @param initialCourseExpanded 课程标签区初始展开态（预览/特殊宿主用，交互中仍可切换）
 * @param newTagColorArgb 输入行颜色球的显示色 = 新标签的颜色草稿（null = 跟随主题
 *   Primary，TagEntry.colorArgb 同语义）；由宿主经选色弹卡回写
 * @param onPickColor 点颜色球上报：宿主唤起选色弹卡（BottomColorSelectCard）。默认空
 *   实现——不需要选色的宿主（预览等）保持颜色球不可交互的现状
 * @param hazeState 删除菜单的毛玻璃采样源：菜单卡对背后的浮层内容做磨砂（跨 Popup
 *   窗口采样，同滚轮弹窗/长按图片菜单材质）；null（如 @Preview）退化为不透明白卡
 */
@Composable
fun TagChoosingCard(
    userTags: List<TagItem>,
    courseTags: List<TagItem>,
    selectedNames: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onAddTag: (name: String, colorArgb: Long?) -> Unit,
    onDeleteTag: (String) -> Unit,
    modifier: Modifier = Modifier,
    inputPlaceholder: String = stringResource(R.string.tag_choosing_input_hint),
    courseSectionTitle: String = stringResource(R.string.tag_choosing_course_title),
    initialCourseExpanded: Boolean = false,
    newTagColorArgb: Long? = null,
    onPickColor: () -> Unit = {},
    hazeState: HazeState? = null,
) {
    var text by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    // 长按删除菜单「锚点 + 可见性」两态（同 DayImageSheet 的长按图片菜单）：deleteTarget
    // 记待删标签与长按时刻的窗口坐标，收起/删除只翻 deleteVisible，锚点保留到退场播完清
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }
    var deleteVisible by remember { mutableStateOf(false) }
    // 课程标签区展开状态：默认收起；展开/收起动画走 motion 层 Expandable（下方部分实时让位）
    var courseExpanded by rememberSaveable { mutableStateOf(initialCourseExpanded) }
    // 标签行删空后的折叠快照：Expandable 收起动画期间列表已空，行内容若直接跟随列表清空
    // 会瞬间只剩 padding 在收——快照删除前的标签，让胶囊跟着窗帘一起收完；非空时同步刷新
    var collapsingTags by remember { mutableStateOf(userTags) }
    if (userTags.isNotEmpty()) collapsingTags = userTags

    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // 提交当前输入：trim 非空且不与现有标签（用户/课程）重名才回调，随后清空输入。
    // 重名静默忽略：标签本质是字符串（Todo.tags），同名再建一份没有意义。
    // 颜色草稿随标签一并交给宿主（null = 跟随主题 Primary）
    fun submitInput() {
        val name = text.trim()
        if (name.isEmpty()) return
        val duplicated = userTags.any { it.name == name } || courseTags.any { it.name == name }
        if (!duplicated) onAddTag(name, newTagColorArgb)
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

    // Box 兼作删除弹层的锚：Popup 在组合里落零尺寸锚点节点，须收在 Box（叠加摆放）内——
    // 若作为本组件的兄弟节点落进宿主 CardSection 的 spacedBy 内容列，会多占一个组间距，
    // 弹层一开卡片就长高（下方分组随之下移）
    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
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
            // —— 输入行：间距对齐 SheetTextField（16/14 内距、60dp 行高下限），右侧颜色圆球 ——
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
                        // 参与宿主的「键盘弹起把输入框滚到键盘上方」（OverlaySheet 的 ImeScrollTracker）
                        .imeFieldTracking()
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
                // 新标签的颜色圆球：显示当前颜色草稿（null = 跟随主题 Primary），点按上报宿主
                // 唤起选色弹卡。按压反馈在 clip 之前（ColorSwatchCard 色球同款）；只收键盘
                // 不清焦——清焦会触发失焦提交，把输入中的文字抢跑成标签
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .pressClickable(PillShape) {
                            keyboard?.hide()
                            onPickColor()
                        }
                        .clip(PillShape)
                        .background(newTagColorArgb?.let { Color(it) } ?: primaryColor),
                )
            }

            // —— 用户自定义标签行：横向滑动 + 橡皮筋（不足一行也能拉出；多余随卡片裁切）。
            // 删空后整行经 Expandable 折叠收起（下方内容实时让位，收起播的是删除前快照），
            // 首次添加对称展开 ——
            Expandable(expanded = userTags.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .rubberBandHorizontalScroll(rememberScrollState())
                        // padding 在滚动之内 = 两端内容边距：静止时与输入文字左缘对齐，
                        // 滑到头也能停在 16dp 上；视觉裁切由外卡 clip 承担
                        .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = if (courseTags.isNotEmpty()) 0.dp else 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    collapsingTags.forEach { tag ->
                        TagCapsule(
                            item = tag,
                            selected = tag.name in selectedNames,
                            onClick = { toggleSelection(tag.name) },
                            // 长按记录待删标签与其窗口坐标并翻开可见（锚点保留到退场播完再清）
                            onLongClick = { anchor ->
                                deleteTarget = DeleteTarget(tag.name, anchor)
                                deleteVisible = true
                            },
                            // 折叠播放中的是删除前快照：胶囊不可交互，避免给已删标签恢复选中
                            enabled = userTags.isNotEmpty(),
                        )
                    }
                }
            }

            // —— 课程标签区：头部行可点展开/收起；展开后 FlowRow 换行，颜色=课程卡片配色 ——
            if (courseTags.isNotEmpty()) {
                // 头部行与标签行的间距（有标签行 14dp / 无 2dp）：标签行删空折叠时同规格
                // （ExpandMillis + Accelerate，与 shrinkVertically 同步）动画过渡，头部行
                // 平滑 riding 折叠、不出现瞬时跳位；首次添加标签时对称回扩
                val courseHeaderTop by animateDpAsState(
                    targetValue = if (userTags.isEmpty()) 2.dp else 14.dp,
                    animationSpec = tween(Motion.ExpandMillis, easing = Motion.Accelerate),
                    label = "tagCardCourseHeaderTop",
                )
                // chevron 旋转：固定一张 down 图转 180° 当 up 用，时长与 Expandable 高度
                // 动画同值（ExpandMillis），同帧启动、同帧收尾
                val chevronRotation by animateFloatAsState(
                    targetValue = if (courseExpanded) 180f else 0f,
                    animationSpec = tween(Motion.ExpandMillis, easing = Motion.Standard),
                    label = "tagCardChevron",
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 行无自有 clip：矩形提亮原位接上（不可复用卡片级按压源——那个用于
                        // PressInteraction 唤起键盘，pressClickable 内部自建独立源）
                        .pressClickable(RectangleShape) {
                            focusManager.clearFocus()
                            courseExpanded = !courseExpanded
                        }
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            // 无用户标签行时本行紧贴输入行：输入行下方间距两种状态一致
                            // （14 + 2 = 16dp）；有标签行时标签行 bottom 已收 0，上间距
                            // 由这里的 14dp 提供（随标签行折叠/展开动画过渡，见上）
                            top = courseHeaderTop,
                            bottom = 14.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = courseSectionTitle,
                        style = MaterialTheme.classppTextStyles.fieldLabel,
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer { rotationZ = chevronRotation },
                    )
                }
                Expandable(expanded = courseExpanded) {
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
                                // 课程标签底稍实（20%），与用户标签（10%）区分来源
                                backgroundAlpha = CourseTagAlpha,
                            )
                        }
                    }
                }
            }
        }

        // 长按删除菜单挂在 Box 内（不随胶囊挂载）：删除标签后列表即时刷新也不打断退场播放
        // （同 DayImageSheet 的宿主级挂载）
        deleteTarget?.let { target ->
            TagDeleteMenu(
                anchor = target.anchor,
                expanded = deleteVisible,
                onDismiss = { deleteVisible = false },
                onDelete = {
                    deleteVisible = false
                    onDeleteTag(target.name)
                },
                hazeState = hazeState,
                onExitFinished = { deleteTarget = null },
            )
        }
    }
}

/**
 * 标签胶囊：标签色透明度底 + 标签色字（透明度见 [backgroundAlpha]，用户/课程标签不同）；
 * 选中为标签色实心底 + 白字（图标同白）。
 * 用户标签经 [onLongClick] 长按上报删除请求（带胶囊当时的窗口坐标作菜单锚点）；
 * 课程标签不可删，不传即无长按响应。
 */
@Composable
private fun TagCapsule(
    item: TagItem,
    selected: Boolean,
    onClick: () -> Unit,
    // 未选中态的底色透明度：用户标签 [UserTagAlpha]、课程标签 [CourseTagAlpha]
    backgroundAlpha: Float = UserTagAlpha,
    onLongClick: ((Rect) -> Unit)? = null,
    // false = 不吃点击与长按（折叠快照期的占位胶囊）
    enabled: Boolean = true,
) {
    val contentColor = if (selected) Color.White else item.color
    val bounds = remember { BoundsHolder() }
    // 按压源新建（不碰卡片级键盘唤起源）：pressFeedback 与 combinedClickable 共用，
    // indication 置 null——涟漪由按压反馈取代，长按 onLongClick 语义不变
    val press = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .onGloballyPositioned { bounds.value = it.boundsInWindow() }
            // 按压反馈在 clip 之前：缩放作用于整枚胶囊，提亮范围与 PillShape 对齐；
            // 标签胶囊按压放大单独调大到 1.10（与顶栏按钮同规格，不随全局默认）
            .pressFeedback(press, PillShape, scale = 1.10f)
            .clip(PillShape)
            .background(if (selected) item.color else item.color.copy(alpha = backgroundAlpha))
            .combinedClickable(
                interactionSource = press,
                indication = null,
                enabled = enabled,
                onClick = onClick,
                onLongClick = { onLongClick?.invoke(bounds.value) },
            )
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
}

/**
 * 长按用户标签弹出的「删除」菜单（单组单项）：[PopupMenuPopup] 共享弹层（果冻进场 +
 * 整卡首尾淡入淡出 + 毛玻璃卡壳，同长按图片菜单的 ImageMenuPopup 范式）。
 *
 * 定位：贴标签**上方**、与标签左缘对齐（菜单语义是「对这个标签做动作」，压住标签本身
 * 会挡住长按的高亮），上方放不下（标签贴屏顶）再落到下方；投影留白与越界钳制由宿主处理。
 * 生长角按标签所在半屏现算（菜单贴着标签长出）。删除不做二次确认：设计稿只有一层菜单，
 * 点「删除」直接生效。
 *
 * @param anchor 长按时刻标签的窗口坐标（[DeleteTarget.anchor] 快照，菜单不随标签移动）
 */
@Composable
private fun TagDeleteMenu(
    anchor: Rect,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    hazeState: HazeState?,
    onExitFinished: () -> Unit = {},
) {
    val gap = with(LocalDensity.current) { DeletePopupGap.roundToPx() }
    val windowWidthPx = LocalWindowInfo.current.containerSize.width.takeIf { it > 0 }
        ?: LocalView.current.width
    PopupMenuPopup(
        expanded = expanded,
        onDismiss = onDismiss,
        sections = listOf(
            PopupMenuSection(
                items = listOf(
                    PopupMenuItem(
                        label = stringResource(R.string.edit_delete),
                        icon = R.drawable.ic_delete,
                        onClick = onDelete,
                    ),
                ),
            ),
        ),
        cardPosition = { _, _, cardSize ->
            IntOffset(
                x = anchor.left.roundToInt(),
                y = if (anchor.top - gap - cardSize.height >= 0) {
                    anchor.top - gap - cardSize.height
                } else {
                    anchor.bottom + gap
                }.roundToInt(),
            )
        },
        menuGrowth = if (windowWidthPx > 0 && anchor.center.x > windowWidthPx / 2f) {
            MenuGrowth.BottomRight
        } else {
            MenuGrowth.BottomLeft
        },
        hazeState = hazeState,
        onExitFinished = onExitFinished,
    )
}

/** 长按删除菜单的目标：标签名 + 长按那一刻该标签的窗口坐标（菜单锚点） */
private class DeleteTarget(val name: String, val anchor: Rect)

/**
 * 标签窗口坐标（长按弹菜单的锚点）：只在长按回调里读，故存普通字段而非 Compose state
 * ——写在 onGloballyPositioned 里也不会像 state 那样让布局阶段触发重组（DayImageGrid 同款）。
 */
private class BoundsHolder {
    var value: Rect = Rect.Zero
}
