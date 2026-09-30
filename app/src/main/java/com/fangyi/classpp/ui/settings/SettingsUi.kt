package com.fangyi.classpp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.OnBackground
import com.fangyi.classpp.ui.theme.Primary
import com.fangyi.classpp.ui.theme.PrimaryContainerNontrans
import com.fangyi.classpp.ui.theme.SecondaryTextColor
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * 设置页视觉模板：分组标题 + 白色圆角卡 + 设置行 + 时间胶囊 + 分段选择。
 * 后续设置详情页直接复用这些组件，保持同一套配色与间距。
 *
 * 配色约定：页面底 [Background]、卡底 [Surface]、强调 [Primary]、
 * 浅蓝容器 [PrimaryContainerNontrans]、次级文字 [SecondaryTextColor]。
 *
 * 卡内间距按行数分两类：单行卡（一行「文字+控件」）用默认 [CardContentPadding]；
 * 多行卡（多行「文字+控件」）另传 [MultiLineRowSpacing]，行间 12 → 18dp。
 */

private val CardShape = RoundedCornerShape(16.dp)
private val ChipShape = RoundedCornerShape(8.dp)
private val SegmentShape = RoundedCornerShape(12.dp)

/**
 * 设置页顶栏：左侧圆形返回按钮 + 居中加粗标题。
 *
 * 返回按钮叠放在 TopAppBar 之上、不进 navigationIcon 槽位：槽位位于 TopAppBarLayout 的
 * clipToBounds() 内，圆形投影会贴着顶栏底边被裁断；标题按顶栏全宽居中（与槽位宽度无关，
 * 见 M3 placeTopAppBar），移除槽位不改变标题位置。
 *
 * [hazeState] 非空时对顶栏背后的滚动内容做背景模糊——顶部最强、向下渐弱（渐变模糊），
 * 与 ScheduleHeader 同一套 Haze 规格，兜底色画在模糊层之下；
 * null（如 @Preview）时退化为不透明背景。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            // 兜底色：与页面同色，画在模糊层之下（背后无内容时逐帧一致）
            .background(MaterialTheme.colorScheme.background)
            .then(
                if (hazeState != null) {
                    Modifier.hazeEffect(hazeState) {
                        blurRadius = 32.dp
                        progressive = HazeProgressive.verticalGradient(
                            startIntensity = 1f,
                            endIntensity = 0f,
                        )
                        tints = listOf(HazeTint(Color.White.copy(alpha = 0.30f)))
                        noiseFactor = 0f
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = OnBackground,
                )
            },
            // 状态栏 inset 由顶栏自己吸收：磨砂背景一直铺到屏幕顶（沉浸式）
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                // 与顶栏同套 inset + 8dp 顶距 = 原槽位内垂直居中（(56−40)/2）
                .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                .padding(start = 20.dp, top = 12.dp)
                .size(40.dp)
                // 圆形投影（同 BottomNavBar 胶囊）：graphicsLayer 在 draw 阶段读值，不引发重组；
                // clip 收在 layer 内，投影才不会被外层裁掉
                .graphicsLayer {
                    shape = CircleShape
                    clip = true
                    shadowElevation = 45.dp.toPx()
                    spotShadowColor = Color.Black.copy(alpha = 0.2f)
                }
                .background(MaterialTheme.colorScheme.surface, CircleShape),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_left),
                contentDescription = stringResource(R.string.cd_settings_close),
                tint = OnBackground,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** 分组：灰色小标题（卡片外）+ 下方一组卡片，卡间距 8dp */
@Composable
internal fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = SecondaryTextColor,
        )
        content()
    }
}

/**
 * 卡内基础留白：水平 16、垂直 6（6 网格；[SettingRow] 自带 6dp，垂直值叠加其上）
 * → 首尾 12dp、行间 12dp；行下的说明/提示文字距卡底同样 12dp（自带 6 + 此处 6）。
 *
 * 卡内间距按行数分两类：
 * - **单行卡**（一行「文字+控件」：显示开关、每天 N 节）→ 用默认；
 * - **多行卡**（多行「文字+控件」：学期三行、节次时间）→ 另传
 *   `rowSpacing = `[MultiLineRowSpacing]，行间 = 12 + 6 = 18dp。
 *
 * 两个间距旋钮相互独立：
 * - **首尾距卡边** = [SettingRow] 自带的 6dp + [SettingsCard] 的 `contentPadding` 垂直值
 * - **相邻子内容间距** = 12dp（两行各 6dp）+ [SettingsCard] 的 `rowSpacing`
 */
internal val CardContentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

/** 多行卡（多行「文字+控件」）行间额外间距：实际行间 = 12dp + 此值 = 18dp，学期与节次时间两卡共用 */
internal val MultiLineRowSpacing = 10.dp

/** 设置页白色卡片：圆角 16dp、无投影、无分割线，默认留白 [CardContentPadding]，多行卡另传 [rowSpacing] */
@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = CardContentPadding,
    /** 行与行之间的额外间距（实际行间 = 12dp + 此值），不影响首尾距卡边 */
    rowSpacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(rowSpacing),
            content = content,
        )
    }
}

/**
 * 通用设置行：左标签 + 右侧内容。
 * - [onClick] 非空 → 整行可点
 * - [value] 非空 → 显示 Primary 色数值（日期、周数等）
 * - [trailing] 非空 → 右侧自定义槽（Switch、时间 chip、文字按钮等）
 * - [showChevron] 默认随 [onClick]；置 false 可去掉行尾箭头
 */
@Composable
internal fun SettingRow(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    value: String? = null,
    showChevron: Boolean = onClick != null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            // 行自带 6dp：提供首尾距卡边的 6dp（行间额外间距由 SettingsCard.rowSpacing 提供）
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = Primary,
            )
        }
        trailing?.invoke(this)
        if (showChevron) {
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = SecondaryTextColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 时间胶囊：浅蓝底、Primary 字，点击弹时间选择 */
@Composable
internal fun TimeChip(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        modifier = modifier
            .clip(ChipShape)
            .background(PrimaryContainerNontrans)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = Primary,
    )
}

/**
 * 分段单选：等分选项，选中项浅蓝圆角块 + 勾选 + Primary 字。
 * 自绘而非 M3 SegmentedButton：设计稿是简单圆角块而非缺角分段外形，
 * 且可避免各版本 itemColors 参数名差异。
 */
@Composable
internal fun SegmentedChoice(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(SegmentShape)
                    .background(if (selected) PrimaryContainerNontrans else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selected) {
                    Icon(
                        painter = painterResource(R.drawable.ic_checkmark),
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) Primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** 日期 → `2026-9-7`（无前导零），走 [R.string.date_hyphen_format] */
@Composable
internal fun IsoDate.toSettingsDateText(): String {
    val parts = toString().split('-')
    val year = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val month = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val day = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return stringResource(R.string.date_hyphen_format, year, month, day)
}

/** 顶栏单独预览：底色取自 colorScheme.background（= 页面底 #F2F4F6），与实际渲染一致 */
@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, name = "设置页顶栏")
@Composable
private fun SettingsTopBarPreview() {
    ClassppTheme {
        SettingsTopBar(
            title = stringResource(R.string.settings_title),
            onBack = {},
        )
    }
}
