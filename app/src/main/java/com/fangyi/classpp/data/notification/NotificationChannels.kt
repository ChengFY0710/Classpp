package com.fangyi.classpp.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.fangyi.classpp.R

/** 渠道 id：课程 / 待办各一条，系统设置里可分别控制声音与横幅 */
const val CHANNEL_COURSE = "course_reminders"
const val CHANNEL_TODO = "todo_reminders"

/**
 * 幂等创建两个通知渠道：已存在时重创建为无害空操作（系统保留用户对渠道的手改）。
 * IMPORTANCE_HIGH 让提醒走横幅 + 声音；minSdk 24 < 26 的低版本没有渠道概念，直接跳过。
 */
fun ensureNotificationChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    manager.createNotificationChannels(
        listOf(
            NotificationChannel(
                CHANNEL_COURSE,
                context.getString(R.string.notification_channel_course),
                NotificationManager.IMPORTANCE_HIGH,
            ),
            NotificationChannel(
                CHANNEL_TODO,
                context.getString(R.string.notification_channel_todo),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        ),
    )
}
