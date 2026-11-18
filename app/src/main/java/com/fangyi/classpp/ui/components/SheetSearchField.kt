package com.fangyi.classpp.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.RowCardPaddingHorizontal
import com.fangyi.classpp.ui.theme.RowCardPaddingVertical
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles

/**
 * 浮层内搜索输入框：[SheetTextField] 的搜索形态，共用同一套文字规格——
 * 白底胶囊卡（[PillShape]，shapes 库连续曲率圆滑圆角的全圆角胶囊）、行内距
 * 水平 16 / 垂直 14、输入值 16sp Medium 蓝色
 * （fieldValue）、占位 16sp Medium 灰色（fieldPlaceholder）；聚焦 2dp 蓝描边与
 * SheetTextField 同走一条生长/萎缩动画（宽度与不透明度共用 0→1 进度）。
 *
 * 与 SheetTextField 的差异（搜索语义）：
 * - 卡高不带 60dp 下限：14×2 上下内距 + 24dp 图标自然撑出 ≈52dp 单行条；
 * - 左侧恒显 ic_search（24dp 灰），有内容时右侧补 ic_dismiss_circle 清空钮
 *   （24dp 灰，按压反馈直接落在图标圆上：按下圆内提亮 + 放大）；
 * - singleLine 硬行 + 滤换行符：搜索词永不换行（SheetTextField 是视觉换行、逻辑单行）；
 * - 占位空值即显（聚焦也显示，光标停在占位文字首字前），不学表单框聚焦即藏；
 * - 键盘动作恒 Search，按下搜索键收起焦点；无 label、无 isError（搜索词不参与表单校验）。
 *
 * 键盘行为与 SheetTextField 一致：已聚焦时再点输入区重新 show 一次键盘——Compose
 * 输入框已聚焦时再点不会拉起键盘，键盘一旦被收起就唤不回来（上机踩过）。
 * 「聚焦后点空白取消聚焦（收起键盘）」由宿主容器提供（clearFocusOnTap，
 * OverlaySheet 的内容列、顶栏与钉底槽已内置），字段自身无需处理。
 */
@Composable
fun SheetSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    /** 清空按钮的无障碍描述（TalkBack 朗读用），空串时按钮对读屏不可见 */
    clearContentDescription: String = "",
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val keyboard = LocalSoftwareKeyboardController.current
    // 已聚焦时再点输入区：Compose 不会重新拉起键盘，键盘一旦被收起就唤不回（上机踩过）
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) keyboard?.show()
        }
    }

    // 聚焦蓝描边：宽度与不透明度共用同一条 0→1 动画（与 SheetTextField 同源同曲线），
    // 聚焦“生长+淡入”、失焦“萎缩+淡出”严格同步；进度归 0 后干脆不挂 border
    // （0 宽描边会被 Skia 画成 1px 发丝线而非不可见）
    val strokeProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = Motion.FastMillis, easing = Motion.Decelerate),
        label = "sheetSearchStroke",
    )
    val strokeColor = MaterialTheme.colorScheme.primary
    val strokeWidth = 2.dp * strokeProgress

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(PillShape)
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (strokeWidth > 0.dp) {
                    Modifier.border(
                        width = strokeWidth,
                        color = strokeColor.copy(alpha = strokeColor.alpha * strokeProgress),
                        shape = PillShape,
                    )
                } else {
                    Modifier
                },
            )
            .onFocusChanged { focused = it.hasFocus }
            .padding(horizontal = RowCardPaddingHorizontal, vertical = RowCardPaddingVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = null,
            tint = MaterialTheme.classppColors.secondaryText,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = value,
            // 键盘/粘贴带入的换行符一律滤掉：搜索词是逻辑单行
            onValueChange = { onValueChange(it.filterNot { ch -> ch == '\n' || ch == '\r' }) },
            textStyle = MaterialTheme.classppTextStyles.fieldValue,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            interactionSource = interactionSource,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            modifier = Modifier
                .weight(1f)
                // 参与宿主的「键盘弹起把输入框滚到键盘上方」（OverlaySheet 的 ImeScrollTracker）：
                // 宿主没这项能力时此修饰符空转
                .imeFieldTracking(),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    // 搜索框占位空值即显（聚焦也显示，光标停在占位文字首字前），不学表单框聚焦即藏
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.classppTextStyles.fieldPlaceholder,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(12.dp))
            Icon(
                painter = painterResource(R.drawable.ic_dismiss_circle),
                contentDescription = clearContentDescription.ifEmpty { null },
                tint = MaterialTheme.classppColors.secondaryText,
                // 按压反馈直接落在 24dp 图标圆上：按下圆内提亮 + 放大（小图标钮惯例 1.10f）
                modifier = Modifier
                    .size(24.dp)
                    .pressClickable(shape = CircleShape, scale = 1.10f) { onValueChange("") },
            )
        }
    }
}
