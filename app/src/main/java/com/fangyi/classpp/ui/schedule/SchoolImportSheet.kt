package com.fangyi.classpp.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.DialogPillButton
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SelectCard
import com.fangyi.classpp.ui.components.SettingsCardwithIcon
import com.fangyi.classpp.ui.components.SettingsCardwithIconItem
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetInfoBanner
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.theme.RowCardPaddingHorizontal
import com.fangyi.classpp.ui.theme.RowCardPaddingVertical
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.classppTextStyles

/**
 * 「教务导入」浮层（校无关）：导入课表浮层的教务入口打开，叠在它之上（本浮层盖上来时
 * 导入浮层经 covered 缩小后退，同 Wakeup 输入浮层的分层动效）。数据获取由调用方经
 * `ScheduleImportService` 执行（取学期 / 拉课表只取数 + 映射），这里纯 UI，只认已经
 * 映射好的字符串。
 *
 * 内容两组卡（[CardSection] 分组、组间 [SheetSectionSpacingBetween]）：
 * - 账号与同步行（[SettingsCardwithIcon]）：照搬设置页「账号与同步」入口（设计稿漏画图标，
 *   行首取设置页同款 ic_arrow_sync_circle），行值随连接状态展示（未连接 / 已连接 / 已连接：
 *   姓名，文案由调用方解析后经 [accountStatus] 传入）；点击后由调用方收起整串浮层、编辑态
 *   取消更改并转到设置页-账号与同步（[onOpenAccountSync]）。
 * - 可导入学期单选卡（[SelectCard]）：选项为调用方从教务拉取的真实学期名（[semesters]）；
 *   拉取中显示进度行、失败显示横幅加重试行，两者都替代学期卡且确认胶囊不响应；
 *   拉取课表期间（[importing]）确认胶囊同样不响应并显示进度行。选中态随浮层卸载复位。
 *
 * [importBlockedReason] 非空 = 当前登录方式不支持教务导入（来自服务端 `blockedReason`，
 * 不再由 UI 自己拼 hasCredentials）：内容顶部横幅说明原因，确认胶囊不响应。
 *
 * 顶栏排布同 Wakeup 口令导入：取消在左（X 圆图标）、确认在右（确认胶囊自带对勾图标）；
 * 列表无输入框，键盘不必让位（IgnoreIme）。
 */
@Composable
internal fun SchoolImportSheet(
    visible: Boolean,
    onDismissed: () -> Unit,
    onDismiss: () -> Unit,
    onOpenAccountSync: () -> Unit,
    accountStatus: String,
    onConfirm: (semesterIndex: Int) -> Unit,
    importBlockedReason: String? = null,
    semesters: List<String>,
    semestersLoading: Boolean = false,
    semestersError: String? = null,
    onRetrySemesters: () -> Unit = {},
    importing: Boolean = false,
) {
    // 学期单选随浮层组合存活：两段式关闭播完（onDismissed 卸载）即复位，重开回第一项
    var selectedSemester by remember { mutableIntStateOf(0) }
    // 选项列表刷新（重开重拉）时旧选中可能越界，钳制回有效区间
    val selected = selectedSemester.coerceIn(0, semesters.lastIndex.coerceAtLeast(0))
    // 学期不可选（拉取中/失败/拉课表中）或登录方式被拦截时确认不响应
    val confirmEnabled = semesters.isNotEmpty() && !semestersLoading &&
        semestersError == null && importBlockedReason == null && !importing

    // 返回键关浮层；叠在导入浮层之上，后注册因此先收到
    BackHandler(enabled = true) {
        onDismiss()
    }

    OverlaySheet(
        title = stringResource(R.string.sync_import_sheet_title),
        confirmLabel = stringResource(R.string.edit_confirm),
        // 确认在右、取消（关闭）在其外侧（同 Wakeup 口令导入的顶栏排布）
        confirmAtEnd = true,
        onConfirm = { if (confirmEnabled) onConfirm(selected) },
        rightAction = SheetTopAction(
            label = stringResource(R.string.settings_cancel),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = onDismiss,
        ),
        onDismiss = onDismiss,
        visible = visible,
        onDismissed = onDismissed,
        // 无输入框，键盘不必让位
        imeBehavior = SheetImeBehavior.IgnoreIme,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            // 登录方式不支持教务导入：顶部横幅说明原因（确认胶囊已拦截），引导回设置页换
            // 原生登录
            importBlockedReason?.let { SheetInfoBanner(text = it) }
            // 账号与同步：孤行卡收进无题分组（与其他浮层的内容列约定一致）
            CardSection {
                SettingsCardwithIcon(
                    items = listOf(
                        SettingsCardwithIconItem(
                            icon = R.drawable.ic_arrow_sync_circle,
                            label = stringResource(R.string.settings_account_sync),
                            value = accountStatus,
                            onClick = onOpenAccountSync,
                        ),
                    ),
                )
            }
            when {
                // 拉取课表中：进度行替代学期卡（同 Wakeup 导入的进行中样式）
                importing -> CardSection {
                    ProgressRow(text = stringResource(R.string.sync_import_running))
                }
                // 学期拉取中：进度行替代学期卡
                semestersLoading -> CardSection {
                    ProgressRow(text = stringResource(R.string.sync_import_loading_semesters))
                }
                // 学期拉取失败：横幅说明 + 重试行
                semestersError != null -> {
                    SheetInfoBanner(text = semestersError)
                    CardSection {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = RowCardPaddingHorizontal,
                                    vertical = RowCardPaddingVertical,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.sync_import_semesters_failed),
                                style = MaterialTheme.classppTextStyles.fieldPlaceholder,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            DialogPillButton(
                                label = stringResource(R.string.sync_import_retry),
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                onClick = onRetrySemesters,
                            )
                        }
                    }
                }
                // 学期单选卡（点击未选项替换旧选，组件内部已按单选语义处理）
                semesters.isNotEmpty() -> CardSection {
                    SelectCard(
                        options = semesters,
                        selectedIndices = setOf(selected),
                        onSelectionChange = { next ->
                            next.firstOrNull()?.let { selectedSemester = it }
                        },
                    )
                }
            }
        }
    }
}

/** 进行中行：细转圈 + 一行说明（同 Wakeup 输入浮层的导入中样式） */
@Composable
private fun ProgressRow(text: String) {
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
            text = text,
            style = MaterialTheme.classppTextStyles.fieldPlaceholder,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
