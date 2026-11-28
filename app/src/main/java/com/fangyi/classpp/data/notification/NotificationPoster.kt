package com.fangyi.classpp.data.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fangyi.classpp.MainActivity
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.settings.NotificationSettings

/**
 * 通知构建与发布层：普通通知与实况通知（Live Updates）的 Notification 装配，供闹钟到点
 * （[NotificationAlarmReceiver]）、重排补发（[NotificationScheduler.reschedule]）与测试
 * 入口（[NotificationScheduler.postTestLiveUpdate]）共用——样式改动一处生效。
 */
internal object NotificationPoster {

    /**
     * 发布一条排期提醒（闹钟到点与重排补发的共用出口）：渠道就绪 + 通知总开关双检后，
     * 按 [settings] 的通知方式选 Live Updates（API 36+）或普通通知。
     *
     * @return 是否真正发布——总开关被关或系统拒绝时 false，调用方据此决定是否记入
     * 补发表（未真正弹出的提醒保留补发资格，开关恢复后窗口内仍会补上）
     */
    fun post(context: Context, item: PlannedNotification, settings: NotificationSettings): Boolean {
        ensureNotificationChannels(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val isCourse = item.kind == NotificationKind.Course
        val channel = if (isCourse) CHANNEL_COURSE else CHANNEL_TODO
        // Live Updates 仅 API 36+ 存在；低版本设备按普通通知呈现（回退语义）
        val live = settings.method == NotificationSettings.METHOD_LIVE &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA
        val notification = if (live) {
            buildLiveUpdate(context, channel, item.title, item.text, chipText = item.chipText, isCourse = isCourse)
        } else {
            buildStandard(context, channel, item.title, item.text, isCourse = isCourse, onlyAlertOnce = true)
        }
        // 权限在通知开关检查过、仍可能恰在两步之间被撤销：系统对静默失败场景抛 SecurityException
        return try {
            manager.notify(item.key.hashCode(), notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * 实况通知（Live Updates，API 36+）：持续驻留，状态栏 chip 显示事件名
     * （[Notification.Builder.setShortCriticalText]：≤7 字符完整显示，超出按 chip 宽度
     * 截断），不做倒计时。smallIcon 按提醒类别取专属彩色 drawable（课程 = 日历、
     * 待办 = 笔记，自带 #479CFF 原色）——chip 表面按原色渲染该图标。
     *
     * 平台 [Notification.Builder] 直用：NotificationCompat 1.10.x 不含 Live Updates API。
     * promoted ongoing 的硬性条件（官方文档）：ongoing + setRequestPromotedOngoing +
     * manifest 的 POST_PROMOTED_NOTIFICATIONS 权限 + 样式 ∈ {BigText, Call, Progress,
     * Metric} + 有 contentTitle + 渠道非 MIN——setStyle 由此而来。
     * [Notification.Builder.setRequestPromotedOngoing] 是 36.1 的方法，初版 36 设备上
     * 不存在，NoSuchMethodError 兜底（36 上 ongoing + shortCriticalText 仍是完整实况，
     * 只少 chip 的主动请求位）。API 36- 的设备由调用侧回退 [buildStandard]。
     */
    fun buildLiveUpdate(
        context: Context,
        channel: String,
        title: String,
        text: String,
        chipText: String,
        isCourse: Boolean,
    ): Notification {
        val builder = Notification.Builder(context, channel)
            // chip 上的彩色图标：按提醒类别取专属 drawable（自带 #479CFF 原色）——
            // chip 表面按原色渲染；通知栏头部等经典表面仍会套成单色剪影（形状不变）
            .setSmallIcon(
                if (isCourse) R.drawable.ic_calendar_liveupdates else R.drawable.ic_note_liveupdates,
            )
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent(context, isCourse, title.hashCode()))
            // 持续驻留 + 进度类目 + chip 内容 = 展示信息（课程名/上课地点口径，不做倒计时）
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setShortCriticalText(chipText)
        try {
            builder.setRequestPromotedOngoing(true)
        } catch (_: NoSuchMethodError) {
        }
        return builder.build()
    }

    /**
     * 普通通知：瞬时提醒，点按/滑除即走（全 API 版本通用的形态）。
     * [contentIntent] 非 null 时按调用方落页（自动同步结果进主页），null 走
     * 课程/待办 tab 的默认落页。
     *
     * [onlyAlertOnce] 给提醒类调用方（[post]）用：同一提醒的重复发布（到点与补发的
     * 极小竞态）只静默刷新，不重复响铃；同步报告等每次都是新事件的调用方保持默认。
     */
    fun buildStandard(
        context: Context,
        channel: String,
        title: String,
        text: String,
        isCourse: Boolean,
        contentIntent: PendingIntent? = null,
        onlyAlertOnce: Boolean = false,
    ): Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent ?: contentIntent(context, isCourse, title.hashCode()))
            .setAutoCancel(true)
            .setOnlyAlertOnce(onlyAlertOnce)
            .build()

    /** 通知点击的落页 intent：课程提醒进课表 tab、待办提醒进待办 tab（MainActivity 切 tab） */
    private fun contentIntent(context: Context, isCourse: Boolean, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(
                    NotificationAlarmReceiver.EXTRA_NOTIFICATION_TAB,
                    if (isCourse) NotificationAlarmReceiver.TAB_COURSE else NotificationAlarmReceiver.TAB_TODO,
                ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
