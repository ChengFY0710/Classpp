package com.fangyi.classpp.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.sync.Capability
import com.fangyi.classpp.data.sync.SchoolSpec
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SheetCard
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetInfoBanner
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.schools.schoolText
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles

/**
 * 学校选择浮层（指导 §3.2）：列出 [schools] 注册序，当前学校行文字主色 + 行尾主题色对勾。
 *
 * - 点当前学校 → 仅关浮层；点他校 → 回调 [onSelect]，切换与持久化由宿主（注册表）负责；
 * - 行规格沿用浮层双行列表卡统一规格：72dp 卡高、默认内距、4dp 行距、副行单行省略；
 *   副行 = 能力摘要（待办同步 · 教务课表导入）；
 * - 只注册一所学校时照常渲染（仅一行选中态），底部多校提示 [R.string.school_picker_sync_hint]
 *   仅在 [schools] 多于一项时显示；
 * - `busy`（同步/自动续期在途）时整行不响应：宿主传入的回调自行判定，浮层不弹 toast；
 * - 顶部是远端学校（Tier 1）的手动更新入口：远端学校默认关闭，只有用户点这一行才会
 *   联网拉取；下载的声明保存到本地，重启后仍可用。浮层自身不发起任何请求。
 */
@Composable
internal fun SchoolPickerSheet(
    visible: Boolean,
    schools: List<SchoolSpec>,
    activeSchoolId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    onRefreshRemote: () -> Unit = {},
) {
    // 返回键关浮层（与其余浮层一致的两段式：visible 复位后播退场，播完才卸载）
    BackHandler(enabled = visible) {
        onDismiss()
    }

    OverlaySheet(
        title = stringResource(R.string.school_picker_title),
        rightAction = SheetTopAction(
            label = stringResource(R.string.switcher_close),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = onDismiss,
        ),
        onDismiss = onDismiss,
        visible = visible,
        onDismissed = onDismissed,
        // 列表无输入框，键盘不必让位
        imeBehavior = SheetImeBehavior.IgnoreIme,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            // 远端学校（Tier 1）：默认关闭，只保留手动更新入口。联网与结果提示由宿主负责，
            // 浮层自身不发起任何请求。
            CardSection(spacing = SchoolRowSpacing) {
                SettingsCard(
                    items = listOf(
                        SettingsCardItem.Nav(
                            label = stringResource(R.string.settings_remote_refresh_title),
                            description = stringResource(R.string.settings_remote_refresh_desc),
                            onClick = onRefreshRemote,
                        ),
                    ),
                )
            }
            CardSection(
                title = stringResource(R.string.school_picker_all_schools),
                spacing = SchoolRowSpacing,
            ) {
                schools.forEach { spec ->
                    SchoolRow(
                        spec = spec,
                        selected = spec.id == activeSchoolId,
                        onClick = { onSelect(spec.id) },
                    )
                }
            }
            // 单活语义说明：只有多校时才有信息量
            if (schools.size > 1) {
                SheetInfoBanner(text = stringResource(R.string.school_picker_sync_hint))
            }
        }
    }
}

/** 学校行：学校名 + 能力摘要两行；当前学校主色 + 行尾对勾（同选择类浮层） */
@Composable
private fun SchoolRow(
    spec: SchoolSpec,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    SheetCard(
        // 72dp 卡高：双行列表卡统一规格（内容不足时由 heightIn 撑到下限）
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SchoolRowHeight),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = schoolText(spec.displayName),
                style = MaterialTheme.classppTextStyles.fieldLabel.copy(
                    color = if (selected) colors.primary else colors.onSurface,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = capabilitySummary(spec),
                style = ClassppTextTokens.settingsDescription.copy(
                    color = MaterialTheme.classppColors.secondaryText,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(
                painter = painterResource(R.drawable.ic_checkmark),
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 能力摘要：待办同步为共性能力；教务课表导入仅声明了 [Capability.SCHEDULE_IMPORT] 时追加 */
@Composable
private fun capabilitySummary(spec: SchoolSpec): String {
    val parts = buildList {
        add(stringResource(R.string.school_capability_todo_sync))
        if (Capability.SCHEDULE_IMPORT in spec.capabilities) {
            add(stringResource(R.string.school_capability_schedule_import))
        }
    }
    return parts.joinToString(" · ")
}

/** 双行列表卡统一卡高 */
private val SchoolRowHeight = 72.dp

/** 列表行距：浮层双行列表卡统一规格 */
private val SchoolRowSpacing = 4.dp
