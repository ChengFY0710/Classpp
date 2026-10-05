package com.fangyi.classpp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.schedule.TERM_WEEKS_MAX
import com.fangyi.classpp.ui.schedule.TERM_WEEKS_MIN
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * 设置页组件：顶栏 [SettingsTopBar] 与学期设置卡 [TermDatesCard]。
 * 通用卡片已收敛到 components 的新版 SettingsCard（导航/开关/选择三种行型）；
 * 旧版 SettingsCard / SettingRow / TimeChip 仅剩课表设置浮层的节次卡在用，
 * 已随迁至该文件私有化（LegacySettingsCard）。
 *
 * 配色约定：页面底 `colorScheme.background`、卡底 `Surface`、强调 `colorScheme.primary`、
 * 次级文字 `classppColors.secondaryText`。
 */

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
                    style = MaterialTheme.classppTextStyles.topBarTitle,
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
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
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

/**
 * 学期起止 + 总周数三行卡（起止弹外部传入的 DatePicker、周数弹输入对话框），
 * 顶部带「学期」分组标签。设置页与切换课表浮层的新建表单共用：
 * [onPickStart] / [onPickEnd] 由调用方挂各自的日期选择，[onSetWeeks] 收到合法周数
 * （已按 TERM_WEEKS_MIN..MAX 校验），调用方自行联动结束日（endForTotalWeeks）。
 *
 * 卡体是 components 的新版 [com.fangyi.classpp.ui.components.SettingsCard]
 * （三枚导航行：行内建 16/14 内距与 60dp 行高、涟漪通栏满行由卡片圆角裁剪），
 * 此前「contentPadding 下沉到各行」的通栏手法不再需要。
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

    CardSection(title = stringResource(R.string.section_term)) {
        SettingsCard(
            items = listOf(
                SettingsCardItem.Nav(
                    label = stringResource(R.string.term_start),
                    value = start.toSettingsDateText(),
                    onClick = onPickStart,
                ),
                SettingsCardItem.Nav(
                    label = stringResource(R.string.term_end),
                    value = end.toSettingsDateText(),
                    onClick = onPickEnd,
                ),
                SettingsCardItem.Nav(
                    label = stringResource(R.string.term_weeks),
                    value = stringResource(R.string.term_weeks_value, totalWeeks),
                    onClick = { pickingWeeks = true },
                ),
            ),
        )
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

/** 学期设置卡预览：新 SettingsCard 三行导航（值 + 蓝色箭头），对照设计稿 */
@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, name = "学期设置卡")
@Composable
private fun TermDatesCardPreview() {
    ClassppTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            TermDatesCard(
                start = IsoDate.of(2026, 9, 7),
                end = IsoDate.of(2027, 1, 17),
                onPickStart = {},
                onPickEnd = {},
                onSetWeeks = {},
            )
        }
    }
}
