package com.fangyi.classpp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.fangyi.classpp.ui.schedule.ScheduleScreen
import com.fangyi.classpp.ui.theme.ClassppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClassppTheme {
                ScheduleScreen()
            }
        }
    }
}
