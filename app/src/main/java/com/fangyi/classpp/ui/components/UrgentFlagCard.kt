package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.TodoUrgency
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.SheetCardShape
import com.fangyi.classpp.ui.theme.SheetFieldHeight
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles

/** 旗标图标尺寸（设计稿左右两处同尺寸） */
private val FlagIconSize = 24.dp

/** 右侧切换旗标的触控热区：40dp 相接 = 中心间距 40dp、图标视觉空隙 16dp，复刻设计稿节奏 */
private val FlagTouchTarget = 40.dp

/** 紧急程度 → 旗标色：红 = Error、灰 = 次级文字灰（设计稿指定），橙/黄/绿取主题自定义色。
 *  待办列表/筛选器等要画旗标的地方直接复用。 */
@Composable
fun TodoUrgency.flagColor(): Color = when (this) {
    TodoUrgency.Critical -> MaterialTheme.colorScheme.error
    TodoUrgency.High -> MaterialTheme.classppColors.urgentOrange
    TodoUrgency.Medium -> MaterialTheme.classppColors.urgentYellow
    TodoUrgency.Low -> MaterialTheme.classppColors.urgentGreen
    TodoUrgency.None -> MaterialTheme.classppColors.secondaryText
}

/** 紧急程度 → 档位名（非常紧急/很紧急/紧急/不紧急/无） */
@Composable
fun TodoUrgency.flagLabel(): String = stringResource(
    when (this) {
        TodoUrgency.Critical -> R.string.urgency_critical
        TodoUrgency.High -> R.string.urgency_high
        TodoUrgency.Medium -> R.string.urgency_medium
        TodoUrgency.Low -> R.string.urgency_low
        TodoUrgency.None -> R.string.urgency_none
    },
)

/**
 * 紧急旗标卡：白卡内左侧当前紧急程度、右侧其余四档旗标点按切换（设计稿五态即本组件
 * 在五个档位下的样子）。
 *
 * - 左：当前档旗标 + 档位名，同取档位色（[fieldLabel] 字号字重 + 颜色覆写）；
 * - 右：除当前档外的全部档位（按枚举固定顺序 Critical→High→Medium→Low→None），
 *   每档 [FlagTouchTarget] 触控热区（胶囊 ripple、中心 24dp 旗标），点按回调 [onSelect]；
 * - 无选中描边等特殊态：切换即整卡换色，反馈足够直接。
 *
 * 卡片容器对齐行卡家族：SheetCardShape + surface 底 + 16/14 内距 + 60dp 行高下限，无阴影。
 *
 * @param selected 当前紧急程度
 * @param onSelect 点按右侧某档旗标时回调该档位
 */
@Composable
fun UrgentFlagCard(
    selected: TodoUrgency,
    onSelect: (TodoUrgency) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SheetFieldHeight)
            .clip(SheetCardShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // —— 左：当前档 ——
        val currentColor = selected.flagColor()
        Icon(
            painter = painterResource(R.drawable.ic_flag),
            contentDescription = null,
            tint = currentColor,
            modifier = Modifier.size(FlagIconSize),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = selected.flagLabel(),
            style = MaterialTheme.classppTextStyles.fieldLabel.copy(color = currentColor),
        )

        Spacer(Modifier.weight(1f))

        // —— 右：其余四档，点按切换 ——
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            TodoUrgency.entries.filter { it != selected }.forEach { option ->
                Box(
                    modifier = Modifier
                        .size(FlagTouchTarget)
                        .clip(PillShape)
                        .clickable { onSelect(option) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_flag),
                        contentDescription = option.flagLabel(),
                        tint = option.flagColor(),
                        modifier = Modifier.size(FlagIconSize),
                    )
                }
            }
        }
    }
}
