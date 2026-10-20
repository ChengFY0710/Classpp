package com.fangyi.classpp.ui.placeholder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.components.BottomFadeScrim

/** 日程页占位：真实内容后续实现 */
@Composable
fun AgendaScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.nav_schedule),
            style = MaterialTheme.typography.titleLarge,
        )
        // 底部渐变遮罩：页面自绘（同课表/待办页），真实内容落地后盖住滚进导航栏区域的内容
        BottomFadeScrim(modifier = Modifier.align(Alignment.BottomCenter))
    }
}
