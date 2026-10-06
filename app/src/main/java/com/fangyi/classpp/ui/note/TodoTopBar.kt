package com.fangyi.classpp.ui.note

import androidx.annotation.DrawableRes
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.rememberOffsetOverscrollFactory
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.classppTextStyles

/** 顶栏胶囊高度（与 ScheduleHeader 的 WeekPill 同款 40dp） */
private val TopBarPillHeight = 40.dp

/** 胶囊横向间距 */
private val PillSpacing = 12.dp

/** 胶囊内边距：图标/文字距胶囊左右缘 */
private val PillInnerPadding = 16.dp

/** 胶囊内图标边长 */
private val PillIconSize = 20.dp

/** 左侧常驻图标胶囊宽度（图标 + 左右内边距） */
private val IconPillWidth = PillIconSize + PillInnerPadding * 2

/** 左侧常驻群总宽：文件夹 + 间距 + 排序 */
private val LeftClusterWidth = IconPillWidth * 2 + PillSpacing

/**
 * 首粒「全部」的静止位：常驻群右缘 + 一个胶囊间距。
 * 渐隐遮罩的透明端正是这里——静止时首粒恰好落在遮罩之外，零渐隐伪影。
 */
private val FirstPillRest = LeftClusterWidth + PillSpacing

/** 阴影呼吸位：胶囊柔影上下各留一份，防 LazyRow 视口把阴影裁掉 */
private val ShadowOverhang = 8.dp

/** 胶囊柔影（WeekPill 同款）：大 elevation 换软边、低透明 spot 压深浅 */
private val PillShadowElevation = 45.dp
private val PillShadowColor = Color.Black.copy(alpha = 0.2f)

/** 顶栏与状态栏的间距 */
private val TopBarTopPadding = 12.dp

/**
 * 待办页顶栏：左侧常驻图标胶囊（文件夹、排序）+ 右侧可滑动的分组胶囊条
 * （「全部」常驻首位 + 用户自定义分组）。
 *
 * 三层 z 序：底层 LazyRow 视口横贯整行，首粒「全部」经 contentPadding 停在排序
 * 按钮右侧——向左滑动时分组胶囊从排序按钮背后穿行；中层渐隐遮罩以 background
 * 色在常驻群区完全遮蔽（盖住两枚常驻胶囊间的缝隙防穿帮）、到首粒静止位线性
 * 渐隐至全透明；顶层是 surface 不透明的常驻胶囊，压住穿行的分组胶囊。
 *
 * 极限橡皮筋：经 LocalOverscrollFactory 注入 iOS 式橡皮筋效果，回弹弹簧取全局
 * [Motion.Settle]（与浮层拖拽松手回位同一令牌）。内容不溢出时 foundation 不派发
 * overscroll（无滚动范围即无极限），属预期行为。
 *
 * 纯 UI：分组点击只切选中高亮（过滤由调用方后续接入）；文件夹/排序回调本期空置。
 */
@Composable
fun TodoTopBar(
    groups: List<String>,
    selectedGroupIndex: Int,
    onGroupSelect: (Int) -> Unit,
    onFolderClick: () -> Unit,
    onSortClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val overscrollFactory = rememberOffsetOverscrollFactory(animationSpec = Motion.Settle)
    CompositionLocalProvider(LocalOverscrollFactory provides overscrollFactory) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = TopBarTopPadding),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(TopBarPillHeight + ShadowOverhang * 2),
            ) {
                val listState = rememberLazyListState()
                LazyRow(
                    state = listState,
                    modifier = Modifier.matchParentSize(),
                    contentPadding = PaddingValues(
                        start = PageHorizontalSpacing + FirstPillRest,
                        top = ShadowOverhang,
                        end = PageHorizontalSpacing,
                        bottom = ShadowOverhang,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(PillSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item {
                        GroupPill(
                            label = stringResource(R.string.todo_group_all),
                            selected = selectedGroupIndex == 0,
                            onClick = { onGroupSelect(0) },
                        )
                    }
                    itemsIndexed(groups) { index, group ->
                        GroupPill(
                            label = group,
                            selected = selectedGroupIndex == index + 1,
                            onClick = { onGroupSelect(index + 1) },
                        )
                    }
                }
                // 渐隐遮罩：常驻群区全不透明，到首粒静止位线性渐隐至透明
                val scrimColor = MaterialTheme.colorScheme.background
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(FirstPillRest)
                        .background(
                            Brush.horizontalGradient(
                                0f to scrimColor,
                                (LeftClusterWidth / FirstPillRest) to scrimColor,
                                1f to Color.Transparent,
                            ),
                        ),
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = PageHorizontalSpacing),
                    horizontalArrangement = Arrangement.spacedBy(PillSpacing),
                ) {
                    IconPill(
                        iconRes = R.drawable.ic_folder,
                        contentDescription = stringResource(R.string.cd_todo_folder),
                        onClick = onFolderClick,
                    )
                    IconPill(
                        iconRes = R.drawable.ic_arrow_sort,
                        contentDescription = stringResource(R.string.cd_todo_sort),
                        onClick = onSortClick,
                    )
                }
            }
        }
    }
}

/** 顶栏胶囊的柔影：graphicsLayer 定形状投影（与 WeekPill / NavPill 同款范式） */
private fun Modifier.topBarPillShadow(): Modifier = graphicsLayer {
    shape = PillShape
    clip = true
    shadowElevation = PillShadowElevation.toPx()
    spotShadowColor = PillShadowColor
}

/** 分组胶囊：选中 = primary 底白字，未选 = surface 底黑字（与 NavPill 同一配色逻辑） */
@Composable
private fun GroupPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val contentColor =
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .height(TopBarPillHeight)
            .topBarPillShadow()
            .background(background, PillShape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = PillInnerPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.classppTextStyles.pillButton,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/** 常驻图标胶囊：surface 底 + primary 图标（设计图蓝色图标） */
@Composable
private fun IconPill(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(width = IconPillWidth, height = TopBarPillHeight)
            .topBarPillShadow()
            .background(MaterialTheme.colorScheme.surface, PillShape)
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = PillInnerPadding),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(PillIconSize),
        )
    }
}

private val PreviewGroups = listOf("作业", "日常琐事", "考试复习", "社团活动", "购物清单", "长期计划")

@Preview(showBackground = true, widthDp = 412, name = "待办顶栏")
@Composable
private fun TodoTopBarPreview() {
    ClassppTheme {
        TodoTopBarPreviewContent()
    }
}

@Preview(showBackground = true, widthDp = 412, name = "待办顶栏（深色）")
@Composable
private fun TodoTopBarDarkPreview() {
    ClassppTheme(darkTheme = true) {
        TodoTopBarPreviewContent()
    }
}

@Composable
private fun TodoTopBarPreviewContent() {
    var selected by remember { mutableIntStateOf(0) }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TodoTopBar(
            groups = PreviewGroups,
            selectedGroupIndex = selected,
            onGroupSelect = { selected = it },
            onFolderClick = {},
            onSortClick = {},
        )
    }
}
