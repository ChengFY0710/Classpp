package com.fangyi.classpp.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 浮层选择卡片：白卡片「标题 + 右侧蓝色值 + 上下箭头」，点按在卡片右上方弹出菜单选择。
 * [items] 只传合法选项（不符合要求的选项不出现），由调用方裁剪。
 *
 * 现为 [SettingsCard] 选择行（[SettingsCardItem.Select]）的薄封装，保持原 API——
 * 加课面板等调用方零改动；白卡规格、菜单锚点与「打开前收起键盘与焦点」的行为
 * 全部由新组件的选择行内建。
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
    SettingsCard(
        modifier = modifier,
        items = listOf(
            SettingsCardItem.Select(
                label = title,
                value = valueText,
                items = items,
                selectedId = selectedId,
                onPick = onPick,
            ),
        ),
    )
}
