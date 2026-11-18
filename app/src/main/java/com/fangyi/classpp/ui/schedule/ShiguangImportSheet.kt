package com.fangyi.classpp.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.fangyi.classpp.R
import com.fangyi.classpp.data.shiguang.ShiguangAdapter
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.CardSectionTitle
import com.fangyi.classpp.ui.components.EmptyState
import com.fangyi.classpp.ui.components.EmptyStateActionButton
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SettingsInfoHeaderCard
import com.fangyi.classpp.ui.components.SheetCard
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetInfoBanner
import com.fangyi.classpp.ui.components.SheetSearchField
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.theme.CardSectionSpacing
import com.fangyi.classpp.ui.theme.CardSectionTitleSpacing
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles

/**
 * 「拾光教务导入」学校选择浮层：导入课表浮层的「从拾光教务导入」行打开，叠在导入浮层
 * 之上（covered 缩小后退）。
 *
 * 纯 UI：索引拉取、缓存回退、脚本下载、WebView 导入都由 [ShiguangImportFlow] 负责。
 * 列表是「学校 + 适配」扁平结构（一个学校有本科/研究生多个适配时会出现多行），
 * 搜索匹配学校名/适配名/描述/首字母；点行即选中适配。
 *
 * 整页是 LazyColumn（`OverlaySheet` 的 `lazyContent` 模式）：说明卡、搜索框、列表各自
 * 作为 lazy item，打开浮层只组合视口内的行。**搜索/过滤始终遍历完整 `adapters`**，
 * 懒加载只影响渲染范围，不影响检索范围。
 *
 * 搜索框是 `stickyHeader`：未滚动时仍在说明卡下方，随内容上滑到顶栏下缘后钉住，
 * 钉住期间保持组合（焦点/光标/键盘状态不丢），列表标题与行从它下方滚过。钉住的只有
 * 搜索框本身：不垫通栏底色、不做渐变收边，列表内容照常从顶栏下缘渐隐滚过。
 *
 * 列表为空/加载失败/搜索无结果各有独立状态；`loadFailed` 且列表非空时只在顶部补一条
 * 「已显示缓存」提示，不打断选择。
 */
@Composable
internal fun ShiguangImportSheet(
    visible: Boolean,
    onDismissed: () -> Unit,
    onDismiss: () -> Unit,
    adapters: List<ShiguangAdapter>,
    loading: Boolean,
    loadFailed: Boolean,
    onRefresh: () -> Unit,
    onPick: (ShiguangAdapter) -> Unit,
    covered: Boolean = false,
) {
    var query by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    // 每个适配的可搜索串（小写拼接）随列表只建一次：逐键过滤不再对每行做 4 次
    // ignoreCase 扫描；首字母仍保持「整串相等」的旧口径，不并入这里
    val searchKeys = remember(adapters) {
        adapters.associate { adapter ->
            adapter.key to listOf(
                adapter.schoolName,
                adapter.schoolId,
                adapter.adapterName,
                adapter.description,
            ).joinToString(SearchKeySeparator).lowercase()
        }
    }
    val filtered = remember(adapters, searchKeys, query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            adapters
        } else {
            val lowerKeyword = keyword.lowercase()
            adapters.filter { adapter ->
                searchKeys[adapter.key]?.contains(lowerKeyword) == true ||
                    adapter.initial.equals(keyword, ignoreCase = true)
            }
        }
    }
    // 同一学校有多个适配时，副行要带上适配名，避免只看到重复的学校名
    val adapterCountBySchool = remember(adapters) {
        adapters.groupingBy { it.schoolId }.eachCount()
    }
    // 行样式按主题构造一次后下传：300 行不再各自 .copy()，行参数也因此保持相等、
    // 过滤时更容易整行跳过重组
    val colorScheme = MaterialTheme.colorScheme
    val textStyles = MaterialTheme.classppTextStyles
    val secondaryTextColor = MaterialTheme.classppColors.secondaryText
    val rowTitleStyle = remember(colorScheme, textStyles) {
        textStyles.fieldLabel.copy(color = colorScheme.onSurface)
    }
    val rowDescriptionStyle = remember(secondaryTextColor) {
        ClassppTextTokens.settingsDescription.copy(color = secondaryTextColor)
    }
    // 点行回调经 remember(adapter) 固定：过滤时保留下来的行参数不变（配合 items(key=)），
    // 输入搜索词只重组可见行，不再每次按键重排全部条目
    val currentOnPick = rememberUpdatedState(onPick)

    // 返回键关浮层（本浮层在导入浮层之后组合，因此先收到）
    BackHandler(enabled = visible) {
        onDismiss()
    }

    // 列表状态交给浮层容器：sticky 搜索条要读 layoutInfo 做钉住补偿
    val listState = rememberLazyListState()

    OverlaySheet(
        title = stringResource(R.string.shiguang_sheet_title),
        // 纯入口列表：没有确认语义，右侧只留「关闭」
        confirmLabel = null,
        rightAction = SheetTopAction(
            label = stringResource(R.string.switcher_close),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = onDismiss,
        ),
        onDismiss = onDismiss,
        visible = visible,
        covered = covered,
        onDismissed = onDismissed,
        // 有搜索框：键盘弹起时内容让位
        imeBehavior = SheetImeBehavior.ContentScroll,
        lazyState = listState,
        lazyContent = {
            // 说明卡：原来的 18dp 分段间距从「搜索框顶部」移到本项底部——sticky 搜索条
            // 不能自带顶部留白（钉住时那截留白会一起钉在顶栏下缘）
            item(key = ShiguangIntroKey) {
                CardSection(modifier = Modifier.padding(bottom = SheetSectionSpacingBetween)) {
                    SettingsInfoHeaderCard(
                        icon = R.drawable.ic_globe,
                        title = stringResource(R.string.shiguang_sheet_header_title),
                        description = stringResource(R.string.shiguang_sheet_header_desc),
                    )
                    // 适配列表只由用户手动更新：首次打开自动拉取后，后续打开只读缓存
                    SettingsCard(
                        items = listOf(
                            SettingsCardItem.Nav(
                                label = stringResource(R.string.shiguang_refresh_label),
                                description = stringResource(R.string.shiguang_refresh_desc),
                                onClick = onRefresh,
                            ),
                        ),
                    )
                }
            }
            // 搜索框：滚到顶栏下缘后钉住且保持组合（焦点/光标/键盘状态不丢），
            // 列表标题与行从它下方滚过；钉住的只有搜索框本身，不垫通栏底色
            stickyHeader(key = ShiguangSearchKey) {
                ShiguangStickySearchHeader(listState = listState) {
                    SheetSearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = stringResource(R.string.shiguang_search_placeholder),
                        clearContentDescription = stringResource(R.string.shiguang_search_clear),
                    )
                }
            }
            when {
                loading && adapters.isEmpty() -> item(key = ShiguangLoadingKey) {
                    // 搜索框与下方内容同属两个分段：静止时保持 18dp 分段间距
                    CardSection(modifier = Modifier.padding(top = SheetSectionSpacingBetween)) {
                        SheetCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.shiguang_loading),
                                    style = MaterialTheme.classppTextStyles.fieldPlaceholder,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                adapters.isEmpty() && loadFailed -> item(key = ShiguangLoadFailedKey) {
                    CardSection(modifier = Modifier.padding(top = SheetSectionSpacingBetween)) {
                        EmptyState(
                            icon = R.drawable.ic_alert,
                            iconTint = MaterialTheme.colorScheme.error,
                            title = stringResource(R.string.shiguang_load_failed_title),
                            description = stringResource(R.string.shiguang_load_failed_desc),
                            action = {
                                EmptyStateActionButton(
                                    label = stringResource(R.string.shiguang_retry),
                                    onClick = onRefresh,
                                )
                            },
                        )
                    }
                }
                adapters.isEmpty() -> item(key = ShiguangEmptyKey) {
                    CardSection(modifier = Modifier.padding(top = SheetSectionSpacingBetween)) {
                        EmptyState(
                            icon = R.drawable.ic_search,
                            iconTint = MaterialTheme.classppColors.secondaryText,
                            title = stringResource(R.string.shiguang_empty_title),
                            description = stringResource(R.string.shiguang_empty_desc),
                        )
                    }
                }
                else -> {
                    if (loadFailed) {
                        item(key = ShiguangCachedBannerKey) {
                            CardSection(modifier = Modifier.padding(top = SheetSectionSpacingBetween)) {
                                SheetInfoBanner(text = stringResource(R.string.shiguang_load_failed_cached))
                            }
                        }
                    }
                    item(key = ShiguangListTitleKey) {
                        CardSectionTitle(
                            text = stringResource(R.string.shiguang_list_title),
                            modifier = Modifier.padding(
                                // 缓存提示与列表是两个分段，中间同为 18dp
                                top = SheetSectionSpacingBetween,
                                bottom = CardSectionTitleSpacing,
                            ),
                        )
                    }
                    if (filtered.isEmpty()) {
                        item(key = ShiguangNoResultKey) {
                            CardSection {
                                EmptyState(
                                    icon = R.drawable.ic_search,
                                    iconTint = MaterialTheme.classppColors.secondaryText,
                                    title = stringResource(R.string.shiguang_empty_title),
                                    description = stringResource(R.string.shiguang_empty_desc),
                                )
                            }
                        }
                    } else {
                        // 行按适配身份 key（LazyColumn 据此复用行状态）；过滤仍在完整
                        // adapters 上执行，搜索输入只影响可见行的重组
                        items(items = filtered, key = { it.key }) { adapter ->
                            ShiguangAdapterRow(
                                adapter = adapter,
                                showAdapterName = (adapterCountBySchool[adapter.schoolId] ?: 0) > 1,
                                titleStyle = rowTitleStyle,
                                descriptionStyle = rowDescriptionStyle,
                                modifier = Modifier.padding(bottom = CardSectionSpacing),
                                onClick = remember(adapter) {
                                    {
                                        focusManager.clearFocus()
                                        currentOnPick.value(adapter)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}

/** 学校行：学校名 + 适配名/描述两行；点击即选定该校适配 */
@Composable
private fun ShiguangAdapterRow(
    adapter: ShiguangAdapter,
    showAdapterName: Boolean,
    // 样式由列表统一构造后传入：行本身不做 .copy()，行参数也更容易保持不变而跳过重组
    titleStyle: TextStyle,
    descriptionStyle: TextStyle,
    // 行间距由 lazy item 的调用点给出（每行底部 CardSectionSpacing）
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // 副行文案只在适配或「是否带适配名」变化时重建（每次重组都拼串没有必要）
    val secondaryText = remember(adapter, showAdapterName) {
        adapterSecondaryText(adapter, showAdapterName)
    }
    SheetCard(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ShiguangRowHeight),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = adapter.schoolName,
                style = titleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = secondaryText,
                style = descriptionStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 钉住的搜索条：LazyColumn 的 stickyHeader 原生只把表头钉在**卡片顶端**（被 78dp 顶栏
 * 盖住的位置），这里用 layoutInfo 的原始 offset 做视觉补偿，让它停在顶栏下缘。
 *
 * `visibleItemsInfo.offset` 是内容坐标（不含 contentPadding）：搜索条的视觉位置 =
 * offset + TopBarHeight。offset ≤ 0（已到达/越过顶栏下缘）时补 -offset，把它按在顶栏
 * 下缘；offset > 0（还在说明卡下方）时不补，随内容正常滚动。sticky 机制在
 * offset < -TopBarHeight 后把 offset 夹住，补偿值稳定为 TopBarHeight，列表标题与行从
 * 搜索条下方滚过。
 *
 * 只钉搜索框本身：不铺通栏底色、不做收边——列表内容照常从顶栏下缘渐隐滚过，搜索框
 * 钉住后只由它自己的白底遮挡其矩形范围。搜索框与下方内容的 18dp 分段间距由后续 item
 * 的顶部留白承担（见 lazyContent 中各 item 的 `top = SheetSectionSpacingBetween`），
 * 静止时与原来逐像素一致，钉住时也不会带出一块背景色。
 *
 * zIndex 必须高于后续 item：视觉补偿从「搜索条到达顶栏下缘」就开始，而 LazyColumn 的
 * sticky（包含「钉住项最后绘制」这条 z 序保证）要等整个头部滑出**视口顶端**、搜索条成为
 * firstVisibleItem 后才生效——中间整整隔了一个顶栏高度（78dp）。这段窗口里搜索条还只是
 * 普通 item，若不抬 zIndex，后面的分组标题与行会按 item 顺序画在钉住的搜索条之上，表现为
 * 标题盖在搜索框上、内容穿插进搜索框。常驻 zIndex（不随钉住态切换）即可：未重叠时无
 * 视觉差异，也避免钉住阈值处重组。
 */
@Composable
private fun ShiguangStickySearchHeader(
    listState: LazyListState,
    content: @Composable () -> Unit,
) {
    // 滚动时 layoutInfo 逐帧变化，但派生值只在 offset 跨过 0 区间时变化；位移在放置阶段
    // 读取（offset 的 lambda 版本），不触发逐帧重组
    val offsetY by remember(listState) {
        derivedStateOf {
            val item = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == ShiguangSearchKey }
            (-(item?.offset ?: 0)).coerceAtLeast(0)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(1f)
            .offset { IntOffset(0, offsetY) },
    ) {
        content()
    }
}

/** 副行：描述优先；多适配学校前置适配名；都缺时退回适配 id，保证行不空 */
private fun adapterSecondaryText(adapter: ShiguangAdapter, showAdapterName: Boolean): String {
    val name = adapter.adapterName.trim()
    val description = adapter.description.trim()
    val text = when {
        showAdapterName && name.isNotEmpty() && description.isNotEmpty() -> "$name · $description"
        showAdapterName && name.isNotEmpty() -> name
        description.isNotEmpty() -> description
        name.isNotEmpty() -> name
        else -> adapter.adapterId
    }
    return text
}

/** 搜索串的字段分隔符：避免「学校名尾 + 学校 id 头」拼出跨字段的假命中 */
private const val SearchKeySeparator = "\u0000"

// lazy item / stickyHeader 的稳定 key（也用于在 layoutInfo 里定位钉住的搜索条）
private const val ShiguangIntroKey = "shiguang_intro"
private const val ShiguangSearchKey = "shiguang_search"
private const val ShiguangLoadingKey = "shiguang_loading"
private const val ShiguangLoadFailedKey = "shiguang_load_failed"
private const val ShiguangEmptyKey = "shiguang_empty"
private const val ShiguangCachedBannerKey = "shiguang_cached_banner"
private const val ShiguangListTitleKey = "shiguang_list_title"
private const val ShiguangNoResultKey = "shiguang_no_result"

/** 双行列表卡统一卡高（同学校选择浮层） */
private val ShiguangRowHeight = 72.dp
