package com.fangyi.classpp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.classppColors

/**
 * 全屏加载浮层：与账号与同步页「正在同步…」同款 [FadeOverlayDialog] + [LoadingCard]。
 *
 * 挂载/可见分离由本组件内部处理：`visible` 置 false 后仍保留在场播淡出，播完才卸载。
 * 在途不可关闭（遮罩点击与返回键都不放行），网络/文件操作自带超时兜底。
 * 需要压在当前页面/浮层之上时，把它组合在目标之后即可（组合顺序即绘制顺序）。
 */
@Composable
fun LoadingOverlay(
    visible: Boolean,
    message: String = stringResource(R.string.sync_syncing),
) {
    var mounted by remember { mutableStateOf(false) }
    if (visible) mounted = true
    if (!mounted) return
    FadeOverlayDialog(
        visible = visible,
        onDismiss = {},
        onDismissed = { mounted = false },
    ) {
        LoadingCard(message)
    }
}

/** 加载卡：小转圈 + 文案，垂直居中 */
@Composable
fun LoadingCard(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            strokeWidth = 3.dp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = message,
            style = ClassppTextTokens.dialogMessage,
            color = MaterialTheme.classppColors.secondaryText,
        )
    }
}
