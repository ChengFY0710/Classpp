package com.fangyi.classpp.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.fangyi.classpp.R
import com.fangyi.classpp.data.shiguang.ShiguangAdapter
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.CardSectionTitle
import com.fangyi.classpp.ui.components.EmptyState
import com.fangyi.classpp.ui.components.EmptyStateActionButton
import com.fangyi.classpp.ui.components.LocalOverlayContentGlassBackdrop
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SettingsInfoHeaderCard
import com.fangyi.classpp.ui.components.SheetCard
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetInfoBanner
import com.fangyi.classpp.ui.components.SheetSearchField
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.components.TopBarHeight as OverlaySheetTopBarHeight
import com.fangyi.classpp.ui.theme.CardSectionSpacing
import com.fangyi.classpp.ui.theme.CardSectionTitleSpacing
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
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
 * 整页是 LazyColumn（`OverlaySheet` 的 `lazyContent` 模式）：说明卡、搜索框占位、列表
 * 各自作为 lazy item，打开浮层只组合视口内的行。**搜索/过滤始终遍历完整 `adapters`**，
 * 懒加载只影响渲染范围，不影响检索范围。
 *
 * 可见的搜索条不在列表里，而是画在浮层的 contentOverlay 覆盖槽（滚动容器外、顶栏下）：
 * 列表只留一个同高占位项，条的位置就取这一项的实测屏幕坐标——它滚到哪儿条到哪儿，
 * 被顶到顶栏下缘后钉住不再上移，列表标题与行从它下方滚过。覆盖槽始终组合（焦点/光标/
 * 键盘状态不丢），且在采样层之外——「更高级的玻璃材质」开启时（OverlaySheet 经覆盖槽
 * 下发 backdrop）搜索条出液态玻璃底，钉住时折射下方滚过的行；关闭回退实心白底。
 * 不垫通栏底色、不做渐变收边，列表内容照常从顶栏下缘渐隐滚过。
 *
 * 位置见 [shiguangSearchPinOffsetPx]：只认占位项的实测坐标，不从滚动状态推算
 *（按滚动量算出来的位移在覆盖槽里会因为拿不到重新放置的机会而冻住）。
 * 搜索词一变就把列表按回顶部（`scrollToItem`）：结果集换了从第一条看起，搜索条也随之
 * 回到说明卡下方的自然位——空结果那一屏尤其必须，否则短内容会停在末项、条悬在顶栏下。
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

    // 列表状态交给浮层容器：只负责滚动本身，搜索条的位置另有锚点（见下）
    val listState = rememberLazyListState()
    // 外置搜索条的实测高度：列表占位项跟随，字体缩放等场景不与条脱节（首帧用默认值）
    var searchFieldHeight by remember { mutableStateOf(ShiguangSearchDefaultHeight) }
    // 搜索条占位项在根坐标系里的 y：外置条的定位锚点。取实测坐标、不拿滚动状态推算——
    // 内容怎么动（滚动、橡皮筋回弹、键盘让位抬升）锚点就怎么动，条与内容不会脱节。
    // 用状态对象传引用、只在最里层的定位壳里读：若在这里读成 Int，滚动时整张浮层都会
    // 跟着逐帧重组（列表 item provider 也会重建）。
    val searchAnchorRootY = remember { mutableIntStateOf(0) }
    // 搜索词一变就把列表按回顶部：结果集换了就从第一条看起，外置搜索条也随之回到说明卡
    // 下方的自然位。这不只是「体验偏好」，还是空结果那一屏的必需项——
    // 筛选把列表缩短后，LazyColumn 不会自己回顶：键盘在场时尾部余量把可用高度压到
    // 400dp 上下，连「无结果」这种短内容也会被判成可滚，可见窗口整体停在**末项**上，
    // 说明卡与搜索条占位全被顶出视口，条就悬在顶栏下方：既不在原位、滑到底也没得可滑。
    // 两处细节：
    // - 用 scrollToItem（取滚动锁 + 强制重排一次）而不是滚动动画：回顶是筛选结果的一部分，
    //   不能被手势或别的滚动动画打断在半途；
    // - key 用 query 本身：每次改词都回顶，避免「结果变了但列表停在中段」这类中间态。
    LaunchedEffect(query) {
        listState.scrollToItem(0)
    }

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
        // 搜索条：渲染在滚动容器外的覆盖槽内，钉住定位与玻璃采样都安全（见 ShiguangSearchOverlay）
        contentOverlay = {
            ShiguangSearchOverlay(
                anchorRootY = searchAnchorRootY,
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.shiguang_search_placeholder),
                clearContentDescription = stringResource(R.string.shiguang_search_clear),
                onHeightMeasured = { searchFieldHeight = it },
            )
        },
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
            // 搜索条占位：可见搜索条画在浮层 contentOverlay 槽（滚动容器外），列表只留
            // 同高占位。它同时是外置条的**定位锚点**——这一项的屏幕坐标就是搜索条该在的
            // 位置，内容滚多少、被橡皮筋拉多少，条就跟着走多少
            item(key = ShiguangSearchKey) {
                Spacer(
                    Modifier
                        .height(searchFieldHeight)
                        .onGloballyPositioned { coords ->
                            searchAnchorRootY.intValue = coords.positionInRoot().y.roundToInt()
                        },
                )
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
 * 外置搜索条：画在浮层 contentOverlay 覆盖槽（滚动容器外、顶栏之下），位置跟着列表里
 * 那个同高占位项（key = [ShiguangSearchKey]）走——占位项在屏幕上到哪儿，条就在哪儿，
 * 被顶到顶栏下缘之后不再上移（见 [shiguangSearchPinOffsetPx]）。始终组合、随卡进出场，
 * 焦点/光标/键盘状态不丢。
 *
 * 玻璃材质经 [LocalOverlayContentGlassBackdrop] 下发：覆盖槽在采样层（卡片底色 +
 * 滚动内容）之外，可安全采样——钉住时折射从条下方滚过的行，不会读写同一层；
 * null 回退实心白底，聚焦蓝描边两种材质下原样叠加。
 *
 * @param anchorRootY 占位项在根坐标系里的 y（列表每帧回报，见调用点）；传状态对象而不是值，
 *   读它的就只有下面那个定位壳
 */
@Composable
private fun BoxScope.ShiguangSearchOverlay(
    anchorRootY: MutableIntState,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    clearContentDescription: String,
    onHeightMeasured: (Dp) -> Unit,
) {
    val density = LocalDensity.current
    val backdrop = LocalOverlayContentGlassBackdrop.current
    // 覆盖槽自己量一次卡片顶端（同样用状态对象：读它的是定位壳，不是本层）：
    // 锚点是根坐标，减掉它才是「相对浮层」的位移
    val containerTopRootY = remember { mutableIntStateOf(0) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { containerTopRootY.intValue = it.positionInRoot().y.roundToInt() },
    ) {
        // 定位与内容分开：定位层跟着锚点重组，搜索框走稳定插槽不被带着重组
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
 * 折到顶栏下缘之后被 [shiguangSearchPinOffsetPx] 钳成 0 贴住。
 *
 * 两个坐标都在这里才读：读在上层会把整张浮层（连同列表 item provider）拖进逐帧重组。
 * 搜索框经 [content] 插槽传入，本层重组时插槽实例不变，输入框不会跟着滚动手势重组。
 */
@Composable
private fun BoxScope.PinnedSearchBar(
    anchorRootY: MutableIntState,
    containerTopRootY: MutableIntState,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val topBarPx = with(density) { OverlaySheetTopBarHeight.roundToPx() }
    val pinOffsetPx = shiguangSearchPinOffsetPx(
        anchorRootY = anchorRootY.intValue,
        containerTopRootY = containerTopRootY.intValue,
        topBarPx = topBarPx,
    )
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            // 横向与列表内容同边距（LazyColumn contentPadding 的水平侧）
            .padding(horizontal = PageHorizontalSpacing)
            .offset(y = with(density) { pinOffsetPx.toDp() }),
    ) {
        content()
    }
}

/**
 * 搜索条相对浮层顶端（卡片顶边）的位移（px）：钉住时等于顶栏高（= 停在顶栏下缘），
 * 更大 = 还在说明卡下方、随内容一起走。
 *
 * 两个坐标都是根坐标系的实测值：[anchorRootY] 是列表里搜索条占位项的 y，
 * [containerTopRootY] 是浮层卡片的 y。两者相减就是占位项相对卡片的位置——也就是搜索条
 * 该画的位置；占位项被滚到顶栏下缘以上后钳成 [topBarPx]，条贴住顶栏下缘不再上移。
 *
 * 只做一次减法、不掺任何滚动状态的推算：这一项在屏幕上到哪儿，条就到哪儿。
 */
internal fun shiguangSearchPinOffsetPx(
    anchorRootY: Int,
    containerTopRootY: Int,
    topBarPx: Int,
): Int = (anchorRootY - containerTopRootY).coerceAtLeast(topBarPx)

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

// lazy item 的稳定 key（搜索条占位项同时是外置条的定位锚点）
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

/** 外置搜索条的首帧默认高度：14×2 上下内距 + 24dp 图标（量准后由实测高度接管） */
private val ShiguangSearchDefaultHeight = 52.dp
