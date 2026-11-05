package com.fangyi.classpp.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ScheduleError
import com.fangyi.classpp.data.ScheduleValidator
import com.fangyi.classpp.data.model.CourseAdjustment
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.newUuid
import com.fangyi.classpp.ui.components.BottomNumberSelectCard
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SelectionGrid
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SheetInfoBanner
import com.fangyi.classpp.ui.components.SheetInfoPillRow
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.classppColors
import dev.chrisbanes.haze.rememberHazeState

/**
 * 调课编辑浮层：把「某门课第 N 周那一次课」移到目标周/星期/节次。
 *
 * 入口复用同一块面板：
 *  - 长按课程卡菜单「调课」→ 原课次默认当前浏览周（该周无课时取最近有课的周）；
 *  - 设置 → 设置调课 → 点某条记录「编辑调课」→ [existingId] 定位已有记录。
 *
 * [schedule] 是**有效课表**：编辑态由调用方传入 `courses = 课程草稿`、
 * `adjustments = 调课草稿` 的副本，保证校验看到的是用户眼前的数据。确认只回调草稿，
 * 真正的落库仍由编辑栏「保存」统一完成；右上「删除」同样只删草稿。
 *
 * 校验口径与 [ScheduleValidator] 完全一致（源课次成立、目标越界、同课次唯一、目标格冲突），
 * 面板内实时提示，确认时再拦一次（Toast）。
 */
@Composable
internal fun AdjustCourseSheet(
    visible: Boolean,
    schedule: Schedule,
    courseId: String,
    /** 长按入口的当前浏览周；编辑已有调课时以记录自身的 fromWeek 为准 */
    initialFromWeek: Int,
    existingId: String? = null,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    onConfirm: (CourseAdjustment) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val course = schedule.courses.firstOrNull { it.id == courseId } ?: return
    val existing = remember(existingId, courseId, initialFromWeek, schedule.adjustments) {
        existingId?.let { id -> schedule.adjustments.firstOrNull { it.id == id } }
            ?: schedule.adjustmentFor(courseId, initialFromWeek)
    }

    // 原课次候选：只列这门课真正上课的周（交替课/单双周因此不会给出无效选项）
    val activeWeeks = remember(course.weeks, schedule.totalWeeks) {
        (1..schedule.totalWeeks).filter { course.weeks.contains(it) }
    }
    val preferredSource = existing?.fromWeek ?: initialFromWeek
    val initialSource = remember(activeWeeks, preferredSource) {
        when {
            activeWeeks.isEmpty() -> preferredSource
            preferredSource in activeWeeks -> preferredSource
            else -> activeWeeks.firstOrNull { it >= preferredSource } ?: activeWeeks.last()
        }
    }

    // 目标跨度沿用原课程/已有调课；起始节必须放得下这一跨度
    val span = existing?.toSpan ?: course.span
    val maxStartSlot = (schedule.slotCount - span + 1).coerceAtLeast(1)
    val initialStartSlot = (existing?.toStartSlot ?: course.startSlot).coerceIn(1, maxStartSlot)

    var sourceWeek by rememberSaveable(courseId, existingId) { mutableStateOf(initialSource) }
    var targetWeek by rememberSaveable(courseId, existingId) {
        mutableStateOf(existing?.toWeek ?: initialSource)
    }
    var targetDay by rememberSaveable(courseId, existingId) {
        mutableStateOf(existing?.toDayOfWeek ?: course.dayOfWeek)
    }
    var targetStartSlot by rememberSaveable(courseId, existingId) {
        mutableStateOf(initialStartSlot)
    }
    // 新建时初值 = 原课次（还没改就是 no-op），只有用户动过或编辑已有记录才显示校验错误
    var touched by rememberSaveable(courseId, existingId) { mutableStateOf(false) }

    val adjustmentId = remember(courseId, existingId) { existing?.id ?: newUuid() }
    val candidate = CourseAdjustment(
        id = adjustmentId,
        courseId = course.id,
        fromWeek = sourceWeek,
        toWeek = targetWeek,
        toDayOfWeek = targetDay,
        toStartSlot = targetStartSlot,
        toSpan = span,
    )

    val context = LocalContext.current
    val weekdayNames = stringArrayResource(R.array.weekdays)
    val validationError = remember(candidate, schedule) {
        findAdjustmentError(schedule, candidate)
    }
    val errorText = validationError
        ?.takeIf { touched || existing != null }
        ?.toMessage(context)

    val sheetHazeState = rememberHazeState()
    var weekPickerVisible by remember { mutableStateOf(false) }
    var weekPickerMounted by remember { mutableStateOf(false) }
    var slotPickerVisible by remember { mutableStateOf(false) }
    var slotPickerMounted by remember { mutableStateOf(false) }

    fun submit() {
        touched = true
        val error = findAdjustmentError(schedule, candidate)
        if (error != null) {
            AppToasts.show(context, error.toMessage(context))
            return
        }
        onConfirm(candidate)
    }

    BackHandler(enabled = true) { onDismiss() }

    OverlaySheet(
        title = stringResource(
            if (existing != null) R.string.adjust_sheet_edit_title else R.string.adjust_sheet_title,
        ),
        confirmLabel = stringResource(R.string.edit_confirm),
        confirmAtEnd = true,
        onConfirm = { submit() },
        rightAction = if (existing != null && onDelete != null) {
            SheetTopAction(
                label = stringResource(R.string.edit_delete),
                icon = R.drawable.ic_delete_filled,
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                contentColorGlass = MaterialTheme.classppColors.onPrimaryGlass,
                onClick = onDelete,
            )
        } else {
            SheetTopAction(
                label = stringResource(R.string.settings_cancel),
                icon = R.drawable.ic_dismiss_circle,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                onClick = onDismiss,
            )
        },
        onDismiss = onDismiss,
        visible = visible,
        covered = weekPickerVisible || slotPickerVisible,
        onDismissed = onDismissed,
        hazeState = sheetHazeState,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            SheetInfoPillRow(
                values = listOfNotNull(
                    course.name,
                    course.teacher.takeIf { it.isNotBlank() },
                    course.location.takeIf { it.isNotBlank() },
                ),
            )

            CardSection(title = stringResource(R.string.adjust_section_source)) {
                SettingsCard(
                    items = listOf(
                        SettingsCardItem.Custom(
                            label = stringResource(
                                R.string.adjust_source_summary,
                                sourceWeek,
                                weekdayNames.getOrElse(course.dayOfWeek - 1) { "" },
                                slotRangeText(course.startSlot, course.span),
                            ),
                            description = stringResource(R.string.adjust_source_week_hint),
                        ),
                    ),
                )
                if (activeWeeks.isEmpty()) {
                    SheetInfoBanner(text = stringResource(R.string.adjust_no_source_week))
                } else {
                    SelectionGrid(
                        values = (1..schedule.totalWeeks).toList(),
                        labels = (1..schedule.totalWeeks).map { it.toString() },
                        selected = setOf(sourceWeek),
                        blocked = (1..schedule.totalWeeks)
                            .filterNot { course.weeks.contains(it) }
                            .toSet(),
                        onToggle = { week ->
                            // 单周/双周课只能选自己上课的周；blocked 已拦住，这里再防御一次
                            if (course.weeks.contains(week)) {
                                sourceWeek = week
                                touched = true
                            }
                        },
                    )
                }
            }

            CardSection(title = stringResource(R.string.adjust_section_target)) {
                SettingsCard(
                    hazeState = sheetHazeState,
                    items = listOf(
                        SettingsCardItem.Nav(
                            label = stringResource(R.string.adjust_target_week),
                            value = stringResource(R.string.adjust_week_value, targetWeek),
                            onClick = {
                                weekPickerMounted = true
                                weekPickerVisible = true
                            },
                        ),
                        SettingsCardItem.Select(
                            label = stringResource(R.string.adjust_target_day),
                            value = weekdayNames.getOrElse(targetDay - 1) { "" },
                            items = (1..7).map { it to weekdayNames[it - 1] },
                            selectedId = targetDay,
                            onPick = {
                                targetDay = it
                                touched = true
                            },
                        ),
                        SettingsCardItem.Nav(
                            label = stringResource(R.string.adjust_target_slot),
                            value = slotValueText(schedule, targetStartSlot, span),
                            onClick = {
                                slotPickerMounted = true
                                slotPickerVisible = true
                            },
                        ),
                    ),
                )
                SheetInfoBanner(
                    text = stringResource(
                        R.string.adjust_summary_arrow,
                        stringResource(
                            R.string.adjust_source_summary,
                            sourceWeek,
                            weekdayNames.getOrElse(course.dayOfWeek - 1) { "" },
                            slotRangeText(course.startSlot, course.span),
                        ),
                        stringResource(
                            R.string.adjust_target_summary,
                            targetWeek,
                            weekdayNames.getOrElse(targetDay - 1) { "" },
                            slotRangeText(targetStartSlot, span),
                        ),
                    ),
                )
                if (targetDay > schedule.daysPerWeek) {
                    SheetInfoBanner(text = stringResource(R.string.adjust_weekend_hidden))
                }
            }

            errorText?.let { message ->
                Text(
                    text = message,
                    style = ClassppTextTokens.secondaryTextLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp),
                )
            }
        }
    }

    // 目标周滚轮：画在浮层之后（组合顺序即绘制顺序），covered 让本浮层缩小后退
    if (weekPickerMounted) {
        BottomNumberSelectCard(
            visible = weekPickerVisible,
            onDismiss = { weekPickerVisible = false },
            onDismissed = { weekPickerMounted = false },
            title = stringResource(R.string.adjust_week_picker_title),
            valueRange = 1..schedule.totalWeeks,
            initialValue = targetWeek,
            unit = stringResource(R.string.adjust_unit_week),
            hazeState = sheetHazeState,
            onConfirm = { week ->
                targetWeek = week
                touched = true
                weekPickerVisible = false
            },
        )
    }

    // 目标起始节滚轮：范围只到「放得下整段跨度」的最后一节
    if (slotPickerMounted) {
        BottomNumberSelectCard(
            visible = slotPickerVisible,
            onDismiss = { slotPickerVisible = false },
            onDismissed = { slotPickerMounted = false },
            title = stringResource(R.string.adjust_slot_picker_title),
            valueRange = 1..maxStartSlot,
            initialValue = targetStartSlot.coerceIn(1, maxStartSlot),
            unit = stringResource(R.string.adjust_unit_slot),
            hazeState = sheetHazeState,
            onConfirm = { slot ->
                targetStartSlot = slot
                touched = true
                slotPickerVisible = false
            },
        )
    }
}

/**
 * 只取「本条候选调课」相关的校验错误：编辑态整份草稿里可能还有别的历史错误，
 * 不能因为别处的问题把当前面板卡死。重复项按 courseId + fromWeek 认领。
 */
private fun findAdjustmentError(
    schedule: Schedule,
    candidate: CourseAdjustment,
): ScheduleError? {
    val updated = schedule.copy(
        adjustments = schedule.adjustments.filterNot { it.id == candidate.id } + candidate,
    )
    return ScheduleValidator.validateCourses(updated).firstOrNull { error ->
        when (error) {
            is ScheduleError.AdjustmentSourceInvalid -> error.adjustmentId == candidate.id
            is ScheduleError.AdjustmentTargetInvalid -> error.adjustmentId == candidate.id
            is ScheduleError.AdjustmentUnchanged -> error.adjustmentId == candidate.id
            is ScheduleError.AdjustmentConflict -> error.adjustmentId == candidate.id
            is ScheduleError.AdjustmentDuplicate ->
                error.courseId == candidate.courseId && error.fromWeek == candidate.fromWeek
            else -> false
        }
    }
}

/** 「第 a-b 节」/「第 a 节」：列表与编辑面板共用 */
@Composable
internal fun slotRangeText(startSlot: Int, span: Int): String =
    if (span <= 1) {
        stringResource(R.string.slot_format, startSlot)
    } else {
        stringResource(R.string.slot_range_format, startSlot, startSlot + span - 1)
    }

/** 「第 a 节 · 08:00-09:40」；节次时间非法时退回纯节次文案 */
@Composable
private fun slotValueText(schedule: Schedule, startSlot: Int, span: Int): String {
    val start = schedule.slots.getOrNull(startSlot - 1)
    val end = schedule.slots.getOrNull(startSlot + span - 2)
    val range = if (start != null && end != null) {
        stringResource(R.string.time_range_format, start.startTime, end.endTime)
    } else {
        null
    }
    return if (range == null) {
        stringResource(R.string.adjust_slot_value, startSlot)
    } else {
        stringResource(R.string.adjust_slot_with_time, startSlot, range)
    }
}
