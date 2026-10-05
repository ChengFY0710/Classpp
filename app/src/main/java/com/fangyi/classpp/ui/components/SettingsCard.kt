package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.theme.SettingsCardShape
import com.fangyi.classpp.ui.theme.classppTextStyles

/**
 * 设置卡条目：导航行（label + 蓝色箭头）或开关行（label + [ClassppSwitch]），
 * 均可带灰色描述文字（自动换行、行卡随之长高）。
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
            SettingsCardRow(item)
        }
    }
}

/** 单行渲染：padding 在 clickable/toggleable 内侧，涟漪（或开关整行点区）铺满整行宽 */
@Composable
private fun SettingsCardRow(item: SettingsCardItem) {
    val interactionModifier = when (item) {
        is SettingsCardItem.Nav -> Modifier.clickable(onClick = item.onClick)
        is SettingsCardItem.Toggle -> Modifier.toggleable(
            value = item.checked,
            role = Role.Switch,
            onValueChange = item.onCheckedChange,
        )
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
        when (item) {
            is SettingsCardItem.Nav -> {
                if (item.value != null) {
                    Text(
                        text = item.value,
                        style = MaterialTheme.classppTextStyles.fieldValue,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Chevron()
            }
            is SettingsCardItem.Toggle -> ClassppSwitch(checked = item.checked, onCheckedChange = null)
        }
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
