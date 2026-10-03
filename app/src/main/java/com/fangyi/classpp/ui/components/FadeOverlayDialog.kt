package com.fangyi.classpp.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.ui.theme.DialogShape

/** 弹窗淡入淡出时长：遮罩与卡片同一条透明度，进减速、出加速（与浮层曲线同一族） */
private const val DialogFadeMillis = 200

/**
 * 页内居中弹窗基座（[com.fangyi.classpp.ui.schedule.AlternatePickerDialog] 等共用）：
 * 全屏遮罩压暗 + 居中白卡（[DialogShape] 平滑圆角、scrim 染色的 8dp 柔影、左右 [cardHorizontalPadding] 边距）。
 *
 * 与 [OverlaySheet] 同一约定：页内覆盖层而非窗口类对话框；**挂载与可见性分离**——
 * 调用方在 [visible] 置 false 后保持本组件在组合里，内容淡出（遮罩同步变淡），
 * **播完后才回调 [onDismissed]**，调用方在这一刻把它移出组合，收场期间内容保持原样不闪空
 * （需调用方/组件自行留住最后一份文案或列表）。出场期间遮罩点击与返回键已禁用，
 * 天然防二次触发；遮罩点击/返回键 → [onDismiss]（只表示请求关闭，不直接卸载）。
 */
@Composable
fun FadeOverlayDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    cardHorizontalPadding: Dp = 24.dp,
    content: @Composable () -> Unit,
) {
    // 遮罩与卡片共用一条透明度：0 = 全隐、1 = 全显；挂载即从 0 起播进场淡入
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        alpha.animateTo(
            if (visible) 1f else 0f,
            tween(
                DialogFadeMillis,
                easing = if (visible) LinearOutSlowInEasing else FastOutLinearInEasing,
            ),
        )
        if (!visible) onDismissed()
    }
    val scrimInteraction = remember { MutableInteractionSource() }
    BackHandler(enabled = visible, onBack = onDismiss)
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
            // 点空白处收起（无涟漪）；出场期间禁用
            .clickable(
                interactionSource = scrimInteraction,
                indication = null,
                enabled = visible,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 白卡：graphicsLayer 画法替代 Surface，阴影可染主题 scrim 色（深色模式只动 theme）；
        // 不 clip：内容由 padding 收边，同时放行卡内胶囊按钮溢出卡缘的淡影（同 Surface 行为）
        val cardShadowColor = MaterialTheme.colorScheme.scrim
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = cardHorizontalPadding)
                // 吃掉落在卡片上的点击，避免穿透到遮罩把弹窗关掉
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .graphicsLayer {
                    shape = DialogShape
                    shadowElevation = 36.dp.toPx()
                    spotShadowColor = cardShadowColor.copy(alpha = 0.2f)
                    ambientShadowColor = cardShadowColor.copy(alpha = 0.05f)
                }
                .background(MaterialTheme.colorScheme.surface, DialogShape),
        ) {
            content()
        }
    }
}
