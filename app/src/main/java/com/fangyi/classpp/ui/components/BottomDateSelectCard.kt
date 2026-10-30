package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.classppTextStyles
import dev.chrisbanes.haze.HazeState
import java.util.GregorianCalendar

/** 「清除」宽胶囊的内距 8/13、图标 30dp、图文间距 6dp：同顶栏 [SheetPillButton] 的规格 */
private val ClearPillVerticalPadding = 8.dp
private val ClearPillHorizontalPadding = 13.dp
private val ClearPillIconSize = 30.dp
private val ClearPillIconTextSpacing = 6.dp

/** 「清除」宽胶囊距卡片左右缘 15dp、距滚轮 20dp：分别对齐 BottomSelectSheet 顶栏按钮行的
 *  15dp 与内容槽自带的底部内距 20dp——胶囊与滚轮、卡片底缘的位置关系因此与旧的第三动作
 *  槽位完全一致 */
private val ClearPillHorizontalMargin = 15.dp
private val ClearPillTopSpacing = 20.dp

/** 月/日两位补零显示（08 月 27 日） */
private fun pad2(value: Int): String = value.toString().padStart(2, '0')

/** 某年某月的天数（含闰年 2 月） */
private fun monthLength(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    else -> if (GregorianCalendar().isLeapYear(year)) 29 else 28
}

/** 年份轮默认范围：今年-1 ~ 今年+10（学期起止等大跨度场景用 [BottomDateSelectCard] 的 yearRange 调宽） */
private fun defaultYearRange(): IntRange =
    IsoDate.today().yearMonthDay().first.let { (it - 1)..(it + 10) }

/**
 * 日期选择弹窗（BottomSelectSheet 变体之二）：年/月/日三列滚轮，上下滑动选择，
 * 补零显示（2026 年 08 月 27 日）。滚轮视觉与手感同时间选择（共享积木 SelectWheel.kt）。
 *
 * 「日」轮格数随年月联动：年/月吸附跨过新值即按新月天数增减（[monthLength]），越界值
 * 就地折回（1 月 31 日 → 滚到 4 月变 30 日），确认值恒为合法日历日期（[IsoDate.of]
 * 对非法日期会抛异常，确认侧再做一次天数收敛兜底）。
 *
 * [onConfirm]（确认胶囊）回调当前吸附的 [IsoDate]；滚轮在弹窗保持组合期间记住上次
 * 选择，移出组合（[onDismissed] 之后）重建时回到 [initialDate]。[initialDate] 的年份
 * 落在 [yearRange] 之外时年轮停在最近边界。其余行为（两段式关闭、遮罩、卡片磨砂
 * [hazeState]、阴影参数 [shadowAlpha]）同 [BottomSelectSheet]。
 *
 * [onClear] 非空时在滚轮下方多画一枚整宽的「清除」白胶囊（[ClearPillButton]，本组件私有）：
 * 点按只经它回调，清除后的回写与关闭都由宿主在回调里完成（清除是终态，不会接着弹时刻轮）；
 * null = 不渲染（未设截止时没有可清除的值）。
 */
@Composable
fun BottomDateSelectCard(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.date_select_title),
    initialDate: IsoDate = IsoDate.today(),
    yearRange: IntRange = defaultYearRange(),
    shadowAlpha: Float = 0.3f,
    hazeState: HazeState? = null,
    // 可选「清除」动作：非空时在滚轮下方渲染本组件私有的整宽白胶囊（见 [ClearPillButton]）
    onClear: (() -> Unit)? = null,
    onConfirm: (date: IsoDate) -> Unit,
    onDismissed: () -> Unit = {},
) {
    val (initYear, initMonth, initDay) = initialDate.yearMonthDay()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val yearState = remember(density, yearRange) {
        WheelState(
            count = yearRange.count(),
            initialIndex = (initYear - yearRange.first).coerceIn(0, yearRange.count() - 1),
            density = density,
            scope = scope,
        )
    }
    val monthState = remember(density) {
        WheelState(count = 12, initialIndex = initMonth - 1, density = density, scope = scope)
    }
    val dayState = remember(density) {
        WheelState(
            count = monthLength(initYear, initMonth),
            initialIndex = initDay - 1,
            density = density,
            scope = scope,
        )
    }

    // 「日」轮格数随年月联动：年/月的选中值跨过新格即增减天数，越界值就地折回
    val daysInMonth by remember {
        derivedStateOf { monthLength(yearState.selectedIndex, monthState.selectedIndex + 1) }
    }
    LaunchedEffect(daysInMonth) { dayState.updateCount(daysInMonth) }

    BottomSelectSheet(
        title = title,
        visible = visible,
        onDismiss = onDismiss,
        onDismissed = onDismissed,
        shadowAlpha = shadowAlpha,
        hazeState = hazeState,
        modifier = modifier,
        onConfirm = {
            // 兜底收敛：格数联动的副作用晚于滚动一拍时，防 31 日 + 30 天月这类非法组合
            val year = yearRange.first + yearState.selectedIndex
            val month = monthState.selectedIndex + 1
            val day = (dayState.selectedIndex + 1).coerceAtMost(monthLength(year, month))
            onConfirm(IsoDate.of(year, month, day))
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NumberWheel(
                state = yearState,
                format = { (yearRange.first + it).toString() },
                modifier = Modifier.weight(1f),
                fontSize = ClassppTextTokens.dateWheelNumber.fontSize,
                unit = stringResource(R.string.date_unit_year),
            )
            NumberWheel(
                state = monthState,
                format = { pad2(it + 1) },
                modifier = Modifier.weight(1f),
                fontSize = ClassppTextTokens.dateWheelNumber.fontSize,
                unit = stringResource(R.string.date_unit_month),
            )
            NumberWheel(
                state = dayState,
                format = { pad2(it + 1) },
                modifier = Modifier.weight(1f),
                fontSize = ClassppTextTokens.dateWheelNumber.fontSize,
                unit = stringResource(R.string.date_unit_day),
            )
        }
        // 「清除」宽胶囊（可选，见 [onClear]）：上方与滚轮留 [ClearPillTopSpacing]（20dp，
        // 与内容槽的底部内距同值），下方则是内容槽自带的底部内距——这枚胶囊原本挂在内容槽
        // 之外（BottomSelectSheet 的第三动作槽位），两个 20dp 的分工刚好对调，位置关系不变
        onClear?.let { clear ->
            Spacer(Modifier.size(ClearPillTopSpacing))
            ClearPillButton(
                // 收场滑出期间（visible 已 false）点击与按压反馈一并失效：与弹卡顶栏
                // 各按钮的收场闸门同效，这里没有 closeRequested 可读，用可见态顶替
                enabled = visible,
                modifier = Modifier.padding(horizontal = ClearPillHorizontalMargin),
                onClick = clear,
            )
        }
    }
}

/**
 * 「清除」宽胶囊（本组件私有）：整宽白胶囊，图标在左、文字在右、「图标 + 文字」落在胶囊
 * 正中，整行可点（按压反馈铺满整行、取代涟漪）。
 *
 * 规格与顶栏 [SheetPillButton] 一致——[PillShape] 形状、surface 底 onSurface 内容色、
 * [classppTextStyles.pillButton] 文字、图标 30dp、45dp 撑开的大柔影与 1.10f 按压放大；
 * 差别只在排布：那一枚的宽度随内容自适应、内容靠起始侧，这一枚是整宽使用场景，故收在
 * 本组件内私有实现，不给共享组件开参数（该改动会波及所有顶栏胶囊）。
 *
 * [enabled] 传宿主的可见态（见调用处）：出场滑出期间点击与按压反馈一并失效。
 */
@Composable
private fun ClearPillButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressClickable(PillShape, scale = 1.10f, enabled = enabled, onClick = onClick)
            .graphicsLayer {
                shape = PillShape
                clip = true
                // 大柔影：同顶栏胶囊（高 elevation 撑开模糊半径、低透明度阴影色压存在感）
                shadowElevation = 45.dp.toPx()
                spotShadowColor = Color.Black.copy(alpha = 0.3f)
            }
            .background(MaterialTheme.colorScheme.surface, PillShape)
            .padding(
                horizontal = ClearPillHorizontalPadding,
                vertical = ClearPillVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(
            ClearPillIconTextSpacing,
            Alignment.CenterHorizontally,
        ),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_delete_filled),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(ClearPillIconSize),
        )
        Text(
            text = stringResource(R.string.deadline_card_clear),
            style = MaterialTheme.classppTextStyles.pillButton,
            color = contentColor,
        )
    }
}
