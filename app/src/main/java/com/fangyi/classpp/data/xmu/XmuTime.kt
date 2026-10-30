package com.fangyi.classpp.data.xmu

import com.fangyi.classpp.data.model.IsoDate
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * 课程中心时间换算：远端所有时间字段都是 UTC ISO-8601，一律按固定时区 Asia/Shanghai
 * 换算成 [IsoDate] + 当天分钟数——**不依赖设备时区**（设备不在 +8 时截止日会整体偏移）。
 * 项目惯例规避 java.time（minSdk 24），用 GregorianCalendar + TimeZone。
 */
object XmuTime {

    /** 截止时刻：北京时间日期 + 当天分钟数（0..1439） */
    data class Deadline(val date: IsoDate, val minute: Int)

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    private val SHANGHAI: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    // 秒与毫秒可缺省（实测形如 2026-11-04T10:30:00Z）；matchEntire 拒绝带时区偏移等其他形态
    private val ISO_UTC = Regex(
        """^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?Z$""",
    )

    /**
     * UTC ISO 字符串 → 北京时间 [Deadline]；空串/格式不符/非法日期（如 02-30，isLenient=false
     * 抛异常）返回 null，由映射层跳过该条。秒直接截断到分钟（23:59:59 → 1439）。
     */
    fun parseDeadline(text: String?): Deadline? {
        val m = text?.let(ISO_UTC::matchEntire) ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        return try {
            val utc = GregorianCalendar(UTC).apply {
                isLenient = false
                clear()
                set(
                    y.toInt(), mo.toInt() - 1, d.toInt(),
                    h.toInt(), mi.toInt(), s.toIntOrNull() ?: 0,
                )
            }
            val sh = GregorianCalendar(SHANGHAI).apply { timeInMillis = utc.timeInMillis }
            Deadline(
                date = IsoDate.of(
                    sh.get(GregorianCalendar.YEAR),
                    sh.get(GregorianCalendar.MONTH) + 1,
                    sh.get(GregorianCalendar.DAY_OF_MONTH),
                ),
                minute = sh.get(GregorianCalendar.HOUR_OF_DAY) * 60 +
                    sh.get(GregorianCalendar.MINUTE),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * 截止是否已过（同一 Asia/Shanghai 口径、分钟粒度）。不用 [IsoDate.today]——它按设备
     * 时区取「今天」，与本对象的固定 +8 口径不一致。
     */
    fun isOverdue(deadline: Deadline, nowMillis: Long): Boolean {
        val cal = GregorianCalendar(SHANGHAI).apply { timeInMillis = nowMillis }
        val today = IsoDate.of(
            cal.get(GregorianCalendar.YEAR),
            cal.get(GregorianCalendar.MONTH) + 1,
            cal.get(GregorianCalendar.DAY_OF_MONTH),
        )
        val nowMinute = cal.get(GregorianCalendar.HOUR_OF_DAY) * 60 +
            cal.get(GregorianCalendar.MINUTE)
        // IsoDate 未实现 Comparable，统一用 epochDay 比较
        return deadline.date.epochDay < today.epochDay ||
            (deadline.date.epochDay == today.epochDay && deadline.minute < nowMinute)
    }
}
