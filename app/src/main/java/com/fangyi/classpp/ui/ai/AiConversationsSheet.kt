package com.fangyi.classpp.ui.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ai.AiConversation
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.ConfirmDialogCard
import com.fangyi.classpp.ui.components.DialogPillButton
import com.fangyi.classpp.ui.components.FadeOverlayDialog
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SheetCard
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.classppTextStyles

/**
 * 「所有对话」管理浮层（AI 对话浮层左上「对话」胶囊唤起）——容器为 [OverlaySheet]，
 * 列表与交互同「切换课表」浮层（[com.fangyi.classpp.ui.schedule.ScheduleSwitcherSheet]）
 * 同一套语言：
 * - 每场对话一张白卡（单行名称 + 删除），当前对话主色高亮 + 对勾，点卡片切换并关闭
 *   浮层（由调用方 [AiChatOverlay] 收口）；列表按最近更新在前排序；
 * - 左上「新建」经 [onCreate] 新建空对话并关闭浮层（新对话发首条消息时自动命名）；
 * - 删除先弹 [AiConversationDeleteDialog] 确认（破坏性操作），确认后才回调 [onDelete]，
 *   确认后留在浮层——列表随仓库回流更新，删光时由调用方关闭浮层。
 *
 * 切换/新建/删除的实际执行由调用方（AiChatOverlay）负责，这里纯 UI。
 * 名称回退：未命名对话（title 为空）显示「新对话」；文案随深浅主题由 token/主题装配。
 *
 * 与 [OverlaySheet] 同一约定：页内覆盖层而非窗口类对话框，列表无输入框，键盘不必让位
 * （IgnoreIme）；删除确认框挂载在 OverlaySheet 之后（画到浮层卡片之上、后注册返回键），
 * 取消/返回只关确认框、留在浮层。
 */
@Composable
internal fun AiConversationsSheet(
    visible: Boolean,
    onDismissed: () -> Unit,
    conversations: List<AiConversation>,
    activeConversationId: String?,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onDelete: (String) -> Unit,
    covered: Boolean = false,
) {
    // 待删除的对话 id（非空 = 删除确认框打开）
    var deletingId by remember { mutableStateOf<String?>(null) }

    // 返回键关浮层；删除确认框后注册、比它先收到（见挂载处）
    BackHandler(enabled = true) {
        onDismiss()
    }

    // 最近更新在前：正在聊的对话排最上（删除回落「最近更新」与列表排序同一口径）
    val sorted = remember(conversations) {
        conversations.sortedByDescending { it.updatedAtMillis }
    }
    val defaultName = stringResource(R.string.ai_conversation_default_name)

    OverlaySheet(
        title = stringResource(R.string.ai_conversations_title),
        confirmLabel = stringResource(R.string.ai_conversations_new),
        confirmIcon = R.drawable.ic_add_circle,
        // 「新建」：新建对话并关闭浮层（回调由调用方收口）
        onConfirm = onCreate,
        rightAction = SheetTopAction(
            label = stringResource(R.string.ai_sheet_close),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = onDismiss,
        ),
        onDismiss = onDismiss,
        visible = visible,
        covered = covered,
        onDismissed = onDismissed,
        // 整条链不挂 Haze（同 DayAdjustSheet 的退让规避，见 26d8c1f）：本浮层叠在对话
        // 浮层之上，对话浮层本身就是最重的 Haze 用户——叠加态少一条模糊链，避开
        // Adreno Vulkan 驱动建模糊管线的崩溃路径（RenderThread SIGSEGV，2026-11-06
        // 23:51 复发）。无玻璃按钮、无共享 haze 需求，顶栏走静态渐变底材视觉一致。
        hazeEnabled = false,
        // 列表没有输入框，键盘不必让位
        imeBehavior = SheetImeBehavior.IgnoreIme,
    ) {
        // 对话卡是直接排成一列的同型卡列表：整列收进一个无题 CardSection（组内 10dp 卡间距）
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            CardSection {
                sorted.forEach { conversation ->
                    ConversationCard(
                        conversation = conversation,
                        displayName = conversation.title.ifBlank { defaultName },
                        isActive = conversation.id == activeConversationId,
                        onClick = { if (conversation.id != activeConversationId) onSwitch(conversation.id) },
                        onDelete = { deletingId = conversation.id },
                    )
                }
            }
        }
    }

    // 删除确认框：挂在 OverlaySheet 调用之后（画到浮层卡片之上），后注册 BackHandler——
    // 返回键先到它：取消/返回只关确认框、留在浮层，关掉后才轮到浮层的关闭。
    // 挂载与可见性分离（同浮层两段式关闭）：确认/取消先清 deletingId 播淡出，播完才卸载
    var deleteDialogMounted by remember { mutableStateOf(false) }
    val deleteDialogVisible = deletingId != null
    if (deleteDialogVisible) deleteDialogMounted = true
    if (deleteDialogMounted) {
        AiConversationDeleteDialog(
            conversationName = sorted.firstOrNull { it.id == deletingId }
                ?.let { it.title.ifBlank { defaultName } }
                .orEmpty(),
            visible = deleteDialogVisible,
            onDismissed = { deleteDialogMounted = false },
            onCancel = { deletingId = null },
            onConfirm = {
                val id = deletingId
                deletingId = null
                id?.let(onDelete)
            },
        )
    }
}

/** 一张对话卡：单行名称 + 删除；当前对话名称主色高亮、删除键左侧带对勾（点击切换） */
@Composable
private fun ConversationCard(
    conversation: AiConversation,
    displayName: String,
    isActive: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    SheetCard(onClick = onClick) {
        Text(
            text = displayName,
            style = MaterialTheme.classppTextStyles.fieldLabel.copy(
                color = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            ),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isActive) {
            Icon(
                painter = painterResource(R.drawable.ic_checkmark),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        // M3 按钮的涟漪在组件内部硬编码、调用侧置不了 null：本阶段只叠加按压反馈——
        // 删除钮用独立按压源（宿主 SheetCard 的反馈是它自己的源，共享源规则下子钮不会跟着双亮），
        // pressFeedback 追加在 size 之后、最靠近按钮本体
        val press = remember { MutableInteractionSource() }
        IconButton(
            onClick = onDelete,
            interactionSource = press,
            modifier = Modifier
                .size(40.dp)
                .pressFeedback(press, CircleShape),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = stringResource(R.string.edit_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 删除对话确认框：页内居中白卡（[ConfirmDialogCard]），点名对话并告知删除不可撤销。
 * 确认胶囊 error 红底白字标 destructive；返回键与遮罩点击都只取消。
 * 两段式关闭见 [FadeOverlayDialog]：确认/取消即清 deletingId（名称随之为空），
 * [shownName] 留住最后一份文案，淡出期间不闪空。
 */
@Composable
private fun AiConversationDeleteDialog(
    conversationName: String,
    visible: Boolean,
    onDismissed: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    var shownName by remember { mutableStateOf(conversationName) }
    if (conversationName.isNotEmpty()) shownName = conversationName

    FadeOverlayDialog(
        visible = visible,
        onDismiss = onCancel,
        onDismissed = onDismissed,
    ) {
        ConfirmDialogCard(
            title = stringResource(R.string.ai_conversations_delete_title),
            message = stringResource(R.string.ai_conversations_delete_message, shownName),
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
                label = stringResource(R.string.edit_delete),
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                modifier = Modifier.weight(1f),
                enabled = visible,
                onClick = onConfirm,
            )
        }
    }
}
