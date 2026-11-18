package com.fangyi.classpp.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.shiguang.ShiguangAdapter
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.EmptyState
import com.fangyi.classpp.ui.components.EmptyStateActionButton
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SettingsInfoHeaderCard
import com.fangyi.classpp.ui.components.SheetCard
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetInfoBanner
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.components.imeFieldTracking
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.SheetCardShape
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
    // 点行回调经 remember(adapter) 固定：过滤时保留下来的行参数不变（配合下方 key()），
    // 输入搜索词只重组必要的行，不再每次按键重排全部 300 行
    val currentOnPick = rememberUpdatedState(onPick)

    // 返回键关浮层（本浮层在导入浮层之后组合，因此先收到）
    BackHandler(enabled = visible) {
        onDismiss()
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
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            CardSection {
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
            CardSection {
                ShiguangSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.shiguang_search_placeholder),
                )
            }
            when {
                loading && adapters.isEmpty() -> {
                    CardSection {
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
                adapters.isEmpty() && loadFailed -> {
                    CardSection {
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
                adapters.isEmpty() -> {
                    CardSection {
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
                        CardSection {
                            SheetInfoBanner(text = stringResource(R.string.shiguang_load_failed_cached))
                        }
                    }
                    CardSection(title = stringResource(R.string.shiguang_list_title)) {
                        if (filtered.isEmpty()) {
                            EmptyState(
                                icon = R.drawable.ic_search,
                                iconTint = MaterialTheme.classppColors.secondaryText,
                                title = stringResource(R.string.shiguang_empty_title),
                                description = stringResource(R.string.shiguang_empty_desc),
                            )
                        } else {
                            filtered.forEach { adapter ->
                                // 按适配身份复用行：过滤/重排时同一行跟着 key 走，
                                // 参数不变即整行跳过重组（搜索输入的逐键成本从 300 行降到 0）
                                key(adapter.key) {
                                    ShiguangAdapterRow(
                                        adapter = adapter,
                                        showAdapterName = (adapterCountBySchool[adapter.schoolId] ?: 0) > 1,
                                        titleStyle = rowTitleStyle,
                                        descriptionStyle = rowDescriptionStyle,
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
                }
            }
        }
    }
}

/** 学校行：学校名 + 适配名/描述两行；点击即选定该校适配 */
@Composable
private fun ShiguangAdapterRow(
    adapter: ShiguangAdapter,
    showAdapterName: Boolean,
    // 样式由列表统一构造后传入：行本身不做 .copy()，行参数也更容易保持不变而跳过重组
    titleStyle: TextStyle,
    descriptionStyle: TextStyle,
    onClick: () -> Unit,
) {
    // 副行文案只在适配或「是否带适配名」变化时重建（每次重组都拼串没有必要）
    val secondaryText = remember(adapter, showAdapterName) {
        adapterSecondaryText(adapter, showAdapterName)
    }
    SheetCard(
        modifier = Modifier
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

/** 搜索框：白底圆角、左对齐输入；空白时显示占位文案，有内容时右侧给清除按钮 */
@Composable
private fun ShiguangSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    val focusManager = LocalFocusManager.current
    val clearInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ShiguangSearchHeight)
            .clip(SheetCardShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = MaterialTheme.classppColors.secondaryText,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = ClassppTextTokens.secondaryTextLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier
                    .weight(1f)
                    .imeFieldTracking(),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
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
            if (value.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clickable(
                            interactionSource = clearInteraction,
                            indication = null,
                            onClick = { onValueChange("") },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_dismiss_circle),
                        contentDescription = stringResource(R.string.shiguang_search_clear),
                        tint = MaterialTheme.classppColors.secondaryText,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** 搜索串的字段分隔符：避免「学校名尾 + 学校 id 头」拼出跨字段的假命中 */
private const val SearchKeySeparator = "\u0000"

/** 双行列表卡统一卡高（同学校选择浮层） */
private val ShiguangRowHeight = 72.dp

/** 搜索框高度：46dp 单行条（同 AI 网页地址栏） */
private val ShiguangSearchHeight = 46.dp
