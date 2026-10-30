package com.fangyi.classpp.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.ConfirmDialogCard
import com.fangyi.classpp.ui.components.DialogPillButton
import com.fangyi.classpp.ui.components.FadeOverlayDialog
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetTextField
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.theme.RowCardPaddingHorizontal
import com.fangyi.classpp.ui.theme.RowCardPaddingVertical
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.classppTextStyles

/**
 * 「Wakeup 口令导入」浮层：导入课表浮层的 Wakeup 入口打开，叠在它之上（本浮层盖上来时
 * 导入浮层经 covered 缩小后退，同「新建课表」的分层动效）。
 *
 * 表单只有一个输入卡：粘贴 Wakeup 分享口令或完整分享文案（换行随输入统一过滤，
 * 长文案视觉换行）。「导入」胶囊在右上、关闭在右上外侧（confirmAtEnd，同新建课表表单）；
 * 导入期间进度卡显示脚本回传的最新进度文案，确认胶囊只做视觉守卫（importing 时忽略点击），
 * 关闭浮层即取消导入（协程取消 → 执行器销毁 WebView）。
 *
 * 实际导入由调用方（ScheduleScreen）执行，这里纯 UI；解析成功后的确认弹窗
 * （[WakeupImportConfirmDialog]）也在调用方挂载，叠在本浮层之上。
 *
 * 输入随浮层卸载复位（remember，旋转重建回空值，同新建课表表单）。
 */
@Composable
internal fun WakeupImportSheet(
    visible: Boolean,
    onDismissed: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (shareText: String) -> Unit,
    importing: Boolean = false,
    progressText: String = "",
) {
    var text by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current

    // 返回键优先收键盘（键盘收起后不返回给应用），键盘已收起时才关浮层（同新建课表表单）
    BackHandler {
        if (imeInsets.getBottom(density) > 0) keyboard?.hide() else onDismiss()
    }

    OverlaySheet(
        title = stringResource(R.string.import_wakeup_sheet_title),
        confirmLabel = stringResource(R.string.import_wakeup_action),
        // 确认在右、取消（关闭）在其外侧（同新建课表表单的顶栏排布）
        confirmAtEnd = true,
        onConfirm = { if (!importing) onConfirm(text) },
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
        // 有输入框：键盘弹起时输入卡滚到键盘上方（表单浮层走 ContentScroll）
        imeBehavior = SheetImeBehavior.ContentScroll,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            // 分享文案是孤立的输入卡：无题 CardSection 收拢（单子项，卡内间距不生效）
            CardSection {
                SheetTextField(
                    label = stringResource(R.string.import_wakeup_field_label),
                    value = text,
                    onValueChange = { text = it },
                    placeholder = stringResource(R.string.import_wakeup_field_hint),
                    imeAction = ImeAction.Done,
                    // 键盘「完成」收起键盘，导入走右上「导入」
                    onImeAction = { focusManager.clearFocus() },
                )
            }
            if (importing) {
                CardSection {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = RowCardPaddingHorizontal, vertical = RowCardPaddingVertical),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = progressText.ifEmpty {
                                stringResource(R.string.import_wakeup_running)
                            },
                            style = MaterialTheme.classppTextStyles.fieldPlaceholder,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Wakeup 导入确认框：页内居中白卡（[ConfirmDialogCard]），告知解析到的课程数与新课表名。
 * 确认胶囊走主色（创建语义，非 destructive）；返回键与遮罩点击都只取消——留在输入浮层，
 * 已解析数据作废（再点「导入」重新走一遍）。两段式关闭见 [FadeOverlayDialog]：
 * 确认/取消即清数据源，[shownName]/[shownCount] 留住最后一份文案，淡出期间不闪空。
 */
@Composable
internal fun WakeupImportConfirmDialog(
    courseCount: Int,
    scheduleName: String,
    visible: Boolean,
    onDismissed: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    var shownCount by remember { mutableIntStateOf(0) }
    var shownName by remember { mutableStateOf("") }
    if (scheduleName.isNotEmpty()) {
        shownCount = courseCount
        shownName = scheduleName
    }

    FadeOverlayDialog(
        visible = visible,
        onDismiss = onCancel,
        onDismissed = onDismissed,
        cardHorizontalPadding = 32.dp,
    ) {
        ConfirmDialogCard(
            title = stringResource(R.string.import_wakeup_confirm_title),
            message = stringResource(
                R.string.import_wakeup_confirm_message,
                shownCount,
                shownName,
            ),
        ) {
            DialogPillButton(
                label = stringResource(R.string.settings_cancel),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                enabled = visible,
                onClick = onCancel,
            )
            DialogPillButton(
                label = stringResource(R.string.import_wakeup_action),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.weight(1f),
                enabled = visible,
                onClick = onConfirm,
            )
        }
    }
}
