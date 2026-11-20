package com.fangyi.classpp.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ai.AiFetchedModel
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SheetCard
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.theme.CardSectionSpacing
import com.fangyi.classpp.ui.theme.classppTextStyles
import dev.chrisbanes.haze.rememberHazeState

/**
 * 「选择模型」浮层（AI 配置方案浮层 →「从服务商拉取模型」成功后打开）：
 *
 * - 展示本次拉取到、且尚未加入当前草稿连接的新模型（[AiFetchedModel]，含服务商附带
 *   或模型族推断的推理等级等配置），多选后「添加」；配置随勾选结果一并交回父浮层合并；
 * - 行规格同选择类列表：白卡整行点按切换，选中行文字主色 + 行尾对勾；
 *   对勾恒在组合内（仅透明度/缩放变化），勾选切换不重写采样源结构，Haze 链稳定；
 * - 默认全不选（服务商模型可能很多，避免误加整张列表），未选中时顶栏「添加」置灰；
 * - 确认只把勾选结果交回父浮层合并进**本地草稿**（父浮层「确认」才落库）；
 *   已加入的模型在拉取侧已按同名直接覆盖配置，只有新模型进入本列表，合并不会
 *   改动现有模型的推理等级；
 * - 列表可能较长：整页为 `OverlaySheet` 的 lazy 内容模式，只组合视口内的行。
 *
 * 层级：叠在 AI 配置方案浮层之上，父浮层经 covered 缩小后退；本浮层不重写自身内容，
 * 父层合并结果（模型行结构变化）期间由父层 Haze 退让，本浮层同步卸载效果节点。
 */
@Composable
internal fun AiModelPickerSheet(
    visible: Boolean,
    models: List<AiFetchedModel>,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    hazeEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(emptySet<String>()) }
    // 每次打开清空勾选：上一轮的勾选不带进新一轮拉取结果
    LaunchedEffect(visible, models) {
        if (visible) selected = emptySet()
    }
    val sheetHazeState = rememberHazeState()

    // 返回键关浮层（两段式：visible 复位后播退场，播完才卸载）；退场期间让回父浮层
    BackHandler(enabled = visible, onBack = onDismiss)

    OverlaySheet(
        title = stringResource(R.string.ai_model_fetch_title),
        confirmLabel = stringResource(R.string.ai_model_fetch_add),
        // 未勾选任何模型时确认置灰（同 BottomSelectSheet 的可用态语义）
        confirmEnabled = selected.isNotEmpty(),
        confirmAtEnd = true,
        onConfirm = {
            if (selected.isNotEmpty()) {
                // 保持列表顺序；配置由父浮层按 id 从拉取结果里取
                onConfirm(models.filter { it.id in selected }.map { it.id })
                onDismiss()
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
        hazeEnabled = hazeEnabled,
        hazeState = sheetHazeState,
        modifier = modifier,
        lazyContent = {
            itemsIndexed(
                items = models,
                key = { _, model -> model.id },
            ) { index, model ->
                val isSelected = model.id in selected
                ModelPickerRow(
                    model = model.id,
                    selected = isSelected,
                    modifier = Modifier.padding(
                        // 行卡之间留 CardSectionSpacing；末行不垫，避免尾部多一段
                        bottom = if (index == models.lastIndex) 0.dp else CardSectionSpacing,
                    ),
                    onClick = {
                        selected = if (isSelected) selected - model.id else selected + model.id
                    },
                )
            }
        },
    )
}

/** 模型行：选中主色 + 行尾对勾；对勾常驻组合，切换只改透明度/缩放（不重写结构） */
@Composable
private fun ModelPickerRow(
    model: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val textColor by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.onSurface,
        animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
        label = "modelPickerText",
    )
    val checkScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
        label = "modelPickerCheck",
    )
    SheetCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Text(
            text = model,
            style = MaterialTheme.classppTextStyles.fieldLabel,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Icon(
            painter = painterResource(R.drawable.ic_checkmark),
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    scaleX = checkScale
                    scaleY = checkScale
                    alpha = checkScale
                },
        )
    }
}
