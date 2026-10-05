package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.theme.MenuShape
import com.fangyi.classpp.ui.theme.SettingsCardShape
import com.fangyi.classpp.ui.theme.classppTextStyles

/**
 * 设置卡条目：导航行（label + 蓝色箭头）、开关行（label + [ClassppSwitch]）、
 * 选择行（label + 蓝色当前值 + 上下箭头，点行弹菜单）或自定义行（尾部槽位放任意控件），
 * 前三者均可带灰色描述文字（自动换行、行卡随之长高）。
 */
sealed interface SettingsCardItem {
    val label: String
    val description: String?

    /** 导航行：整行可点，右侧可带 primary 色当前值，尾部蓝色箭头 */
    data class Nav(
        override val label: String,
        override val description: String? = null,
        val value: String? = null,
        val onClick: () -> Unit,
    ) : SettingsCardItem

    /** 开关行：整行可点切换（涟漪铺满整行），尾部 [ClassppSwitch] 只作视觉件 */
    data class Toggle(
        override val label: String,
        override val description: String? = null,
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
    ) : SettingsCardItem

    /**
     * 选择行：整行可点弹出菜单（同 [PopupSelectCard]，打开前自动收起键盘与焦点），
     * [value] 为当前值的展示文字，[items] 只传合法选项（由调用方裁剪）。
     */
    data class Select(
        override val label: String,
        override val description: String? = null,
        val value: String,
        val items: List<Pair<Int, String>>,
        val selectedId: Int,
        val onPick: (Int) -> Unit,
    ) : SettingsCardItem

    /**
     * 自定义行：label（+ 可选描述）与 [trailing] 同行垂直居中，无整行点击（尾部控件自理）。
     * 供暂未收敛为标准行型的控件使用（如节次卡的加减按钮、时间胶囊）；
     * [footer] 可选，画在整行之下、占满行宽（如节次上限的溢出提示），内距由内容自理。
     */
    data class Custom(
        override val label: String,
        override val description: String? = null,
        val trailing: @Composable RowScope.() -> Unit,
        val footer: (@Composable () -> Unit)? = null,
    ) : SettingsCardItem
}

/**
 * 设置卡片：白底（surface）+ [SettingsCardShape] 连续曲率圆角，内含一至多行 [SettingsCardItem]。
 * 行与行无缝堆叠、每行自带内距——整行涟漪铺满行宽后被卡片圆角裁剪（对齐设计稿「点击涟漪」）。
 *
 * 规格（对齐 [SheetTextField]）：
 * - 字体：label 走 fieldLabel（16sp SemiBold onSurface），描述走 fieldPlaceholder
 *   （16sp Medium secondaryText）；
 * - 间距：行高 min 60dp（[SheetFieldHeight]），行内左右 16 / 上下 14，内距画在交互区**内侧**；
 * - 颜色只取四处主题色：surface（卡底）、onSurface（label）、primary（箭头/开关轨道/行值，
 *   值经 fieldValue 角色）、classppColors.secondaryText（描述），深浅模式自动适配。
 */
@Composable
fun SettingsCard(
    items: List<SettingsCardItem>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(SettingsCardShape)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        items.forEach { item ->
            if (item is SettingsCardItem.Custom) CustomRow(item) else SettingsCardRow(item)
        }
    }
}

/** 单行渲染：padding 在 clickable/toggleable 内侧，涟漪（或开关整行点区）铺满整行宽 */
@Composable
private fun SettingsCardRow(item: SettingsCardItem) {
    // 菜单开合只有选择行用得到，其余行型状态恒为 false 不产生行为
    var menuExpanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val interactionModifier = when (item) {
        is SettingsCardItem.Nav -> Modifier.clickable(onClick = item.onClick)
        is SettingsCardItem.Toggle -> Modifier.toggleable(
            value = item.checked,
            role = Role.Switch,
            onValueChange = item.onCheckedChange,
        )
        is SettingsCardItem.Select -> Modifier.clickable {
            // 打开菜单前先收起键盘与焦点：键盘若开着，菜单会被盖住、焦点还留在原输入框上
            focusManager.clearFocus()
            keyboard?.hide()
            menuExpanded = true
        }
        // 自定义行无整行点击（尾部控件自理）
        is SettingsCardItem.Custom -> Modifier
    }
    val rowValue = when (item) {
        is SettingsCardItem.Nav -> item.value
        is SettingsCardItem.Select -> item.value
        is SettingsCardItem.Toggle, is SettingsCardItem.Custom -> null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(interactionModifier)
            .heightIn(min = SheetFieldHeight)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsRowText(label = item.label, description = item.description, modifier = Modifier.weight(1f))
        if (rowValue != null) {
            Text(
                text = rowValue,
                style = MaterialTheme.classppTextStyles.fieldValue,
            )
            Spacer(Modifier.width(6.dp))
        }
        when (item) {
            is SettingsCardItem.Nav -> Chevron()
            is SettingsCardItem.Toggle -> ClassppSwitch(checked = item.checked, onCheckedChange = null)
            is SettingsCardItem.Select -> SelectMenu(
                item = item,
                expanded = menuExpanded,
                onDismiss = { menuExpanded = false },
            )
            // 自定义行由 CustomRow 渲染，走不到这里
            is SettingsCardItem.Custom -> Unit
        }
    }
}

/** 自定义行渲染：文字块与尾部槽同行居中，[SettingsCardItem.Custom.footer] 画在行下、占满行宽 */
@Composable
private fun CustomRow(item: SettingsCardItem.Custom) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SheetFieldHeight)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsRowText(label = item.label, description = item.description, modifier = Modifier.weight(1f))
            item.trailing(this)
        }
        item.footer?.invoke()
    }
}

/** label + 可选灰色描述：无描述时单行垂直居中，有描述时整块随行数长高、与尾部控件垂直居中 */
@Composable
private fun SettingsRowText(
    label: String,
    description: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.classppTextStyles.fieldLabel,
        )
        if (description != null) {
            Text(
                text = description,
                // 描述可能多行换行：补 20sp 行高，多行时行间节奏与 16sp 字号匹配
                style = MaterialTheme.classppTextStyles.fieldPlaceholder.copy(lineHeight = 20.sp),
            )
        }
    }
}

@Composable
private fun Chevron() {
    Icon(
        painter = painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp),
    )
}

/**
 * 选择行尾部：上下箭头 + 锚在其上的弹出菜单（独立 Popup 窗口，不受卡片圆角裁剪）。
 * offset 上移 34dp 使菜单顶边贴行卡顶边（对齐设计稿），自 PopupSelectCard 沿袭。
 */
@Composable
private fun SelectMenu(
    item: SettingsCardItem.Select,
    expanded: Boolean,
    onDismiss: () -> Unit,
) {
    Box {
        Icon(
            painter = painterResource(R.drawable.ic_chevron_up_down),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            offset = DpOffset(x = 0.dp, y = (-34).dp),
            shape = MenuShape,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            item.items.forEach { (id, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label,
                            style = MaterialTheme.classppTextStyles.menuItem,
                            lineHeight = 16.sp,
                        )
                    },
                    onClick = {
                        onDismiss()
                        if (id != item.selectedId) item.onPick(id)
                    },
                )
            }
        }
    }
}
