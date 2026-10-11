package com.fangyi.classpp.data.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.fangyi.classpp.MainActivity
import com.fangyi.classpp.R

/**
 * 通知构建层：普通通知与实况通知（Live Updates）的 Notification 装配，供闹钟到点
 * （[NotificationAlarmReceiver]）与测试入口（[NotificationScheduler.postTestLiveUpdate]）
 * 两个出口共用——样式改动一处生效。
 */
internal object NotificationPoster {

    /**
     * 实况通知（Live Updates，API 36+）：持续驻留，状态栏 chip 显示事件名
     * （[Notification.Builder.setShortCriticalText]：≤7 字符完整显示，超出按 chip 宽度
     * 截断），不做倒计时。
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
        isCourse: Boolean,
    ): Notification {
        val builder = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent(context, isCourse, title.hashCode()))
            // 持续驻留 + 进度类目 + chip 内容 = 事件名（不做倒计时）
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setShortCriticalText(title)
        try {
            builder.setRequestPromotedOngoing(true)
        } catch (_: NoSuchMethodError) {
        }
        return builder.build()
    }

    /** 普通通知：瞬时提醒，点按/滑除即走（全 API 版本通用的形态） */
    fun buildStandard(
        context: Context,
        channel: String,
        title: String,
        text: String,
        isCourse: Boolean,
    ): Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent(context, isCourse, title.hashCode()))
            .setAutoCancel(true)
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
