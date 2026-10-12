package com.fangyi.classpp.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.ui.components.BottomDateSelectCard
import com.fangyi.classpp.ui.components.BottomNumberSelectCard
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetTextField
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.settings.TermDatesCard
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import dev.chrisbanes.haze.rememberHazeState

/**
 * 新建课表浮层：两个入口——无激活课表的空态经「创建课表」按钮打开；切换课表浮层的
 * 「新建」打开时**叠在切换课表之上**（调用方负责挂载与 covered 接线，动效同待办详情→编辑）。
 * 容器为 [OverlaySheet]，与课程编辑/切换课表浮层同一套交互语言。表单只保留课表名与
 * 学期设置（[TermDatesCard]，设置页同款三行卡：开学日/结束日弹日期选择、总周数弹输入框）。
 * 「确认」胶囊在右上、「取消」在左上（confirmAtEnd，同课程编辑面板的顶栏排布）；
 * 创建的实际执行由调用方负责（createSchedule 在无激活课表时自动激活新课表），这里纯 UI。
 *
 * 表单字段随浮层卸载而复位（remember，旋转重建回默认值）。
 */
@Composable
internal fun NewScheduleSheet(
    visible: Boolean,
    onDismissed: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (name: String, start: IsoDate, end: IsoDate) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val defaultStart = remember { IsoDate.today() }
    var start by remember { mutableStateOf(defaultStart) }
    var end by remember { mutableStateOf(defaultStart + TERM_DEFAULT_DAYS) }

    // 开学/结束日期滚轮弹窗（BottomDateSelectCard）：期望态 + 挂载态两段式（同新建待办
    // 的各滚轮弹窗），声明在 OverlaySheet 之前——弹窗在场时经 covered 让本浮层缩小后退
    var pickerVisible by remember { mutableStateOf(false) }
    var pickerMounted by remember { mutableStateOf(false) }
    var pickerInitial by remember { mutableStateOf(IsoDate.today()) }
    var pickerCallback by remember { mutableStateOf<((IsoDate) -> Unit)?>(null) }

    // 学期周数横向滚轮弹窗（BottomNumberSelectCard）：两段式同上；初值在请求时快照
    // （挂载期间表单的起止日不会再变）
    var weeksVisible by remember { mutableStateOf(false) }
    var weeksMounted by remember { mutableStateOf(false) }
    var weeksInitial by remember { mutableIntStateOf(TERM_WEEKS_MIN) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current

    // 返回键优先收键盘（键盘收起后不返回给应用），键盘已收起时才关浮层
    BackHandler {
        if (imeInsets.getBottom(density) > 0) keyboard?.hide() else onDismiss()
    }

    // 表单内容的 haze 采样源：OverlaySheet 顶栏磨砂用，叠在其上的开学/结束日期滚轮弹窗
    // 也拿它做卡片磨砂背景模糊——同一状态同源采样
    val sheetHazeState = rememberHazeState()

    OverlaySheet(
        title = stringResource(R.string.switcher_new),
        confirmLabel = stringResource(R.string.edit_confirm),
        // 确认在右、取消在左（同课程编辑面板）
        confirmAtEnd = true,
        onConfirm = { onConfirm(name.trim(), start, end) },
        rightAction = SheetTopAction(
            label = stringResource(R.string.settings_cancel),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = onDismiss,
        ),
        onDismiss = onDismiss,
        visible = visible,
        covered = pickerVisible || weeksVisible,
        onDismissed = onDismissed,
        imeBehavior = SheetImeBehavior.ContentScroll,
        // 共享采样源：叠在本浮层上的日期滚轮弹窗拿它做卡片磨砂背景模糊（见 sheetHazeState 声明处）
        hazeState = sheetHazeState,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            SheetTextField(
                label = stringResource(R.string.schedule_name_label),
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.schedule_name_hint),
                imeAction = ImeAction.Done,
                // 键盘「完成」收起键盘，创建走右上「确认」
                onImeAction = { focusManager.clearFocus() },
            )
            TermDatesCard(
                start = start,
                end = end,
                // 改开始日 → 结束日整周平移（星期几不变），与设置页同一联动；
                // 闭包捕获 var 本体，确认时读到的就是最新值
                onPickStart = {
                    pickerInitial = start
                    pickerCallback = { picked ->
                        end = end + (picked - start).toInt()
                        start = picked
                    }
                    pickerMounted = true
                    pickerVisible = true
                },
                onPickEnd = {
                    pickerInitial = end
                    pickerCallback = { picked -> end = picked }
                    pickerMounted = true
                    pickerVisible = true
                },
                // 改周数 → 挂横向滚轮弹窗（画在本浮层之后）；确认后结束日整周平移
                // （星期几不变），与设置页同一联动
                onPickWeeks = {
                    weeksInitial = totalWeeks(start, end)
                    weeksMounted = true
                    weeksVisible = true
                },
                onSetWeeks = { weeks -> end = endForTotalWeeks(start, end, weeks) },
            )
        }
    }

    // 开学日期滚轮弹窗画在浮层之后（组合顺序即绘制顺序）；确认沿用同一套联动：
    // 开始日任意一天可选、平移结束日保持学期长度；结束日同样任意一天都合法
    if (pickerMounted) {
        BottomDateSelectCard(
            visible = pickerVisible,
            onDismiss = { pickerVisible = false },
            onDismissed = {
                pickerMounted = false
                pickerCallback = null
            },
            hazeState = sheetHazeState,
            initialDate = pickerInitial,
            onConfirm = { picked ->
                pickerVisible = false
                pickerCallback?.invoke(picked)
                pickerCallback = null
            },
        )
    }

    // 学期周数横向滚轮弹窗（BottomNumberSelectCard）画在浮层之后（组合顺序即绘制顺序）；
    // 确认把结束日整周平移到目标周数（星期几不变），取消/遮罩/返回只请求关闭，
    // 播完退场才卸载（两段式，同日期轮）
    if (weeksMounted) {
        BottomNumberSelectCard(
            visible = weeksVisible,
            onDismiss = { weeksVisible = false },
            onDismissed = { weeksMounted = false },
            title = stringResource(R.string.weeks_select_title),
            valueRange = TERM_WEEKS_MIN..TERM_WEEKS_MAX,
            initialValue = weeksInitial,
            unit = stringResource(R.string.unit_week),
            hazeState = sheetHazeState,
            onConfirm = { weeks ->
                weeksVisible = false
                end = endForTotalWeeks(start, end, weeks)
            },
        )
    }
}
