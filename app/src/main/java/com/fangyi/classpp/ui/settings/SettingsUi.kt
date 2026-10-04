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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.ui.schedule.TERM_WEEKS_MAX
import com.fangyi.classpp.ui.schedule.TERM_WEEKS_MIN
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.SettingsCardShape
import com.fangyi.classpp.ui.theme.classppColors
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * 设置页视觉模板：分组标题 + 白色圆角卡 + 设置行 + 时间胶囊。
 * 后续设置详情页直接复用这些组件，保持同一套配色与间距。
 *
 * 配色约定：页面底 `colorScheme.background`、卡底 `Surface`、强调 `colorScheme.primary`、
 * 浅蓝容器 `classppColors.primaryContainerNontrans`、次级文字 `classppColors.secondaryText`。
 *
 * 卡内间距按行数分两类：单行卡（一行「文字+控件」）用默认 [CardContentPadding]；
 * 多行卡（多行「文字+控件」）另传 [MultiLineRowSpacing]。
 */

private val ChipShape = RoundedCornerShape(8.dp)

/**
 * 设置页顶栏：左侧圆形返回按钮 + 居中加粗标题。
 *
 * 返回按钮叠放在 TopAppBar 之上、不进 navigationIcon 槽位：
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
    // 磨砂 tint 随主题（hazeEffect 的 block 在绘制期执行、非 composable 上下文，取值提到 Box 之前）
    val hazeTint = MaterialTheme.classppColors.hazeTint
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
                        tints = listOf(HazeTint(hazeTint.copy(alpha = 0.30f)))
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
                    color = MaterialTheme.colorScheme.onBackground,
                )
            },
            // 状态栏 inset 由顶栏自己吸收：磨砂背景一直铺到屏幕顶（沉浸式）
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                //原槽位内垂直居中
                .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                .padding(start = 24.dp, top = 12.dp)  // 返回按钮位置
                .size(40.dp)
                .graphicsLayer {    // 返回按钮投影
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
                tint = MaterialTheme.colorScheme.onBackground,
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
            color = MaterialTheme.classppColors.secondaryText,
        )
        content()
    }
}

// 默认间距：卡片内容左右padding：horizontal,卡片内容首尾间距：vertical.
// 另外，对于单行文字加控件内容，卡片内容高度SettingsScreen里oneLineControlHeight参数进行了严格控制。
internal val CardContentPadding = PaddingValues(start = 17.dp, end = 13.dp, top = 13.dp, bottom = 13.dp)

// 多行卡片行与行间距增值，要修改调这个MultiLineRowSpacing,传入rowSpacing
internal val MultiLineRowSpacing = 10.dp

/** 设置页白色卡片：[SettingsCardShape] 平滑圆角、无投影、无分割线，默认留白 [CardContentPadding]，多行卡另传 [rowSpacing]。
 *  内层显式裁剪到卡片形状：通栏行（见 [TermDatesCard]）的涟漪铺满整卡宽时，圆角处不溢出卡外。 */
@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = CardContentPadding,
    // 行与行之间的额外间距
    rowSpacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = SettingsCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .clip(SettingsCardShape)
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(rowSpacing),
            content = content,
        )
    }
}

/**
 * 通用设置行：左标签 + 右侧内容。
 * - [onClick] 非空 → 整行可点
 * - [value] 非空 → 显示 primary 色数值（日期、周数等）
 * - [trailing] 非空 → 右侧自定义槽（Switch、时间 chip、文字按钮等）
 * - [showChevron] 默认随 [onClick]；置 false 可去掉行尾箭头
 * - [contentPadding] 画在 clickable **内侧**：默认 0（横向内缩由 SettingsCard.contentPadding 提供）；
 *   通栏行（卡片 contentPadding 归零，见 [TermDatesCard]）用它自带内缩——
 *   padding 在涟漪边界之内，行按压时涟漪铺满整个白块并被卡片圆角裁剪
 */
@Composable
internal fun SettingRow(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    value: String? = null,
    showChevron: Boolean = onClick != null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding)
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
                color = MaterialTheme.colorScheme.primary,
            )
        }
        trailing?.invoke(this)
        if (showChevron) {
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.classppColors.secondaryText,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 时间胶囊：背景灰底、primary 字，点击弹时间选择。
 * 文字启用等宽数字（tnum）：所有时间同为 00:00 五字符，数字位等宽后各胶囊文字宽度天然一致。
 */
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
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.primary,
    )
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

/**
 * 学期起止 + 总周数三行卡（起止弹外部传入的 DatePicker、周数弹输入对话框），
 * 顶部带「学期」分组标签。设置页与切换课表浮层的新建表单共用（见 [SettingRow]）：
 * [onPickStart] / [onPickEnd] 由调用方挂各自的日期选择，[onSetWeeks] 收到合法周数
 * （已按 TERM_WEEKS_MIN..MAX 校验），调用方自行联动结束日（endForTotalWeeks）。
 *
 * 通栏行：卡片 contentPadding 归零，横向 17/13 与首尾 13 下沉到各行 [SettingRow.contentPadding]
 * （clickable 内侧）→ 每行涟漪铺满整个白块、由卡片圆角裁剪；文字位置与原布局完全一致。
 * 行间不留死区：rowSpacing 归零，10dp 行距同样下沉到中间行的 contentPadding（上下各 +10），
 * 中间行涟漪与首末行无缝接轨；视觉行距不变（仍为 6+10+6=22dp），间隙处按压落在中间行上。
 */
@Composable
internal fun TermDatesCard(
    start: IsoDate,
    end: IsoDate,
    onPickStart: () -> Unit,
    onPickEnd: () -> Unit,
    onSetWeeks: (weeks: Int) -> Unit,
) {
    // 与 Schedule.totalWeeks 同式：两端各取所在日历周的周一相减（开学日可为任意星期几）
    val totalWeeks = ((end.mondayOfWeek() - start.mondayOfWeek()).toInt() / 7) + 1
    var pickingWeeks by remember { mutableStateOf(false) }

    SettingsSection(title = stringResource(R.string.section_term)) {
        SettingsCard(contentPadding = PaddingValues(0.dp)) {
            SettingRow(
                label = stringResource(R.string.term_start),
                value = start.toSettingsDateText(),
                onClick = onPickStart,
                contentPadding = PaddingValues(start = 17.dp, end = 13.dp, top = 13.dp),
            )
            SettingRow(
                label = stringResource(R.string.term_end),
                value = end.toSettingsDateText(),
                onClick = onPickEnd,
                // 吸收原 rowSpacing 的 10dp 行距（上下各 +10，另加行自带 6dp）：
                // 涟漪向上接首行、向下接末行，文字位置不变
                contentPadding = PaddingValues(
                    start = 17.dp,
                    end = 13.dp,
                    top = MultiLineRowSpacing,
                    bottom = MultiLineRowSpacing,
                ),
            )
            SettingRow(
                label = stringResource(R.string.term_weeks),
                value = stringResource(R.string.term_weeks_value, totalWeeks),
                onClick = { pickingWeeks = true },
                contentPadding = PaddingValues(start = 17.dp, end = 13.dp, bottom = 13.dp),
            )
        }
    }

    // 周数对话框：仅打开期间进入组合（关闭即出组合，重开以当前周数重置输入）；
    // 非法输入（空/0/>30）→ 字段 isError + 确定禁用；取消/外部点击丢弃
    if (pickingWeeks) {
        var input by remember { mutableStateOf(totalWeeks.toString()) }
        val weeks = input.toIntOrNull()?.takeIf { it in TERM_WEEKS_MIN..TERM_WEEKS_MAX }
        AlertDialog(
            onDismissRequest = { pickingWeeks = false },
            title = {
                Text(stringResource(R.string.term_weeks))
            },
            text = {
                OutlinedTextField(
                    value = input,
                    // 只留 ASCII 数字并截 2 位（1..30 至多两位），结构性杜绝 "-5"/"abc"/全角数字
                    onValueChange = { input = it.filter { c -> c in '0'..'9' }.take(2) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = weeks == null,
                    supportingText = { Text(stringResource(R.string.term_weeks_range)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = weeks != null,
                    onClick = {
                        weeks?.let(onSetWeeks)
                        pickingWeeks = false
                    },
                ) {
                    Text(stringResource(R.string.settings_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pickingWeeks = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
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
