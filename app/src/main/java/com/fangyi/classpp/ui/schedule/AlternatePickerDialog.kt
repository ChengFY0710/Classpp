package com.fangyi.classpp.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.ui.components.FadeOverlayDialog
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.RowShape

/**
 * 「请选择要编辑的交替课程」选择弹窗（设计稿三）：同一格上的多门课先让用户点名，
 * 再打开该课的编辑面板——否则点卡片只能编辑到当周那一门，其余的门无从进入。
 *
 * [courses] 是同一格（同一天 + 同一起始节）的全部课程，顺序即添加顺序。
 * 与 [AddCoursePanel] 同一约定：页内覆盖层而非 AlertDialog（对话框是独立窗口，
 * 该 ROM 上输入法与它有兼容问题）；本弹窗无输入框，纯点选。
 *
 * 两段式关闭见 [FadeOverlayDialog]：选完/关闭即清来源格 → [courses] 随之变空，
 * [shownCourses] 留住最后一份列表，淡出期间内容不闪空。
 */
@Composable
internal fun AlternatePickerDialog(
    courses: List<CourseEntry>,
    onDismiss: () -> Unit,
    onPick: (CourseEntry) -> Unit,
    visible: Boolean = true,
    onDismissed: () -> Unit = {},
) {
    var shownCourses by remember { mutableStateOf(courses) }
    if (courses.isNotEmpty()) shownCourses = courses

    FadeOverlayDialog(visible = visible, onDismiss = onDismiss, onDismissed = onDismissed) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(
                text = stringResource(R.string.edit_alternate_pick_title),
                modifier = Modifier.fillMaxWidth(),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shownCourses.forEach { course ->
                    AlternateCourseRow(
                        course = course,
                        // 淡出期间冻结行点按：防止连点把已打开的编辑面板换到另一门课
                        enabled = visible,
                        onClick = { onPick(course) },
                    )
                }
            }
        }
    }
}

/** 一门交替课：浅灰圆角块内课程名 + 「教师 | 上课地点」副标题 */
@Composable
private fun AlternateCourseRow(
    course: CourseEntry,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val separator = stringResource(R.string.edit_alternate_meta_separator)
    val meta = listOf(course.teacher, course.location)
        .filter { it.isNotBlank() }
        .joinToString(separator)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 按压反馈在 clip 之前：缩放作用于整行、不被 RowShape 裁掉；涟漪由其取代
            .pressClickable(RowShape, enabled = enabled, onClick = onClick)
            .clip(RowShape)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = course.name,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = meta,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
