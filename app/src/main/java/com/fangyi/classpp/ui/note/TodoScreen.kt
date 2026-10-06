package com.fangyi.classpp.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** 演示分组：数据层接入后由用户自定义分组替换 */
private val DemoGroups = listOf("作业", "日常琐事")

/**
 * 待办页：页面底色铺 background（tab 转场缩小淡出时不透出其他层）。
 * 顶栏（文件夹/排序 + 分组条）已就位；选中态在此持有，后续按分组过滤列表。
 */
@Composable
fun TodoScreen(modifier: Modifier = Modifier) {
    var selectedGroupIndex by rememberSaveable { mutableIntStateOf(0) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TodoTopBar(
            groups = DemoGroups,
            selectedGroupIndex = selectedGroupIndex,
            onGroupSelect = { selectedGroupIndex = it },
            // 文件夹/排序的操作后续接入，本期纯 UI
            onFolderClick = {},
            onSortClick = {},
        )
        // 分组列表内容后续填充
        Box(Modifier.fillMaxSize())
    }
}
