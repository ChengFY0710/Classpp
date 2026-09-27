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

/** 日程页占位：真实内容后续实现 */
@Composable
fun AgendaScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.nav_schedule),
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

/** 待办页占位：真实内容后续实现 */
@Composable
fun TodoScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.nav_todo),
            style = MaterialTheme.typography.titleLarge,
        )
    }
}
