package com.fangyi.classpp.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 待办页：页面底色铺 background（tab 转场缩小淡出时不透出其他层），
 * 内容由 NoteCardSection / NoteCard 分组列表逐步填充。
 */
@Composable
fun TodoScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    )
}
