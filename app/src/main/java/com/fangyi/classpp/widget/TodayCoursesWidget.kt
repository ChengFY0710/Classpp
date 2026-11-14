package com.fangyi.classpp.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.PreviewSizeMode
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.currentState
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.CourseColor
import com.fangyi.classpp.ui.theme.resolveWidgetColors
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页「今日课程」组件（4×2）。
 *
 * 渲染数据走 Glance 状态（[WidgetSnapshotKey]）：`provideGlance` 只在会话启动时跑一次，
 * 之后的 `updateAll` 只会让会话重读状态并重组。所以「读课表 + 解析当前节 + 取主题色」
 * 统一在 [refreshTodayCoursesWidget] 里完成并写入状态，组合只读状态渲染。
 */
class TodayCoursesWidget : GlanceAppWidget() {

    // Exact：按 launcher 实际给的尺寸渲染，行数由 LocalSize 自适应
    override val sizeMode: SizeMode = SizeMode.Exact

    // 组件选择器预览固定按 4×2 目标尺寸渲染：默认 Single 会取 minWidth/minHeight
    // （250×110dp），只够 1 行，预览看不出「三行 + 当前节高亮」的版式
    override val previewSizeMode: PreviewSizeMode =
        SizeMode.Responsive(setOf(DpSize(320.dp, 170.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initialState = withContext(Dispatchers.IO) { loadTodayCoursesWidgetState(context) }
        val initialEncoded = encodeWidgetState(initialState)
        // 会话启动时先把现场快照落到状态里：旧会话残留的状态可能来自昨天
        runCatching {
            updateAppWidgetState(context, id) { prefs ->
                prefs[WidgetSnapshotKey] = initialEncoded
            }
        }
        provideContent {
            val encoded = currentState(WidgetSnapshotKey) ?: initialEncoded
            val state = remember(encoded) { decodeWidgetState(encoded) ?: initialState }
            TodayCoursesWidgetContent(state)
        }
    }

    /**
     * 组件选择器预览：用示意数据渲染（不读用户课表，避免在 picker 里泄露真实课程名）。
     * 版式与设计稿一致——中间那节即当前节。
     */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val state = previewState(context)
        provideContent { TodayCoursesWidgetContent(state) }
    }

    private fun previewState(context: Context): TodayCoursesWidgetState {
        val colors = resolveWidgetColors(context)
        val rows = listOf(
            WidgetCourseRow(
                courseId = "preview-1",
                name = context.getString(R.string.widget_preview_course_1),
                location = context.getString(R.string.widget_preview_location_1),
                barColor = colors.courseBar(CourseColor.Blue).toArgb(),
                startMinute = 10 * 60 + 10,
                endMinute = 11 * 60 + 50,
                current = false,
            ),
            WidgetCourseRow(
                courseId = "preview-2",
                name = context.getString(R.string.widget_preview_course_2),
                location = context.getString(R.string.widget_preview_location_2),
                barColor = colors.primary.toArgb(),
                startMinute = 14 * 60 + 30,
                endMinute = 16 * 60 + 10,
                current = true,
            ),
            WidgetCourseRow(
                courseId = "preview-3",
                name = context.getString(R.string.widget_preview_course_3),
                location = context.getString(R.string.widget_preview_location_3),
                barColor = colors.courseBar(CourseColor.Green).toArgb(),
                startMinute = 19 * 60 + 10,
                endMinute = 20 * 60 + 50,
                current = false,
            ),
        )
        return TodayCoursesWidgetState(
            hasSchedule = true,
            weekNumber = 12,
            weekday = 4,
            nowMinute = 14 * 60 + 40,
            palette = colors.toPalette(),
            entries = rows,
        )
    }
}

class TodayCoursesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayCoursesWidget()

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // launcher 主动更新（添加 / 周期到点 / 尺寸变化）：重写快照并续排边界闹钟。
        // 只调超类不够——已存在的会话不会重跑 provideGlance，会继续渲染旧快照
        refreshTodayCoursesWidgetInBackground(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        refreshTodayCoursesWidgetInBackground(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // 最后一个实例被移除：停掉边界闹钟，不留常驻唤醒
        WidgetRefresh.cancel(context)
    }
}

/**
 * 把现场快照写入所有组件实例的 Glance 状态并触发重绘（无实例时为空操作）。
 * App 内数据 / 主题变化、边界闹钟、系统事件都走这一条路径。
 */
internal suspend fun refreshTodayCoursesWidget(context: Context) {
    val appContext = context.applicationContext
    val ids = GlanceAppWidgetManager(appContext).getGlanceIds(TodayCoursesWidget::class.java)
    if (ids.isEmpty()) return
    val encoded = withContext(Dispatchers.IO) {
        encodeWidgetState(loadTodayCoursesWidgetState(appContext))
    }
    runCatching {
        ids.forEach { id ->
            updateAppWidgetState(appContext, id) { prefs ->
                prefs[WidgetSnapshotKey] = encoded
            }
        }
        TodayCoursesWidget().updateAll(appContext)
    }
}

/** 系统回调（onUpdate / onEnabled）不能挂起：后台刷新 + 续排，失败静默。 */
internal fun refreshTodayCoursesWidgetInBackground(context: Context) {
    val appContext = context.applicationContext
    val handler = CoroutineExceptionHandler { _, _ -> }
    CoroutineScope(SupervisorJob() + Dispatchers.Default + handler).launch {
        refreshTodayCoursesWidget(appContext)
        WidgetRefresh.scheduleNext(appContext)
    }
}
