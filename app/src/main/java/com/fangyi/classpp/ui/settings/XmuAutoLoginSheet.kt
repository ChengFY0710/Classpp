package com.fangyi.classpp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.xmu.XmuAutoLoginError
import com.fangyi.classpp.data.xmu.XmuDebugLog
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SheetInfoBanner
import com.fangyi.classpp.ui.components.SheetTextFieldGroup
import com.fangyi.classpp.ui.components.SheetTextFieldItem
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween

/**
 * 开启自动续期的凭据浮层：学号 + 密码 → 原生登录一次。
 *
 * 密码框用 [PasswordVisualTransformation]；输入值用 remember 而非 rememberSaveable，
 * 避免密码进入 savedInstanceState（旋转后重输可接受）。
 *
 * 忙碌期间禁止关闭（下拉/取消/确认），登录本身有 60 秒超时兜底。
 */
@Composable
internal fun XmuAutoLoginSheet(
    visible: Boolean,
    busy: Boolean,
    error: XmuAutoLoginError?,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    onSubmit: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var validationShown by remember { mutableStateOf(false) }

    val usernameBlank = username.isBlank()
    val passwordBlank = password.isEmpty()
    val context = LocalContext.current
    val blankHint = stringResource(R.string.xmu_auto_login_blank)

    // 读实时 state（不经组合期快照）：诊断「点了没反应」时，uLen/pLen 是判定
    // 「打字有没有进 state」的唯一可信值
    fun submit() {
        XmuDebugLog.d("sheet submit busy=$busy uLen=${username.length} pLen=${password.length}")
        if (busy) return
        if (username.isBlank() || password.isEmpty()) {
            validationShown = true
            AppToasts.show(context, blankHint)
            return
        }
        onSubmit(username.trim(), password)
    }

    val errorText = when (error) {
        XmuAutoLoginError.NeedCaptcha -> stringResource(R.string.xmu_auto_login_need_captcha)
        XmuAutoLoginError.InvalidCredentials ->
            stringResource(R.string.xmu_auto_login_invalid_credentials)
        XmuAutoLoginError.StorageUnavailable ->
            stringResource(R.string.xmu_auto_login_storage_unavailable)
        XmuAutoLoginError.ServiceBusy -> stringResource(R.string.xmu_error_busy)
        XmuAutoLoginError.NetworkError -> stringResource(R.string.xmu_error_network)
        XmuAutoLoginError.Timeout -> stringResource(R.string.xmu_error_timeout)
        XmuAutoLoginError.FormatChanged -> stringResource(R.string.xmu_error_format)
        XmuAutoLoginError.SessionNotEstablished ->
            stringResource(R.string.xmu_auto_login_not_established)
        null -> null
    }

    OverlaySheet(
        title = stringResource(
            if (busy) R.string.xmu_auto_login_sheet_title_busy else R.string.xmu_auto_login_sheet_title,
        ),
        confirmLabel = stringResource(R.string.xmu_auto_login_confirm),
        confirmAtEnd = true,
        onConfirm = ::submit,
        rightAction = SheetTopAction(
            label = stringResource(R.string.settings_cancel),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = { if (!busy) onDismiss() },
        ),
        onDismiss = { if (!busy) onDismiss() },
        visible = visible,
        dragDismissEnabled = !busy,
        onDismissed = onDismissed,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            // 进行中/失败都放在最上方：键盘弹起时内容可能已滚到输入框，
            // 错误若在长说明卡之后容易被用户忽略（表现为「点了没反应」）
            if (busy) {
                SheetInfoBanner(text = stringResource(R.string.xmu_auto_login_busy))
            }
            errorText?.let { SheetInfoBanner(text = it) }

            CardSection {
                SheetTextFieldGroup(
                    fields = listOf(
                        SheetTextFieldItem(
                            label = stringResource(R.string.xmu_auto_login_username),
                            value = username,
                            onValueChange = {
                                username = it
                                validationShown = false
                            },
                            placeholder = stringResource(R.string.xmu_auto_login_username_hint),
                            isError = validationShown && usernameBlank,
                            imeAction = ImeAction.Next,
                        ),
                        SheetTextFieldItem(
                            label = stringResource(R.string.xmu_auto_login_password),
                            value = password,
                            onValueChange = {
                                password = it
                                validationShown = false
                            },
                            isError = validationShown && passwordBlank,
                            imeAction = ImeAction.Done,
                            onImeAction = ::submit,
                            keyboardType = KeyboardType.Password,
                            visualTransformation = PasswordVisualTransformation(),
                        ),
                    ),
                )
                SettingsCard(
                    items = listOf(
                        SettingsCardItem.Custom(
                            label = stringResource(R.string.xmu_auto_login_notes_title),
                            description = stringResource(R.string.xmu_auto_login_warning),
                        ),
                    ),
                )
            }
        }
    }
}
