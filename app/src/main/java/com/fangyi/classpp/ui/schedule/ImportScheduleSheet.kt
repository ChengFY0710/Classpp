package com.fangyi.classpp.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween

/**
 * 「导入课表」浮层：课表编辑栏的「导入」按钮打开（导入入口仅在编辑态提供）。
 *
 * 四个入口排成四张独立白卡（设计稿即四张卡、卡间露灰底），整列收进一个无题 [CardSection]：
 * 教务、JSON 文件、剪贴板、Wakeup 分享口令。与 [ScheduleSwitcherSheet] 同一职责划分——实际导入
 * （教务会话 / 文件选择器 / 读剪贴板 / 跑 Wakeup 适配脚本 / 仓库落盘）全由调用方执行，这里纯 UI。
 * 教务入口再叠一层教务导入浮层（[SchoolImportSheet]，调用方挂载）、Wakeup 入口再叠一层输入浮层
 * （[WakeupImportSheet]，调用方挂载），盖上来时本浮层经 covered 缩小后退。
 *
 * [onImportSchedule] 为 null = 活跃学校未实现教务导入（可选能力）：该行整张卡隐藏。
 *
 * 顶栏只有右侧「关闭」胶囊（confirmLabel = null 时自动右对齐、文字在前图标在后）；
 * 列表无输入框，键盘不必让位（IgnoreIme）。
 */
@Composable
internal fun ImportScheduleSheet(
    visible: Boolean,
    onDismissed: () -> Unit,
    onDismiss: () -> Unit,
    onImportSchedule: (() -> Unit)?,
    onImportJsonFile: () -> Unit,
    onImportClipboard: () -> Unit,
    onImportWakeup: () -> Unit,
    covered: Boolean = false,
) {
    // 返回键关浮层；叠在切换课表浮层之上，后注册因此先收到
    BackHandler(enabled = true) {
        onDismiss()
    }

    OverlaySheet(
        title = stringResource(R.string.import_sheet_title),
        // 纯入口列表：没有确认语义，右侧只留「关闭」
        confirmLabel = null,
        rightAction = SheetTopAction(
            label = stringResource(R.string.switcher_close),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = onDismiss,
        ),
        onDismiss = onDismiss,
        visible = visible,
        covered = covered,
        onDismissed = onDismissed,
        // 列表没有输入框，键盘不必让位
        imeBehavior = SheetImeBehavior.IgnoreIme,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            // 三行是视觉上彼此独立的入口卡（不是一张卡内的三行）：整列收进一个无题
            // CardSection，组内卡间距走 CardSectionSpacing
            CardSection {
                // 教务导入：打开教务导入浮层（调用方挂载，叠在本浮层之上）。
                // 未实现教务导入的学校整卡隐藏
                if (onImportSchedule != null) {
                    SettingsCard(
                        items = listOf(
                            SettingsCardItem.Nav(
                                label = stringResource(R.string.sync_import_label),
                                description = stringResource(R.string.sync_import_desc),
                                onClick = onImportSchedule,
                            ),
                        ),
                    )
                }
                SettingsCard(
                    items = listOf(
                        SettingsCardItem.Nav(
                            label = stringResource(R.string.import_json_label),
                            description = stringResource(R.string.import_json_desc),
                            onClick = onImportJsonFile,
                        ),
                    ),
                )
                SettingsCard(
                    items = listOf(
                        SettingsCardItem.Nav(
                            label = stringResource(R.string.import_clipboard_label),
                            description = stringResource(R.string.import_clipboard_desc),
                            onClick = onImportClipboard,
                        ),
                    ),
                )
                SettingsCard(
                    items = listOf(
                        // Wakeup 分享口令：打开输入浮层（调用方挂载，叠在本浮层之上）
                        SettingsCardItem.Nav(
                            label = stringResource(R.string.import_wakeup_label),
                            description = stringResource(R.string.import_wakeup_desc),
                            onClick = onImportWakeup,
                        ),
                    ),
                )
            }
        }
    }
}
