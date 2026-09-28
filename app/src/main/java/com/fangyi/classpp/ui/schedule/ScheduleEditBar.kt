package com.fangyi.classpp.ui.schedule

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.theme.CancelRed
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.SaveGreen
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

private val EditActionTextSize = 18.sp
private val EditActionIconSize = 30.dp
private val EditActionGap = 6.dp
private val EditActionInnerPadding = 8.dp

/** 动作块离屏幕边的距离 = 顶栏图标内缩 20dp − 动作块自身水平内缩 → 图标仍落在 20dp 上 */
private val EditBarEdgePadding = 20.dp

private val EditActionShape = RoundedCornerShape(10.dp)

/**
 * 编辑态顶栏：保存 / 切换课表 / 取消 + 星期行，取代可折叠的 [ScheduleHeader]。
 *
 * 三按钮一行高 [TopBarHeight]，与顶栏行等高，两侧图标内缩也与顶栏图标一致，
 * 进出编辑态时图标位置不跳。星期行沿用头部同款 padding 与字号，保证与网格列对齐；
 * 编辑态没有"今天"语义，故不做今日高亮。
 *
 * 两侧的动作各自是"图标 + 文字"整块可点（设计稿里图标与文字同色成对），
 * 中间的"切换课表"是纯文字。
 *
 * [blurProgress] / [hazeState]：与 [ScheduleHeader] 折叠后同一套背景模糊——
 * 内容滚到编辑栏下方时按 [blurProgress] 渐入，顶部最强、向下渐弱；回到顶部恢复不透明。
 */
@Composable
fun ScheduleEditBar(
    onSave: () -> Unit,
    onSwitchSchedule: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    blurProgress: Float = 0f,
    hazeState: HazeState? = null,
) {
    val weekdays = stringArrayResource(R.array.weekdays)
    val progress = blurProgress.coerceIn(0f, 1f)
    Column(
        modifier = modifier
            .fillMaxWidth()
            // surface 兜底：progress≈0 时与原不透明背景逐帧一致
            .background(Color.Transparent)
            .then(
                if (hazeState != null && progress > 0f) {
                    // 背景模糊画在兜底色之上、内容之下；alpha 随进度渐入实现无缝衔接
                    Modifier.hazeEffect(hazeState) {
                        alpha = progress
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
            )
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TopBarHeight),
        ) {
            EditAction(
                text = stringResource(R.string.edit_save),
                iconRes = R.drawable.ic_calendar_checkmark,
                color = SaveGreen,
                iconAtStart = true,
                onClick = onSave,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = EditBarEdgePadding),
            )
            Text(
                text = stringResource(R.string.edit_switch_schedule),
                color = MaterialTheme.colorScheme.primary,
                fontSize = EditActionTextSize,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(EditActionShape)
                    .clickable(onClick = onSwitchSchedule)
                    .padding(horizontal = EditActionInnerPadding, vertical = 6.dp),
            )
            EditAction(
                text = stringResource(R.string.edit_cancel),
                iconRes = R.drawable.ic_calendar_cancel,
                color = CancelRed,
                iconAtStart = false,
                onClick = onCancel,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = EditBarEdgePadding),
            )
        }
        // 星期行：与 ScheduleHeader 同几何（零水平边距五等分），列宽与网格天然对齐
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 7.dp),
        ) {
            for (i in 0..4) {
                Text(
                    text = weekdays[i].substring(1),
                    modifier = Modifier.weight(1f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** 编辑栏两侧的"图标 + 文字"动作块（[iconAtStart] 决定图标在文字左还是右） */
@Composable
private fun EditAction(
    text: String,
    @DrawableRes iconRes: Int,
    color: Color,
    iconAtStart: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(EditActionShape)
            .clickable(onClick = onClick)
            .padding(horizontal = EditActionInnerPadding, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconAtStart) {
            EditActionIcon(iconRes = iconRes, color = color)
            Spacer(Modifier.width(EditActionGap))
        }
        Text(
            text = text,
            color = color,
            fontSize = EditActionTextSize,
            fontWeight = FontWeight.SemiBold,
        )
        if (!iconAtStart) {
            Spacer(Modifier.width(EditActionGap))
            EditActionIcon(iconRes = iconRes, color = color)
        }
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
        ScheduleEditBar(onSave = {}, onSwitchSchedule = {}, onCancel = {})
    }
}
