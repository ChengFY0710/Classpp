package com.fangyi.classpp.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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

private val DialogShape = RoundedCornerShape(24.dp)
private val RowShape = RoundedCornerShape(12.dp)

/**
 * 「请选择要编辑的交替课程」选择弹窗（设计稿三）：同一格上的多门课先让用户点名，
 * 再打开该课的编辑面板——否则点卡片只能编辑到当周那一门，其余的门无从进入。
 *
 * [courses] 是同一格（同一天 + 同一起始节）的全部课程，顺序即添加顺序。
 * 与 [AddCoursePanel] 同一约定：页内覆盖层而非 AlertDialog（对话框是独立窗口，
 * 该 ROM 上输入法与它有兼容问题）；本弹窗无输入框，纯点选。
 */
@Composable
internal fun AlternatePickerDialog(
    courses: List<CourseEntry>,
    onDismiss: () -> Unit,
    onPick: (CourseEntry) -> Unit,
) {
    val scrimInteraction = remember { MutableInteractionSource() }
    BackHandler { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
            // 点空白处收起（无涟漪）
            .clickable(
                interactionSource = scrimInteraction,
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                // 吃掉落在卡片上的点击，避免穿透到遮罩把弹窗关掉
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = DialogShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
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
                    courses.forEach { course ->
                        AlternateCourseRow(course = course, onClick = { onPick(course) })
                    }
                }
            }
        }
    }
}

/** 一门交替课：浅灰圆角块内课程名 + 「教师 | 上课地点」副标题 */
@Composable
private fun AlternateCourseRow(course: CourseEntry, onClick: () -> Unit) {
    val separator = stringResource(R.string.edit_alternate_meta_separator)
    val meta = listOf(course.teacher, course.location)
        .filter { it.isNotBlank() }
        .joinToString(separator)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
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
