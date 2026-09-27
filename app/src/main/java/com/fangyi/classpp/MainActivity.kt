package com.fangyi.classpp

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.fangyi.classpp.ui.navigation.AppTab
import com.fangyi.classpp.ui.navigation.BottomNavBar
import com.fangyi.classpp.ui.placeholder.AgendaScreen
import com.fangyi.classpp.ui.placeholder.TodoScreen
import com.fangyi.classpp.ui.schedule.ScheduleScreen
import com.fangyi.classpp.ui.theme.ClassppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 固定浅色系统栏外观：状态栏/导航栏全透明、深色前景。
        // 必须用 light 而非 auto：auto 会把 isNavigationBarContrastEnforced 置为 true，
        // 三键导航下系统会自动垫一层灰色遮罩
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            ClassppTheme {
                var selectedTab by rememberSaveable { mutableStateOf(AppTab.Timetable) }
                Box(Modifier.fillMaxSize()) {
                    // 三个页面常驻组合，切 tab 仅将非选中页移出窗口。
                    // 若用 SaveableStateProvider 按 key 重建课表页，首帧 headerHeight
                    // 回落到估算值再被校正，紧贴头部的日期带会明显跳闪一次。
                    AgendaScreen(
                        Modifier
                            .fillMaxSize()
                            .offscreenWhenHidden(selectedTab != AppTab.Agenda),
                    )
                    ScheduleScreen(
                        Modifier
                            .fillMaxSize()
                            .offscreenWhenHidden(selectedTab != AppTab.Timetable),
                    )
                    TodoScreen(
                        Modifier
                            .fillMaxSize()
                            .offscreenWhenHidden(selectedTab != AppTab.Todo),
                    )
                    BottomNavBar(
                        selectedTab = selectedTab,
                        onTabSelected = { selectedTab = it },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

/**
 * 隐藏页面时把该页整体放置到窗口之外：组合状态全程存活（切 tab 不重建、不闪烁），
 * 离屏位置不参与绘制（被窗口裁剪）也不参与命中测试（后台页收不到点击/滚动）。
 */
private fun Modifier.offscreenWhenHidden(hidden: Boolean): Modifier =
    if (!hidden) {
        this
    } else {
        this
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.place(placeable.width * 2, 0)
                }
            }
            // 屏幕外的内容对 TalkBack 隐藏
            .clearAndSetSemantics {}
    }
