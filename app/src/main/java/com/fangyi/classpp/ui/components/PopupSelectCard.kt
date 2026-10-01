package com.fangyi.classpp.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.theme.OnSurface
import com.fangyi.classpp.ui.theme.Primary

/**
 * 浮层选择卡片：白卡片「标题 + 右侧蓝色值 + 上下箭头」，点按在卡片右上方弹出菜单选择。
 * [items] 只传合法选项（不符合要求的选项不出现），由调用方裁剪。
 *
 * 打开菜单前先收起键盘与焦点：键盘若开着，菜单会被盖住、输入焦点还留在原输入框上。
 * 菜单锚在右侧箭头上（独立 Popup 窗口，不受浮层圆角裁剪），靠窗口右缘自动钳位对齐卡片右缘。
 */
@Composable
fun PopupSelectCard(
    title: String,
    valueText: String,
    items: List<Pair<Int, String>>,
    selectedId: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    SheetCard(
        modifier = modifier.fillMaxWidth(),
        onClick = {
            focusManager.clearFocus()
            keyboard?.hide()
            expanded = true
        },
    ) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = OnSurface,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = valueText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primary,
        )
        // 菜单锚点：上缘与卡片内容顶对齐（卡片内距 14 + 图标上缘），下弹后被 offset 抬回卡片顶
        Box {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_up_down),
                contentDescription = null,
                tint = Primary,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(20.dp),
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                // 锚点底缘 ≈ 卡片顶 + 14 + 20；上移 34dp 使菜单顶边贴卡片顶边（对齐设计稿）
                offset = DpOffset(x = 0.dp, y = (-34).dp),
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                items.forEach { (id, label) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = label,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = OnSurface,
                            )
                        },
                        onClick = {
                            expanded = false
                            if (id != selectedId) onPick(id)
                        },
                    )
                }
            }
        }
    }
}
