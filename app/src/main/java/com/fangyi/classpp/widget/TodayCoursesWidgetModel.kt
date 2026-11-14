package com.fangyi.classpp.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fangyi.classpp.data.AppContainer
import com.fangyi.classpp.data.agendaOn
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.activeSchedule
import com.fangyi.classpp.data.store.FileScheduleStore
import com.fangyi.classpp.data.weekOf
import com.fangyi.classpp.ui.theme.WidgetColors
import com.fangyi.classpp.ui.theme.resolveWidgetColors
import java.util.Calendar
import java.util.GregorianCalendar
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 首页「今日课程」组件的数据模型。
 *
 * **为什么渲染数据要进 Glance 状态**：Glance 的 `updateAll` 只会让已存在的会话重新读一次
 * 状态并重组，**不会重跑 `provideGlance`**。如果渲染数据只是 `provideGlance` 里读到的普通
 * 局部变量，那么 App 内改课表 / 换主题后调 `updateAll` 也只是把旧快照再画一遍（组件看起来
 * 「不刷新」）。所以每次刷新都把整份快照写进 `PreferencesGlanceStateDefinition`，组合只读
 * 这份状态（见 [WidgetSnapshotKey]）。
 *
 * 快照里连主题色一起存：主题切换时快照字符串变化，组合才会重组并用新色重绘。
 */
internal val WidgetSnapshotKey = stringPreferencesKey("today_courses_widget_snapshot")

private val SnapshotJson = Json { ignoreUnknownKeys = true }

/** 组件取色快照（ARGB int）：与 `ui/theme/WidgetTheme.kt` 的 [WidgetColors] 一一对应。 */
@Serializable
internal data class WidgetPalette(
    val dark: Boolean,
    val surface: Int,
    val outline: Int,
    val onSurface: Int,
    val secondaryText: Int,
    val primary: Int,
    val currentRowBackground: Int,
)

/** 渲染一行课程：竖条颜色在当前节判定后已解析（当前节 = primary，其余 = 课程色）。 */
@Serializable
internal data class WidgetCourseRow(
    val courseId: String,
    val name: String,
    val location: String,
    val barColor: Int,
    val startMinute: Int,
    val endMinute: Int,
    val current: Boolean,
)

@Serializable
internal data class TodayCoursesWidgetState(
    val hasSchedule: Boolean,
    /** 展示用周次：开学前钳到 0（与课表页一致），学期结束后钳到总周数。 */
    val weekNumber: Int,
    /** 1 = 周一 … 7 = 周日 */
    val weekday: Int,
    val nowMinute: Int,
    val palette: WidgetPalette,
    /** 当天全部课程（升序）；显示几行在组合期按组件高度裁（[selectWindow]）。 */
    val entries: List<WidgetCourseRow> = emptyList(),
)

internal fun WidgetColors.toPalette(): WidgetPalette = WidgetPalette(
    dark = dark,
    surface = surface.toArgb(),
    outline = outline.toArgb(),
    onSurface = onSurface.toArgb(),
    secondaryText = secondaryText.toArgb(),
    primary = primary.toArgb(),
    currentRowBackground = currentRowBackground.toArgb(),
)

internal fun WidgetPalette.toColors(): WidgetColors = WidgetColors(
    dark = dark,
    surface = Color(surface),
    outline = Color(outline),
    onSurface = Color(onSurface),
    secondaryText = Color(secondaryText),
    primary = Color(primary),
    currentRowBackground = Color(currentRowBackground),
)

internal fun encodeWidgetState(state: TodayCoursesWidgetState): String =
    SnapshotJson.encodeToString(state)

internal fun decodeWidgetState(encoded: String): TodayCoursesWidgetState? =
    runCatching { SnapshotJson.decodeFromString<TodayCoursesWidgetState>(encoded) }.getOrNull()

/** 当前课表：进程内容器优先，未装配时退回只读快照（不会触发损坏隔离副作用）。 */
internal fun loadActiveSchedule(context: Context): Schedule? =
    AppContainer.peek()?.scheduleRepository?.activeSchedule?.value
        ?: FileScheduleStore(context.filesDir).peek()?.activeSchedule

/** 读一次现场数据 + 主题，生成可写入 Glance 状态的快照。 */
internal fun loadTodayCoursesWidgetState(
    context: Context,
    nowMillis: Long = System.currentTimeMillis(),
): TodayCoursesWidgetState = buildTodayCoursesWidgetState(
    schedule = loadActiveSchedule(context),
    today = IsoDate.today(nowMillis),
    nowMinute = minuteOfDay(nowMillis),
    colors = resolveWidgetColors(context),
)

/** 当天第几分钟（0..1439）。minSdk 24 未开脱糖，用 Calendar 而非 java.time。 */
internal fun minuteOfDay(millis: Long = System.currentTimeMillis()): Int {
    val cal = GregorianCalendar().apply { timeInMillis = millis }
    return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
}

internal fun buildTodayCoursesWidgetState(
    schedule: Schedule?,
    today: IsoDate,
    nowMinute: Int,
    colors: WidgetColors,
): TodayCoursesWidgetState {
    val weekday = today.isoDayOfWeek()
    val palette = colors.toPalette()
    if (schedule == null) {
        return TodayCoursesWidgetState(
            hasSchedule = false,
            weekNumber = 0,
            weekday = weekday,
            nowMinute = nowMinute,
            palette = palette,
        )
    }
    val rows = schedule.agendaOn(today).map { entry ->
        val current = isOngoing(entry.startMinute, entry.endMinute, nowMinute)
        WidgetCourseRow(
            courseId = entry.courseId,
            name = entry.name,
            location = entry.location,
            barColor = (if (current) colors.primary else colors.courseBar(entry.color)).toArgb(),
            startMinute = entry.startMinute,
            endMinute = entry.endMinute,
            current = current,
        )
    }
    return TodayCoursesWidgetState(
        hasSchedule = true,
        weekNumber = schedule.weekOf(today).coerceIn(0, schedule.totalWeeks),
        weekday = weekday,
        nowMinute = nowMinute,
        palette = palette,
        entries = rows,
    )
}

/** 当前是否正在上这节课：开始含、结束不含（与 DayCourseAlert 同口径）。 */
internal fun isOngoing(startMinute: Int, endMinute: Int, nowMinute: Int): Boolean =
    nowMinute >= startMinute && nowMinute < endMinute

/**
 * 取一段可显示的连续课程：
 * - 当天总节数不超过 [limit]：全显示；
 * - 有当前节：以当前节为锚、先带前一节（贴边时向后补足），保证高亮行永远在窗口里；
 * - 无当前节、后面还有课：从下一节开始取；
 * - 当天已结束：取最后 [limit] 节。
 */
internal fun selectWindow(
    rows: List<WidgetCourseRow>,
    nowMinute: Int,
    limit: Int,
): List<WidgetCourseRow> {
    if (limit <= 0) return emptyList()
    if (rows.size <= limit) return rows
    val ongoing = rows.indexOfFirst { isOngoing(it.startMinute, it.endMinute, nowMinute) }
    val anchor = if (ongoing >= 0) {
        ongoing - 1
    } else {
        val next = rows.indexOfFirst { it.startMinute > nowMinute }
        if (next >= 0) next else rows.lastIndex
    }
    val start = anchor.coerceIn(0, rows.size - limit)
    return rows.subList(start, start + limit)
}

/**
 * 下一次需要重绘的时间点：今天所有课的开始 / 结束 + 次日零点，取最近的一个。
 * 返回值一定晚于 [nowMillis]——同一分钟触发后续排不会排回自己，避免闹钟自激。
 */
internal fun nextRefreshMillis(schedule: Schedule?, nowMillis: Long): Long {
    val midnight = nextMidnightMillis(nowMillis)
    val today = IsoDate.today(nowMillis)
    val boundaries = schedule?.agendaOn(today).orEmpty()
        .flatMap { listOf(it.startMinute, it.endMinute) }
        .map { minuteMillis(nowMillis, it) }
    return (boundaries + midnight).filter { it > nowMillis }.minOrNull() ?: midnight
}

private fun minuteMillis(nowMillis: Long, minuteOfDay: Int): Long =
    GregorianCalendar().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, (minuteOfDay / 60).coerceIn(0, 23))
        set(Calendar.MINUTE, (minuteOfDay % 60).coerceIn(0, 59))
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

private fun nextMidnightMillis(nowMillis: Long): Long =
    GregorianCalendar().apply {
        timeInMillis = nowMillis
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
