package com.fangyi.classpp.ui.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ai.AiChatRepository
import com.fangyi.classpp.data.ai.AiConnection
import com.fangyi.classpp.data.ai.AiProviderPresets
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SheetInfoBanner
import com.fangyi.classpp.ui.components.SheetTextField
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.PillShape
import kotlinx.coroutines.launch

/**
 * AI 设置（全屏浮层，从对话浮层右上角 ⚙ 或未配置引导卡进入，覆盖其上）。
 *
 * - 服务商预设：单选 chip，选中即回填服务地址与建议模型（本地/自定义预设必手填模型）；
 * - 连接：服务地址 + 模型名（SheetTextField 卡式输入，与各浮层表单同规格）；
 * - API Key：密码框——已配置时占位「留空保持不变」，覆盖保存只在输入非空时发生，
 *   **不回显已存 Key**；
 * - 保存走确认胶囊：先落连接配置再（按需）落 Key，成功后 [AiChatRepository.refreshConfigured]
 *   让对话浮层的引导态即时切换；
 * - 隐私说明固定展示（SheetInfoBanner）；「清空对话历史」为两击确认的行内按钮（不弹窗）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiSettingsScreen(
    visible: Boolean,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    aiChatRepository: AiChatRepository?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val invalidMessage = stringResource(R.string.ai_settings_invalid)
    val savedMessage = stringResource(R.string.ai_settings_saved)

    // 表单快照：挂载时装载一次（浮层生命周期内不随外部变化回写）
    var connection by remember { mutableStateOf(AiConnection()) }
    var hadApiKey by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var baseUrlInvalid by remember { mutableStateOf(false) }
    var modelInvalid by remember { mutableStateOf(false) }
    // 「清空对话历史」两击确认：第一次点击置位，3s 无后续点击自动解除，关闭浮层复位
    var clearArmed by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (!visible) {
            clearArmed = false
            return@LaunchedEffect
        }
        val repository = aiChatRepository ?: return@LaunchedEffect
        connection = repository.loadConnection()
        hadApiKey = repository.loadApiKey() != null
        loaded = true
    }

    LaunchedEffect(clearArmed) {
        if (clearArmed) {
            kotlinx.coroutines.delay(3000)
            clearArmed = false
        }
    }

    BackHandler(enabled = visible) { onDismiss() }

    fun save() {
        val repository = aiChatRepository ?: return
        val trimmedUrl = connection.baseUrl.trim()
        val trimmedModel = connection.model.trim()
        val valid = trimmedUrl.startsWith("http") && trimmedModel.isNotBlank()
        baseUrlInvalid = !trimmedUrl.startsWith("http")
        modelInvalid = trimmedModel.isBlank()
        if (!valid) {
            AppToasts.show(context, invalidMessage)
            return
        }
        scope.launch {
            val normalized = connection.copy(
                baseUrl = trimmedUrl.trimEnd('/'),
                model = trimmedModel,
            )
            val connectionSaved = repository.saveConnection(normalized)
            val keySaved = if (keyInput.isNotBlank()) {
                repository.saveApiKey(keyInput.trim())
            } else {
                true
            }
            repository.refreshConfigured()
            if (connectionSaved && keySaved) {
                AppToasts.show(context, savedMessage)
                onDismiss()
            } else {
                AppToasts.show(context, invalidMessage)
            }
        }
    }

    OverlaySheet(
        title = stringResource(R.string.ai_settings_title),
        confirmLabel = stringResource(R.string.edit_confirm),
        onConfirm = ::save,
        rightAction = SheetTopAction(
            label = stringResource(R.string.day_image_close),
            icon = R.drawable.ic_dismiss,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.classppColors.secondaryText,
            onClick = onDismiss,
        ),
        visible = visible,
        onDismiss = onDismiss,
        onDismissed = onDismissed,
        modifier = modifier,
    ) {
        if (!loaded) return@OverlaySheet

        // ——— 服务商预设 ———
        Text(
            text = stringResource(R.string.ai_settings_provider),
            style = ClassppTextTokens.sectionTitle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 16.dp),
        ) {
            for (preset in AiProviderPresets.all) {
                val selected = connection.providerId == preset.id
                Box(
                    modifier = Modifier
                        .clip(PillShape)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        )
                        .then(
                            if (!selected) {
                                Modifier.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    shape = PillShape,
                                )
                            } else {
                                Modifier
                            },
                        )
                        .clickable {
                            connection = connection.copy(
                                providerId = preset.id,
                                // 选中预设即回填端点；建议模型仅在非手填预设下覆盖，
                                // ollama/custom 无公认默认，保留用户已填值
                                baseUrl = preset.baseUrl.ifBlank { connection.baseUrl },
                                model = if (!preset.requiresManualModel && preset.suggestedModel.isNotBlank()) {
                                    preset.suggestedModel
                                } else {
                                    connection.model
                                },
                            )
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = preset.label,
                        style = ClassppTextTokens.secondaryTextSmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.classppColors.secondaryText
                        },
                    )
                }
            }
        }

        // ——— 连接 ———
        SheetTextField(
            label = stringResource(R.string.ai_settings_base_url),
            value = connection.baseUrl,
            onValueChange = {
                connection = connection.copy(baseUrl = it)
                baseUrlInvalid = false
            },
            placeholder = stringResource(R.string.ai_settings_base_url_hint),
            isError = baseUrlInvalid,
            imeAction = ImeAction.Next,
        )
        SheetTextField(
            label = stringResource(R.string.ai_settings_model),
            value = connection.model,
            onValueChange = {
                connection = connection.copy(model = it)
                modelInvalid = false
            },
            placeholder = stringResource(R.string.ai_settings_model_hint),
            isError = modelInvalid,
            imeAction = ImeAction.Next,
        )
        // ——— API Key（密码框；已配置时不回显，留空 = 保持不变） ———
        SheetTextField(
            label = stringResource(R.string.ai_settings_api_key),
            value = keyInput,
            onValueChange = { keyInput = it },
            placeholder = stringResource(
                if (hadApiKey) R.string.ai_settings_api_key_saved else R.string.ai_settings_api_key_hint,
            ),
            imeAction = ImeAction.Done,
            visualTransformation = if (keyInput.isEmpty()) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
        )
        val secureAvailable = aiChatRepository?.secureAvailable ?: true
        if (!secureAvailable) {
            SheetInfoBanner(text = stringResource(R.string.ai_settings_secure_unavailable))
        }
        SheetInfoBanner(text = stringResource(R.string.ai_settings_privacy))

        // ——— 清空对话历史（两击确认的行内按钮，不弹窗） ———
        val chatRepository = aiChatRepository
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = stringResource(
                    if (clearArmed) R.string.ai_settings_clear_history_confirm else R.string.ai_settings_clear_history,
                ),
                style = ClassppTextTokens.textButton,
                color = if (clearArmed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.classppColors.secondaryText
                },
                modifier = Modifier
                    .clip(PillShape)
                    .clickable {
                        if (clearArmed) {
                            clearArmed = false
                            if (chatRepository != null) {
                                scope.launch {
                                    chatRepository.clearHistory()
                                    AppToasts.show(
                                        context,
                                        context.getString(R.string.ai_settings_clear_history_done),
                                    )
                                }
                            }
                        } else {
                            clearArmed = true
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
