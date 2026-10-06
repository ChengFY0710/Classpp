package com.fangyi.classpp.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/** 演示分组：数据层接入后由用户自定义分组替换 */
private val DemoGroups = listOf("作业", "日常琐事")

/**
 * 待办页：页面底色铺 background（tab 转场缩小淡出时不透出其他层）。
 * 内容层垫底（[hazeSource] 采样源：列表滚动到顶栏之下时，未选中胶囊透出模糊虚影），
 * 顶栏浮在上层；选中态在此持有，后续按分组过滤列表。
 */
@Composable
fun TodoScreen(modifier: Modifier = Modifier) {
    var selectedGroupIndex by rememberSaveable { mutableIntStateOf(0) }
    val hazeState = remember { HazeState() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 内容层：列表内容后续填充；标为 hazeSource 供顶栏毛玻璃采样
        Box(
            Modifier
                .matchParentSize()
                .hazeSource(hazeState),
        )
        TodoTopBar(
            groups = DemoGroups,
            selectedGroupIndex = selectedGroupIndex,
            onGroupSelect = { selectedGroupIndex = it },
            // 文件夹/排序的操作后续接入，本期纯 UI
            onFolderClick = {},
            onSortClick = {},
            modifier = Modifier.align(Alignment.TopCenter),
            hazeState = hazeState,
        )
    }
}
