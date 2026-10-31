package com.fangyi.classpp.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SelectCard
import com.fangyi.classpp.ui.components.SettingsCardwithIcon
import com.fangyi.classpp.ui.components.SettingsCardwithIconItem
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetInfoBanner
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween

/** 占位学期列表（本次仅 UI）：条目取自教务「我的课表」学期下拉的真实文案；接口接入后由调用方传入实际可导入学期 */
internal val XmuImportPlaceholderSemesters = listOf(
    "2026-2027学年 秋季学期",
    "2025-2026学年 夏季学期",
    "2025-2026学年 春季学期",
    "2025-2026学年 秋季学期",
    "2024-2025学年 夏季学期",
    "2024-2025学年 春季学期",
    "2024-2025学年 秋季学期",
    "2023-2024学年 夏季学期",
    "2023-2024学年 春季学期",
    "2023-2024学年 秋季学期",
)

/**
 * 「教务导入」浮层：导入课表浮层的教务入口打开，叠在它之上（本浮层盖上来时导入浮层经
 * covered 缩小后退，同 Wakeup 输入浮层的分层动效）。本次仅 UI：不请求教务接口，确认
 * 胶囊只回调 [onConfirm] 不落库（调用方暂挂空）。
 *
 * 内容两组卡（[CardSection] 分组、组间 [SheetSectionSpacingBetween]）：
 * - 账号与同步行（[SettingsCardwithIcon]）：照搬设置页「账号与同步」入口（设计稿漏画图标，
 *   行首取设置页同款 ic_arrow_sync_circle），行值随连接状态展示（未连接 / 已连接 / 已连接：
 *   姓名，文案由调用方解析后经 [accountStatus] 传入）；点击后由调用方收起整串浮层、编辑态
 *   取消更改并转到设置页-账号与同步（[onOpenAccountSync]）。
 * - 可导入学期单选卡（[SelectCard]）：选项默认取 [XmuImportPlaceholderSemesters] 占位，
 *   选中态随浮层卸载复位（重开回第一项）；列表为空时整卡不渲染、确认胶囊不响应。
 *
 * [importBlockedReason] 非空 = 当前登录方式不支持教务导入（已连接但没保存凭据，即网页
 * 登录或已关自动续期）：内容顶部横幅说明原因，确认胶囊不响应。
 *
 * 顶栏排布同 Wakeup 口令导入：取消在左（X 圆图标）、确认在右（确认胶囊自带对勾图标）；
 * 列表无输入框，键盘不必让位（IgnoreIme）。
 */
@Composable
internal fun XmuImportSheet(
    visible: Boolean,
    onDismissed: () -> Unit,
    onDismiss: () -> Unit,
    onOpenAccountSync: () -> Unit,
    accountStatus: String,
    onConfirm: (semesterIndex: Int) -> Unit,
    importBlockedReason: String? = null,
    semesters: List<String> = XmuImportPlaceholderSemesters,
) {
    // 学期单选随浮层组合存活：两段式关闭播完（onDismissed 卸载）即复位，重开回第一项
    var selectedSemester by remember { mutableIntStateOf(0) }
    // 选项列表变化（未来接真数据）时旧选中可能越界，钳制回有效区间
    val selected = selectedSemester.coerceIn(0, semesters.lastIndex.coerceAtLeast(0))

    // 返回键关浮层；叠在导入浮层之上，后注册因此先收到
    BackHandler(enabled = true) {
        onDismiss()
    }

    OverlaySheet(
        title = stringResource(R.string.import_xmu_sheet_title),
        confirmLabel = stringResource(R.string.edit_confirm),
        // 确认在右、取消（关闭）在其外侧（同 Wakeup 口令导入的顶栏排布）
        confirmAtEnd = true,
        // 当前登录方式不支持教务导入（或学期列表为空）时确认不响应
        onConfirm = {
            if (semesters.isNotEmpty() && importBlockedReason == null) onConfirm(selected)
        },
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
            // 可导入学期：单选卡（点击未选项替换旧选，组件内部已按单选语义处理）
            if (semesters.isNotEmpty()) {
                CardSection {
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
