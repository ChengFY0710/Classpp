package com.fangyi.classpp.data.calendar

import android.content.ContentResolver
import android.content.ContentValues
import android.provider.CalendarContract
import com.fangyi.classpp.data.TodoError
import com.fangyi.classpp.data.TodoReadResult
import com.fangyi.classpp.data.model.IsoDate
import java.util.GregorianCalendar
import java.util.TimeZone

/** 可写入的本地日历账户（事件的写入目标） */
data class CalendarAccount(
    val id: Long,
    val displayName: String,
    val accountName: String,
)

/**
 * 系统日历读写器：仅封装 ContentResolver 调用与错误映射，不含业务规则
 * （哪些待办可加由 CalendarEventPlanner 决定）。需要 READ_CALENDAR /
 * WRITE_CALENDAR 运行时权限，未授权时 SecurityException 映射为
 * [TodoError.CalendarAccessDenied]。阻塞 I/O，调用方包到 Dispatchers.IO。
 */
class SystemCalendar(private val resolver: ContentResolver) {

    /** 列出设备上的日历账户；无权限返回 [TodoError.CalendarAccessDenied] */
    fun listCalendars(): TodoReadResult<List<CalendarAccount>> {
        val accounts = try {
            queryCalendars()
        } catch (_: SecurityException) {
            return TodoReadResult.Err(TodoError.CalendarAccessDenied)
        }
        return TodoReadResult.Ok(accounts)
    }

    /** 把事件草稿写入指定日历，返回创建的事件 id（顺序与 [drafts] 一致） */
    fun insertEvents(drafts: List<CalendarEventDraft>, calendarId: Long): TodoReadResult<List<Long>> {
        if (drafts.isEmpty()) return TodoReadResult.Err(TodoError.CalendarNoTime)
        val ids = try {
            drafts.map { insert(it, calendarId) }
        } catch (_: SecurityException) {
            return TodoReadResult.Err(TodoError.CalendarAccessDenied)
        } catch (e: Exception) {
            return TodoReadResult.Err(TodoError.CalendarInsertFailed(e.message ?: e::class.simpleName ?: "?"))
        }
        return TodoReadResult.Ok(ids)
    }

    private fun queryCalendars(): List<CalendarAccount> {
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            CALENDAR_PROJECTION,
            /* selection = */ null,
            /* selectionArgs = */ null,
            /* sortOrder = */ null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val accountIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
            return buildList {
                while (cursor.moveToNext()) {
                    add(
                        CalendarAccount(
                            id = cursor.getLong(idIdx),
                            displayName = cursor.getString(nameIdx) ?: "",
                            accountName = cursor.getString(accountIdx) ?: "",
                        ),
                    )
                }
            }
        } ?: return emptyList()
    }

    private fun insert(draft: CalendarEventDraft, calendarId: Long): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, draft.title)
            put(CalendarContract.Events.DESCRIPTION, draft.description)
            put(CalendarContract.Events.EVENT_LOCATION, draft.location)
            if (draft.allDay) {
                // 全天事件约定：UTC 零点起、次日零点止、allDay=1、时区 UTC
                put(CalendarContract.Events.DTSTART, draft.date.utcMidnightMillis())
                put(CalendarContract.Events.DTEND, draft.date.utcMidnightMillis() + MILLIS_PER_DAY)
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else {
                val startMinute = draft.startMinute
                    ?: throw IllegalArgumentException("timed event without startMinute")
                put(CalendarContract.Events.DTSTART, draft.date.localMillis(startMinute))
                put(CalendarContract.Events.DTEND, draft.date.localMillis(draft.endMinute ?: startMinute))
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
        }
        val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: throw IllegalStateException("calendar provider returned null uri")
        return uri.lastPathSegment?.toLongOrNull()
            ?: throw IllegalStateException("calendar provider returned no event id: $uri")
    }

    /** 当日 UTC 零点的 epoch millis（全天事件约定） */
    private fun IsoDate.utcMidnightMillis(): Long = epochDay * MILLIS_PER_DAY

    /** 当日本地时区 [minuteOfDay] 的 epoch millis（与 IsoDate.today 同用本地 GregorianCalendar） */
    private fun IsoDate.localMillis(minuteOfDay: Int): Long {
        val utc = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = epochDay * MILLIS_PER_DAY
        }
        return GregorianCalendar().apply {
            clear()
            set(
                utc.get(GregorianCalendar.YEAR),
                utc.get(GregorianCalendar.MONTH),
                utc.get(GregorianCalendar.DAY_OF_MONTH),
                minuteOfDay / 60,
                minuteOfDay % 60,
            )
        }.timeInMillis
    }

    companion object {
        private const val MILLIS_PER_DAY = 86_400_000L

        private val CALENDAR_PROJECTION = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
        )
    }
}
