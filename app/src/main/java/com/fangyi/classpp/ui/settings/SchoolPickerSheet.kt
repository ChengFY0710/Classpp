package com.fangyi.classpp.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.sync.Capability
import com.fangyi.classpp.data.sync.SchoolSpec
import com.fangyi.classpp.ui.components.AlphabetOtherLetter
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.CardSectionTitle
import com.fangyi.classpp.ui.components.EmptyState
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.PinnedSheetSearchField
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SheetCard
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetSearchFieldDefaultHeight
import com.fangyi.classpp.ui.components.SheetSearchSectionSpacing
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.components.firstLatinLetter
import com.fangyi.classpp.ui.components.groupInAlphabetOrder
import com.fangyi.classpp.ui.schools.schoolText
import com.fangyi.classpp.ui.theme.CardSectionSpacing
import com.fangyi.classpp.ui.theme.CardSectionTitleSpacing
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles

/**
 * 学校选择浮层（指导 §3.2）：列出 [schools] 注册序，当前学校行文字主色 + 行尾主题色对勾。
 *
 * - 学校行按显示名首字母分组（A→Z，取不到拉丁首字母的归入 `#` 排在最后），
 *   每组用独立的组标题，与拾光教务导入页同一套字母分组；
 *   组内保持注册序（首个学校仍是默认活跃学校，分组不改变切校语义）；
 * - 搜索框与拾光教务导入浮层同款：画在浮层的覆盖槽里（滚动容器外），跟着列表里的
 *   同高占位项定位、被顶到顶栏下缘就钉住；搜索匹配学校显示名与学校 id，输入单个字母
 *   时按组标题整串匹配（点 `X` 即筛出 X 组）。匹配结果同样按字母分组，无结果时给空态；
 * - 点当前学校 → 仅关浮层；点他校 → 回调 [onSelect]，切换与持久化由宿主（注册表）负责；
 * - 行规格沿用浮层双行列表卡统一规格：72dp 卡高、默认内距、`CardSection` 默认行距、副行单行省略；
 *   副行 = 能力摘要（待办同步 · 教务课表导入）；
 * - 只注册一所学校时照常渲染（仅一行选中态）；
 * - `busy`（同步/自动续期在途）时整行不响应：宿主传入的回调自行判定，浮层不弹 toast；
 * - 顶部是远端学校（Tier 1）的手动更新入口：远端学校默认关闭，只有用户点这一行才会
 *   联网拉取；下载的声明保存到本地，重启后仍可用。浮层自身不发起任何请求。
 *
 * 整页是 LazyColumn（`OverlaySheet` 的 `lazyContent` 模式）：更新入口卡、搜索条占位、
 * 分组标题与学校行各自作为 lazy item，打开浮层只组合视口内的行。搜索/过滤始终遍历完整
 * `schools`，懒加载只影响渲染范围。
 */
@Composable
internal fun SchoolPickerSheet(
    visible: Boolean,
    schools: List<SchoolSpec>,
    activeSchoolId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    onRefreshRemote: () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    // 学校行按显示名首字母分组：名称走 schoolText 随系统语言切换，中文名没有拉丁字母时
    // 退回学校 id 的首字母（id 按拼音缩写约定）；组标题只由字母决定，与注册序无关。
    // 显示名只解析一次，同时供分组、搜索与行文字使用
    val entries = schools.map { spec ->
        val name = schoolText(spec.displayName)
        SchoolPickerEntry(
            spec = spec,
            name = name,
            letter = schoolGroupLetter(name, spec.id),
        )
    }
    // 搜索匹配显示名 / 学校 id / 组标题（输入单个字母即筛该字母组），大小写不敏感
    val keyword = query.trim()
    val filtered = if (keyword.isEmpty()) {
        entries
    } else {
        entries.filter { entry ->
            entry.name.contains(keyword, ignoreCase = true) ||
                entry.spec.id.contains(keyword, ignoreCase = true) ||
                entry.letter.equals(keyword, ignoreCase = true)
        }
    }
    val groups = groupInAlphabetOrder(filtered, letterOf = { it.letter })

    // 返回键关浮层（与其余浮层一致的两段式：visible 复位后播退场，播完才卸载）
    BackHandler(enabled = visible) {
        onDismiss()
    }

    val listState = rememberLazyListState()
    // 外置搜索条的实测高度：列表占位项跟随，字体缩放等场景不与条脱节（首帧用默认值）
    var searchFieldHeight by remember { mutableStateOf(SheetSearchFieldDefaultHeight) }
    // 搜索条占位项的根坐标：外置条的定位锚点，取实测坐标、不拿滚动状态推算。
    // 状态对象只在定位壳的绘制块里读，滚动时不触发整页重组
    val searchAnchorRootY = remember { mutableFloatStateOf(0f) }
    // 搜索词一变就把列表按回顶部：结果集换了从第一条看起，搜索条也回到更新入口卡下方
    LaunchedEffect(query) {
        listState.scrollToItem(0)
    }

    OverlaySheet(
        title = stringResource(R.string.school_picker_title),
        rightAction = SheetTopAction(
            label = stringResource(R.string.switcher_close),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = onDismiss,
        ),
        onDismiss = onDismiss,
        visible = visible,
        onDismissed = onDismissed,
        // 有搜索框：键盘弹起时内容让位
        imeBehavior = SheetImeBehavior.ContentScroll,
        lazyState = listState,
        // 搜索条：渲染在滚动容器外的覆盖槽内，钉住定位与玻璃采样都安全
        contentOverlay = {
            PinnedSheetSearchField(
                anchorRootY = searchAnchorRootY,
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.school_search_placeholder),
                clearContentDescription = stringResource(R.string.school_search_clear),
                onHeightMeasured = { searchFieldHeight = it },
            )
        },
        lazyContent = {
            // 远端学校（Tier 1）：默认关闭，只保留手动更新入口。联网与结果提示由宿主负责，
            // 浮层自身不发起任何请求。底部垫 10dp：搜索条不能自带顶部留白（钉住时会一起钉住）
            item(key = SchoolPickerIntroKey) {
                CardSection(modifier = Modifier.padding(bottom = SheetSearchSectionSpacing)) {
                    SettingsCard(
                        items = listOf(
                            SettingsCardItem.Nav(
                                label = stringResource(R.string.settings_remote_refresh_title),
                                description = stringResource(R.string.settings_remote_refresh_desc),
                                onClick = onRefreshRemote,
                            ),
                        ),
                    )
                }
            }
            // 搜索条占位：可见搜索条画在浮层 contentOverlay 槽（滚动容器外），列表只留
            // 同高占位。它同时是外置条的定位锚点
            item(key = SchoolPickerSearchKey) {
                Spacer(
                    Modifier
                        .height(searchFieldHeight)
                        .onGloballyPositioned { coords ->
                            searchAnchorRootY.floatValue = coords.positionInRoot().y
                        },
                )
            }
            if (filtered.isEmpty()) {
                item(key = SchoolPickerNoResultKey) {
                    // 搜索框与下方内容同属两个分段：静止时保持 18dp 分段间距
                    CardSection(modifier = Modifier.padding(top = SheetSectionSpacingBetween)) {
                        EmptyState(
                            icon = R.drawable.ic_search,
                            title = stringResource(R.string.school_search_no_result_title),
                            description = stringResource(R.string.school_search_no_result_desc),
                        )
                    }
                }
            } else {
                // 每个字母一组：标题与行各自是 lazy item，间距按 CardSection 的排版语义拆开——
                // 标题承担组间 18dp 与「标题↔首卡」10dp，行承担「卡↔卡」10dp；
                // 组末行不再垫 10dp，否则下一组标题前会叠成 28dp
                groups.forEach { group ->
                    item(key = SchoolPickerGroupKeyPrefix + group.letter) {
                        CardSectionTitle(
                            text = group.letter,
                            modifier = Modifier.padding(
                                top = SheetSectionSpacingBetween,
                                bottom = CardSectionTitleSpacing,
                            ),
                        )
                    }
                    itemsIndexed(
                        items = group.items,
                        key = { _, entry -> entry.spec.id },
                    ) { index, entry ->
                        SchoolRow(
                            name = entry.name,
                            spec = entry.spec,
                            selected = entry.spec.id == activeSchoolId,
                            modifier = Modifier.padding(
                                bottom = if (index == group.items.lastIndex) {
                                    0.dp
                                } else {
                                    CardSectionSpacing
                                },
                            ),
                            onClick = {
                                // 选中即关浮层：先收起键盘，退场时不再挂着输入焦点
                                focusManager.clearFocus()
                                onSelect(entry.spec.id)
                            },
                        )
                    }
                }
            }
        },
    )
}

/** 学校行：学校名 + 能力摘要两行；当前学校主色 + 行尾对勾（同选择类浮层） */
@Composable
private fun SchoolRow(
    name: String,
    spec: SchoolSpec,
    selected: Boolean,
    // 行间距由 lazy item 的调用点给出（组内每行底部 CardSectionSpacing，组末行不垫）
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    SheetCard(
        // 72dp 卡高：双行列表卡统一规格（内容不足时由 heightIn 撑到下限）
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SchoolRowHeight),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.classppTextStyles.fieldLabel.copy(
                    color = if (selected) colors.primary else colors.onSurface,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = capabilitySummary(spec),
                style = ClassppTextTokens.settingsDescription.copy(
                    color = MaterialTheme.classppColors.secondaryText,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(
                painter = painterResource(R.drawable.ic_checkmark),
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 能力摘要：待办同步为共性能力；教务课表导入仅声明了 [Capability.SCHEDULE_IMPORT] 时追加 */
@Composable
private fun capabilitySummary(spec: SchoolSpec): String {
    val parts = buildList {
        add(stringResource(R.string.school_capability_todo_sync))
        if (Capability.SCHEDULE_IMPORT in spec.capabilities) {
            add(stringResource(R.string.school_capability_schedule_import))
        }
    }
    return parts.joinToString(" · ")
}

/** 分组用的中间行：学校声明 + 已解析的显示名 + 组标题字母（搜索/分组/行文字共用） */
private data class SchoolPickerEntry(
    val spec: SchoolSpec,
    val name: String,
    val letter: String,
)

/**
 * 学校行的组标题字母：优先取当前显示名里的首个拉丁字母（英文显示名直接给出，
 * 如 Xiamen University → X）；中文显示名没有拉丁字母时退回学校 id 的首字母
 *（id 按拼音缩写约定，如 xmu → X）；都没有则归入 `#`。
 */
internal fun schoolGroupLetter(displayName: String, schoolId: String): String {
    val letter = displayName.firstLatinLetter() ?: schoolId.firstLatinLetter()
    return letter?.uppercaseChar()?.toString() ?: AlphabetOtherLetter
}

// lazy item 的稳定 key（搜索条占位项同时是外置条的定位锚点）
private const val SchoolPickerIntroKey = "school_picker_intro"
private const val SchoolPickerSearchKey = "school_picker_search"
private const val SchoolPickerNoResultKey = "school_picker_no_result"
private const val SchoolPickerGroupKeyPrefix = "school_picker_group_"

/** 双行列表卡统一卡高 */
private val SchoolRowHeight = 72.dp
