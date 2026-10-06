package com.fangyi.classpp.ui.note

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetTextField
import com.fangyi.classpp.ui.components.SheetTopAction

/**
 * 新建待办浮层：容器为 [OverlaySheet]，与新建课表浮层同一套交互语言。
 * 表单本期只保留待办名一个属性。「确认」胶囊在右上、「取消」在左上（confirmAtEnd）。
 * 创建的实际执行由调用方负责（写入 TodoRepository），这里纯 UI。
 *
 * 待办名为空时点「确认」不创建：输入卡红描边报错（AddCoursePanel 同款交互），输入即清除。
 * 表单字段随浮层卸载而复位（remember，旋转重建回默认值）。
 */
@Composable
internal fun NewTodoSheet(
    visible: Boolean,
    onDismissed: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (name: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var nameBlank by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current

    // 返回键优先收键盘（键盘收起后不返回给应用），键盘已收起时才关浮层
    BackHandler {
        if (imeInsets.getBottom(density) > 0) keyboard?.hide() else onDismiss()
    }

    OverlaySheet(
        title = stringResource(R.string.todo_new_title),
        confirmLabel = stringResource(R.string.edit_confirm),
        // 确认在右、取消在左（同新建课表浮层）
        confirmAtEnd = true,
        onConfirm = {
            if (name.isBlank()) {
                nameBlank = true
            } else {
                onConfirm(name.trim())
            }
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
        imeBehavior = SheetImeBehavior.ContentScroll,
    ) {
        SheetTextField(
            label = stringResource(R.string.todo_name_label),
            value = name,
            onValueChange = {
                name = it
                nameBlank = false
            },
            placeholder = stringResource(R.string.todo_name_hint),
            imeAction = ImeAction.Done,
            // 键盘「完成」收起键盘，创建走右上「确认」
            onImeAction = { focusManager.clearFocus() },
            isError = nameBlank,
        )
    }
}
