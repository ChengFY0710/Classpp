package com.fangyi.classpp.ui.schedule

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.motion.TabTransitionState
import com.fangyi.classpp.ui.motion.tabTransitionFreeze
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.EditActionShape
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

private val EditActionIconSize = 30.dp
private val EditActionLabelTextSize = 13.sp
private val EditActionLabelGap = 2.dp
private val EditActionInnerPadding = 8.dp

/** 动作行高度：纵向"图标 + 标签"块在其中垂直居中，上下留出呼吸空间 */
private val EditActionBarHeight = 64.dp

/**
 * 编辑态顶栏：取消 / 切换课表 / 课表设置 / 保存 + 星期行，取代可折叠的 [ScheduleHeader]。
 *
 * 四个动作块等距一行（各占一等份），每块为"图标在上、文字在下"的纵向组合，
 * 文字 13sp Medium、与图标水平居中对齐；取消/保存分居最左/最右。
 * 星期行沿用头部同款 padding 与字号、按 [daysPerWeek] 等分（5/7 列），
 * 保证与网格列对齐；编辑态没有"今天"语义，故不做今日高亮。
 *
 * [blurProgress] / [hazeState]：与 [ScheduleHeader] 折叠后同一套背景模糊——
 * 内容滚到编辑栏下方时按 [blurProgress] 渐入，顶部最强、向下渐弱；回到顶部恢复不透明。
 *
 * [tabTransition] 非 null 时启用同款转场冻结层（[tabTransitionFreeze]）：
 * 覆盖「转场中途点编辑」的边角情况——编辑栏在转场结束前进场时同样重放就位帧，
 * 不让 haze 用中间态几何重采样。
 */
@Composable
fun ScheduleEditBar(
    onSave: () -> Unit,
    onSwitchSchedule: () -> Unit,
    onScheduleSettings: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    blurProgress: Float = 0f,
    hazeState: HazeState? = null,
    daysPerWeek: Int = 5,
    tabTransition: TabTransitionState? = null,
) {
    // 列数只认资源里真有的星期名（同 ScheduleHeader）
    val weekdayNames = stringArrayResource(R.array.weekdays)
    val days = daysPerWeek.coerceIn(1, weekdayNames.size)
    // 星期行的列标签用单字简称资源（同 ScheduleHeader），勿对全名做字符串假设
    val weekdays = stringArrayResource(R.array.weekdays_short).take(days)
    val progress = blurProgress.coerceIn(0f, 1f)
    // hazeEffect 的 block 在绘制期执行、非 composable 上下文：tint 取值提到 modifier 之前
    val hazeTint = MaterialTheme.classppColors.hazeTint
    // 转场冻结层：就位帧（含模糊输出）录制于此，tab 转场期间整帧重放
    val transitionFreezeLayer = rememberGraphicsLayer()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (tabTransition != null) {
                    Modifier.tabTransitionFreeze(transitionFreezeLayer, tabTransition)
                } else {
                    Modifier
                },
            )
            // surface 兜底：progress≈0 时与原不透明背景逐帧一致
            .background(Color.Transparent)
            .then(
                if (hazeState != null && progress > 0f) {
                    // 背景模糊画在兜底色之上、内容之下；alpha 随进度渐入实现无缝衔接。
                    // forceInvalidateOnPreDraw 与 ScheduleHeader 同款：源重画后强制重采样，
                    // 杜绝快速切 tab 后首帧画出空内容（只剩白底）的闪帧
                    Modifier.hazeEffect(hazeState) {
                        alpha = progress
                        blurRadius = 32.dp
                        progressive = HazeProgressive.verticalGradient(
                            startIntensity = 1f,
                            endIntensity = 0f,
                        )
                        tints = listOf(HazeTint(hazeTint.copy(alpha = 0.30f)))
                        noiseFactor = 0f
                        forceInvalidateOnPreDraw = true
                    }
                } else {
                    Modifier
                },
            )
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Spacer(modifier = Modifier.fillMaxWidth().height(6.dp)) //调整icon到屏幕顶端距离
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(EditActionBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditAction(
                text = stringResource(R.string.edit_cancel),
                iconRes = R.drawable.ic_calendar_cancel,
                color = MaterialTheme.colorScheme.error,
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .wrapContentWidth(Alignment.CenterHorizontally),
            )
            EditAction(
                text = stringResource(R.string.edit_switch_schedule),
                iconRes = R.drawable.ic_calendar_multiple,
                color = MaterialTheme.colorScheme.onSurface,
                onClick = onSwitchSchedule,
                modifier = Modifier
                    .weight(1f)
                    .wrapContentWidth(Alignment.CenterHorizontally),
            )
            EditAction(
                text = stringResource(R.string.edit_schedule_settings),
                iconRes = R.drawable.ic_calendar_settings,
                color = MaterialTheme.colorScheme.onSurface,
                onClick = onScheduleSettings,
                modifier = Modifier
                    .weight(1f)
                    .wrapContentWidth(Alignment.CenterHorizontally),
            )
            EditAction(
                text = stringResource(R.string.edit_save),
                iconRes = R.drawable.ic_calendar_checkmark,
                color = MaterialTheme.colorScheme.primary,
                onClick = onSave,
                modifier = Modifier
                    .weight(1f)
                    .wrapContentWidth(Alignment.CenterHorizontally),
            )
        }
        // 星期行：与 ScheduleHeader 同几何（零水平边距按天数等分），列宽与网格天然对齐
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 7.dp),
        ) {
            for (i in weekdays.indices) {
                Text(
                    text = weekdays[i],
                    modifier = Modifier
                        .weight(1f)
                        .wrapContentWidth(Alignment.CenterHorizontally),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** 编辑栏的纵向动作块：图标在上、13sp Medium 标签在下，两者水平居中对齐，整块可点 */
@Composable
private fun EditAction(
    text: String,
    @DrawableRes iconRes: Int,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(EditActionShape)
            .clickable(onClick = onClick)
            .padding(horizontal = EditActionInnerPadding, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EditActionIcon(iconRes = iconRes, color = color)
        Spacer(Modifier.height(EditActionLabelGap))
        Text(
            text = text,
            color = color,
            fontSize = EditActionLabelTextSize,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 动作图标：文字已经承载语义，故 contentDescription 交给整块的文本 */
@Composable
private fun EditActionIcon(@DrawableRes iconRes: Int, color: Color) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(EditActionIconSize),
    )
}

@Preview(showBackground = true, name = "编辑栏")
@Composable
private fun ScheduleEditBarPreview() {
    ClassppTheme {
        ScheduleEditBar(onSave = {}, onSwitchSchedule = {}, onScheduleSettings = {}, onCancel = {})
    }
}
