package com.fangyi.classpp.ui.ai

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.components.MenuGravity
import com.fangyi.classpp.ui.components.MenuPlacement
import com.fangyi.classpp.ui.components.PopupMenuItem
import com.fangyi.classpp.ui.components.PopupMenuPopup
import com.fangyi.classpp.ui.components.PopupMenuSection
import com.fangyi.classpp.ui.motion.PressShade
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.isDarkTheme
import dev.chrisbanes.haze.HazeState

/** 胶囊高度：固定在底部光场头寸内，切换显示/隐藏不改变钉底槽总高度 */
internal val ModelSwitchChipHeight = 36.dp

/** 胶囊与输入栏之间的间距 */
internal val ModelSwitchChipGap = 10.dp

/** 非 focusable 菜单收起后极短窗口内忽略胶囊的「打开」请求（同 SettingsCard.SelectMenu） */
private const val MenuReopenGuardMillis = 200L

/** 模型胶囊在窗口里的坐标（菜单锚点）：布局回调只写普通字段，不写 snapshot state */
private class ChipAnchorBounds {
    var value: Rect = Rect.Zero
}

/**
 * 对话浮层输入栏上方的模型切换胶囊：显示当前模型名，点开列出本方案保存的模型。
 *
 * 只在本方案至少有两个模型时由宿主渲染（见 AI 对话浮层）；切换经
 * [onSwitch] 交给仓库落库，菜单本身不持有配置状态。
 *
 * 菜单用 [PopupMenuPopup]（独立 Popup 窗口、白卡 + 磨砂），向上展开：
 * - 非 focusable：点胶囊时键盘保持原位，菜单不抢焦点；
 * - 点外部收起与落回胶囊的点击是同一个手势，收起后 [MenuReopenGuardMillis] 内忽略
 *   这次「打开」，避免退场动画播到一半又反向进场；
 * - 锚点取点开那一刻的胶囊窗口坐标，键盘让位/inset 变化时菜单不跟着漂。
 */
@Composable
fun AiModelSwitchChip(
    models: List<String>,
    currentModel: String,
    enabled: Boolean,
    hazeState: HazeState?,
    onSwitch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var dismissedAtMillis by remember { mutableLongStateOf(0L) }
    val anchorBounds = remember { ChipAnchorBounds() }
    // 浅色模式白底提亮无感，改用微微压暗表达按压（同 SettingsCard/AiConversationsSheet）
    val rowShade = if (MaterialTheme.isDarkTheme) PressShade.Brighten else PressShade.Darken
    val switchDescription = stringResource(R.string.cd_ai_switch_model, currentModel)
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.classppColors.negative
    }

    // 发送中禁用：菜单若还开着就收起，避免回答期间切模型造成「这条回复到底用哪个模型」的歧义
    LaunchedEffect(enabled) {
        if (!enabled) expanded = false
    }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            anchorBounds.value = coordinates.boundsInWindow()
        },
    ) {
        Row(
            modifier = Modifier
                .height(ModelSwitchChipHeight)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .pressClickable(
                    shape = CircleShape,
                    shade = rowShade,
                    enabled = enabled,
                    role = Role.Button,
                ) {
                    if (expanded) {
                        // 菜单开着时再点胶囊 = 收起（退场动画照常播完），记下时刻防同手势重开
                        dismissedAtMillis = SystemClock.uptimeMillis()
                        expanded = false
                    } else if (
                        SystemClock.uptimeMillis() - dismissedAtMillis > MenuReopenGuardMillis
                    ) {
                        expanded = true
                    }
                }
                .semantics(mergeDescendants = true) {
                    contentDescription = switchDescription
                }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = currentModel,
                style = ClassppTextTokens.metaText,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
            Icon(
                painter = painterResource(R.drawable.ic_chevron_up_down),
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary else contentColor,
                modifier = Modifier.size(16.dp),
            )
        }

        PopupMenuPopup(
            expanded = expanded,
            onDismiss = {
                // 点外部 / 返回键收起也记时刻：同一手势落回胶囊的点击不再重开
                dismissedAtMillis = SystemClock.uptimeMillis()
                expanded = false
            },
            sections = listOf(
                PopupMenuSection(
                    items = models.map { model ->
                        PopupMenuItem(
                            label = model,
                            checked = model.equals(currentModel, ignoreCase = true),
                            onClick = {
                                // 退场播完前胶囊上的点击不再把菜单反向拉回来
                                dismissedAtMillis = SystemClock.uptimeMillis()
                                expanded = false
                                if (!model.equals(currentModel, ignoreCase = true)) {
                                    onSwitch(model)
                                }
                            },
                        )
                    },
                ),
            ),
            placement = MenuPlacement(gravity = MenuGravity.TopStart, gap = 8.dp),
            anchor = anchorBounds.value,
            hazeState = hazeState,
            // 单选型菜单：行暴露 selected 语义 + RadioButton 角色（TalkBack 播报已选中）
            selectionMode = true,
            // 非 focusable：胶囊不在输入框里，打开菜单时键盘保持原样；返回键由 MenuBackHandler 兜底
            focusable = false,
        )
    }
}
