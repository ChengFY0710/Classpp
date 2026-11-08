package com.fangyi.classpp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.sync.LoginError
import com.fangyi.classpp.data.sync.SchoolSpec
import com.fangyi.classpp.data.sync.SchoolText
import com.fangyi.classpp.data.sync.SyncDebugLog
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SheetInfoBanner
import com.fangyi.classpp.ui.components.SheetTextFieldGroup
import com.fangyi.classpp.ui.components.SheetTextFieldItem
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.schools.schoolText
import com.fangyi.classpp.ui.theme.SheetCardShape
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween

/**
 * 原生登录凭据浮层（校无关）：账号 + 密码 → 原生登录一次。默认登录（「去登录」，当学校
 * 支持原生登录且密钥库可用）与开启自动续期共用本浮层（[title] 由调用方按入口传入，
 * 语义相同：登录即保存凭据）。
 *
 * 学校专属文案（账号框标签/占位、注意事项、顶部图）全部来自 [spec]；密码框与其余
 * 通用标签走中性键。密码框用 [PasswordVisualTransformation]；输入值用 remember 而非
 * rememberSaveable，避免密码进入 savedInstanceState（旋转后重输可接受）。
 *
 * 忙碌期间禁止关闭（下拉/取消/确认），登录本身有 60 秒超时兜底。
 */
@Composable
internal fun CredentialLoginSheet(
    spec: SchoolSpec,
    visible: Boolean,
    busy: Boolean,
    error: LoginError?,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    onSubmit: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.sync_native_login_title),
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var validationShown by remember { mutableStateOf(false) }
    // 顶部随机图：浮层每次打开都重新挂载（关闭即卸载），remember 正好等于「每次打开重 roll」；
    // 学校未提供图片（新校常见）时整块不渲染
    val imageRes = remember(spec.loginImages) { spec.loginImages.randomOrNull() }

    val usernameBlank = username.isBlank()
    val passwordBlank = password.isEmpty()
    val context = LocalContext.current
    val blankHint = schoolText(spec.loginBlankHint)

    // 读实时 state（不经组合期快照）：诊断「点了没反应」时，uLen/pLen 是判定
    // 「打字有没有进 state」的唯一可信值
    fun submit() {
        SyncDebugLog.d("sheet submit busy=$busy uLen=${username.length} pLen=${password.length}")
        if (busy) return
        if (username.isBlank() || password.isEmpty()) {
            validationShown = true
            AppToasts.show(context, blankHint)
            return
        }
        onSubmit(username.trim(), password)
    }

    val errorText = when (error) {
        LoginError.NeedCaptcha -> stringResource(R.string.sync_login_need_captcha)
        LoginError.InvalidCredentials -> schoolText(
            spec.invalidCredentials ?: SchoolText.Res(R.string.sync_login_invalid_credentials),
        )
        LoginError.StorageUnavailable ->
            stringResource(R.string.sync_login_storage_unavailable)
        LoginError.ServiceBusy -> schoolText(spec.serviceBusy ?: SchoolText.Res(R.string.sync_error_busy))
        LoginError.NetworkError -> stringResource(R.string.sync_error_network)
        LoginError.Timeout -> stringResource(R.string.sync_error_timeout)
        LoginError.FormatChanged -> stringResource(R.string.sync_error_format)
        LoginError.SessionNotEstablished -> schoolText(
            spec.sessionNotEstablished ?: SchoolText.Res(R.string.sync_login_not_established),
        )
        null -> null
    }

    OverlaySheet(
        title = if (busy) stringResource(R.string.sync_login_sheet_title_busy) else title,
        confirmLabel = stringResource(R.string.sync_login_confirm),
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
                SheetInfoBanner(text = stringResource(R.string.sync_login_busy))
            }
            errorText?.let { SheetInfoBanner(text = it) }

            CardSection {
                if (imageRes != null) {
                    AsyncImage(
                        model = imageRes,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(SheetCardShape),
                    )
                }
                SheetTextFieldGroup(
                    fields = listOf(
                        SheetTextFieldItem(
                            label = schoolText(spec.usernameLabel),
                            value = username,
                            onValueChange = {
                                username = it
                                validationShown = false
                            },
                            placeholder = schoolText(spec.usernameHint),
                            isError = validationShown && usernameBlank,
                            imeAction = ImeAction.Next,
                        ),
                        SheetTextFieldItem(
                            label = stringResource(R.string.sync_login_password),
                            value = password,
                            onValueChange = {
                                password = it
                                validationShown = false
                            },
                            placeholder = stringResource(R.string.sync_login_password_hint),
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
                            label = stringResource(R.string.sync_login_notes_title),
                            description = schoolText(spec.loginNotes),
                        ),
                    ),
                )
            }
        }
    }
}
