package com.fangyi.classpp.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.CourseOccurrence
import com.fangyi.classpp.data.ScheduleError
import com.fangyi.classpp.data.buildDayMovePlan
import com.fangyi.classpp.data.model.CourseAdjustment
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.ui.components.BottomNumberSelectCard
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SheetCard
import com.fangyi.classpp.ui.components.SheetInfoBanner
import com.fangyi.classpp.ui.components.SheetInfoPillRow
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.isDarkTheme
import dev.chrisbanes.haze.rememberHazeState

/** 课程行色条宽：与课表卡片竖条同档（3dp，见间距规范 §2.1） */
private val CourseBarWidth = 3.dp

/** 课程行色条高：大图标档（32dp），与两行文字的行卡垂直居中 */
private val CourseBarHeight = 32.dp

/**
 * 整天调休浮层：把某周某天真正要上的全部课程，整体平移到另一周/另一天，每门课保持原节次。
 *
 * 入口：课表设置 → 调课与调休 → 设置调休（见 ScheduleSettingsSheet）。
 * 一次调休展开成每门课一条 [CourseAdjustment]，确认只回调草稿的增/删，
 * 真正的落库仍由编辑栏「保存」统一完成（与单次调课面板同一套语义）。
 *
 * [schedule] 是**有效课表**：编辑态由调用方传入 `courses = 课程草稿`、
 * `adjustments = 调课草稿` 的副本，保证列表与校验看到的是用户眼前的数据。
 *
 * 校验走 [buildDayMovePlan]：原日无课、原日=目标日、目标越界、目标日冲突都在面板内实时提示，
 * 确认时再拦一次（Toast）。
 */
@Composable
internal fun DayAdjustSheet(
    visible: Boolean,
    schedule: Schedule,
    /** 原周次初值：调用方传入当前浏览周 */
    initialWeek: Int,
    /** 原星期初值：调用方传入今天（浏览今周时）或周一 */
    initialDay: Int,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    onConfirm: (upserts: List<CourseAdjustment>, removals: List<String>) -> Unit,
) {
    val maxWeek = schedule.totalWeeks.coerceAtLeast(1)
    val initialSourceWeek = initialWeek.coerceIn(1, maxWeek)
    val initialSourceDay = initialDay.coerceIn(1, 7)
    // 5 天课表的调休通常补到周末：默认周六；7 天课表取原星期的下一天（周日→周一）
    val defaultTargetDay = when {
        schedule.daysPerWeek == 5 && initialSourceDay <= 5 -> 6
        else -> initialSourceDay % 7 + 1
    }

    var sourceWeek by rememberSaveable { mutableIntStateOf(initialSourceWeek) }
    var sourceDay by rememberSaveable { mutableIntStateOf(initialSourceDay) }
    var targetWeek by rememberSaveable { mutableIntStateOf(initialSourceWeek) }
    var targetDay by rememberSaveable { mutableIntStateOf(defaultTargetDay) }
    // 新建时初值就是原课次（没改就提示无变化），只有用户动过才显示校验错误
    var touched by rememberSaveable { mutableStateOf(false) }

    val plan = remember(schedule, sourceWeek, sourceDay, targetWeek, targetDay) {
        buildDayMovePlan(schedule, sourceWeek, sourceDay, targetWeek, targetDay)
    }
    val sameDay = sourceWeek == targetWeek && sourceDay == targetDay

    val context = LocalContext.current
    val weekdayNames = stringArrayResource(R.array.weekdays)
    val emptyText = stringResource(R.string.error_day_adjust_empty)
    val unchangedText = stringResource(R.string.error_day_adjust_unchanged)
    val targetInvalidText = stringResource(R.string.error_day_adjust_target_invalid)

    // 冲突按课程名去重后聚合成一行；其余错误（越界/无变化）按类型映射，避免逐门课刷屏
    val conflictText = plan.errors
        .filterIsInstance<ScheduleError.AdjustmentConflict>()
        .map { it.conflictName }
        .distinct()
        .takeIf { it.isNotEmpty() }
        ?.let { names ->
            stringResource(R.string.error_day_adjust_conflict, names.joinToString("、"))
        }
    val otherErrorTexts = plan.errors
        .filterNot { it is ScheduleError.AdjustmentConflict }
        .map { error ->
            when (error) {
                is ScheduleError.AdjustmentUnchanged -> unchangedText
                is ScheduleError.AdjustmentTargetInvalid -> targetInvalidText
                else -> error.toMessage(context)
            }
        }
        .distinct()
    val errorLines = when {
        plan.occurrences.isEmpty() -> emptyList()
        sameDay -> listOf(unchangedText)
        else -> listOfNotNull(conflictText) + otherErrorTexts
    }

    val sheetHazeState = rememberHazeState()
    var sourceWeekPickerVisible by remember { mutableStateOf(false) }
    var sourceWeekPickerMounted by remember { mutableStateOf(false) }
    var targetWeekPickerVisible by remember { mutableStateOf(false) }
    var targetWeekPickerMounted by remember { mutableStateOf(false) }

    fun submit() {
        touched = true
        val message = when {
            plan.occurrences.isEmpty() -> emptyText
            sameDay -> unchangedText
            else -> (listOfNotNull(conflictText) + otherErrorTexts).firstOrNull()
        }
        if (message != null) {
            AppToasts.show(context, message)
            return
        }
        onConfirm(plan.upserts, plan.removals)
    }

    BackHandler(enabled = true) { onDismiss() }

    OverlaySheet(
        title = stringResource(R.string.day_adjust_sheet_title),
        confirmLabel = stringResource(R.string.edit_confirm),
        confirmAtEnd = true,
        onConfirm = { submit() },
        rightAction = SheetTopAction(
            label = stringResource(R.string.settings_cancel),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = onDismiss,
        ),
        onDismiss = onDismiss,
        visible = visible,
        covered = sourceWeekPickerVisible || targetWeekPickerVisible,
        onDismissed = onDismissed,
        hazeState = sheetHazeState,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            SheetInfoPillRow(
                values = listOf(
                    stringResource(R.string.adjust_week_value, sourceWeek),
                    weekdayNames.getOrElse(sourceDay - 1) { "" },
                    stringResource(R.string.day_adjust_course_count, plan.occurrences.size),
                ),
            )

            CardSection(title = stringResource(R.string.day_adjust_section_source)) {
                SettingsCard(
                    hazeState = sheetHazeState,
                    items = listOf(
                        SettingsCardItem.Nav(
                            label = stringResource(R.string.day_adjust_source_week),
                            value = stringResource(R.string.adjust_week_value, sourceWeek),
                            onClick = {
                                sourceWeekPickerMounted = true
                                sourceWeekPickerVisible = true
                            },
                        ),
                        SettingsCardItem.Select(
                            label = stringResource(R.string.day_adjust_source_day),
                            value = weekdayNames.getOrElse(sourceDay - 1) { "" },
                            items = (1..7).map { it to weekdayNames[it - 1] },
                            selectedId = sourceDay,
                            onPick = { picked ->
                                // 原日改到与目标同一天时目标顺延一天，避免默认落到"无变化"
                                if (targetWeek == sourceWeek && targetDay == picked) {
                                    targetDay = picked % 7 + 1
                                }
                                sourceDay = picked
                                touched = true
                            },
                        ),
                    ),
                )
                if (plan.occurrences.isEmpty()) {
                    SheetInfoBanner(text = stringResource(R.string.day_adjust_empty))
                } else {
                    plan.occurrences.forEach { occurrence -> DayCourseRow(occurrence) }
                }
            }

            CardSection(title = stringResource(R.string.day_adjust_section_target)) {
                SettingsCard(
                    hazeState = sheetHazeState,
                    items = listOf(
                        SettingsCardItem.Nav(
                            label = stringResource(R.string.day_adjust_target_week),
                            value = stringResource(R.string.adjust_week_value, targetWeek),
                            onClick = {
                                targetWeekPickerMounted = true
                                targetWeekPickerVisible = true
                            },
                        ),
                        SettingsCardItem.Select(
                            label = stringResource(R.string.day_adjust_target_day),
                            value = weekdayNames.getOrElse(targetDay - 1) { "" },
                            items = (1..7).map { it to weekdayNames[it - 1] },
                            selectedId = targetDay,
                            onPick = { picked ->
                                targetDay = picked
                                touched = true
                            },
                        ),
                    ),
                )
                if (plan.occurrences.isNotEmpty()) {
                    SheetInfoBanner(
                        text = stringResource(
                            R.string.day_adjust_summary,
                            sourceWeek,
                            weekdayNames.getOrElse(sourceDay - 1) { "" },
                            plan.occurrences.size,
                            targetWeek,
                            weekdayNames.getOrElse(targetDay - 1) { "" },
                        ),
                    )
                }
                if (targetDay > schedule.daysPerWeek) {
                    SheetInfoBanner(text = stringResource(R.string.day_adjust_weekend_hidden))
                }
            }

            if (touched && errorLines.isNotEmpty()) {
                Text(
                    text = errorLines.joinToString("\n"),
                    style = ClassppTextTokens.secondaryTextLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp),
                )
            }
        }
    }

    // 原周滚轮：画在浮层之后（组合顺序即绘制顺序），covered 让本浮层缩小后退
    if (sourceWeekPickerMounted) {
        BottomNumberSelectCard(
            visible = sourceWeekPickerVisible,
            onDismiss = { sourceWeekPickerVisible = false },
            onDismissed = { sourceWeekPickerMounted = false },
            title = stringResource(R.string.day_adjust_source_week_picker_title),
            valueRange = 1..maxWeek,
            initialValue = sourceWeek,
            unit = stringResource(R.string.adjust_unit_week),
            hazeState = sheetHazeState,
            onConfirm = { picked ->
                // 目标周原本跟着原周（同一周调休）时一起走，跨周调休则保持不变
                if (targetWeek == sourceWeek) targetWeek = picked
                sourceWeek = picked
                touched = true
                sourceWeekPickerVisible = false
            },
        )
    }

    // 目标周滚轮
    if (targetWeekPickerMounted) {
        BottomNumberSelectCard(
            visible = targetWeekPickerVisible,
            onDismiss = { targetWeekPickerVisible = false },
            onDismissed = { targetWeekPickerMounted = false },
            title = stringResource(R.string.day_adjust_target_week_picker_title),
            valueRange = 1..maxWeek,
            initialValue = targetWeek,
            unit = stringResource(R.string.adjust_unit_week),
            hazeState = sheetHazeState,
            onConfirm = { picked ->
                targetWeek = picked
                touched = true
                targetWeekPickerVisible = false
            },
        )
    }
}

/**
 * 当天课程只读行：左侧课程色条 + 课名 + 节次/地点。
 * 样式对齐「设置调课」列表行（[SheetCard] + fieldLabel / settingsDescription），
 * 色条取课程卡同一套 [CourseColor.barColor] 映射。
 */
@Composable
private fun DayCourseRow(occurrence: CourseOccurrence) {
    val course = occurrence.course
    val barColor = CourseColor.valueOf(course.color.name).barColor(MaterialTheme.isDarkTheme)
    val detail = listOfNotNull(
        slotRangeText(occurrence.startSlot, occurrence.span),
        course.location.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    SheetCard(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .width(CourseBarWidth)
                .height(CourseBarHeight)
                .background(barColor, PillShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = course.name,
                style = MaterialTheme.classppTextStyles.fieldLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = detail,
                style = ClassppTextTokens.settingsDescription,
                color = MaterialTheme.classppColors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
