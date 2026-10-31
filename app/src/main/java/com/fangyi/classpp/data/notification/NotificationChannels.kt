package com.fangyi.classpp.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.fangyi.classpp.R

/** 渠道 id：课程 / 待办 / 同步报告各一条，系统设置里可分别控制声音与横幅 */
const val CHANNEL_COURSE = "course_reminders"
const val CHANNEL_TODO = "todo_reminders"
const val CHANNEL_SYNC = "sync_reports"

/**
 * 幂等创建三个通知渠道：已存在时重创建为无害空操作（系统保留用户对渠道的手改）。
 * IMPORTANCE_HIGH 让提醒走横幅 + 声音；同步报告用 IMPORTANCE_DEFAULT（结果汇报，
 * 不抢横幅）；minSdk 24 < 26 的低版本没有渠道概念，直接跳过。
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
            NotificationChannel(
                CHANNEL_SYNC,
                context.getString(R.string.notification_channel_sync),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        ),
    )
}
