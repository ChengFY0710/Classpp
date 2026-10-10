package com.fangyi.classpp.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetTextField
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.settings.TermDatesCard
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween

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
@OptIn(ExperimentalMaterial3Api::class)
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
    var picking by remember { mutableStateOf<DateTarget?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current

    // 返回键优先收键盘（键盘收起后不返回给应用），键盘已收起时才关浮层
    BackHandler {
        if (imeInsets.getBottom(density) > 0) keyboard?.hide() else onDismiss()
    }

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
        onDismissed = onDismissed,
        imeBehavior = SheetImeBehavior.ContentScroll,
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
                onPickStart = { picking = DateTarget.Start },
                onPickEnd = { picking = DateTarget.End },
                // 改周数 → 结束日整周平移（星期几不变），与设置页同一联动
                onSetWeeks = { weeks -> end = endForTotalWeeks(start, end, weeks) },
            )
        }
    }

    // 选日对话框：与设置页/切换课表浮层同一套联动（开始日任意一天可选、平移结束日保持
    // 学期长度；结束日同样任意一天都合法）
    picking?.let { target ->
        val initial = if (target == DateTarget.Start) start else end
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initial.toPickerMillis())
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                // M3 按钮的涟漪在组件内部硬编码、调用侧置不了 null：本阶段只叠加按压反馈——
                // 按压源交给按钮形参，pressFeedback 接在 modifier 链末尾（贴按钮本体，对齐 textShape）
                val press = remember { MutableInteractionSource() }
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val picked = millis.toIsoDate()
                            if (target == DateTarget.Start) {
                                end = end + (picked - start).toInt()
                                start = picked
                            } else {
                                end = picked
                            }
                        }
                        picking = null
                    },
                    interactionSource = press,
                    modifier = Modifier.pressFeedback(press, ButtonDefaults.textShape),
                ) {
                    Text(stringResource(R.string.settings_confirm))
                }
            },
            dismissButton = {
                // 叠按压反馈：M3 涟漪在按钮内部、调用侧去不掉（按压源与按钮共用）
                val press = remember { MutableInteractionSource() }
                TextButton(
                    onClick = { picking = null },
                    interactionSource = press,
                    modifier = Modifier.pressFeedback(press, ButtonDefaults.textShape),
                ) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}
