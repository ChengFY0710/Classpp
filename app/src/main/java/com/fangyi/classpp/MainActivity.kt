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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                // 各 tab 状态（滚动位置、选中周等）切换后保留
                val stateHolder = rememberSaveableStateHolder()
                Box(Modifier.fillMaxSize()) {
                    stateHolder.SaveableStateProvider(selectedTab) {
                        when (selectedTab) {
                            AppTab.Agenda -> AgendaScreen()
                            AppTab.Timetable -> ScheduleScreen()
                            AppTab.Todo -> TodoScreen()
                        }
                    }
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
